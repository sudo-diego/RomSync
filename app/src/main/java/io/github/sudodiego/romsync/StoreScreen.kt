package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class StoreFilter(val label: String) {
    // "Repetidos" se fue al asistente Ordenar biblioteca: con la biblioteca ordenada no hace falta como filtro.
    ALL("Todos"), INSTALLED("En la tablet"), MISSING("Para descargar"), TABLET_ONLY("Sin copia en el PC")
}

object StoreState {
    var filter by mutableStateOf(StoreFilter.ALL)
    var system by mutableStateOf("")
    var query by mutableStateOf("")
    var bySize by mutableStateOf(false)
    var coversHidden by mutableStateOf(false)
    /** Selección múltiple (mantener presionada una tarjeta): ids de juegos. */
    var selection by mutableStateOf(setOf<String>())
}

@Composable
fun StoreScreen() {
    val ctx = LocalContext.current
    val scope = appScope
    val lib = Repo.library
    val all = remember(lib, Repo.tabletOnly) { Repo.allGames() }
    val localFiles = Repo.localFiles
    val games = remember(all, localFiles, StoreState.filter, StoreState.system, StoreState.query, StoreState.bySize) {
        val q = StoreState.query.trim().lowercase()
        all.filter { g ->
            if (StoreState.system.isNotEmpty() && g.system != StoreState.system) return@filter false
            if (q.isNotEmpty() && q !in g.title.lowercase() && q !in g.name.lowercase()) return@filter false
            val p = Repo.where(g).presence
            when (StoreState.filter) {
                StoreFilter.ALL -> true
                StoreFilter.INSTALLED -> p == Presence.INSTALLED || p == Presence.PARTIAL || p == Presence.TABLET_ONLY
                // Un acceso directo (Android, Steam) no es algo que "falte" en la tablet.
                StoreFilter.MISSING -> (p == Presence.MISSING || p == Presence.PARTIAL) && g.kind != "shortcut"
                StoreFilter.TABLET_ONLY -> p == Presence.TABLET_ONLY
            }
        }.let { list ->
            // Una tarjeta por juego: las versiones (región, formato, con mods) van juntas y se eligen adentro.
            // Se muestra la que está en la tablet o, si no, la de mejor formato.
            list.groupBy { if (it.kind == "shortcut") it.id else Repo.versionKey(it) }.values.map { vs ->
                vs.maxWith(compareBy<Game>({ Repo.where(it).presence != Presence.MISSING }, { Advice.format(it).first }))
            }
        }.let { list -> if (StoreState.bySize) list.sortedByDescending { it.size } else list.sortedBy { it.title.lowercase() } }
    }
    val versionCount = remember(all) {
        all.filter { it.kind != "shortcut" }.groupingBy { Repo.versionKey(it) }.eachCount()
    }
    val systems = remember(all) { all.map { it.system }.distinct().map { Repo.system(it) }.sortedBy { it.name.lowercase() } }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // En la tablet, las plataformas en una columna a la izquierda (como en la web) en vez de una fila que se desliza.
        if (wide) SystemList(all, Modifier.width(230.dp).fillMaxHeight())
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Header(onRefresh = { scope.launch { Repo.refresh() } })
                StorageCard()
                OrganizeCard()
                Toolbar(systems, showSystems = !wide)
            }
        }
        if (games.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
        }
        items(games, key = { it.id }) { g -> GameCard(g, if (g.kind == "shortcut") 1 else versionCount[Repo.versionKey(g)] ?: 1) }
        // Espacio para que la barra de selección no tape las últimas tarjetas.
        if (StoreState.selection.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(90.dp)) }
    }
    }
    if (StoreState.selection.isNotEmpty()) SelectionBar(all.filter { it.id in StoreState.selection })
    }
    if (Art.bulkRunning && !Art.bulkQuiet && !StoreState.coversHidden) CoversDialog()
}

/** Lista de plataformas (pantalla ancha): nombre, cuántos juegos y cuánto ocupan en la tablet. */
@Composable
private fun SystemList(all: List<Game>, modifier: Modifier) {
    val rows = remember(all, Repo.localFiles) {
        all.groupBy { it.system }.map { (id, gs) ->
            Triple(Repo.system(id), gs.size, gs.sumOf { g -> Repo.where(g).let { if (it.presence == Presence.MISSING) 0L else it.localSize } })
        }.sortedBy { it.first.name.lowercase() }
    }
    androidx.compose.foundation.lazy.LazyColumn(modifier.padding(start = 8.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SystemRow("Todas", Color(0xFF3A55C8), "${all.size}", "", StoreState.system == "") { StoreState.system = "" } }
        items(rows) { (s, n, size) ->
            SystemRow(s.name, Color(s.color), "$n", if (size > 0) formatSize(size) else "", StoreState.system == s.id) {
                StoreState.system = if (StoreState.system == s.id) "" else s.id
            }
        }
    }
}

@Composable
private fun SystemRow(name: String, color: Color, count: String, size: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
        Column(horizontalAlignment = Alignment.End) {
            Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (size.isNotEmpty()) Text(size, style = MaterialTheme.typography.labelSmall, color = Palette.ok)
        }
    }
}

/** Buscar todas las carátulas: se espera aquí con una barra y al final aparecen todas juntas. */
@Composable
private fun CoversDialog() {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { StoreState.coversHidden = true },
        title = { Text("Buscando carátulas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { Art.bulkDone.toFloat() / Art.bulkTotal.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                Muted("${Art.bulkDone} de ${Art.bulkTotal}. Al terminar aparecen todas.")
            }
        },
        confirmButton = { TextButton(onClick = { StoreState.coversHidden = true }) { Text("Seguir en segundo plano") } },
    )
}

@Composable
private fun Header(onRefresh: () -> Unit) {
    SpacedRow(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Tienda", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            val conn = Repo.conn
            Muted(when (conn) {
                is Conn.Online -> "PC leído ${formatAgo(conn.at)} · ${Repo.library?.games?.size ?: 0} juegos"
                Conn.Connecting -> Repo.scanStatus.ifEmpty { "Conectando con el PC…" }
                is Conn.Offline -> "Sin conexión con el PC · ${conn.message}"
                Conn.Idle -> "Conecta tu PC en Ajustes"
            })
        }
        val missingArt = remember(Repo.library, Repo.tabletOnly, Art.versions.size, Art.bulkTotal) {
            Repo.allGames().count { Art.needsFetch(it) }
        }
        if (Prefs.sgdbKey.isNotBlank() && missingArt > 0 && !Art.bulkRunning) TextButton(onClick = {
            StoreState.coversHidden = false
            Art.startFetchAll(Repo.allGames())
        }) { Text("Buscar imágenes ($missingArt)") }
        if (Art.bulkRunning && Art.bulkQuiet) Muted("Bajando imágenes ${Art.bulkDone} de ${Art.bulkTotal}…")
        if (Repo.refreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Actualizar") }
    }
}

/** Espacio de la tablet: lo que ocupan los ROMs por plataforma, el resto y lo libre. */
@Composable
fun StorageCard() {
    val space = Repo.space
    if (space.total <= 0) return
    val bySystem = remember(Repo.localFiles) {
        Repo.localFiles.groupBy { it.path.substringBefore('/') }.mapValues { e -> e.value.sumOf { it.size } }
            .entries.sortedByDescending { it.value }
    }
    val roms = bySystem.sumOf { it.value }
    val used = space.total - space.free
    val other = (used - roms).coerceAtLeast(0)
    val top = bySystem.take(5)
    val rest = bySystem.drop(5).sumOf { it.value }
    val segments = top.map { it.value.toFloat() / space.total to Color(Repo.system(it.key).color) } +
        listOf(rest.toFloat() / space.total to Color(0xFF64748B), other.toFloat() / space.total to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
    Section("") {
        SpacedRow {
            Text("Almacenamiento", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${formatSize(space.free)} libres de ${formatSize(space.total)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UsageBar(segments, height = 14.dp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(top) { (sys, size) ->
                SpacedRow {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(Repo.system(sys).color)))
                    Text("${Repo.system(sys).short} ${formatSize(size)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Muted("Otras apps y sistema ${formatSize(other)}", Modifier) }
        }
        val pending = DownloadService.pendingBytes()
        if (pending > 0) Muted("Descargas pendientes: ${formatSize(pending)}")
    }
}

@Composable
private fun Toolbar(systems: List<SystemInfo>, showSystems: Boolean) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpacedRow {
            OutlinedTextField(
                value = StoreState.query, onValueChange = { StoreState.query = it },
                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Buscar juego") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(99.dp),
            )
            Box {
                TextButton(onClick = { sortOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (StoreState.bySize) "Tamaño" else "Nombre")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(text = { Text("Nombre") }, onClick = { StoreState.bySize = false; sortOpen = false })
                    DropdownMenuItem(text = { Text("Tamaño (más grandes primero)") }, onClick = { StoreState.bySize = true; sortOpen = false })
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StoreFilter.entries) { f ->
                FilterChip(selected = StoreState.filter == f, onClick = { StoreState.filter = f }, label = { Text(f.label) })
            }
        }
        if (showSystems) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = StoreState.system == "", onClick = { StoreState.system = "" }, label = { Text("Todas") }) }
            items(systems) { s ->
                FilterChip(selected = StoreState.system == s.id, onClick = { StoreState.system = if (StoreState.system == s.id) "" else s.id },
                    label = { Text(s.name) })
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Muted(when {
            Repo.library == null && !Prefs.configured -> "Conecta tu PC en Ajustes para ver tu biblioteca."
            Repo.library == null -> "Todavía no se ha podido leer la biblioteca del PC."
            Repo.library?.error?.isNotBlank() == true -> "El PC dice: ${Repo.library?.error}"
            else -> "No hay juegos con este filtro."
        })
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GameCard(g: Game, versions: Int = 1) {
    val w = Repo.where(g)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    val selecting = StoreState.selection.isNotEmpty()
    val selected = g.id in StoreState.selection
    fun toggle() { StoreState.selection = if (selected) StoreState.selection - g.id else StoreState.selection + g.id }
    Column(Modifier.clip(RoundedCornerShape(14.dp))
        .then(if (selected) Modifier.border(3.dp, Palette.sky, RoundedCornerShape(14.dp)) else Modifier)
        .combinedClickable(onClick = { if (selecting) toggle() else Nav.game = g }, onLongClick = { toggle() })) {
        Cover(g, Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                if (selecting) Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    Modifier.align(Alignment.BottomEnd).size(30.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC0D1220)),
                    tint = if (selected) Palette.skyLight else Color.White)
                when {
                    dl != null && dl.status != DlStatus.FAILED -> Pill(
                        if (dl.status == DlStatus.PAUSED) "En pausa" else "${(dl.done * 100 / dl.total.coerceAtLeast(1))} %",
                        Palette.sky, Modifier.align(Alignment.TopStart))
                    w.presence == Presence.INSTALLED -> Pill("En la tablet", Palette.ok, Modifier.align(Alignment.TopStart))
                    w.presence == Presence.PARTIAL -> Pill(if (g.kind == "switch") "Falta contenido" else "A medias", Palette.warn, Modifier.align(Alignment.TopStart))
                    w.presence == Presence.TABLET_ONLY -> Pill("Solo tablet", Color(0xFF475569), Modifier.align(Alignment.TopStart))
                }
                Pill(Repo.system(g.system).short, Color(0xCC0D1220), Modifier.align(Alignment.TopEnd))
            }
        }
        Text(if (versions > 1) Group.cleanTitle(g.name).first else g.title, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp), fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(buildString {
            // El formato y la región distinguen las versiones repetidas (RVZ/ISO, USA/Europa, la con mods).
            val ext = Group.ext(g.primary).removePrefix(".").uppercase()
            if (g.kind == "shortcut") append("Acceso directo · ") else if (ext.isNotEmpty()) append("$ext · ")
            g.tags.firstOrNull()?.let { append("$it · ") }
            append(formatSize(g.size))
            if (g.parts.size > 1) append(" · ${g.parts.size} partes")
            if (g.kind == "discs") append(" · ${g.files.size} discos")
            if (versions > 1) append(" · $versions versiones")
        }, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Barra de la selección múltiple: borrar muchos juegos de una vez (de la tablet, del PC o de los dos). */
@Composable
private fun SelectionBar(sel: List<Game>) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf<String?>(null) } // "tablet" o "pc"
    val onTablet = sel.filter { Repo.where(it).presence != Presence.MISSING }
    val onPc = sel.filter { Repo.where(it).presence != Presence.TABLET_ONLY }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.Surface(Modifier.padding(16.dp), shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 8.dp) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${sel.size} seleccionado(s)", fontWeight = FontWeight.Bold)
                androidx.compose.material3.OutlinedButton(enabled = onTablet.isNotEmpty(), onClick = { ask = "tablet" }) {
                    Text("Borrar de la tablet (${onTablet.size})")
                }
                if (Prefs.configured) androidx.compose.material3.OutlinedButton(enabled = onPc.isNotEmpty(), onClick = { ask = "pc" },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Borrar del PC (${onPc.size})")
                }
                TextButton(onClick = { StoreState.selection = emptySet() }) { Text("Cancelar") }
            }
        }
    }
    val where = ask ?: return
    val list = if (where == "tablet") onTablet else onPc
    val noCopy = if (where == "tablet") list.filter { Repo.where(it).presence == Presence.TABLET_ONLY } else
        list.filter { Repo.where(it).presence == Presence.MISSING && it.kind != "shortcut" }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = null },
        title = { Text(if (where == "tablet") "¿Borrar ${list.size} de la tablet?" else "¿Borrar ${list.size} del PC?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.take(8).forEach { Text("• ${it.title}") }
                if (list.size > 8) Muted("y ${list.size - 8} más")
                Text("Las partidas no se borran.", fontWeight = FontWeight.SemiBold)
                if (noCopy.isNotEmpty()) Text("${noCopy.size} no tienen otra copia: se pierden del todo (${noCopy.take(3).joinToString { it.title }}).",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = {
                ask = null
                StoreState.selection = emptySet()
                appScope.launch {
                    var fails = 0
                    for (g in list) runCatching { if (where == "tablet") Repo.delete(g) else Repo.deleteFromPc(g) }.onFailure { fails++ }
                    android.widget.Toast.makeText(ctx, if (fails == 0) "Listo: ${list.size} borrado(s)" else "$fails no se pudieron borrar",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
        },
        dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancelar") } },
    )
}
