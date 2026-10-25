package io.github.sudodiego.romsync

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ViewModule
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
    /** Cambia en cada apertura de la app: los destacados salen en otro orden cada vez. */
    val seed = System.nanoTime()
    /** Selección múltiple (mantener presionada una tarjeta): ids de juegos. */
    var selection by mutableStateOf(setOf<String>())
}

@Composable
fun StoreScreen() {
    val scope = appScope
    val lib = Repo.library
    // Un acceso directo del frontend (GravityRush.dpt) de un juego que ya está en la biblioteca no es otra tarjeta.
    val all = remember(lib, Repo.tabletOnly) {
        val games = Repo.allGames()
        val keys = games.filter { it.kind != "shortcut" }.map { Repo.versionKey(it) }.toSet()
        games.filter { it.kind != "shortcut" || Repo.versionKey(it) !in keys }
    }
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

    // Con la cruceta, abajo desde el carrusel va a la PRIMERA plataforma (no a la que quede más al centro).
    val firstPlatform = remember { androidx.compose.ui.focus.FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (UiState.icons) 124.dp else 158.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "cabecera") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Header(onRefresh = { scope.launch { Repo.refresh() } })
                    OfflineBanner { scope.launch { Repo.refresh() } }
                    Toolbar(systems.map { it.first })
                }
            }
            if (!browsing && cards.isNotEmpty()) {
                // Inicio, como la eShop: destacados grandes y filas por tema; la grilla aparece al buscar o filtrar.
                item(span = { GridItemSpan(maxLineSpan) }, key = "destacados") { FeaturedCarousel(cards, firstPlatform) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "plataformas") { PlatformRow(systems, firstPlatform) }
                val onTablet = cards.filter { Repo.where(it).presence.let { p -> p == Presence.INSTALLED || p == Presence.PARTIAL } && it.kind != "shortcut" }
                val toGet = cards.filter { Repo.where(it).presence == Presence.MISSING && it.kind != "shortcut" }
                if (onTablet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-tablet") {
                    ShelfRow("En tu dispositivo", onTablet, { StoreState.filter = StoreFilter.INSTALLED }, ::versionsOf)
                }
                // Para descargar: tiles anchos con fondo y logo (como las promociones de la eShop), los que tienen imagen primero.
                if (toGet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-bajar") {
                    WideShelf("Para descargar", toGet.sortedByDescending { Art.cached(it.id, "hero") != null }) { StoreState.filter = StoreFilter.MISSING }
                }
                // Una sola sección para explorar por plataforma (antes eran hasta 8 filas seguidas, una por consola).
                val explore = systems.filter { it.second >= 2 && it.first.id !in Group.shortcutSystems }.map { it.first }
                if (explore.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "explorar") {
                    ExploreSection(explore, cards, ::versionsOf)
                }
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
private fun FeaturedCarousel(cards: List<Game>, below: androidx.compose.ui.focus.FocusRequester) {
    val featured = remember(cards, Art.versions.size) {
        val toGet = cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null && Repo.where(it).presence == Presence.MISSING }
        // Si ya está todo descargado, se muestran los de siempre para que el inicio no quede vacío.
        (toGet.ifEmpty { cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null } })
            .shuffled(java.util.Random(StoreState.seed)).take(6)
    }
    if (featured.isEmpty()) return
    val pager = rememberPagerState { featured.size }
    val pageFocus = remember(featured.size) { List(featured.size) { androidx.compose.ui.focus.FocusRequester() } }
    val carouselScope = androidx.compose.runtime.rememberCoroutineScope()
    // Con el foco del control encima, no pasa solo (si no, se lleva el foco a otra página).
    var focusedIn by remember { mutableStateOf(false) }
    LaunchedEffect(pager, featured.size) {
        while (true) {
            kotlinx.coroutines.delay(6000)
            if (!pager.isScrollInProgress && !focusedIn) pager.animateScrollToPage((pager.currentPage + 1) % featured.size,
                animationSpec = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(260.dp).onFocusChanged { focusedIn = it.hasFocus }
            .focusProperties { canFocus = false } /* el carrusel no, la página sí */, pageSpacing = 16.dp,
            beyondViewportPageCount = 1) { i ->
            val g = featured[i]
            val offset = (pager.currentPage - i) + pager.currentPageOffsetFraction
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f - 0.06f * kotlin.math.abs(offset).coerceAtMost(1f)
                    scaleX = s; scaleY = s
                }.focusRequester(pageFocus[i])
                    .focusProperties { down = below }
                    // Izquierda/derecha cambian de página y el foco va con ella (antes se perdía o saltaba fuera).
                    .onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = when (e.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                            else -> return@onPreviewKeyEvent false
                        }
                        val to = i + step
                        if (to < 0) return@onPreviewKeyEvent false // a la izquierda del primero: el panel lateral
                        val target = if (to >= featured.size) 0 else to
                        Sound.play(Sound.Fx.MOVE)
                        carouselScope.launch {
                            pager.animateScrollToPage(target, animationSpec = tween(350))
                            runCatching { pageFocus[target].requestFocus() }
                        }
                        true
                    }
                    .focusHighlight(RoundedCornerShape(26.dp), grow = 1.02f).clip(RoundedCornerShape(26.dp)).pressable { Sound.play(Sound.Fx.OPEN); Nav.game = g },
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

/** Fila de tiles anchos (fondo + logo + tamaño), como las promociones de la eShop. */
@Composable
private fun WideShelf(title: String, games: List<Game>, onAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpacedRow {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { Sound.play(Sound.Fx.MOVE); onAll() }) { Text("Ver todo (${games.size})") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(games.take(16), key = { it.id }) { g -> WideTile(g) }
        }
    }
}

@Composable
private fun WideTile(g: Game) {
    val sys = Repo.system(g.system)
    Box(Modifier.size(360.dp, 200.dp).focusHighlight(RoundedCornerShape(22.dp), grow = 1.03f).shadow(8.dp, RoundedCornerShape(22.dp))
        .clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(Color(sys.color), Color(0xFF0D1220))))
        .pressable { Sound.play(Sound.Fx.OPEN); Nav.game = g }) {
        ArtImage(g, "hero", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
        Box(Modifier.align(Alignment.TopStart).padding(16.dp).size(200.dp, 70.dp)) {
            ArtImage(g, "logo", Modifier.fillMaxSize(), ContentScale.Fit, Alignment.TopStart)
        }
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(g.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Pill(sys.short, Color(0x99000000))
            Pill(formatSize(g.size), Color(UiState.accent))
        }
    }
}

/**
 * Explorar: pestañas de plataforma (con el ícono de su emulador) y una sola fila de juegos que cambia con la pestaña,
 * más "Ver todo" para abrir esa plataforma entera.
 */
@Composable
private fun ExploreSection(systems: List<SystemInfo>, cards: List<Game>, versions: (Game) -> Int) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(systems.first().id) }
    if (systems.none { it.id == sel }) sel = systems.first().id
    val games = remember(sel, cards) { cards.filter { it.system == sel } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpacedRow {
            Text("Explorar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { Sound.play(Sound.Fx.MOVE); StoreState.system = sel }) { Text("Ver todo (${games.size})") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(systems, key = { it.id }) { sys ->
                val emu = remember(sys.id, EmuChoice.version) { EmuChoice.pkg(ctx, sys.id) }
                FilterChip(selected = sel == sys.id, onClick = { Sound.play(Sound.Fx.MOVE); sel = sys.id }, shape = RoundedCornerShape(99.dp),
                    label = { Text(sys.short) },
                    leadingIcon = {
                        val icon = emu?.let { EmuChoice.icon(ctx, it) }
                        if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
                        else Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(sys.color)))
                    })
            }
        }
        androidx.compose.animation.AnimatedContent(sel, label = "explorar") { id ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(cards.filter { it.system == id }.take(20), key = { it.id }) { g ->
                    GameCard(g, versions(g), Modifier.width(if (UiState.icons) 124.dp else 158.dp))
                }
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
            items(games.take(20), key = { it.id }) { g -> GameCard(g, versions(g), Modifier.width(if (UiState.icons) 124.dp else 158.dp)) }
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

/** Sin conexión con el servidor: la tienda se sigue viendo (la última lista guardada), pero se avisa. */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    val conn = Repo.conn as? Conn.Offline ?: return
    val at = Repo.savedAt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.warn.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Palette.warn)
        Column(Modifier.weight(1f)) {
            Text("Sin conexión con el servidor", fontWeight = FontWeight.Bold)
            Muted((if (at > 0) "Ves la lista guardada ${formatAgo(at)}. " else "") +
                "Lo que está en tu dispositivo funciona igual; para descargar hace falta conexión.")
        }
        TextButton(onClick = { Sound.play(Sound.Fx.MOVE); onRetry() }) { Text("Reintentar") }
    }
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
                modifier = Modifier.weight(1f).gamepadText(), singleLine = true, placeholder = { Text("Buscar") },
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
            // Carátulas (eShop) o íconos (menú de Wii U).
            FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.TOGGLE); UiState.icons = !UiState.icons; Prefs.icons = UiState.icons }) {
                Icon(if (UiState.icons) Icons.Rounded.ViewModule else Icons.Rounded.Apps, if (UiState.icons) "Ver carátulas" else "Ver íconos")
            }
            Box {
                FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.MOVE); sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar") }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(text = { Text("Por nombre") }, onClick = { StoreState.bySize = false; sortOpen = false })
                    DropdownMenuItem(text = { Text("Por tamaño") }, onClick = { StoreState.bySize = true; sortOpen = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreFilter.entries.forEach { f ->
                FilterChip(selected = StoreState.filter == f, onClick = { Sound.play(Sound.Fx.MOVE); StoreState.filter = f },
                    label = { Text(f.label) }, shape = RoundedCornerShape(99.dp))
            }
            // Plataforma elegida (desde las tarjetas del inicio): un chip con su nombre para quitarla.
            systems.firstOrNull { it.id == StoreState.system }?.let { cur ->
                Spacer(Modifier.weight(1f))
                FilterChip(selected = true, onClick = { Sound.play(Sound.Fx.MOVE); StoreState.system = "" },
                    label = { Text(cur.name) }, shape = RoundedCornerShape(99.dp),
                    leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(cur.color))) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Quitar", Modifier.size(18.dp)) })
                // Con qué app se juega esta plataforma (también se cambia manteniendo presionada su tarjeta).
                val ctx = LocalContext.current
                var pick by remember { mutableStateOf(false) }
                val emu = remember(cur.id, EmuChoice.version) { EmuChoice.pkg(ctx, cur.id) }
                FilterChip(selected = false, onClick = { Sound.play(Sound.Fx.MOVE); pick = true }, shape = RoundedCornerShape(99.dp),
                    label = { Text(emu?.let { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
                        ?: "Elegir emulador") },
                    leadingIcon = { emu?.let { EmuChoice.icon(ctx, it) }?.let { androidx.compose.foundation.Image(it, null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))) } })
                if (pick) EmuPickerDialog(cur) { pick = false }
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
        val cardShape = RoundedCornerShape(if (UiState.icons) 22.dp else 18.dp)
        Box(Modifier.focusHighlight(cardShape).shadow(if (pressed) 2.dp else 8.dp, cardShape).clip(cardShape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(interactionSource = press, indication = null,
                onClick = { if (selecting) toggle() else { Sound.play(Sound.Fx.OPEN); Nav.game = g } }, onLongClick = { toggle() })) {
            val overlay: @Composable () -> Unit = {
                DownloadOverlay(g)
                Box(Modifier.fillMaxSize().padding(8.dp)) {
                    if (selecting) Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        Modifier.align(Alignment.BottomEnd).size(30.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xCC0D1220)),
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White)
                    PresenceBadge(g, Modifier.align(Alignment.TopStart))
                    if (!UiState.icons) Pill(Repo.system(g.system).short, Color(0xAA0D1220), Modifier.align(Alignment.TopEnd))
                    if (versions > 1) Pill("×$versions", Palette.blue, Modifier.align(Alignment.BottomStart))
                }
            }
            if (UiState.icons) IconCover(g, Modifier.fillMaxWidth(), overlay) else Cover(g, Modifier.fillMaxWidth(), overlay = overlay)
        }
        Text(if (versions > 1) Group.cleanTitle(g.name).first else g.title, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            fontWeight = FontWeight.Bold, maxLines = if (UiState.icons) 1 else 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        if (!UiState.icons) Text(formatSize(g.size), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
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
                    Notice.info(if (fails == 0) "Listo: ${list.size} borrado(s)" else "$fails no se pudieron borrar")
                }
            }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Borrar") }
        },
        dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancelar") } },
    )
}

/** Plataformas como tarjetas de color (como las categorías de la eShop): tocar una muestra solo sus juegos. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun PlatformRow(systems: List<Pair<SystemInfo, Int>>, first: androidx.compose.ui.focus.FocusRequester) {
    var pickEmu by remember { mutableStateOf<SystemInfo?>(null) }
    pickEmu?.let { EmuPickerDialog(it) { pickEmu = null } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SpacedRow {
            Text("Plataformas", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Muted("Mantén presionada una para elegir su emulador")
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(systems.filter { it.first.id !in Group.shortcutSystems }, key = { _, it -> it.first.id }) { idx, (sys, n) ->
                // El fondo es el ícono de la app con que se juega (elegida por el usuario; se cambia manteniendo presionado).
                val ctx = LocalContext.current
                val emu = remember(sys.id, EmuChoice.version) { EmuChoice.pkg(ctx, sys.id) }
                val icon = emu?.let { EmuChoice.icon(ctx, it) }
                Box(Modifier.size(190.dp, 100.dp).then(if (idx == 0) Modifier.focusRequester(first) else Modifier).focusHighlight().shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(Color(sys.color), Color.Black, 0.15f),
                        androidx.compose.ui.graphics.lerp(Color(sys.color), Color.Black, 0.55f))))
                    .combinedClickable(onClick = { Sound.play(Sound.Fx.OPEN); StoreState.system = sys.id },
                        onLongClick = { Sound.play(Sound.Fx.MOVE); pickEmu = sys })) {
                    // El ícono grande, girado y recortado a la derecha, como marca de agua.
                    if (icon != null) androidx.compose.foundation.Image(icon, null,
                        Modifier.size(120.dp).align(Alignment.CenterEnd).graphicsLayer {
                            translationX = 30.dp.toPx(); rotationZ = -12f; alpha = 0.35f
                        }.clip(RoundedCornerShape(26.dp)))
                    Text(sys.short, color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.TopStart).padding(14.dp))
                    Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)))
                        Text("$n juegos", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
