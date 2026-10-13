package io.github.sudodiego.romsync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

enum class StoreFilter(val label: String) { ALL("Todos"), INSTALLED("En la tablet"), MISSING("No en la tablet"), TABLET_ONLY("Solo en la tablet") }

object StoreState {
    var filter by mutableStateOf(StoreFilter.ALL)
    var system by mutableStateOf("")
    var query by mutableStateOf("")
    var bySize by mutableStateOf(false)
}

@Composable
fun StoreScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
                StoreFilter.MISSING -> p == Presence.MISSING || p == Presence.PARTIAL
                StoreFilter.TABLET_ONLY -> p == Presence.TABLET_ONLY
            }
        }.let { list -> if (StoreState.bySize) list.sortedByDescending { it.size } else list.sortedBy { it.title.lowercase() } }
    }
    val systems = remember(all) { all.map { it.system }.distinct().map { Repo.system(it) }.sortedBy { it.name.lowercase() } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Header(onRefresh = { scope.launch { Repo.refresh() } })
                StorageCard()
                Toolbar(systems)
            }
        }
        if (games.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
        }
        items(games, key = { it.id }) { g -> GameCard(g) }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit) {
    SpacedRow(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Tienda", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            val conn = Repo.conn
            Muted(when (conn) {
                is Conn.Online -> "PC leído ${formatAgo(conn.at)} · ${Repo.library?.games?.size ?: 0} juegos"
                Conn.Connecting -> "Leyendo el disco del PC…"
                is Conn.Offline -> "Sin conexión con el PC · ${conn.message}"
                Conn.Idle -> "Conecta tu PC en Ajustes"
            })
        }
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
private fun Toolbar(systems: List<SystemInfo>) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun GameCard(g: Game) {
    val w = Repo.where(g)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { Nav.game = g }) {
        Cover(g, Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
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
        Text(g.title, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp), fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(buildString {
            append(formatSize(g.size))
            if (g.parts.size > 1) append(" · ${g.parts.size} partes")
        }, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
