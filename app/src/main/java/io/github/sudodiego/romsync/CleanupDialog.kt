package io.github.sudodiego.romsync

import android.widget.Toast
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
    "Archivo oculto de otra app" to "Archivos que empiezan con punto: los deja otra app y no son juegos.",
    "Opciones o trucos del emulador" to "Ajustes por juego de DraStic/melonDS. Si los borras, el juego vuelve a las opciones de siempre.",
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
private fun CleanupDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = remember(Repo.localJunk, Repo.library) {
        Repo.localJunk.map { Junk(it, false, Group.junkKind(it.path.substringAfterLast('/')) ?: "") } +
            Repo.library?.junk.orEmpty().map { Junk(it, true, Group.junkKind(it.path.substringAfterLast('/')) ?: "") }
    }
    // Por defecto solo lo que seguro sobra; las copias de partidas y las opciones las marcas tú.
    var selected by remember(all) {
        mutableStateOf(all.filter { it.kind == "Descarga a medias" || it.kind == "Archivo oculto de otra app" }.toSet())
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
                                Muted((if (j.onPc) "En el PC" else "En la tablet") + " · " + formatSize(j.file.size))
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
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        onClose()
                    }
                }) { Text("Borrar ${selected.size}") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
