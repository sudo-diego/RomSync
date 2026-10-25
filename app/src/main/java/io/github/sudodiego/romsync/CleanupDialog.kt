package io.github.sudodiego.romsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Un archivo sobrante, en la tablet o en el PC. */
private data class Junk(val file: FileEntry, val onPc: Boolean, val kind: String)

private val explain = mapOf(
    "Descarga a medias" to "Quedó de una descarga que no terminó (de otra app). Se puede borrar.",
    "Copia vieja de una partida" to "Copias que hizo el emulador (\".SAV(1)\"). La partida de verdad es el .sav sin número: esa no aparece aquí.",
)

@Composable
fun CleanupCard() {
    var open by remember { mutableStateOf(false) }
    val pcJunk = Repo.library?.junk.orEmpty()
    val n = Repo.localJunk.size + pcJunk.size
    if (n == 0) return
    Section("") {
        SpacedRow {
            Column(Modifier.weight(1f)) {
                Text("Archivos que sobran: $n", fontWeight = FontWeight.Bold)
                Muted("Descargas a medias, copias viejas de partidas y opciones de emuladores. No se muestran como juegos.")
            }
            TextButton(onClick = { open = true }) { Text("Revisar") }
        }
    }
    if (open) CleanupDialog { open = false }
}

@Composable
fun CleanupDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    val all = remember(Repo.localJunk, Repo.library) {
        Repo.localJunk.map { Junk(it, false, Group.junkKind(it.path.substringAfterLast('/')) ?: "") } +
            Repo.library?.junk.orEmpty().map { Junk(it, true, Group.junkKind(it.path.substringAfterLast('/')) ?: "") }
    }
    // Por defecto solo las descargas a medias; lo demás lo marcas tú.
    var selected by remember(all) {
        mutableStateOf(all.filter { it.kind == "Descarga a medias" }.toSet())
    }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Archivos que sobran") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                all.groupBy { it.kind }.forEach { (kind, list) ->
                    item(kind) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("$kind (${list.size})", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                            explain[kind]?.let { Muted(it) }
                        }
                    }
                    items(list, key = { (if (it.onPc) "pc:" else "t:") + it.file.path }) { j ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (j in selected) selected - j else selected + j },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = j in selected, onCheckedChange = { selected = if (it) selected + j else selected - j })
                            Column(Modifier.weight(1f)) {
                                Text(j.file.path, style = MaterialTheme.typography.bodySmall)
                                Muted((if (j.onPc) "En el servidor" else "En el dispositivo") + " · " + formatSize(j.file.size))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty() && !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true
                    scope.launch {
                        val msg = runCatching {
                            Repo.deleteJunk(selected.filter { !it.onPc }.map { it.file }, selected.filter { it.onPc }.map { it.file })
                        }.fold({ "Borrado · ${formatSize(it)} libres" }, { it.message ?: "Error" })
                        busy = false
                        Notice.info(msg)
                        onClose()
                    }
                }) { Text("Borrar ${selected.size}") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}

/**
 * Para qué sirve cada archivo de un volcado de BIOS de PS2 (PCSX2/ARMSX2 solo necesitan el .bin de 4 MB; con uno
 * de cualquier región se juegan todos los juegos; los demás son opcionales).
 */
fun biosRole(name: String, size: Long): String = when (Group.ext(name)) {
    ".bin" -> if (size == 4L * 1024 * 1024) "BIOS principal (la que usa el emulador)" else "BIOS"
    ".rom1", ".rom2", ".erom" -> "Reproductor de DVD (opcional)"
    ".nvm" -> "Ajustes de la consola (opcional, se crea sola)"
    ".mec" -> "Versión del lector (opcional, se crea sola)"
    else -> "BIOS"
}

/** Región por el nombre ("USA v01.60(07-02-2002).bin" → USA); null si el nombre no lo dice. */
fun biosRegion(name: String): String? {
    val n = name.lowercase()
    return listOf("usa" to "USA", "europe" to "Europa", "japan" to "Japón", "asia" to "Asia", "china" to "China")
        .firstOrNull { it.first in n }?.second
}

/** BIOS que quedaron en la carpeta de una plataforma: se ofrecen mover a "bios". */
@Composable
fun StrayBiosCard() {
    var open by remember { mutableStateOf(false) }
    val tablet = Repo.localStrayBios
    val pc = Repo.library?.strayBios.orEmpty()
    if (tablet.isEmpty() && pc.isEmpty()) return
    Section("") {
        SpacedRow {
            Column(Modifier.weight(1f)) {
                Text("BIOS fuera de su carpeta: ${tablet.size + pc.size}", fontWeight = FontWeight.Bold)
                Muted("Están junto a los juegos (por ejemplo en ps2). No salen como juegos; puedes moverlas a la carpeta bios.")
            }
            TextButton(onClick = { open = true }) { Text("Ordenar") }
        }
    }
    if (open) StrayBiosDialog(tablet, pc) { open = false }
}

@Composable
fun StrayBiosDialog(tablet: List<FileEntry>, pc: List<FileEntry>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = appScope
    val all = remember(tablet, pc) { tablet.map { Junk(it, false, "") } + pc.map { Junk(it, true, "") } }
    var selected by remember(all) { mutableStateOf(all.toSet()) }
    var busy by remember { mutableStateOf(false) }
    // BIOS principales repetidas en la tablet: mismo contenido con otro nombre.
    val sameAs by androidx.compose.runtime.produceState(emptyMap<String, String>(), tablet) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val seen = HashMap<String, String>()
            val out = HashMap<String, String>()
            for (f in tablet.filter { it.size == 4L * 1024 * 1024 }) {
                val md5 = runCatching {
                    val md = java.security.MessageDigest.getInstance("MD5")
                    Local.romFile(f.path).inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
                    md.digest().joinToString("") { "%02x".format(it) }
                }.getOrNull() ?: continue
                seen[md5]?.let { out[f.path] = it } ?: run { seen[md5] = f.path }
            }
            out
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mover BIOS a su carpeta") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Muted("Van a «bios» (dispositivo: ${Prefs.biosDir}; servidor: ${Prefs.remoteBios}). Si un emulador estaba configurado " +
                            "para buscarlas en la carpeta de juegos (por ejemplo ARMSX2 en ps2), después elige la carpeta bios en sus ajustes.")
                        Text("PS2: el emulador solo necesita el .bin de 4 MB, y con uno de cualquier región se juegan todos los juegos " +
                            "(conviene el de tu región, por el idioma del menú). .ROM1/.ROM2/.EROM son el reproductor de DVD; .NVM y .MEC se crean solos.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(all, key = { (if (it.onPc) "pc:" else "t:") + it.file.path }) { j ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (j in selected) selected - j else selected + j },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = j in selected, onCheckedChange = { selected = if (it) selected + j else selected - j })
                        Column(Modifier.weight(1f)) {
                            Text(j.file.path, style = MaterialTheme.typography.bodySmall)
                            val name = j.file.path.substringAfterLast('/')
                            Muted(listOfNotNull(if (j.onPc) "En el servidor" else "En el dispositivo", formatSize(j.file.size), biosRole(name, j.file.size),
                                biosRegion(name)).joinToString(" · "))
                            sameAs[j.file.path]?.let { Text("Repetida: igual a ${it.substringAfterLast('/')}", color = Palette.warn,
                                style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    val msg = runCatching {
                        Repo.moveBios(selected.filter { !it.onPc }.map { it.file }, selected.filter { it.onPc }.map { it.file })
                    }.fold({ "$it BIOS movida(s) a bios" }, { it.message ?: "Error" })
                    busy = false
                    Notice.info(msg)
                    onClose()
                }
            }) { Text("Mover ${selected.size}") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}
