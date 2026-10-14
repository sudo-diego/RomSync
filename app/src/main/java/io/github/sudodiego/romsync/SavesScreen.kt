package io.github.sudodiego.romsync

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SavesScreen() {
    val ctx = LocalContext.current
    val scope = appScope
    var allBusy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Prefs.autoBackup) }
    var restoreFor by remember { mutableStateOf<Emulator?>(null) }

    LaunchedEffect(Shell.state) { Emulators.all.forEach { Saves.inspect(ctx, it) } }

    val installed = Emulators.all.filter { Saves.statuses[it.id]?.installed == true }
    val needShell = installed.any { Saves.statuses[it.id]?.needsShell == true } && Shell.state != Shell.State.READY

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SpacedRow(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Partidas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Muted("En el servidor: ${Prefs.remoteBackups.removePrefix("/").ifBlank { "sin configurar" }}")
                }
                if (allBusy) CircularProgressIndicator(Modifier.size(28.dp))
                else Button(enabled = Prefs.configured, onClick = {
                    allBusy = true
                    scope.launch {
                        val msg = Saves.backupAll(ctx, auto = false)
                        allBusy = false
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Respaldar todo") }
            }
        }
        item {
            Section("") {
                SpacedRow {
                    Column(Modifier.weight(1f)) {
                        Text("Respaldo automático", fontWeight = FontWeight.Bold)
                        Muted("Cada ${Prefs.autoBackupHours} h, con la app cerrada. Si el servidor está apagado, reintenta cada hora.")
                        val last = Prefs.lastAutoBackupOk
                        if (last > 0 || Prefs.lastAutoBackupNote.isNotEmpty()) Muted(
                            (if (last > 0) "Último que salió bien: ${formatAgo(last)}" else "Todavía no salió ninguno") +
                                Prefs.lastAutoBackupNote.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it; Prefs.autoBackup = it; BackupWorker.schedule(ctx) })
                }
                // Sin esto, el ahorro de batería de Android (y el de Lenovo) puede frenar el respaldo con la app cerrada.
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                var free by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
                if (auto && !free) SpacedRow {
                    Text("Android puede frenarlo con la app cerrada.", Modifier.weight(1f),
                        color = Palette.warn, style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                        }
                        free = true
                    }) { Text("Permitir") }
                }
                var keep by remember { mutableStateOf(Prefs.keepBackups) }
                SpacedRow {
                    Muted("Respaldos completos que se guardan", Modifier.weight(1f))
                    listOf(5, 10, 20).forEach { n ->
                        androidx.compose.material3.FilterChip(selected = keep == n, onClick = { keep = n; Prefs.keepBackups = n },
                            label = { Text("$n") })
                    }
                }
                Muted("Más 5 por juego. Los viejos se borran solos.")
            }
        }
        if (needShell) item { ShizukuCard() }
        items(Emulators.all, key = { it.id }) { emu -> EmulatorCard(emu) { restoreFor = emu } }
    }

    restoreFor?.let { emu -> RestoreDialog(emu) { restoreFor = null } }
}

@Composable
fun ShizukuCard() {
    val ctx = LocalContext.current
    Section("Shizuku") {
        Muted("Para leer las partidas de Dolphin, Eden, ARMSX2 y Vita3K (sin root).")
        SpacedRow {
            when (Shell.state) {
                Shell.State.NOT_INSTALLED -> Muted("Shizuku no está instalado.")
                Shell.State.NOT_RUNNING -> FilledTonalButton(onClick = { Shell.launchIntent(ctx)?.let(ctx::startActivity) }) { Text("Abrir Shizuku") }
                Shell.State.NO_PERMISSION -> Button(onClick = { Shell.requestPermission() }) { Text("Dar permiso a RomSync") }
                Shell.State.READY -> Muted("Listo")
            }
        }
    }
}

@Composable
private fun EmulatorCard(emu: Emulator, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    val st = Saves.statuses[emu.id]
    val busy = Saves.busy[emu.id]
    var editPath by remember { mutableStateOf(false) }
    var pickOne by remember { mutableStateOf(false) }
    var askTransfer by remember { mutableStateOf(false) }
    var transferDone by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(Prefs.emuPath(emu.id)) }
    Section("") {
        SpacedRow {
            Column(Modifier.weight(1f)) {
                Text(emu.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (st?.installed != true) Muted("No instalado")
        }
        if (st?.installed == true && st.supported) {
            when {
                busy != null -> SpacedRow { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Muted(busy) }
                st.error != null -> Text(if (st.needsShell && Shell.state != Shell.State.READY) "Necesita Shizuku para leer sus partidas" else st.error,
                    color = MaterialTheme.colorScheme.error)
                else -> Muted("${formatSize(st.bytes)} · respaldado ${formatAgo(Prefs.lastBackupAt(emu.id))}")
            }
            SpacedRow {
                Button(enabled = busy == null && Prefs.configured && st.error == null, onClick = {
                    scope.launch {
                        val msg = when (val r = Saves.backup(ctx, emu, auto = false)) {
                            is Saves.Result.Done -> "${emu.name}: ${r.files} archivos respaldados"
                            Saves.Result.Unchanged -> "${emu.name}: sin cambios desde el último respaldo"
                            Saves.Result.Empty -> "${emu.name}: no hay partidas"
                            is Saves.Result.Failed -> "${emu.name}: ${r.message}"
                        }
                        Saves.inspect(ctx, emu)
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Respaldar") }
                FilledTonalButton(enabled = busy == null && Prefs.configured, onClick = onRestore) { Text("Restaurar…") }
                TextButton(onClick = { editPath = !editPath }) { Text("Carpeta") }
            }
            if (editPath) {
                st.sources.forEach { Muted("• ${it.root}") }
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Otra carpeta (vacío = la detectada)") })
                TextButton(onClick = { Prefs.setEmuPath(emu.id, path); scope.launch { Saves.inspect(ctx, emu) } }) { Text("Guardar carpeta") }
            }
        }
        if (emu.note.isNotEmpty() && st?.installed == true && emu.id == "nethersx2") Muted(emu.note)
        if (emu.id == "nethersx2" && st?.installed == true && Saves.statuses["armsx2"]?.installed == true) {
            Button(enabled = busy == null, onClick = { askTransfer = true }) { Text("Pasar tarjetas a ARMSX2") }
        }
        if (st?.installed == true && st.needsShell && Shell.state != Shell.State.READY) Muted("Sin Shizuku: ${emu.exportHint}")
    }
    if (pickOne) BackupOneDialog(emu) { pickOne = false }
    if (askTransfer) AlertDialog(
        onDismissRequest = { askTransfer = false },
        title = { Text("Pasar tus partidas de NetherSX2 a ARMSX2") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Se copian tus tarjetas de memoria (Mcd001.ps2…) de NetherSX2 a ARMSX2. Son el mismo formato.")
                Text("Antes se guarda una copia de las tarjetas de los dos emuladores en el dispositivo (RomSync/antes-del-traspaso)" +
                    if (Prefs.configured) " y un respaldo en el servidor." else ".", fontWeight = FontWeight.SemiBold)
                Text("Los estados guardados (save states) no sirven entre emuladores: si estabas usando uno, guarda dentro del juego en NetherSX2 antes.")
                Muted("NetherSX2 no se toca. Cierra los dos emuladores antes de seguir.")
            }
        },
        confirmButton = {
            Button(onClick = {
                askTransfer = false
                scope.launch {
                    val e = Saves.transferToArmsx2(ctx)
                    if (e != null) Toast.makeText(ctx, e, Toast.LENGTH_LONG).show() else transferDone = true
                }
            }) { Text("Pasar") }
        },
        dismissButton = { TextButton(onClick = { askTransfer = false }) { Text("Cancelar") } },
    )
    // ARMSX2 deja Mcd001.ps2 en el slot 1 aunque tus partidas estén en otra tarjeta (en la tablet del usuario, la de
    // carpeta "hola123.ps2"): sin elegirla, parece que no se pasó nada.
    if (transferDone) AlertDialog(
        onDismissRequest = { transferDone = false },
        title = { Text("Listo: tus tarjetas ya están en ARMSX2") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Se pasaron: " + Saves.transferredCards.joinToString(", "))
                Text("Falta un paso en ARMSX2: menú → Memory Cards → pon en «Slot 1» la tarjeta que usabas en NetherSX2 " +
                    "(la que tiene tus partidas). ARMSX2 empieza con Mcd001.ps2, que suele estar vacía.",
                    fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = { TextButton(onClick = { transferDone = false }) { Text("Entendido") } },
    )
}

/** Restaurar desde un respaldo del PC. Con [onlyKey] (desde la ficha de un juego) solo muestra y marca ese juego. */
@Composable
fun RestoreDialog(emu: Emulator, onlyKey: String? = null, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    var list by remember { mutableStateOf<List<SaveBackupInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<SaveBackupInfo?>(null) }
    var units by remember { mutableStateOf<List<Saves.Unit>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(emu) {
        runCatching { Pc.io { Backups.list(it, emu.id) } }.onSuccess { list = it }.onFailure { error = it.message }
        // Los respaldos viejos no dicen qué traen: se revisan una vez (y queda anotado en el PC).
        if (list.orEmpty().any { it.kind.isEmpty() }) runCatching {
            Pc.io { c -> Backups.describeOld(c, emu.id, list.orEmpty(), ctx.cacheDir) }
        }.onSuccess { list = it }
    }
    LaunchedEffect(picked) {
        val b = picked ?: return@LaunchedEffect
        units = null
        runCatching { Saves.units(ctx, emu, b.id) }.onSuccess { all ->
            units = if (onlyKey == null) all else all.filter { it.key == onlyKey }
            selected = if (onlyKey == null) emptySet() else units.orEmpty().map { it.key }.toSet()
        }.onFailure { error = it.message }
    }
    val b = picked
    if (b != null && confirm) {
        val chosen = units.orEmpty().filter { it.key in selected }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Restaurar ${chosen.size} en ${emu.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chosen.take(6).forEach { Text("• ${it.title}") }
                    if (chosen.size > 6) Muted("y ${chosen.size - 6} más")
                    Text("Solo se reemplazan esas partidas. Las demás de ${emu.name} no se tocan.", fontWeight = FontWeight.SemiBold)
                    Muted("Antes se respalda lo de ahora. Cierra ${emu.name}.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    onClose()
                    scope.launch {
                        val e = Saves.restore(ctx, emu, b.id, only = selected)
                        Toast.makeText(ctx, e ?: "${emu.name}: ${selected.size} restaurado(s)", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (b == null) "Respaldos de ${emu.name}" else "${backupTitle(b)} · ${friendlyDate(b.at)}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    b == null && list == null -> item { CircularProgressIndicator() }
                    b == null && list!!.isEmpty() -> item { Muted("No hay respaldos en el servidor.") }
                    b == null -> {
                        // Desde la ficha de un juego: solo los respaldos que tienen ese juego (los viejos no lo dicen: se muestran).
                        val shown = list!!.filter { onlyKey == null || it.keys.isEmpty() || onlyKey in it.keys }.take(30)
                        item { Muted(if (onlyKey == null) "Elige un respaldo para ver qué trae. Mirar no cambia nada."
                            else "Respaldos con partidas de este juego. Mirar no cambia nada.") }
                        if (shown.isEmpty()) item { Muted("Ningún respaldo tiene partidas de este juego todavía.") }
                        items(shown, key = { it.id }) { bk -> BackupRow(bk) { picked = bk } }
                    }
                    units == null -> item { SpacedRow { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted("Abriendo el respaldo…") } }
                    units!!.isEmpty() -> item { Muted("Este respaldo no tiene partidas de este juego. Prueba con otro.") }
                    else -> {
                        item {
                            SpacedRow {
                                Muted("Marca lo que quieres restaurar (${units!!.size} en total).", Modifier.weight(1f))
                                TextButton(onClick = { selected = if (selected.size == units!!.size) emptySet() else units!!.map { it.key }.toSet() }) {
                                    Text(if (selected.size == units!!.size) "Ninguno" else "Todos")
                                }
                            }
                        }
                        items(units!!, key = { it.key }) { u ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                                .clickable { selected = if (u.key in selected) selected - u.key else selected + u.key }) {
                                Checkbox(checked = u.key in selected, onCheckedChange = { selected = if (it) selected + u.key else selected - u.key })
                                Column(Modifier.weight(1f)) {
                                    Text(u.title, fontWeight = FontWeight.SemiBold)
                                    Muted("${u.files} archivo(s) · ${formatSize(u.bytes)} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(u.newest))}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (b != null && units != null) Button(enabled = selected.isNotEmpty(), onClick = { confirm = true }) { Text("Restaurar (${selected.size})") }
            else TextButton(onClick = onClose) { Text("Cerrar") }
        },
        dismissButton = { if (b != null) TextButton(onClick = { picked = null; units = null }) { Text("Otro respaldo") } },
    )
}

/** Respaldar en el PC solo algunos juegos (por ejemplo antes de probar algo). */
@Composable
private fun BackupOneDialog(emu: Emulator, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var units by remember { mutableStateOf<List<Saves.Unit>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(emu) { runCatching { Saves.currentUnits(ctx, emu) }.onSuccess { units = it }.onFailure { error = it.message } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Respaldar en ${emu.name}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                when {
                    error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    units == null -> item { CircularProgressIndicator() }
                    units!!.isEmpty() -> item { Muted("No hay partidas.") }
                    else -> items(units!!, key = { it.key }) { u ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .clickable { selected = if (u.key in selected) selected - u.key else selected + u.key }) {
                            Checkbox(checked = u.key in selected, onCheckedChange = { selected = if (it) selected + u.key else selected - u.key })
                            Column(Modifier.weight(1f)) {
                                Text(u.title, fontWeight = FontWeight.SemiBold)
                                Muted("${u.files} archivo(s) · ${formatSize(u.bytes)}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty(), onClick = {
                val only = selected
                onClose()
                appScope.launch {
                    val msg = when (val r = Saves.backup(ctx, emu, auto = false, force = true, only = only)) {
                        is Saves.Result.Done -> "${emu.name}: respaldado (${r.files} archivos)"
                        Saves.Result.Unchanged, Saves.Result.Empty -> "${emu.name}: no había nada que respaldar"
                        is Saves.Result.Failed -> "${emu.name}: ${r.message}"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }) { Text("Respaldar (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

/** Qué es un respaldo, en palabras (antes era "2026-10-13 21:01 · 17 archivos" y no se entendía la diferencia). */
fun backupTitle(b: SaveBackupInfo): String {
    val one = b.games.singleOrNull()?.let(Saves::prettyUnitName)
    return when (b.kind) {
        "todo" -> "Completo · todas tus partidas"
        "auto" -> "Automático · todas tus partidas"
        "juego" -> if (one != null) "Solo $one" else "Solo ${b.games.size} juegos"
        "antes" -> "Copia de seguridad antes de restaurar"
        "traspaso" -> "Copia antes de pasar a ARMSX2"
        else -> if (b.auto) "Automático" else "Respaldo"
    }
}

/** "lunes 13 de octubre, 21:01" (o "hoy, 21:01"). */
fun friendlyDate(at: String): String = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.ROOT).parse(at)!!
    val es = java.util.Locale("es")
    val day = java.text.SimpleDateFormat("yyyyMMdd", es)
    val today = day.format(java.util.Date())
    val yesterday = day.format(java.util.Date(System.currentTimeMillis() - 86_400_000))
    val time = java.text.SimpleDateFormat("HH:mm", es).format(d)
    when (day.format(d)) {
        today -> "hoy, $time"
        yesterday -> "ayer, $time"
        else -> java.text.SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", es).format(d)
    }
}.getOrDefault(at.take(16).replace('T', ' '))

@Composable
private fun BackupRow(b: SaveBackupInfo, onClick: () -> Unit) {
    val (icon, color) = when (b.kind) {
        "todo", "auto" -> "Todo" to Palette.ok
        "juego" -> "Juego" to Palette.sky
        "antes", "traspaso" -> "Seguridad" to Palette.warn
        else -> (if (b.auto) "Auto" else "Respaldo") to androidx.compose.ui.graphics.Color(0xFF475569)
    }
    androidx.compose.material3.Surface(onClick = onClick, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(icon, color)
            Column(Modifier.weight(1f)) {
                Text(backupTitle(b), fontWeight = FontWeight.SemiBold)
                Muted(listOfNotNull(friendlyDate(b.at),
                    if (b.games.isNotEmpty()) "${b.games.size} juego(s)" else "${b.files} archivo(s)",
                    formatSize(b.size), if (b.keep) "conservar" else null).joinToString(" · "))
                if (b.kind == "todo" || b.kind == "auto") b.games.map(Saves::prettyUnitName).distinct().sorted().take(4).joinToString(", ").takeIf { it.isNotEmpty() }?.let {
                    Muted(it + if (b.games.size > 4) " y ${b.games.size - 4} más" else "")
                }
            }
        }
    }
}
