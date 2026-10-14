package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Organizar: todo lo que deja ordenadas las ROMs, BIOS y archivos, en un solo lugar (antes estaba repartido entre la
 * Tienda y Descargas). Arriba el espacio; abajo una lista de cosas por revisar, cada una con su botón. Si no hay nada,
 * lo dice.
 */
@Composable
fun OrganizeScreen() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf("") }
    val junk = Organize.junkCount()
    val stray = Organize.strayBiosCount()
    val dups = remember(Repo.library, Repo.tabletOnly, Organize.keptVersions) { Organize.duplicateGroups() }
    val only = remember(Repo.tabletOnly, Repo.tabletExtras) { Organize.tabletOnly() }
    val partial = remember(Repo.library, Repo.localFiles) {
        Repo.allGames().filter { it.kind != "shortcut" && Repo.where(it).presence == Presence.PARTIAL }
    }
    val biosMissing = Repo.biosMissing
    val noArt = remember(Repo.library, Repo.tabletOnly, Art.versions.size, Art.bulkTotal) { Repo.allGames().count { Art.needsFetch(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Organizar", style = MaterialTheme.typography.headlineLarge) }
        item { StorageCard() }
        item {
            Section("Por revisar") {
                val any = junk + stray + dups.size + only.size + partial.size + biosMissing.size + noArt > 0
                if (!any) SpacedRow {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Palette.ok)
                    Text("Todo en orden.", fontWeight = FontWeight.SemiBold)
                }
                Fix(Icons.Rounded.DeleteSweep, "Archivos que sobran", "Descargas a medias y copias viejas de partidas", junk) { open = "junk" }
                Fix(Icons.Rounded.Memory, "BIOS fuera de su carpeta", "Van a «bios», donde las buscan los emuladores", stray) { open = "bios" }
                Fix(Icons.Rounded.Memory, "BIOS que faltan en el dispositivo",
                    biosMissing.take(3).joinToString(", ") { it.path.substringAfterLast('/') } + if (biosMissing.size > 3) "…" else "",
                    biosMissing.size, "Copiar") {
                    Sound.play(Sound.Fx.DONE)
                    DownloadService.enqueue(ctx, Download("bios", "BIOS", "bios", biosMissing, bios = true))
                }
                Fix(Icons.Rounded.ContentCopy, "Juegos repetidos", "El mismo juego en varias regiones o formatos", dups.size) { open = "dups" }
                Fix(Icons.Rounded.CloudUpload, "Sin copia en el servidor", "Solo están en el dispositivo: si lo pierdes, se pierden", only.size) { open = "only" }
                Fix(Icons.Rounded.Downloading, "Juegos a medias", "Les faltan archivos en el dispositivo", partial.size) { open = "partial" }
                Fix(Icons.Rounded.Image, "Sin imágenes", "Carátula, fondo o logo por buscar", noArt, "Buscar") {
                    Art.startFetchAll(Repo.allGames(), quiet = true)
                }
            }
        }
    }

    when (open) {
        "junk" -> CleanupDialog { open = "" }
        "bios" -> StrayBiosDialog(Repo.localStrayBios, Repo.library?.strayBios.orEmpty()) { open = "" }
        "dups" -> DuplicatesDialog { open = "" }
        "only" -> TabletOnlyDialog { open = "" }
        "partial" -> AlertDialog(
            onDismissRequest = { open = "" },
            title = { Text("Juegos a medias") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(partial, key = { it.id }) { g ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = ""; Sound.play(Sound.Fx.OPEN); Nav.game = g }
                            .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title, fontWeight = FontWeight.SemiBold)
                                Muted("${Repo.system(g.system).short} · faltan ${formatSize(Repo.where(g).missing.sumOf { it.size })}")
                            }
                            Text("Abrir", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = "" }) { Text("Cerrar") } },
        )
    }
}

/** Una fila de "Por revisar": ícono, qué es, cuántos, y el botón. No se muestra si no hay nada. */
@Composable
private fun Fix(icon: ImageVector, title: String, detail: String, count: Int, action: String = "Revisar", onClick: () -> Unit) {
    if (count == 0) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f)) {
            Text("$title ($count)", fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) Muted(detail)
        }
        FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); onClick() }) { Text(action) }
    }
}

