package io.github.sudodiego.romsync

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.Icons
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

    val missing = Emulators.all.filter { Saves.statuses[it.id]?.installed == false }
    // Estado de cada emulador instalado: al día (lo mismo que el último respaldo), con cambios, o sin respaldo.
    val states = Saves.backupTick.let { installed.associate { it.id to saveState(it) } }

    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(400.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
            { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
        item(span = full) { Text("Partidas", style = MaterialTheme.typography.headlineLarge) }
        // Resumen + el botón grande.
        item(span = full) {
            Section("") {
                SpacedRow {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val ok = states.values.count { it == SaveState.OK }
                        val changed = states.values.count { it == SaveState.CHANGED }
                        val none = states.values.count { it == SaveState.NONE }
                        Text(when {
                            !Prefs.configured -> "Sin servidor: tus partidas solo están en el dispositivo"
                            changed + none == 0 && ok > 0 -> "Todo respaldado"
                            else -> "Hay partidas sin respaldar"
                        }, style = MaterialTheme.typography.titleLarge,
                            color = if (Prefs.configured && changed + none == 0) Palette.ok else Palette.warn)
                        FlowRowCompat {
                            if (ok > 0) Pill("$ok al día", Palette.ok)
                            if (changed > 0) Pill("$changed con cambios", Palette.warn)
                            if (none > 0) Pill("$none sin respaldo", Palette.danger)
                        }
                        Muted("Para respaldar o restaurar un solo juego, ábrelo en la Tienda.")
                    }
                    if (allBusy) CircularProgressIndicator(Modifier.size(32.dp))
                    else BigActionButton("Respaldar todo", Icons.Rounded.CloudUpload, Modifier.focusWhen("entrada:SAVES"), enabled = Prefs.configured) {
                        allBusy = true
                        scope.launch {
                            val msg = Saves.backupAll(ctx, auto = false)
                            allBusy = false
                            Sound.play(Sound.Fx.JINGLE)
                            Notice.info(msg)
                        }
                    }
                }
            }
        }
        if (needShell) item(span = full) { ShizukuCard() }
        items(installed, key = { it.id }) { emu -> EmulatorCard(emu, states[emu.id]) { restoreFor = emu } }
        if (missing.isNotEmpty()) item(span = full) {
            Muted("No instalados: " + missing.joinToString(", ") { it.name })
        }
        // Ajustes del respaldo automático, al final (se tocan poco).
        item(span = full) {
            Section("Respaldo automático") {
                SpacedRow {
                    Column(Modifier.weight(1f)) {
                        Text("Cada ${Prefs.autoBackupHours} h, con la app cerrada", fontWeight = FontWeight.SemiBold)
                        Muted("Si el servidor está apagado, reintenta cada hora.")
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
                    Muted("Respaldos completos que se guardan (más 5 por juego; los viejos se borran solos)", Modifier.weight(1f))
                    listOf(5, 10, 20).forEach { n ->
                        androidx.compose.material3.FilterChip(selected = keep == n, onClick = { keep = n; Prefs.keepBackups = n },
                            label = { Text("$n") })
                    }
                }
            }
        }
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
private fun EmulatorCard(emu: Emulator, state: SaveState?, onRestore: () -> Unit) {
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
            st?.pkg?.let { EmuChoice.icon(ctx, it) }?.let {
                androidx.compose.foundation.Image(it, null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            }
            Column(Modifier.weight(1f)) {
                Text(emu.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Muted(consoles[emu.id].orEmpty())
            }
            when (state) {
                SaveState.OK -> Pill("Al día", Palette.ok)
                SaveState.CHANGED -> Pill("Con cambios", Palette.warn)
                SaveState.NONE -> Pill("Sin respaldo", Palette.danger)
                SaveState.EMPTY -> Pill("Sin partidas", Color(0xFF64748B))
                null -> {}
            }
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
                            is Saves.Result.Done -> { Sound.play(Sound.Fx.JINGLE); "${emu.name}: ${r.files} archivos respaldados" }
                            Saves.Result.Unchanged -> "${emu.name}: sin cambios desde el último respaldo"
                            Saves.Result.Empty -> "${emu.name}: no hay partidas"
                            is Saves.Result.Failed -> "${emu.name}: ${r.message}"
                        }
                        Saves.inspect(ctx, emu)
                        Notice.info(msg)
                    }
                }, modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text("Respaldar") }
                FilledTonalButton(enabled = busy == null && Prefs.configured, onClick = onRestore,
                    modifier = Modifier.focusHighlight(RoundedCornerShape(99.dp))) { Text("Restaurar…") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editPath = !editPath }) { Text("Carpeta") }
            }
            if (editPath) {
                st.sources.forEach { Muted("• ${it.root}") }
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, modifier = Modifier.fillMaxWidth().gamepadText(),
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
                    if (e != null) Notice.info(e) else transferDone = true
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
    // Lo que hay ahora en el dispositivo, para decir si la partida del respaldo es más nueva o más vieja.
    var current by remember { mutableStateOf<Map<String, Saves.Unit>>(emptyMap()) }
    LaunchedEffect(emu) { current = runCatching { Saves.currentUnits(ctx, emu).associateBy { it.key } }.getOrDefault(emptyMap()) }
    val b = picked
    if (b != null && confirm) {
        val chosen = units.orEmpty().filter { it.key in selected }
        // Las que pisarían una partida más nueva del dispositivo.
        val older = chosen.mapNotNull { u -> current[u.key]?.takeIf { it.newest > u.newest + 60_000 }?.let { u to it } }
        var sureOld by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (older.isNotEmpty()) "Ojo: vas a volver atrás" else "¿Restaurar ${chosen.size} en ${emu.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (older.isNotEmpty()) {
                        Text("Estas partidas del respaldo son más viejas que las que tienes ahora. Si sigues, pierdes el progreso de después:",
                            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        older.take(5).forEach { (u, now) -> Text("• ${u.title}: respaldo de ${formatAgo(u.newest)}, la tuya es de ${formatAgo(now.newest)}") }
                        Muted("Lo que tienes ahora se respalda antes, así que puedes volver a recuperarlo.")
                        Row(Modifier.fillMaxWidth().clickable { sureOld = !sureOld }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(sureOld, { sureOld = it })
                            Text("Entiendo, quiero las partidas viejas", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    chosen.take(6).forEach { Text("• ${it.title}") }
                    if (chosen.size > 6) Muted("y ${chosen.size - 6} más")
                    Text("Solo se reemplazan esas partidas. Las demás de ${emu.name} no se tocan.", fontWeight = FontWeight.SemiBold)
                    Muted("Antes se respalda lo de ahora. Cierra ${emu.name}.")
                }
            },
            confirmButton = {
                Button(enabled = older.isEmpty() || sureOld, onClick = {
                    confirm = false
                    onClose()
                    scope.launch {
                        val e = Saves.restore(ctx, emu, b.id, only = selected)
                        Notice.info(e ?: "${emu.name}: ${selected.size} restaurado(s)")
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
        )
        return
    }
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    ReviewSheet(
        if (b == null) "Restaurar partidas de ${emu.name}" else backupTitle(b),
        if (b == null) (if (onlyKey == null) "Elige un respaldo para ver qué trae. Mirar no cambia nada." else "Respaldos con partidas de este juego. Mirar no cambia nada.")
        else "Respaldo del ${friendlyDate(b.at)} · ${formatSize(b.size)}. Marca lo que quieres traer al dispositivo.",
        onClose = onClose,
        footer = {
            if (b == null) {
                Muted("${list?.size ?: 0} respaldo(s) en el servidor", Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cerrar") }
            } else {
                TextButton(onClick = { picked = null; units = null }) { Text("‹ Otro respaldo") }
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} de ${units?.size ?: 0} marcadas", fontWeight = FontWeight.Bold)
                    Muted("Antes se respalda lo que tienes ahora")
                }
                BigActionButton("Restaurar (${selected.size})", Icons.Rounded.Restore, enabled = selected.isNotEmpty()) { confirm = true }
            }
        },
    ) {
        when {
            error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            b == null && list == null -> items(4) { SkeletonRow() }
            b == null && list!!.isEmpty() -> item { Muted("No hay respaldos en el servidor.") }
            b == null -> {
                val shown = list!!.filter { onlyKey == null || it.keys.isEmpty() || onlyKey in it.keys }.take(40)
                if (shown.isEmpty()) item { Muted("Ningún respaldo tiene partidas de este juego todavía.") }
                // Agrupados por día ("hoy", "ayer", "lunes 13 de octubre").
                shown.groupBy { friendlyDate(it.at).substringBefore(',') }.forEach { (day, bs) ->
                    item(key = "d-$day") { Text(day.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                    items(bs, key = { it.id }) { bk -> BackupCard(bk, latest = bk.id == shown.first().id) { picked = bk } }
                }
            }
            units == null -> items(4) { SkeletonRow() }
            units!!.isEmpty() -> item { Muted("Este respaldo no tiene partidas de este juego. Prueba con otro.") }
            else -> {
                item {
                    SpacedRow {
                        Muted("${units!!.size} partida(s) en este respaldo", Modifier.weight(1f))
                        TextButton(onClick = { selected = if (selected.size == units!!.size) emptySet() else units!!.map { it.key }.toSet() }) {
                            Text(if (selected.size == units!!.size) "Desmarcar todo" else "Marcar todo")
                        }
                    }
                }
                items(units!!, key = { it.key }) { u ->
                    val on = u.key in selected
                    val game = remember(u.title) { gameForUnit(u.title) }
                    val now = current[u.key]
                    SheetCard(Modifier.clickable { selected = if (on) selected - u.key else selected + u.key }) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Checkbox(on, { selected = if (it) selected + u.key else selected - u.key })
                            if (game != null) Cover(game, Modifier.width(48.dp), showTitle = false)
                            else androidx.compose.foundation.layout.Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Icon(Icons.Rounded.SportsEsports, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(u.title, fontWeight = FontWeight.Bold)
                                Muted("Partida de ${formatAgo(u.newest)} · ${formatSize(u.bytes)}")
                                when {
                                    now == null -> Text("No la tienes en el dispositivo", color = Palette.ok, style = MaterialTheme.typography.bodySmall)
                                    now.newest > u.newest + 60_000 -> Text("Ojo: la del dispositivo es más nueva (${formatAgo(now.newest)})",
                                        color = Palette.warn, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    now.newest + 60_000 < u.newest -> Text("Más nueva que la del dispositivo", color = Palette.ok, style = MaterialTheme.typography.bodySmall)
                                    else -> Muted("Igual a la del dispositivo")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** El juego de la biblioteca que corresponde a una partida (por el nombre), para mostrar su carátula. */
private fun gameForUnit(title: String): Game? {
    fun n(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    val t = n(Saves.prettyUnitName(title))
    if (t.length < 3) return null
    return Repo.allGames().firstOrNull { n(it.title) == t } ?: Repo.allGames().firstOrNull { n(it.title).startsWith(t) || t.startsWith(n(it.title)) }
}

/** Mientras carga: un bloque gris que late (skeleton), en vez de un círculo solo. */
@Composable
fun SkeletonRow(height: androidx.compose.ui.unit.Dp = 72.dp) = SkeletonRow(Modifier.fillMaxWidth().height(height))

@Composable
fun SkeletonRow(modifier: Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.75f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    androidx.compose.foundation.layout.Box(modifier.clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f * a / 0.5f)))
}

/** Un respaldo: ícono por tipo, qué es, cuándo, qué juegos trae y su tamaño. */
@Composable
private fun BackupCard(b: SaveBackupInfo, latest: Boolean, onClick: () -> Unit) {
    val (icon, color) = when (b.kind) {
        "todo" -> Icons.Rounded.Inventory2 to Palette.ok
        "auto" -> Icons.Rounded.Schedule to Palette.ok
        "juego" -> Icons.Rounded.SportsEsports to Palette.sky
        "antes", "traspaso" -> Icons.Rounded.Shield to Palette.warn
        else -> Icons.Rounded.Inventory2 to androidx.compose.ui.graphics.Color(0xFF475569)
    }
    SheetCard(Modifier.clickable { Sound.play(Sound.Fx.OPEN); onClick() }) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)),
                contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(icon, null, tint = color) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SpacedRow {
                    Text(backupTitle(b), fontWeight = FontWeight.Bold)
                    if (latest) Pill("Más reciente", Palette.ok)
                    if (b.keep) Pill("Se conserva", Palette.blue)
                }
                Muted(listOfNotNull(friendlyDate(b.at).substringAfter(", ", ""),
                    if (b.games.isNotEmpty()) "${b.games.size} juego(s)" else "${b.files} archivo(s)", formatSize(b.size)).filter { it.isNotBlank() }.joinToString(" · "))
                b.games.map(Saves::prettyUnitName).distinct().sorted().take(4).joinToString(", ").takeIf { it.isNotEmpty() }?.let {
                    Text(it + if (b.games.size > 4) " y ${b.games.size - 4} más" else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
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
                        is Saves.Result.Done -> { Sound.play(Sound.Fx.JINGLE); "${emu.name}: respaldado (${r.files} archivos)" }
                        Saves.Result.Unchanged, Saves.Result.Empty -> "${emu.name}: no había nada que respaldar"
                        is Saves.Result.Failed -> "${emu.name}: ${r.message}"
                    }
                    Notice.info(msg)
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

/** Qué consolas guarda cada emulador (para entender qué es cada tarjeta). */
private val consoles = mapOf(
    "retroarch" to "NES, SNES, Game Boy, GBA, N64, PS1, Saturn, Genesis y más",
    "dolphin" to "GameCube y Wii", "eden" to "Nintendo Switch", "azahar" to "Nintendo 3DS", "watermelonds" to "Nintendo DS",
    "armsx2" to "PlayStation 2", "nethersx2" to "PlayStation 2 (el anterior)", "vita3k" to "PlayStation Vita",
    "gamenative" to "Juegos de PC (Steam)",
)

enum class SaveState { OK, CHANGED, NONE, EMPTY }

/** Al día si lo que hay ahora es igual a lo último respaldado (mismo hash); con cambios si no; sin respaldo si nunca. */
fun saveState(emu: Emulator): SaveState? {
    @Suppress("UNUSED_VARIABLE") val tick = Saves.backupTick
    val st = Saves.statuses[emu.id] ?: return null
    if (!st.installed || !st.supported || st.error != null) return null
    if (st.files == 0) return SaveState.EMPTY
    val last = Prefs.lastBackupHash(emu.id)
    return when {
        Prefs.lastBackupAt(emu.id) == 0L -> SaveState.NONE
        last == st.hash -> SaveState.OK
        else -> SaveState.CHANGED
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun FlowRowCompat(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Botón de acción principal (como Descargar): grande, con el color destacado. */
@Composable
fun BigActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier,
                    enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(99.dp)
    val accent = Color(UiState.accent)
    val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
        androidx.compose.ui.graphics.lerp(accent, Color.White, 0.30f), accent, androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.15f)))
    Row(
        modifier.height(60.dp).focusHighlight(shape, grow = 1.03f).clip(shape)
            .background(if (enabled) brush else androidx.compose.ui.graphics.SolidColor(Color(0xFF94A3B8)))
            .then(if (enabled) Modifier.pressable { Sound.play(Sound.Fx.MOVE); onClick() } else Modifier)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    }
}
