package io.github.sudodiego.romsync

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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
    ALL("Todos"), INSTALLED("Descargados"), MISSING("Para descargar"), TABLET_ONLY("Sin copia")
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
    val scope = appScope
    val lib = Repo.library
    val all = remember(lib, Repo.tabletOnly) { Repo.allGames() }
    val localFiles = Repo.localFiles
    // Una tarjeta por juego: las versiones (región, formato, con mods) van juntas y se eligen adentro.
    // Se muestra la que está en la tablet o, si no, la de mejor formato.
    val cards = remember(all, localFiles) {
        all.groupBy { if (it.kind == "shortcut") it.id else Repo.versionKey(it) }.values.map { vs ->
            vs.maxWith(compareBy<Game>({ Repo.where(it).presence != Presence.MISSING }, { Advice.format(it).first }))
        }.sortedBy { it.title.lowercase() }
    }
    val versionCount = remember(all) { all.filter { it.kind != "shortcut" }.groupingBy { Repo.versionKey(it) }.eachCount() }
    fun versionsOf(g: Game) = if (g.kind == "shortcut") 1 else versionCount[Repo.versionKey(g)] ?: 1
    val browsing = StoreState.query.isNotBlank() || StoreState.filter != StoreFilter.ALL || StoreState.system.isNotEmpty()
    val games = remember(cards, StoreState.filter, StoreState.system, StoreState.query, StoreState.bySize) {
        val q = StoreState.query.trim().lowercase()
        cards.filter { g ->
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
        }.let { list -> if (StoreState.bySize) list.sortedByDescending { it.size } else list }
    }
    val systems = remember(all) {
        all.groupBy { it.system }.map { (id, gs) -> Repo.system(id) to gs.size }.sortedByDescending { it.second }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(158.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "cabecera") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Header(onRefresh = { scope.launch { Repo.refresh() } })
                    Toolbar(systems.map { it.first })
                }
            }
            if (!browsing && cards.isNotEmpty()) {
                // Inicio, como la eShop: destacados grandes y filas por tema; la grilla aparece al buscar o filtrar.
                item(span = { GridItemSpan(maxLineSpan) }, key = "destacados") { FeaturedCarousel(cards) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "ordenar") { OrganizeCard() }
                val onTablet = cards.filter { Repo.where(it).presence.let { p -> p == Presence.INSTALLED || p == Presence.PARTIAL } && it.kind != "shortcut" }
                val toGet = cards.filter { Repo.where(it).presence == Presence.MISSING && it.kind != "shortcut" }
                if (onTablet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-tablet") {
                    ShelfRow("En tu dispositivo", onTablet, { StoreState.filter = StoreFilter.INSTALLED }, ::versionsOf)
                }
                if (toGet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-bajar") {
                    ShelfRow("Para descargar", toGet.sortedByDescending { Art.cached(it.id, "hero") != null }, { StoreState.filter = StoreFilter.MISSING }, ::versionsOf)
                }
                systems.filter { it.second >= 3 && it.first.id !in Group.shortcutSystems }.take(8).forEach { (sys, _) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "fila-${sys.id}") {
                        ShelfRow(sys.name, cards.filter { it.system == sys.id }, { StoreState.system = sys.id }, ::versionsOf, Color(sys.color))
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "espacio") { StorageCard() }
            } else {
                if (games.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "vacio") { EmptyState() }
                items(games, key = { it.id }) { g -> GameCard(g, versionsOf(g), Modifier.animateItem()) }
            }
            // Espacio para que la barra de selección no tape las últimas tarjetas.
            if (StoreState.selection.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "pie") { Spacer(Modifier.height(90.dp)) }
        }
        if (StoreState.selection.isNotEmpty()) SelectionBar(all.filter { it.id in StoreState.selection })
    }
    if (Art.bulkRunning && !Art.bulkQuiet && !StoreState.coversHidden) CoversDialog()
}

/** Destacados: juegos con fondo (hero) en grande, pasan solos cada 6 s, como los banners de la eShop. */
@Composable
private fun FeaturedCarousel(cards: List<Game>) {
    val featured = remember(cards, Art.versions.size) {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        val toGet = cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null && Repo.where(it).presence == Presence.MISSING }
        // Si ya está todo descargado, se muestran los de siempre para que el inicio no quede vacío.
        (toGet.ifEmpty { cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null } })
            .shuffled(java.util.Random(day.toLong())).take(6)
    }
    if (featured.isEmpty()) return
    val pager = rememberPagerState { featured.size }
    LaunchedEffect(pager, featured.size) {
        while (true) {
            kotlinx.coroutines.delay(6000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % featured.size, animationSpec = tween(700))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(260.dp), pageSpacing = 16.dp) { i ->
            val g = featured[i]
            val offset = (pager.currentPage - i) + pager.currentPageOffsetFraction
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f - 0.06f * kotlin.math.abs(offset).coerceAtMost(1f)
                    scaleX = s; scaleY = s
                }.clip(RoundedCornerShape(26.dp)).pressable { Sound.play(Sound.Fx.OPEN); Nav.game = g },
            ) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(Repo.system(g.system).color), Color(0xFF0D1220)))))
                // El fondo se mueve un poco contra el gesto (paralaje).
                ArtImage(g, "hero", Modifier.fillMaxSize().graphicsLayer { translationX = offset * 120f })
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent), endX = 900f)))
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(80.dp).width(300.dp)) {
                        ArtImage(g, "logo", Modifier.fillMaxSize(), ContentScale.Fit, Alignment.CenterStart)
                    }
                    SpacedRow {
                        Pill(Repo.system(g.system).short, Color(0x99000000))
                        PresencePill(g)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(featured.size) { i ->
                val sel = pager.currentPage == i
                val w by animateDpAsState(if (sel) 22.dp else 8.dp, label = "punto")
                Box(Modifier.padding(horizontal = 3.dp).height(8.dp).width(w).clip(RoundedCornerShape(99.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
            }
        }
    }
}

/** Una fila de la tienda (título + juegos que se deslizan + "Ver todo"). */
@Composable
private fun ShelfRow(title: String, games: List<Game>, onAll: () -> Unit, versions: (Game) -> Int, dot: Color? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpacedRow {
            if (dot != null) Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(dot))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { Sound.play(Sound.Fx.MOVE); onAll() }) { Text("Ver todo (${games.size})") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(games.take(20), key = { it.id }) { g -> GameCard(g, versions(g), Modifier.width(158.dp)) }
        }
    }
}

/** Estado en la tarjeta, sin texto: ✓ verde en la tablet, medio ámbar a medias, % descargando, tablet gris solo ahí. */
@Composable
private fun PresenceBadge(g: Game, modifier: Modifier = Modifier) {
    val w = Repo.where(g)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    val (icon, color) = when {
        dl != null && dl.status != DlStatus.FAILED -> {
            Box(modifier.size(30.dp).clip(RoundedCornerShape(99.dp)).background(Palette.blue), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { dl.done.toFloat() / dl.total.coerceAtLeast(1) }, Modifier.size(22.dp),
                    color = Color.White, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = .3f))
            }
            return
        }
        w.presence == Presence.INSTALLED -> Icons.Rounded.CheckCircle to Palette.ok
        w.presence == Presence.PARTIAL -> Icons.Rounded.Downloading to Palette.warn
        w.presence == Presence.TABLET_ONLY -> Icons.Rounded.TabletAndroid to Color(0xFF64748B)
        else -> return
    }
    Box(modifier.size(30.dp).clip(RoundedCornerShape(99.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
    }
}

/** Botón de estado del juego en la tablet (sin texto si no está). */
@Composable
private fun PresencePill(g: Game, modifier: Modifier = Modifier) {
    val w = Repo.where(g)
    val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
    when {
        dl != null && dl.status != DlStatus.FAILED -> Pill(
            if (dl.status == DlStatus.PAUSED) "En pausa" else "${(dl.done * 100 / dl.total.coerceAtLeast(1))} %", Palette.blue, modifier)
        w.presence == Presence.INSTALLED -> Pill("En el dispositivo", Palette.ok, modifier)
        w.presence == Presence.PARTIAL -> Pill(if (g.kind == "switch") "Falta contenido" else "A medias", Palette.warn, modifier)
        w.presence == Presence.TABLET_ONLY -> Pill("Solo dispositivo", Color(0xFF64748B), modifier)
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
    val conn = Repo.conn
    SpacedRow(Modifier.fillMaxWidth()) {
        Text("Tienda", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        if (Art.bulkRunning && Art.bulkQuiet) {
            CircularProgressIndicator(progress = { Art.bulkDone.toFloat() / Art.bulkTotal.coerceAtLeast(1) },
                Modifier.size(18.dp), strokeWidth = 2.dp)
            Muted("Imágenes")
        }
        // Estado del PC en una píldora: punto de color y lo justo (el detalle, al tocarla).
        var showConn by remember { mutableStateOf(false) }
        val (dotColor, label) = when (conn) {
            is Conn.Online -> Palette.ok to "${Repo.library?.games?.size ?: 0} juegos"
            Conn.Connecting -> Palette.blue to (Repo.scanStatus.ifEmpty { "Conectando…" }.take(34))
            is Conn.Offline -> Palette.danger to "Servidor sin conexión"
            Conn.Idle -> Color(0xFF94A3B8) to "Sin servidor"
        }
        Row(
            Modifier.clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable { Sound.play(Sound.Fx.MOVE); showConn = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(99.dp)).background(dotColor))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        if (showConn) androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConn = false },
            title = { Text("Tu servidor") },
            text = {
                Text(when (conn) {
                    is Conn.Online -> "Leído ${formatAgo(conn.at)}: ${Repo.library?.games?.size ?: 0} juegos."
                    Conn.Connecting -> Repo.scanStatus.ifEmpty { "Conectando con el servidor…" }
                    is Conn.Offline -> "Sin conexión: ${conn.message}"
                    Conn.Idle -> "Conecta tu servidor en Ajustes."
                })
            },
            confirmButton = { TextButton(onClick = { showConn = false }) { Text("Cerrar") } },
        )
        FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.MOVE); onRefresh() }, enabled = !Repo.refreshing) {
            if (Repo.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, "Actualizar")
        }
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
            TextField(
                value = StoreState.query, onValueChange = { StoreState.query = it },
                modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Buscar") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (StoreState.query.isNotEmpty()) IconButton(onClick = { StoreState.query = "" }) { Icon(Icons.Rounded.Close, "Borrar") }
                },
                shape = RoundedCornerShape(99.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Box {
                FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.MOVE); sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(text = { Text("Por nombre") }, onClick = { StoreState.bySize = false; sortOpen = false })
                    DropdownMenuItem(text = { Text("Por tamaño") }, onClick = { StoreState.bySize = true; sortOpen = false })
                }
            }
        }
        var sysOpen by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreFilter.entries.forEach { f ->
                FilterChip(selected = StoreState.filter == f, onClick = { Sound.play(Sound.Fx.MOVE); StoreState.filter = f },
                    label = { Text(f.label) }, shape = RoundedCornerShape(99.dp))
            }
            Spacer(Modifier.weight(1f))
            val cur = systems.firstOrNull { it.id == StoreState.system }
            Box {
                FilterChip(selected = cur != null, onClick = { Sound.play(Sound.Fx.MOVE); sysOpen = true },
                    label = { Text(cur?.name ?: "Plataforma") }, shape = RoundedCornerShape(99.dp),
                    leadingIcon = { if (cur != null) Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(cur.color))) },
                    trailingIcon = {
                        if (cur != null) Icon(Icons.Rounded.Close, "Quitar", Modifier.size(18.dp).clickable { StoreState.system = "" })
                        else Icon(Icons.Rounded.ArrowDropDown, null)
                    })
                DropdownMenu(expanded = sysOpen, onDismissRequest = { sysOpen = false }) {
                    DropdownMenuItem(text = { Text("Todas") }, onClick = { StoreState.system = ""; sysOpen = false })
                    systems.forEach { sys ->
                        DropdownMenuItem(text = { Text(sys.name) }, onClick = { StoreState.system = sys.id; sysOpen = false },
                            leadingIcon = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(Color(sys.color))) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Muted(when {
            Repo.library == null && !Prefs.configured -> "Conecta tu servidor en Ajustes para ver tu biblioteca."
            Repo.library == null -> "Todavía no se ha podido leer la biblioteca del servidor."
            Repo.library?.error?.isNotBlank() == true -> "El servidor dice: ${Repo.library?.error}"
            else -> "No hay juegos con este filtro."
        })
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GameCard(g: Game, versions: Int = 1, modifier: Modifier = Modifier) {
    val selecting = StoreState.selection.isNotEmpty()
    val selected = g.id in StoreState.selection
    fun toggle() {
        Sound.play(Sound.Fx.TOGGLE)
        StoreState.selection = if (selected) StoreState.selection - g.id else StoreState.selection + g.id
    }
    // Al tocar, la tarjeta se hunde un poco y vuelve con rebote (como las fichas de la eShop).
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else if (selected) 0.97f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 500f), label = "tarjeta")
    // Aparece subiendo un poco la primera vez.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(320)) }
    Column(modifier.graphicsLayer {
        scaleX = scale; scaleY = scale; alpha = shown.value; translationY = (1f - shown.value) * 24f
    }) {
        Box(Modifier.shadow(if (pressed) 2.dp else 8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(interactionSource = press, indication = null,
                onClick = { if (selecting) toggle() else { Sound.play(Sound.Fx.OPEN); Nav.game = g } }, onLongClick = { toggle() })) {
            Cover(g, Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    if (selecting) Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        Modifier.align(Alignment.BottomEnd).size(30.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC0D1220)),
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White)
                    PresenceBadge(g, Modifier.align(Alignment.TopStart))
                    Pill(Repo.system(g.system).short, Color(0xAA0D1220), Modifier.align(Alignment.TopEnd))
                    if (versions > 1) Pill("×$versions", Palette.blue, Modifier.align(Alignment.BottomStart))
                }
            }
        }
        Text(if (versions > 1) Group.cleanTitle(g.name).first else g.title, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(formatSize(g.size), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text("Borrar del dispositivo (${onTablet.size})")
                }
                if (Prefs.configured) androidx.compose.material3.OutlinedButton(enabled = onPc.isNotEmpty(), onClick = { ask = "pc" },
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Borrar del servidor (${onPc.size})")
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
        title = { Text(if (where == "tablet") "¿Borrar ${list.size} del dispositivo?" else "¿Borrar ${list.size} del servidor?") },
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
