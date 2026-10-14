package io.github.sudodiego.romsync

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
                    Muted("Se respaldan en el PC (${Prefs.remoteBackups.ifBlank { "sin configurar" }}). En la tablet nunca se borran.")
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
                        Muted("Cada ${Prefs.autoBackupHours} h, con WiFi y el PC encendido. Solo sube lo que cambió.")
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it; Prefs.autoBackup = it; BackupWorker.schedule(ctx) })
                }
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
        Muted("Dolphin, Eden, ARMSX2 y Vita3K guardan sus partidas en Android/data, que una app normal no puede leer. " +
            "Con Shizuku (el mismo de TBF Companion) RomSync las copia sin root.")
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
                Muted(emu.what)
            }
            if (st?.installed != true) Muted("No instalado")
        }
        if (st?.installed == true && st.supported) {
            when {
                busy != null -> SpacedRow { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Muted(busy) }
                st.error != null -> Text(if (st.needsShell && Shell.state != Shell.State.READY) "Necesita Shizuku para leer sus partidas" else st.error,
                    color = MaterialTheme.colorScheme.error)
                else -> Muted("${st.files} archivos · ${formatSize(st.bytes)} · último respaldo ${formatAgo(Prefs.lastBackupAt(emu.id))}")
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
                TextButton(enabled = busy == null && Prefs.configured && st.error == null, onClick = { pickOne = true }) { Text("Respaldar un juego…") }
                TextButton(onClick = { editPath = !editPath }) { Text("Carpeta") }
            }
            if (editPath) {
                st.sources.forEach { Muted("• ${it.root}") }
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Otra carpeta (vacío = la detectada)") })
                TextButton(onClick = { Prefs.setEmuPath(emu.id, path); scope.launch { Saves.inspect(ctx, emu) } }) { Text("Guardar carpeta") }
            }
        }
        if (emu.note.isNotEmpty()) Muted(emu.note)
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
                Text("Antes se guarda una copia de las tarjetas de los dos emuladores en la tablet (RomSync/antes-del-traspaso)" +
                    if (Prefs.configured) " y un respaldo en el PC." else ".", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun RestoreDialog(emu: Emulator, onClose: () -> Unit) {
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
    }
    LaunchedEffect(picked) {
        val b = picked ?: return@LaunchedEffect
        units = null
        runCatching { Saves.units(ctx, emu, b.id) }.onSuccess { units = it; selected = emptySet() }.onFailure { error = it.message }
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
                    Muted("Antes se guarda en el PC un respaldo de cómo está todo ahora, por si quieres volver atrás. Cierra ${emu.name} antes de seguir.")
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
        title = { Text(if (b == null) "Respaldos de ${emu.name}" else "Respaldo del ${b.at.take(16).replace('T', ' ')}") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    b == null && list == null -> item { CircularProgressIndicator() }
                    b == null && list!!.isEmpty() -> item { Muted("No hay respaldos en el PC.") }
                    b == null -> {
                        item { Muted("Elige un respaldo para ver qué juegos trae. Mirar no cambia nada.") }
                        items(list!!.take(30)) { bk ->
                            TextButton(onClick = { picked = bk }, Modifier.fillMaxWidth()) {
                                Text("${bk.at.take(16).replace('T', ' ')} · ${bk.files} archivos · ${formatSize(bk.size)}" +
                                    (if (bk.auto) " · auto" else "") + (if (bk.keep) " · conservar" else ""), Modifier.weight(1f))
                            }
                        }
                    }
                    units == null -> item { SpacedRow { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted("Abriendo el respaldo…") } }
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
