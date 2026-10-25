package io.github.sudodiego.romsync

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.CloudDownload
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    /** El buscador: con mando se entra con X (no con la cruceta). */
    val searchFocus = androidx.compose.ui.focus.FocusRequester()
    var searchArmed by mutableStateOf(false)
    /** Lo que hacen los botones del mando en la Tienda. Devuelve true si se usó. */
    fun onPadButton(code: Int): Boolean {
        val filters = StoreFilter.entries
        when (code) {
            android.view.KeyEvent.KEYCODE_BUTTON_L2, android.view.KeyEvent.KEYCODE_BUTTON_R2 -> {
                val step = if (code == android.view.KeyEvent.KEYCODE_BUTTON_R2) 1 else -1
                filter = filters[(filters.indexOf(filter) + step + filters.size) % filters.size]
                Sound.play(Sound.Fx.MOVE)
                // El foco va a lo primero de lo que se ve ahora.
                val home = filter == StoreFilter.ALL && system.isEmpty() && query.isBlank()
                appScope.launch { runCatching { grid.scrollToItem(0) }; requestFocusOn(if (home) "carrusel" else "primero") }
            }
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> { UiState.icons = !UiState.icons; Prefs.icons = UiState.icons; Sound.play(Sound.Fx.TOGGLE) }
            android.view.KeyEvent.KEYCODE_BUTTON_X -> { searchArmed = true; runCatching { searchFocus.requestFocus() }; Sound.play(Sound.Fx.OPEN) }
            android.view.KeyEvent.KEYCODE_BUTTON_START -> { bySize = !bySize; Sound.play(Sound.Fx.TOGGLE) }
            android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> { appScope.launch { Repo.refresh() }; Sound.play(Sound.Fx.MOVE) }
            else -> return false
        }
        return true
    }
    /** La lista y cada fila guardan dónde estaban: al volver de una ficha queda todo igual. */
    val grid = androidx.compose.foundation.lazy.grid.LazyGridState()
    private val rows = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun row(key: String) = rows.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
    /** El juego cuya carátula tenía el foco al abrir una ficha: al volver con B, el foco vuelve ahí. */
    var returnTo by mutableStateOf<String?>(null)
    /** A qué elemento le toca el foco ahora ("carrusel", "primero", "plataforma:<id>", "vertodo:<fila>"). Ver focusWhen. */
    var focusTarget by mutableStateOf<String?>("carrusel")
    /** Sube con cada pedido de foco, así pedir lo mismo dos veces también cuenta (antes, si quedaba "carrusel" sin usar,
     *  volver a pedirlo no hacía nada y la derecha desde el panel no entraba a la Tienda). */
    var focusSeq by mutableStateOf(0)
    fun requestFocusOn(key: String) { focusTarget = key; focusSeq++ }
    val isHome get() = filter == StoreFilter.ALL && system.isEmpty() && query.isBlank()
    /** Dónde volver con B al salir de una plataforma o de "Ver todo". */
    var backTarget: String? = null
    /** Abrir una plataforma o "Ver todo" como una sección: el foco va al primer juego y B vuelve a [from]. */
    fun openSection(system: String = "", filter: StoreFilter = StoreFilter.ALL, from: String) {
        if (this.system.isEmpty() && this.filter == StoreFilter.ALL && query.isBlank())
            homeScroll = grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset
        this.system = system; this.filter = filter; backTarget = from; focusTarget = "primero"
    }
    /** Dónde estaba la lista del inicio al entrar a una sección (para volver ahí). */
    var homeScroll: Pair<Int, Int>? = null
    fun home() { system = ""; filter = StoreFilter.ALL; query = ""; focusTarget = backTarget ?: "carrusel"; backTarget = null }
    /** Carátulas que ya aparecieron (su animación de entrada es solo la primera vez, no cada vez que se vuelve). */
    val appeared = HashSet<String>()
    /** Selección múltiple (mantener presionada una tarjeta): ids de juegos. */
    var selection by mutableStateOf(setOf<String>())
}

@Composable
fun StoreScreen() {
    val scope = appScope
    val lib = Repo.library
    // Un acceso directo del frontend (GravityRush.dpt) de un juego que ya está en la biblioteca no es otra tarjeta.
    val all = remember(lib, Repo.tabletOnly, UiState.showShortcuts) {
        val games = Repo.allGames()
        val keys = games.filter { it.kind != "shortcut" }.map { Repo.versionKey(it) }.toSet()
        // Los comprimidos (.rar/.7z) no son juegos que se puedan jugar: se ordenan en Organizar.
        games.filter { (it.kind != "shortcut" || (UiState.showShortcuts && Repo.versionKey(it) !in keys)) && !Repo.isArchive(it) }
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
    // También con los archivos del dispositivo: al borrar o bajar un juego, el filtro "Descargados" se vuelve a aplicar.
    val games = remember(cards, localFiles, Repo.tabletOnly, StoreState.filter, StoreState.system, StoreState.query, StoreState.bySize) {
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
    val filtered = StoreState.system.isNotEmpty() || StoreState.filter != StoreFilter.ALL || StoreState.query.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = filtered && Nav.game == null) { Sound.play(Sound.Fx.BACK); StoreState.home() }
    // La lista es la misma para el inicio y las secciones: al entrar a una sección empieza arriba; al volver, donde estaba.
    var wasFiltered by remember { mutableStateOf(filtered) }
    val swap = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(filtered, StoreState.filter, StoreState.system) {
        swap.snapTo(0f); swap.animateTo(1f, tween(280))
    }
    LaunchedEffect(filtered) {
        if (filtered == wasFiltered) return@LaunchedEffect
        wasFiltered = filtered
        if (filtered) StoreState.grid.scrollToItem(0)
        else StoreState.homeScroll?.let { (i, o) -> StoreState.grid.scrollToItem(i, o); StoreState.homeScroll = null }
    }
    // Al empezar a usar el mando sin nada enfocado en la Tienda, el foco va al carrusel (no al botón del panel).
    var storeHasFocus by remember { mutableStateOf(false) }
    // Con un mando conectado, Android pone su propio foco inicial (en el panel) un instante después: si no se ha
    // apretado nada todavía, el foco vuelve al carrusel.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000)
        if (Nav.pad && !Nav.anyKey && !filtered && Nav.game == null) StoreState.focusTarget = "carrusel"
    }
    LaunchedEffect(Nav.keys) {
        if (Nav.keys && !storeHasFocus && Nav.game == null && StoreState.focusTarget == null) StoreState.focusTarget = if (filtered) "primero" else "carrusel"
    }
    PadHints(listOfNotNull("A" to "Abrir", (if (filtered) "B" to "Inicio" else "B" to "Panel"), "X" to "Buscar", "Y" to "Vista",
        "ZL ZR" to "Filtro", "−" to "Actualizar", "+" to "Orden", "L R" to "Sección"))
    Box(Modifier.fillMaxSize().onFocusChanged { storeHasFocus = it.hasFocus }
        .screenFocus { StoreState.grid.animateScrollBy(it) }) {
        LazyVerticalGrid(
            state = StoreState.grid,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f + 0.65f * swap.value; translationY = (1f - swap.value) * 36f },
            columns = GridCells.Adaptive(if (UiState.icons) 124.dp else 158.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "cabecera") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Header(onRefresh = { scope.launch { Repo.refresh() } })
                    OfflineBanner { scope.launch { Repo.refresh() } }
                    // Lo que está haciendo al actualizar, completo (en la píldora no cabía y se cortaba).
                    if (Repo.conn == Conn.Connecting && Repo.scanStatus.isNotBlank()) SpacedRow {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Muted(Repo.scanStatus)
                    }
                    Toolbar(systems.map { it.first })
                }
            }
            if (!browsing && cards.isNotEmpty()) {
                // Inicio, como la eShop: destacados grandes y filas por tema; la grilla aparece al buscar o filtrar.
                item(span = { GridItemSpan(maxLineSpan) }, key = "destacados") { FeaturedCarousel(cards, firstPlatform) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "plataformas") { PlatformRow(systems, firstPlatform) }
                val onTablet = cards.filter { Repo.where(it).presence.let { p -> p == Presence.INSTALLED || p == Presence.PARTIAL } && it.kind != "shortcut" }
                // Los del carrusel no se repiten aquí.
                val inCarousel = featuredOf(cards).map { it.id }.toSet()
                val toGet = cards.filter { Repo.where(it).presence == Presence.MISSING && it.kind != "shortcut" && it.id !in inCarousel }
                // Lo más importante de la Tienda es descargar: primero "Para descargar", después lo que ya está.
                // Tiles anchos con fondo y logo (como las promociones de la eShop), los que tienen imagen primero.
                if (toGet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-bajar") {
                    StorePanel("Para descargar", Icons.Rounded.CloudDownload, Color(UiState.accent)) {
                        WideShelf(toGet.shuffled(java.util.Random(StoreState.seed + 1)).sortedByDescending { Art.cached(it.id, "hero") != null }) {
                            StoreState.openSection(filter = StoreFilter.MISSING, from = "vertodo:bajar")
                        }
                    }
                }
                if (onTablet.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "fila-tablet") {
                    StorePanel("En tu dispositivo", Icons.Rounded.TabletAndroid, Palette.ok) {
                        ShelfRow(onTablet.shuffled(java.util.Random(StoreState.seed)), { StoreState.openSection(filter = StoreFilter.INSTALLED, from = "vertodo:tablet") }, ::versionsOf, "tablet")
                    }
                }
                // Una sola sección para explorar por plataforma.
                val explore = systems.filter { it.second >= 2 && it.first.id !in Group.shortcutSystems }.map { it.first }
                if (explore.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "explorar") {
                    StorePanel("Explorar", Icons.Rounded.Explore, Palette.blue) { SectionTiles(cards) }
                }
            } else {
                // Dónde se está: la plataforma y/o la sección (al cambiar con ZL/ZR se ve aquí).
                item(span = { GridItemSpan(maxLineSpan) }, key = "titulo-seccion") {
                    val sys = systems.firstOrNull { it.first.id == StoreState.system }?.first
                    val filterName = when (StoreState.filter) {
                        StoreFilter.ALL -> if (sys == null) "Todos los juegos" else null
                        StoreFilter.TABLET_ONLY -> "Sin copia en el servidor"
                        else -> StoreState.filter.label
                    }
                    SpacedRow(Modifier.padding(top = 4.dp)) {
                        if (sys != null) Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(Color(sys.color)))
                        Text(listOfNotNull(sys?.name, filterName).joinToString(" · "), style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Muted("${games.size} juegos")
                    }
                }
                if (games.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "vacio") { EmptyState() }
                itemsIndexed(games, key = { _, it -> it.id }) { idx, g ->
                    GameCard(g, versionsOf(g), Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).then(if (idx == 0) Modifier.focusWhen("primero") else Modifier)
                        .gridNav(idx, games))
                }
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
    val featured = remember(cards, Art.versions.size) { featuredOf(cards) }
    if (featured.isEmpty()) return
    val pager = rememberPagerState { featured.size }
    // El carrusel es UN solo elemento para el mando: izquierda/derecha cambian de página y A abre la que se ve.
    // (Mover el foco de página en página hacía pelear al foco, a la animación y al reacomodo de la lista.)
    val carouselFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Nav.game) {
        val i = featured.indexOfFirst { it.id == StoreState.returnTo }
        if (Nav.game == null && i >= 0) { pager.scrollToPage(i); refocus { carouselFocus.requestFocus() }; StoreState.returnTo = null }
    }
    var target by remember { mutableStateOf(0) }
    val carouselScope = androidx.compose.runtime.rememberCoroutineScope()
    // Con el foco del control encima, no pasa solo.
    var focusedIn by remember { mutableStateOf(false) }
    LaunchedEffect(pager, featured.size) {
        while (true) {
            kotlinx.coroutines.delay(6000)
            if (!pager.isScrollInProgress && !focusedIn) pager.animateScrollToPage((pager.currentPage + 1) % featured.size,
                animationSpec = tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(26.dp))
            .focusRequester(carouselFocus).focusWhen("carrusel")
            .onFocusChanged { focusedIn = it.isFocused }
            .onPreviewKeyEvent { e ->
                val isCenter = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (isCenter) {
                    // Se abre al soltar el botón: abrir al apretar hacía que al soltarlo se apretara Descargar en la ficha.
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        val g = featured[pager.currentPage]
                        Sound.play(Sound.Fx.OPEN); StoreState.returnTo = g.id; Nav.game = g
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    // Abajo: a la primera plataforma si está; si no, lo que haya debajo.
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> runCatching { below.requestFocus() }.isSuccess
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val step = if (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        // Desde la página a la que ya va: dos toques seguidos saltan dos páginas.
                        val from = if (pager.isScrollInProgress) target else pager.currentPage
                        if (from + step < 0) return@onPreviewKeyEvent false // a la izquierda del primero: el panel lateral
                        target = (from + step) % featured.size
                        Sound.play(Sound.Fx.MOVE)
                        carouselScope.launch { pager.animateScrollToPage(target, animationSpec = tween(260)) }
                        true
                    }
                    else -> false
                }
            }
            .focusHighlight(RoundedCornerShape(26.dp), grow = 1f).focusable(), pageSpacing = 16.dp,
            beyondViewportPageCount = 1) { i ->
            val g = featured[i]
            val offset = (pager.currentPage - i) + pager.currentPageOffsetFraction
            Box(
                // La escala y el recorte en la misma capa: al pasar de página la imagen se salía del borde.
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1f - 0.06f * kotlin.math.abs(offset).coerceAtMost(1f)
                    scaleX = s; scaleY = s; shape = RoundedCornerShape(26.dp); clip = true
                }
                    // Con el dedo se toca la página; con el mando se usa el carrusel entero (las páginas no reciben foco).
                    .focusProperties { canFocus = false }
                    .pressable { Sound.play(Sound.Fx.OPEN); StoreState.returnTo = g.id; Nav.game = g },
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

/** Fila de tiles anchos (fondo + logo + tamaño), como las promociones de la eShop; al final "Ver todo". */
@Composable
private fun WideShelf(games: List<Game>, onAll: () -> Unit) {
    CenterRow { LazyRow(state = StoreState.row("bajar"), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        items(games.take(10), key = { it.id }) { g -> WideTile(g) }
        if (games.size > 10) item(key = "ver-todo") { SeeAllCard(games.size, Modifier.size(200.dp, 200.dp).focusWhen("vertodo:bajar"), onAll) }
    } }
}

@Composable
private fun WideTile(g: Game) {
    val sys = Repo.system(g.system)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Nav.game) {
        if (Nav.game == null && StoreState.returnTo == g.id) { refocus { focus.requestFocus() }; StoreState.returnTo = null }
    }
    Box(Modifier.size(360.dp, 200.dp).focusRequester(focus).focusHighlight(RoundedCornerShape(22.dp), grow = 1.03f).softShadow(22.dp)
        .clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(Color(sys.color), Color(0xFF0D1220))))
        .pressable { Sound.play(Sound.Fx.OPEN); StoreState.returnTo = g.id; Nav.game = g }) {
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
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
            ShelfRow(cards.filter { it.system == id }.shuffled(java.util.Random(StoreState.seed + id.hashCode())),
                { StoreState.openSection(system = id, from = "vertodo:explorar-$id") }, versions, "explorar-$id")
        }
    }
}

/** Explorar: una tarjeta por sección (todos, en el dispositivo, para descargar, sin copia); abre esa sección. */
@Composable
private fun SectionTiles(cards: List<Game>) {
    val counts = StoreFilter.entries.associateWith { f ->
        cards.count { g ->
            val p = Repo.where(g).presence
            when (f) {
                StoreFilter.ALL -> true
                StoreFilter.INSTALLED -> p == Presence.INSTALLED || p == Presence.PARTIAL || p == Presence.TABLET_ONLY
                StoreFilter.MISSING -> (p == Presence.MISSING || p == Presence.PARTIAL) && g.kind != "shortcut"
                StoreFilter.TABLET_ONLY -> p == Presence.TABLET_ONLY
            }
        }
    }
    val look = mapOf(
        StoreFilter.ALL to (Icons.Rounded.GridView to Palette.blue), StoreFilter.INSTALLED to (Icons.Rounded.TabletAndroid to Palette.ok),
        StoreFilter.MISSING to (Icons.Rounded.CloudDownload to Color(UiState.accent)), StoreFilter.TABLET_ONLY to (Icons.Rounded.CloudOff to Palette.warn),
    )
    CenterRow { LazyRow(state = StoreState.row("secciones"), horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        items(StoreFilter.entries.filter { (counts[it] ?: 0) > 0 }, key = { it.name }) { f ->
            val (icon, tint) = look.getValue(f)
            Column(Modifier.size(220.dp, 120.dp).focusWhen("seccion:${f.name}").focusHighlight(RoundedCornerShape(20.dp), grow = 1.04f)
                .softShadow(20.dp, 6.dp).clip(RoundedCornerShape(20.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(tint, androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.35f))))
                .pressable { Sound.play(Sound.Fx.OPEN); StoreState.openSection(filter = f, from = "seccion:${f.name}") }
                .padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                Column {
                    Text(if (f == StoreFilter.ALL) "Todos los juegos" else if (f == StoreFilter.TABLET_ONLY) "Sin copia en el servidor" else f.label,
                        color = Color.White, fontWeight = FontWeight.Bold)
                    Text("${counts[f]} juegos", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } }
}

/** Dentro de una fila (de lado), lo enfocado queda centrado. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun CenterRow(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenteredFocus) { content() }

/**
 * En la cuadrícula, arriba y abajo van al juego de la misma columna (la búsqueda de Android a veces elegía el primero
 * de la fila). En la primera fila, la lista sube del todo para que se vea el encabezado.
 */
@Composable
private fun Modifier.gridNav(idx: Int, games: List<Game>): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.onFocusChanged { f ->
        if (!f.isFocused) return@onFocusChanged
        val cols = StoreState.grid.layoutInfo.let { li -> li.visibleItemsInfo.filter { it.key is String && games.any { g -> g.id == it.key } }
            .groupBy { it.row }.values.maxOfOrNull { it.size } ?: 1 }
        if (idx < cols) scope.launch { StoreState.grid.animateScrollToItem(0) }
    }.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val down = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        val up = e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
        if (!down && !up) return@onPreviewKeyEvent false
        val li = StoreState.grid.layoutInfo
        val cols = li.visibleItemsInfo.filter { info -> games.any { it.id == info.key } }.groupBy { it.row }.values.maxOfOrNull { it.size } ?: 1
        val to = idx + if (down) cols else -cols
        if (to < 0) { scope.launch { StoreState.grid.animateScrollToItem(0) }; return@onPreviewKeyEvent true }
        val target = games.getOrNull(to.coerceAtMost(games.size - 1)) ?: return@onPreviewKeyEvent true
        if (down && idx / cols == (games.size - 1) / cols) return@onPreviewKeyEvent true // última fila
        StoreState.focusTarget = "juego:${target.id}"
        // Desplazar solo lo justo para que la fila de destino se vea entera (antes saltaba una fila de más y se
        // redibujaban las carátulas); si ya se ve, nada.
        val info = li.visibleItemsInfo.firstOrNull { it.key == target.id }
        val top = li.viewportStartOffset + 24; val bottom = li.viewportEndOffset - 24
        if (info == null) {
            // La fila no se ve: se desplaza suave una fila (saltar con scrollToItem descartaba las carátulas un
            // instante y se veía todo redibujarse).
            val cur = li.visibleItemsInfo.firstOrNull { it.key == games[idx].id }
            val step = ((cur?.size?.height ?: 600) + 24).toFloat()
            scope.launch { StoreState.grid.animateScrollBy(if (down) step else -step) }
        } else if (info.offset.y < top) scope.launch { StoreState.grid.animateScrollBy((info.offset.y - top).toFloat()) }
        else if (info.offset.y + info.size.height > bottom) scope.launch { StoreState.grid.animateScrollBy((info.offset.y + info.size.height - bottom).toFloat()) }
        Sound.play(Sound.Fx.MOVE)
        true
    }
}

/**
 * Devolver el foco después de cerrar una ficha: espera a que termine la transición (si no, al desaparecer la ficha
 * Android mandaba el foco al panel lateral) y reintenta unas veces hasta que quede.
 */
suspend fun refocus(request: () -> Unit) {
    // Si el usuario se mueve mientras tanto, manda él: no se le devuelve el foco.
    val stamp = Nav.moveStamp
    kotlinx.coroutines.delay(30)
    repeat(12) {
        if (Nav.moveStamp != stamp) return
        if (runCatching { request() }.isSuccess) {
            kotlinx.coroutines.delay(150)
            if (Nav.moveStamp == stamp) runCatching { request() }
            return
        }
        kotlinx.coroutines.delay(50)
    }
}

/** Los destacados del carrusel: 6 para descargar con imagen grande (si ya está todo descargado, de los que hay). */
private fun featuredOf(cards: List<Game>): List<Game> {
    val toGet = cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null && Repo.where(it).presence == Presence.MISSING }
    return (toGet.ifEmpty { cards.filter { it.kind != "shortcut" && Art.cached(it.id, "hero") != null } })
        .shuffled(java.util.Random(StoreState.seed)).take(6)
}

/** Un panel del inicio: título con ícono de color y su contenido, para que cada sección se distinga. */
@Composable
private fun StorePanel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color,
                       content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(tint.copy(alpha = 0.07f)).padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpacedRow(Modifier.padding(horizontal = 18.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** La tarjeta "Ver todo" al final de una fila (en vez de un botón en el título: con mando era raro llegar a él). */
@Composable
private fun SeeAllCard(count: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.focusHighlight(RoundedCornerShape(18.dp), grow = 1.04f).clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surface).pressable { Sound.play(Sound.Fx.OPEN); onClick() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.GridView, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Text("Ver todo", fontWeight = FontWeight.Bold)
            Muted("$count juegos")
        }
    }
}

/** Fila de juegos que se desliza: hasta 10 y al final "Ver todo". Con margen para que la tarjeta con foco no se corte. */
@Composable
private fun ShelfRow(games: List<Game>, onAll: () -> Unit, versions: (Game) -> Int, key: String = "") {
    val w = if (UiState.icons) 124.dp else 158.dp
    CenterRow { LazyRow(state = StoreState.row("fila-$key"), horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)) {
        items(games.take(10), key = { it.id }) { g -> GameCard(g, versions(g), Modifier.width(w)) }
        if (games.size > 10) item(key = "ver-todo") {
            SeeAllCard(games.size, Modifier.width(w).aspectRatio(if (UiState.icons) 1f else 2f / 3f).focusWhen("vertodo:$key"), onAll)
        }
    } }
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
                "Lo que está en tu dispositivo funciona igual. Para descargar hace falta conexión.")
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
            Conn.Connecting -> Palette.blue to "Actualizando…"
            is Conn.Offline -> Palette.danger to "Servidor sin conexión"
            Conn.Idle -> Color(0xFF94A3B8) to "Sin servidor"
        }
        Row(
            Modifier.notForDpad().clip(RoundedCornerShape(99.dp)).background(MaterialTheme.colorScheme.surface)
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
        FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.MOVE); onRefresh() }, enabled = !Repo.refreshing, modifier = Modifier.notForDpad()) {
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
            items(top, key = { it.key }) { (sys, size) ->
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
                modifier = Modifier.weight(1f).focusRequester(StoreState.searchFocus)
                    .focusProperties { canFocus = !Nav.keys || StoreState.searchArmed }
                    // Mientras se usa, sigue activo.
                    .onFocusChanged { StoreState.searchArmed = it.isFocused }
                    .gamepadText(), singleLine = true, placeholder = { Text("Buscar") },
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
            FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.TOGGLE); UiState.icons = !UiState.icons; Prefs.icons = UiState.icons },
                modifier = Modifier.notForDpad()) {
                Icon(if (UiState.icons) Icons.Rounded.ViewModule else Icons.Rounded.Apps, if (UiState.icons) "Ver carátulas" else "Ver íconos")
            }
            Box {
                FilledTonalIconButton(onClick = { Sound.play(Sound.Fx.MOVE); sortOpen = true }, modifier = Modifier.notForDpad()) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(text = { Text("Por nombre") }, onClick = { StoreState.bySize = false; sortOpen = false })
                    DropdownMenuItem(text = { Text("Por tamaño") }, onClick = { StoreState.bySize = true; sortOpen = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Las secciones (todos, descargados, para descargar, sin copia) se abren desde "Explorar" en el inicio; aquí solo
            // se ve en cuál se está, y ZL/ZR cambian de una a otra.
            if (StoreState.filter != StoreFilter.ALL || StoreState.system.isNotEmpty())
                FilterChip(selected = true, onClick = { Sound.play(Sound.Fx.MOVE); StoreState.home() },
                    label = { Text(if (StoreState.system.isNotEmpty()) "Inicio" else StoreState.filter.label) }, shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.notForDpad(), trailingIcon = { Icon(Icons.Rounded.Close, "Volver al inicio", Modifier.size(18.dp)) })
            // Plataforma elegida (desde las tarjetas del inicio): un chip con su nombre para quitarla.
            systems.firstOrNull { it.id == StoreState.system }?.let { cur ->
                Spacer(Modifier.weight(1f))
                FilterChip(selected = true, onClick = { Sound.play(Sound.Fx.MOVE); StoreState.home() }, modifier = Modifier.notForDpad(),
                    label = { Text(cur.name) }, shape = RoundedCornerShape(99.dp),
                    leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(Color(cur.color))) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Quitar", Modifier.size(18.dp)) })
                // Con qué app se juega esta plataforma (también se cambia manteniendo presionada su tarjeta).
                val ctx = LocalContext.current
                var pick by remember { mutableStateOf(false) }
                val emu = remember(cur.id, EmuChoice.version) { EmuChoice.pkg(ctx, cur.id) }
                FilterChip(selected = false, onClick = { Sound.play(Sound.Fx.MOVE); pick = true }, shape = RoundedCornerShape(99.dp), modifier = Modifier.notForDpad(),
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
    val first = remember { g.id !in StoreState.appeared }
    val shown = remember { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(Unit) { if (first) { StoreState.appeared += g.id; shown.animateTo(1f, tween(320)) } }
    // Al volver de su ficha con el mando, el foco vuelve a esta carátula.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Nav.game) {
        if (Nav.game == null && StoreState.returnTo == g.id) { refocus { focus.requestFocus() }; StoreState.returnTo = null }
    }
    LaunchedEffect(StoreState.focusTarget) {
        if (StoreState.focusTarget == "juego:${g.id}") {
            kotlinx.coroutines.delay(40)
            if (runCatching { focus.requestFocus() }.isSuccess) StoreState.focusTarget = null
        }
    }
    Column(modifier.graphicsLayer {
        scaleX = scale; scaleY = scale; alpha = shown.value; translationY = (1f - shown.value) * 24f
        // Sin capa aparte al desvanecer: con ella la sombra quedaba recortada en un rectángulo al pasar rápido.
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }) {
        val cardShape = RoundedCornerShape(if (UiState.icons) 22.dp else 18.dp)
        // La sombra crece con la aparición de la tarjeta (en la misma capa): al pasar rápido se veían sombras sin tarjeta.
        Box(Modifier.focusRequester(focus).focusHighlight(cardShape).softShadow(if (UiState.icons) 22.dp else 18.dp, if (pressed) 2.dp else 8.dp)
            .clip(cardShape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(interactionSource = press, indication = null,
                onClick = { if (selecting) toggle() else { Sound.play(Sound.Fx.OPEN); StoreState.returnTo = g.id; Nav.game = g } }, onLongClick = { toggle() })) {
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
                OutlinedButton(enabled = onTablet.isNotEmpty(), onClick = { ask = "tablet" }) {
                    Text("Borrar del dispositivo (${onTablet.size})")
                }
                if (Prefs.configured) OutlinedButton(enabled = onPc.isNotEmpty(), onClick = { ask = "pc" },
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
                if (noCopy.isNotEmpty()) Text("${noCopy.size} no tienen otra copia y se pierden del todo (${noCopy.take(3).joinToString { it.title }}).",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
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
        CenterRow { LazyRow(state = StoreState.row("plataformas"), horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)) {
            itemsIndexed(systems.filter { it.first.id !in Group.shortcutSystems }, key = { _, it -> it.first.id }) { idx, (sys, n) ->
                // El fondo es el ícono de la app con que se juega (elegida por el usuario; se cambia manteniendo presionado).
                val ctx = LocalContext.current
                val emu = remember(sys.id, EmuChoice.version) { EmuChoice.pkg(ctx, sys.id) }
                val icon = emu?.let { EmuChoice.icon(ctx, it) }
                Box(Modifier.size(190.dp, 100.dp).then(if (idx == 0) Modifier.focusRequester(first) else Modifier).focusHighlight(RoundedCornerShape(18.dp), grow = 1.04f).softShadow(18.dp, 6.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(Color(sys.color), Color.Black, 0.15f),
                        androidx.compose.ui.graphics.lerp(Color(sys.color), Color.Black, 0.55f))))
                    .focusWhen("plataforma:${sys.id}")
                    .combinedClickable(onClick = { Sound.play(Sound.Fx.OPEN); StoreState.openSection(system = sys.id, from = "plataforma:${sys.id}") },
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
        } }
    }
}
