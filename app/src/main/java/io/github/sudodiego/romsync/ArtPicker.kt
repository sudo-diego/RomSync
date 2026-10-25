package io.github.sudodiego.romsync

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
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

    var savingUrl by remember { mutableStateOf<String?>(null) }
    val ratio = when (kind) { "hero" -> 3f; "logo" -> 2f; "icon" -> 1f; else -> 2f / 3f }
    val cell = if (kind == "grid" || kind == "icon") 150.dp else 300.dp
    ReviewSheet(
        if (chosen == null) "$what de ${g.title}" else chosen!!.second,
        if (chosen == null) "Busca el juego en SteamGridDB y elige una imagen. Se guarda en el dispositivo."
        else "Toca la que más te guste.",
        onClose = onClose,
        footer = {
            if (chosen != null) TextButton(onClick = { chosen = null; images = null }) { Text("‹ Otro juego") }
            else if (kind == "grid") TextButton(onClick = { Art.clear(g.id); onClose() }) { Text("Sin carátula") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Cerrar") }
        },
    ) {
        if (chosen == null) item {
            SpacedRow {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.weight(1f).gamepadText(),
                    label = { Text("Buscar en SteamGridDB") })
                Button(onClick = { search() }) { Text("Buscar") }
            }
        }
        when {
            error != null -> item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            chosen == null && games == null -> items(5) { SkeletonRow(56.dp) }
            chosen == null -> {
                if (games!!.isEmpty()) item { Muted("Sin resultados. Prueba con otro nombre, en inglés suele encontrar más.") }
                items(games!!.size, key = { "r$it-" + games!![it].hashCode() }) { i ->
                    val r = games!![i]
                    SheetCard(Modifier.clickable { Sound.play(Sound.Fx.OPEN); chosen = r }) {
                        SpacedRow {
                            Text(r.second, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Ver imágenes ›", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            images?.isEmpty() == true -> item { Muted("No hay imágenes de ese tipo para este juego.") }
            else -> item {
                // Cuadrícula hecha a mano (dentro de la lista de la hoja): columnas según el ancho.
                androidx.compose.foundation.layout.BoxWithConstraints {
                    val cols = (maxWidth / cell).toInt().coerceAtLeast(2)
                    val list: List<Pair<String, String>?> = images ?: List(cols * 2) { null }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        list.chunked(cols).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { img ->
                                    Box(Modifier.weight(1f).aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        if (img == null) SkeletonRow(Modifier.fillMaxSize())
                                        else {
                                            val (thumb, url) = img
                                            coil.compose.SubcomposeAsyncImage(model = thumb, contentDescription = null,
                                                contentScale = if (kind == "logo") ContentScale.Fit else ContentScale.Crop,
                                                loading = { SkeletonRow(Modifier.fillMaxSize()) },
                                                modifier = Modifier.fillMaxSize().clickable(enabled = savingUrl == null) {
                                                    Sound.play(Sound.Fx.DONE)
                                                    savingUrl = url
                                                    scope.launch {
                                                        runCatching { Art.useUrl(g.id, kind, url) }.onFailure { error = it.message }
                                                        savingUrl = null
                                                        if (error == null) {
                                                            // Elegido a mano: ese es el juego (su nombre oficial y, después, su fondo y logo).
                                                            if (kind == "grid") chosen?.let { (id, name) -> Art.choose(g.id, id, name); Repo.reapplyNames(); Art.startFetchAll(listOf(g), quiet = true) }
                                                            onClose()
                                                        }
                                                    }
                                                })
                                            if (savingUrl == url) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                                                    Text("Guardando…", color = Color.White, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    }
                                }
                                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

