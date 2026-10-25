package io.github.sudodiego.romsync

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Unarchive
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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        Notice.info(if (ok) "Llaves guardadas en bios" else "Ese archivo no tiene las llaves (header_key)")
        if (ok) appScope.launch { Tidy.scan() }
    }
    val noArt = remember(Repo.library, Repo.tabletOnly, Art.versions.size, Art.bulkTotal) { Repo.allGames().count { Art.needsFetch(it) } }

    val missingKeys = if (hasSwitch && !keysOk) 1 else 0
    val gameIssues = tidy.size + dups.size + partial.size + only.size + noArt
    val systemIssues = missingKeys + junk + stray + biosMissing.size
    // Dos columnas: a la izquierda lo que hay que revisar (juegos y sistema); a la derecha el espacio.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Por el ancho de la pantalla, no del espacio que deja el panel: abrir o cerrar el panel no debe cambiar el diseño.
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1000 || maxWidth > 900.dp
        val issues: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Resumen arriba: cuántas cosas hay y si está todo bien.
                Section("") {
                    SpacedRow {
                        Icon(if (gameIssues + systemIssues == 0) Icons.Rounded.CheckCircle else Icons.Rounded.AutoFixHigh, null,
                            tint = if (gameIssues + systemIssues == 0) Palette.ok else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (gameIssues + systemIssues == 0) "Todo en orden" else "${gameIssues + systemIssues} cosas por revisar",
                                style = MaterialTheme.typography.titleLarge)
                            Muted(if (Tidy.scanning) Tidy.progress.ifEmpty { "Revisando tus juegos…" }
                                else "Nada se cambia sin que lo veas y lo confirmes.")
                        }
                        if (Tidy.scanning) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                if (gameIssues > 0) Section("Juegos") {
                    Fix(Icons.Rounded.AutoFixHigh, "Juegos por ordenar", "Comprimidos, nombres raros, fuera de su carpeta o repetidos",
                        tidy.size) { open = "tidy" }
                    Fix(Icons.Rounded.ContentCopy, "Juegos repetidos", "El mismo juego en varias regiones o formatos", dups.size) { open = "dups" }
                    Fix(Icons.Rounded.Downloading, "Juegos a medias", "Les faltan archivos en el dispositivo", partial.size) { open = "partial" }
                    Fix(Icons.Rounded.CloudUpload, "Sin copia en el servidor", "Solo están en el dispositivo: si lo pierdes, se pierden", only.size) { open = "only" }
                    Fix(Icons.Rounded.Image, "Sin imágenes", "Carátula, fondo o logo por buscar", noArt, "Buscar") {
                        Art.startFetchAll(Repo.allGames(), quiet = true)
                    }
                }
                if (systemIssues > 0) Section("Sistema") {
                    Fix(Icons.Rounded.Key, "Faltan las llaves de Switch", "prod.keys: sin ellas no se reconocen los juegos de Switch " +
                        "que no traen su código en el nombre. Se buscan en el dispositivo y, si no aparecen, puedes elegir el archivo",
                        missingKeys, "Buscar") {
                        appScope.launch {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SwitchId.findKeys() }
                            keysOk = ok
                            if (ok) {
                                Notice.info("Llaves encontradas y guardadas en bios")
                                Tidy.scan()
                            } else {
                                Notice.info("No se encontraron. Elige dónde está prod.keys")
                                pickKeys.launch(arrayOf("*/*"))
                            }
                        }
                    }
                    Fix(Icons.Rounded.Memory, "BIOS que faltan en el dispositivo",
                        biosMissing.take(3).joinToString(", ") { it.path.substringAfterLast('/') } + if (biosMissing.size > 3) "…" else "",
                        biosMissing.size, "Copiar") {
                        Sound.play(Sound.Fx.DONE)
                        DownloadService.enqueue(ctx, Download("bios", "BIOS", "bios", biosMissing, bios = true))
                    }
                    Fix(Icons.Rounded.Memory, "BIOS fuera de su carpeta", "Van a «bios», donde las buscan los emuladores", stray) { open = "bios" }
                    Fix(Icons.Rounded.DeleteSweep, "Archivos que sobran", "Descargas a medias y copias viejas de partidas", junk) { open = "junk" }
                }
            }
        }
        val space: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StorageCard()
                BiggestGamesCard()
                AppsStorageCard()
            }
        }
        PadHints(listOf("A" to "Elegir", "B" to "Panel", "L R" to "Sección"))
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().screenFocus { listState.animateScrollBy(it) }, state = listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Organizar", style = MaterialTheme.typography.headlineLarge) }
            if (wide) item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1.1f)) { issues() }
                    Box(Modifier.weight(1f)) { space() }
                }
            } else {
                item { issues() }
                item { space() }
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
        FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); onClick() }, modifier = Modifier.focusWhen("entrada:ORGANIZE")) { Text(action) }
    }
}


/**
 * El plan de "Ordenar juegos" agrupado por qué se hace (sacar de comprimidos, mover/renombrar, renombrar en el
 * servidor, copias que sobran, consejos). Cada grupo se marca entero o de a uno, y se pliega; cada fila dice
 * "antes → después". Todo viene marcado.
 */
@Composable
private fun TidyDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val plan = Tidy.plan.orEmpty()
    val doable = plan.filter { it.kind != Tidy.Kind.INFO }
    var selected by remember(plan) { mutableStateOf(doable.map { it.id }.toSet()) }
    data class G(val kind: Tidy.Kind, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: androidx.compose.ui.graphics.Color)
    val groups = listOf(
        G(Tidy.Kind.EXTRACT, "Sacar de comprimidos", Icons.Rounded.Unarchive, Palette.blue),
        G(Tidy.Kind.MOVE, "Mover y poner el nombre correcto", Icons.Rounded.DriveFileRenameOutline, Palette.ok),
        G(Tidy.Kind.RENAME_SERVER, "Renombrar en el servidor", Icons.Rounded.Dns, Palette.indigo),
        G(Tidy.Kind.DELETE, "Copias que sobran", Icons.Rounded.DeleteSweep, Palette.danger),
        G(Tidy.Kind.DELETE_SERVER, "Comprimidos que sobran en el servidor", Icons.Rounded.DeleteSweep, Palette.danger),
        G(Tidy.Kind.INFO, "Para tener en cuenta (no se cambia nada)", Icons.Rounded.Info, Palette.warn),
    )
    // Los grupos grandes empiezan plegados para que se vea el resumen de todo.
    var open by remember(plan) { mutableStateOf(groups.filter { g -> plan.count { it.kind == g.kind } in 1..6 }.map { it.kind }.toSet()) }
    val chosen = doable.filter { it.id in selected }
    ReviewSheet("Ordenar juegos",
        if (Tidy.running) Tidy.progress else "Así van a quedar. Los de Switch con su nombre oficial y su código. Nada se borra sin comprobar que es una copia exacta.",
        onClose = { if (!Tidy.running) onClose() },
        footer = {
            if (Tidy.running) {
                // Como en Descargas: cuánto va, a qué velocidad y cuánto falta.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Tidy.progress, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (Tidy.workTotal > 0) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { Tidy.workDone.toFloat() / Tidy.workTotal }, Modifier.fillMaxWidth())
                        Muted("${formatSize(Tidy.workDone)} de ${formatSize(Tidy.workTotal)}" +
                            listOf(formatSpeed(Tidy.speed), formatEta(Tidy.workTotal - Tidy.workDone, Tidy.speed)).filter { it.isNotBlank() }
                                .joinToString(" · ", prefix = " · "))
                    } else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("${chosen.size} de ${doable.size} cambios", fontWeight = FontWeight.Bold)
                    val freed = chosen.filter { it.kind == Tidy.Kind.DELETE }.sumOf { it.size }
                    Muted((if (freed > 0) "Libera ${formatSize(freed)} · " else "") + "Tarda ${Tidy.estimate(chosen)}")
                }
                TextButton(onClick = { selected = if (selected.size == doable.size) emptySet() else doable.map { it.id }.toSet() }) {
                    Text(if (selected.size == doable.size) "Desmarcar todo" else "Marcar todo")
                }
                BigActionButton("Ordenar (${chosen.size})", Icons.Rounded.AutoFixHigh, enabled = chosen.isNotEmpty()) {
                    appScope.launch {
                        val msg = Tidy.run(chosen)
                        Sound.play(Sound.Fx.DONE)
                        Notice.info(msg)
                        onClose()
                    }
                }
            }
        },
    ) {
        if (plan.isEmpty()) item { Muted("Nada que ordenar.") }
        groups.forEach { grp ->
            val list = plan.filter { it.kind == grp.kind }.sortedBy { it.title.lowercase() }
            if (list.isEmpty()) return@forEach
            item(key = "g-${grp.kind}") {
                SheetCard {
                    val ids = list.map { it.id }
                    val isOpen = grp.kind in open
                    GroupHeader(grp.icon, grp.color, grp.title, "${list.size} · ${formatSize(list.sumOf { it.size })}",
                        if (grp.kind == Tidy.Kind.INFO) null else toggleState(ids, selected), isOpen,
                        onToggle = { selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids },
                        onExpand = { open = if (isOpen) open - grp.kind else open + grp.kind })
                    if (isOpen) list.forEach { it ->
                        CheckRow(if (it.kind == Tidy.Kind.INFO) null else it.id in selected,
                            { selected = if (it.id in selected) selected - it.id else selected + it.id }) {
                            Column {
                                val before = it.from.substringAfterLast('/')
                                if (it.kind == Tidy.Kind.DELETE) Text(before, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                else {
                                    Text(it.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    // En los consejos no se cambia nada: se muestra el archivo, sin "antes".
                                    if (before != it.title) Text(if (it.kind == Tidy.Kind.INFO) before else "antes: $before", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                Text(it.note + " · " + formatSize(it.size), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                // Los consejos de formato llevan a la app que convierte.
                                if (it.kind == Tidy.Kind.INFO) when {
                                    "CHDroid" in it.note -> TextButton(onClick = { AppsStorage.openOrFind(ctx, "CHDroid") }) { Text("Abrir CHDroid") }
                                    "Dolphin" in it.note -> TextButton(onClick = { AppsStorage.openOrFind(ctx, "Dolphin", "org.dolphinemu.dolphinemu") }) { Text("Abrir Dolphin") }
                                    "Cemu" in it.note -> TextButton(onClick = { AppsStorage.openOrFind(ctx, "Cemu", "info.cemu.cemu") }) { Text("Abrir Cemu") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Los juegos que más ocupan en el dispositivo, con una barra para comparar; tocar abre su ficha (ahí se borran). */
@Composable
private fun BiggestGamesCard() {
    val top = remember(Repo.localFiles, Repo.library, Repo.tabletOnly) {
        Repo.allGames().filter { it.kind != "shortcut" }.map { it to Repo.where(it).localSize }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(8)
    }
    if (top.isEmpty()) return
    val max = top.first().second.toFloat()
    Section("Lo que más ocupa") {
        top.forEach { (g, bytes) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { Sound.play(Sound.Fx.OPEN); Nav.game = g }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(g, Modifier.width(36.dp), showTitle = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpacedRow {
                        Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(formatSize(bytes), fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.outline)) {
                        Box(Modifier.fillMaxWidth(bytes / max).height(6.dp).clip(RoundedCornerShape(99.dp))
                            .background(androidx.compose.ui.graphics.Color(Repo.system(g.system).color)))
                    }
                }
            }
        }
    }
}
