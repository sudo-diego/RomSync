package io.github.sudodiego.romsync

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    val scope = rememberCoroutineScope()
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
    val scope = rememberCoroutineScope()
    val st = Saves.statuses[emu.id]
    val busy = Saves.busy[emu.id]
    var editPath by remember { mutableStateOf(false) }
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
        if (st?.installed == true && st.needsShell && Shell.state != Shell.State.READY) Muted("Sin Shizuku: ${emu.exportHint}")
    }
}

@Composable
private fun RestoreDialog(emu: Emulator, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SaveBackupInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<SaveBackupInfo?>(null) }
    LaunchedEffect(emu) {
        runCatching { Pc.io { Backups.list(it, emu.id) } }.onSuccess { list = it }.onFailure { error = it.message }
    }
    val c = confirm
    if (c != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("¿Restaurar ${emu.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se copian las partidas del respaldo del ${c.at.take(16).replace('T', ' ')} (${c.files} archivos).")
                    Text("Antes se respalda lo que hay ahora, por si acaso.", fontWeight = FontWeight.SemiBold)
                    Muted("Cierra ${emu.name} antes de seguir.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    onClose()
                    scope.launch {
                        val e = Saves.restore(ctx, emu, c.id)
                        Toast.makeText(ctx, e ?: "${emu.name}: partidas restauradas", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Respaldos de ${emu.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> CircularProgressIndicator()
                    list!!.isEmpty() -> Muted("No hay respaldos en el PC.")
                    else -> list!!.take(15).forEach { b ->
                        TextButton(onClick = { confirm = b }, Modifier.fillMaxWidth()) {
                            Text("${b.at.take(16).replace('T', ' ')} · ${b.files} archivos · ${formatSize(b.size)}" +
                                (if (b.auto) " · auto" else "") + (if (b.keep) " · conservar" else ""), Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
