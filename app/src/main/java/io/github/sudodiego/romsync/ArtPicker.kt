package io.github.sudodiego.romsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Elegir una imagen a mano ([kind]: grid = carátula, hero = fondo, logo): buscar el juego y tocar la que guste. */
@Composable
fun ArtPicker(g: Game, kind: String = "grid", onClose: () -> Unit) {
    val what = when (kind) { "hero" -> "Fondo"; "logo" -> "Logo"; else -> "Carátula" }
    val scope = appScope
    var q by remember { mutableStateOf(g.title) }
    var games by remember { mutableStateOf<List<Pair<Int, String>>?>(null) }
    var chosen by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var images by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun search() {
        games = null; error = null; chosen = null; images = null
        scope.launch {
            runCatching { Art.search(q = q) }.onSuccess { games = it }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(Unit) { if (Prefs.sgdbKey.isBlank()) error = "Falta la clave de SteamGridDB (Ajustes)" else search() }
    LaunchedEffect(chosen) {
        val c = chosen ?: return@LaunchedEffect
        images = null
        runCatching { Art.options(c.first, kind) }.onSuccess { images = it }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (chosen == null) "$what de ${g.title}" else chosen!!.second) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chosen == null) {
                    OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth().gamepadText(),
                        label = { Text("Buscar en SteamGridDB") }, trailingIcon = { TextButton(onClick = { search() }) { Text("Buscar") } })
                }
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    saving -> CircularProgressIndicator()
                    chosen == null && games == null -> CircularProgressIndicator()
                    chosen == null -> {
                        if (games!!.isEmpty()) Muted("Sin resultados: prueba con otro nombre (en inglés suele encontrar más).")
                        Column(Modifier.heightIn(max = 360.dp)) {
                            androidx.compose.foundation.lazy.LazyColumn {
                                items(games!!.size) { i ->
                                    val r = games!![i]
                                    TextButton(onClick = { chosen = r }, Modifier.fillMaxWidth()) { Text(r.second, Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    images == null -> CircularProgressIndicator()
                    images!!.isEmpty() -> Muted("No hay imágenes de ese tipo para este juego.")
                    else -> LazyVerticalGrid(GridCells.Adaptive(if (kind == "grid") 110.dp else 220.dp), Modifier.heightIn(max = 420.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images!!) { (thumb, url) ->
                            AsyncImage(model = thumb, contentDescription = null, contentScale = if (kind == "logo") ContentScale.Fit else ContentScale.Crop,
                                // Fondo mientras baja la miniatura (sin él la ventana parecía vacía varios segundos).
                                modifier = Modifier.aspectRatio(when (kind) { "hero" -> 3f; "logo" -> 2f; else -> 2f / 3f }).clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                                    saving = true
                                    scope.launch {
                                        runCatching { Art.useUrl(g.id, kind, url) }.onFailure { error = it.message }
                                        saving = false
                                        if (error == null) {
                                            // Elegido a mano: ese es el juego (su nombre oficial y, después, su fondo y logo).
                                            if (kind == "grid") chosen?.let { (id, name) -> Art.choose(g.id, id, name); Repo.reapplyNames(); Art.startFetchAll(listOf(g), quiet = true) }
                                            onClose()
                                        }
                                    }
                                })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        dismissButton = {
            if (chosen != null) TextButton(onClick = { chosen = null; images = null }) { Text("Otro juego") }
            else if (kind == "grid") TextButton(onClick = { Art.clear(g.id); onClose() }) { Text("Sin carátula") }
        },
    )
}
