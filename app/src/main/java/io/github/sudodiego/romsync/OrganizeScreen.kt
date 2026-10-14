package io.github.sudodiego.romsync

import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
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
    // El plan de "Ordenar juegos" se arma solo al entrar (lee cabeceras; no cambia nada).
    androidx.compose.runtime.LaunchedEffect(Unit) { if (Tidy.plan == null) Tidy.scan() }
    val tidy = Tidy.plan.orEmpty().filter { it.kind != Tidy.Kind.INFO }
    // Sin prod.keys no se pueden reconocer los juegos de Switch que no traen su código en el nombre.
    val hasSwitch = remember(Repo.library, Repo.localFiles) { Repo.allGames().any { it.system == "switch" } }
    var keysOk by remember { mutableStateOf(SwitchId.headerKey != null) }
    val pickKeys = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { SwitchId.useKeys(it.readBytes()) } }.getOrDefault(false)
        keysOk = ok
        android.widget.Toast.makeText(ctx, if (ok) "Llaves guardadas en bios" else "Ese archivo no tiene las llaves (header_key)",
            android.widget.Toast.LENGTH_LONG).show()
        if (ok) appScope.launch { Tidy.scan() }
    }
    val noArt = remember(Repo.library, Repo.tabletOnly, Art.versions.size, Art.bulkTotal) { Repo.allGames().count { Art.needsFetch(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Organizar", style = MaterialTheme.typography.headlineLarge) }
        item { StorageCard() }
        item { AppsStorageCard() }
        item {
            Section("Por revisar") {
                val missingKeys = if (hasSwitch && !keysOk) 1 else 0
                val any = missingKeys + junk + stray + dups.size + only.size + partial.size + biosMissing.size + noArt + tidy.size > 0
                if (Tidy.scanning) SpacedRow {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Muted(Tidy.progress.ifEmpty { "Revisando tus juegos…" })
                }
                if (!any) SpacedRow {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Palette.ok)
                    Text("Todo en orden.", fontWeight = FontWeight.SemiBold)
                }
                Fix(Icons.Rounded.Key, "Faltan las llaves de Switch", "prod.keys: sin ellas no se reconocen los juegos de Switch " +
                    "que no traen su código en el nombre. Se buscan en el dispositivo; si no aparecen, elige el archivo",
                    missingKeys, "Buscar") {
                    appScope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SwitchId.findKeys() }
                        keysOk = ok
                        if (ok) {
                            android.widget.Toast.makeText(ctx, "Llaves encontradas y guardadas en bios", android.widget.Toast.LENGTH_LONG).show()
                            Tidy.scan()
                        } else {
                            android.widget.Toast.makeText(ctx, "No las encontré: elige dónde está prod.keys", android.widget.Toast.LENGTH_LONG).show()
                            pickKeys.launch(arrayOf("*/*"))
                        }
                    }
                }
                Fix(Icons.Rounded.AutoFixHigh, "Juegos por ordenar", "Comprimidos, nombres raros, fuera de su carpeta o repetidos",
                    tidy.size) { open = "tidy" }
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
        "tidy" -> TidyDialog { open = "" }
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


/** El plan de "Ordenar juegos": qué se va a hacer con cada archivo; todo marcado, se puede desmarcar. */
@Composable
private fun TidyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val plan = Tidy.plan.orEmpty()
    val doable = plan.filter { it.kind != Tidy.Kind.INFO }
    var selected by remember(plan) { mutableStateOf(doable.map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = { if (!Tidy.running) onClose() },
        title = { Text("Ordenar juegos") },
        text = {
            if (Tidy.running) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                Muted(Tidy.progress)
            } else LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Muted("Así van a quedar. Los juegos de Switch se nombran con su nombre oficial y su TitleID.") }
                item {
                    val all = selected.size == doable.size
                    TextButton(onClick = { selected = if (all) emptySet() else doable.map { it.id }.toSet() }) {
                        Text(if (all) "Desmarcar todo" else "Marcar todo")
                    }
                }
                items(plan, key = { it.id }) { it ->
                    val (icon, color) = when (it.kind) {
                        Tidy.Kind.EXTRACT -> Icons.Rounded.Unarchive to Palette.blue
                        Tidy.Kind.MOVE, Tidy.Kind.RENAME_SERVER -> Icons.Rounded.DriveFileRenameOutline to Palette.ok
                        Tidy.Kind.DELETE -> Icons.Rounded.DeleteSweep to MaterialTheme.colorScheme.error
                        Tidy.Kind.INFO -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = it.kind != Tidy.Kind.INFO) { selected = if (it.id in selected) selected - it.id else selected + it.id }
                        .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (it.kind != Tidy.Kind.INFO) androidx.compose.material3.Checkbox(it.id in selected,
                            { c -> selected = if (c) selected + it.id else selected - it.id })
                        Icon(icon, null, tint = color)
                        Column(Modifier.weight(1f)) {
                            Text(if (it.kind == Tidy.Kind.DELETE) "Borrar: " + it.from.substringAfterLast('/') else it.title,
                                fontWeight = FontWeight.SemiBold)
                            Muted((if (it.kind == Tidy.Kind.DELETE) "" else "${it.from.substringAfterLast('/')} → ") + it.note +
                                " · " + formatSize(it.size))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!Tidy.running) Button(enabled = selected.isNotEmpty(), onClick = {
                val chosen = doable.filter { it.id in selected }
                appScope.launch {
                    val msg = Tidy.run(chosen)
                    Sound.play(Sound.Fx.DONE)
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    onClose()
                }
            }) { Text("Ordenar (${selected.size})") }
        },
        dismissButton = { if (!Tidy.running) TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
