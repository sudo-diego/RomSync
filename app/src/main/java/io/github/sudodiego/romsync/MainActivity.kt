package io.github.sudodiego.romsync

import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    STORE("Tienda", Icons.Rounded.Storefront),
    DOWNLOADS("Descargas", Icons.Rounded.Download),
    SAVES("Partidas", Icons.Rounded.CloudSync),
    ORGANIZE("Organizar", Icons.Rounded.AutoFixHigh),
    KITCHEN("Web", Icons.Rounded.Language),
    SETTINGS("Ajustes", Icons.Rounded.Settings),
}

/** Estado de navegación de la app (pestaña y juego abierto). */
/** En las filas (de lado): lo enfocado queda centrado, como en consola. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val CenteredFocus = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (Nav.keys) offset - (containerSize - size) / 2f else MarginFocus.calculateScrollDistance(offset, size, containerSize)
}

/**
 * Hacia arriba y abajo: solo se mueve si lo enfocado no se ve entero (con un margen). Centrar también en vertical hacía
 * que la pantalla subiera y bajara un poco al pasar de un juego a otro (las tarjetas no miden todas lo mismo).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val MarginFocus = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.1f
        return when {
            size > containerSize - 2 * margin -> offset - margin
            offset < margin -> offset - margin
            offset + size > containerSize - margin -> offset + size - (containerSize - margin)
            else -> 0f
        }
    }
}

object Nav {
    var tab by mutableStateOf(Tab.STORE)
    private var gameState by mutableStateOf<Game?>(null)
    var game: Game?
        get() = gameState
        set(v) { if (v?.id != gameState?.id && keys) switching = true; gameState = v }
    var hasFilesAccess by mutableStateOf(true)
    /** Se está usando control o teclado (no el dedo): entonces al abrir una pantalla el foco va a su botón principal. */
    var keys by mutableStateOf(false)
    /** Ya se apretó algún botón desde que se abrió la app. */
    var anyKey = false
    /** Hay un mando conectado (las guías de botones se muestran solo así). */
    var pad by mutableStateOf(false)
    /** Cómo se llaman sus botones: "ps" (✕ ○ □ △) o "xbox" (A B X Y). Sale del último mando que se usó. */
    var padStyle by mutableStateOf("xbox")
    fun styleOf(d: android.view.InputDevice?): String? = d?.let { if (it.vendorId == 0x054C) "ps" else "xbox" }
    /** Foco del panel lateral (para volver a él con B desde la pantalla principal de una sección). */
    private val sidebar = HashMap<Tab, androidx.compose.ui.focus.FocusRequester>()
    fun sidebarFocus(t: Tab) = sidebar.getOrPut(t) { androidx.compose.ui.focus.FocusRequester() }
    var inSidebar by mutableStateOf(false)
    /** Cambiando de pantalla (abrir o cerrar una ficha): el panel no acepta el foco mientras tanto. */
    var switching by mutableStateOf(false)
    /** El panel desplegado por el mando (con B); se vuelve a plegar al salir de él. */
    var padOpen by mutableStateOf(false)
    /** El panel se ve desplegado ahora. */
    val sidebarShown get() = (if (game == null) UiState.sidebarOpen else sidebarInGame) || padOpen
    /** Lo último elegido en cada pantalla ("game" es la ficha). */
    val lastFocus = mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>()

    /** Derecha desde el panel: de vuelta a lo último que se eligió en la sección; si ya no está, a su entrada. */
    fun backToContent() {
        val fr = lastFocus[tab.name]
        if (fr == null || runCatching { fr.requestFocus() }.isFailure) enter(tab)
    }
    /** La pantalla que tiene el foco ahora (null: el panel o ninguna). */
    var focusedScreen: String? = null

    /**
     * Entrar a una sección (con A o derecha en el panel, o con L/R): el foco va a su primer elemento, de inmediato y
     * igual en todas. En la Tienda es el carrusel (o la primera carátula si se está dentro de una sección).
     */
    fun enter(t: Tab) {
        StoreState.focusTarget = if (t != Tab.STORE) entryOf(t)
            else if (StoreState.filter == StoreFilter.ALL && StoreState.system.isBlank() && StoreState.query.isBlank()) "carrusel" else "primero"
    }
    /** Al entrar a una sección con mando, el foco va a lo principal de esa pantalla. */
    fun entryOf(t: Tab) = if (t == Tab.STORE) "carrusel" else "entrada:${t.name}"
    /** El panel lateral dentro de una ficha (empieza cerrado para dejarle espacio al juego). */
    var sidebarInGame by mutableStateOf(false)

    fun checkPad() {
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            if (!d.isVirtual && d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD) {
                if (!pad) styleOf(d)?.let { st -> padStyle = st }
                break
            }
        }
        pad = android.view.InputDevice.getDeviceIds().any { id ->
            val d = android.view.InputDevice.getDevice(id) ?: return@any false
            !d.isVirtual && (d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                d.sources and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Shell.init(this)
        Names.init(this)
        SwitchDb.init(this)
        Art.init(this) // antes que Repo: los nombres oficiales de SteamGridDB se usan al cargar la biblioteca
        // Caché de imágenes de internet (miniaturas al elegir carátula) acotada: por defecto Coil usa hasta 250 MB.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(this)
            .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(48L shl 20).build() }
            .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build() }
            .build())
        appScope.launch { Art.shrinkAll() }
        Repo.init(this)
        Sound.init(this)
        DownloadService.load(this)
        BackupWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        openTab(intent)
        if (!Prefs.configured) Nav.tab = Tab.SETTINGS

        // Cada vez que se vuelve a la app: releer la tablet y el PC (las partidas y los ROMs pueden haber cambiado).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                Nav.hasFilesAccess = Environment.isExternalStorageManager()
                Shell.refresh()
                if (Nav.hasFilesAccess) {
                    Repo.refresh()
                    DownloadService.start(this@MainActivity)
                }
            }
        }

        UiState.theme = Prefs.theme
        UiState.accent = Prefs.accent
        UiState.background = Prefs.background
        UiState.icons = Prefs.icons
        UiState.fullscreen = Prefs.fullscreen
        UiState.openAnim = Prefs.openAnim
        DownloadArt.init()
        // Mando conectado o desconectado: se revisa al abrir y cada vez que cambia.
        Nav.checkPad()
        getSystemService(android.hardware.input.InputManager::class.java).registerInputDeviceListener(object :
            android.hardware.input.InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = Nav.checkPad()
            override fun onInputDeviceRemoved(id: Int) = Nav.checkPad()
            override fun onInputDeviceChanged(id: Int) = Nav.checkPad()
        }, null)
        UiState.sidebarOpen = Prefs.sidebarOpen
        // La tablet baja a 60 Hz cuando no se toca la pantalla y el carrusel automático se veía a saltos: se pide la
        // frecuencia más alta de la pantalla (misma resolución).
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val cur = d!!.mode
            // Nunca más que lo que permite el ajuste del sistema: pedir 165 Hz con el máximo en 120 dejaba la pantalla en 30 Hz.
            val peak = runCatching { android.provider.Settings.System.getFloat(contentResolver, "peak_refresh_rate") }.getOrDefault(Float.MAX_VALUE)
                .let { if (it <= 0f) Float.MAX_VALUE else it }
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight &&
                it.refreshRate <= peak + 0.5f }.maxByOrNull { it.refreshRate }
            if (best != null) window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        setContent {
            val view = androidx.compose.ui.platform.LocalView.current
            // Se lee aquí (no dentro del efecto) para que al cambiar de tema se vuelva a aplicar.
            val darkTheme = isDark()
            androidx.compose.runtime.LaunchedEffect(darkTheme) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            androidx.compose.runtime.LaunchedEffect(UiState.fullscreen) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (UiState.fullscreen) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                    if (UiState.fullscreen) {
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    } else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            RomSyncTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!Nav.hasFilesAccess) FilesAccessScreen { openFilesAccess() } else androidx.compose.foundation.layout.Box {
                        // Sin el "estiramiento" al pasar el borde del scroll (deformaba sombras y carátulas) y, al moverse con
                        // la cruceta, lo enfocado queda centrado (como en consola: nunca pegado al borde ni bajo el panel).
                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.LocalOverscrollConfiguration provides null,
                            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MarginFocus,
                        ) {
                            AppShell()
                        }
                        PadBar(Modifier.align(Alignment.BottomEnd).padding(18.dp))
                        NoticeOverlay()
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (Nav.keys) Nav.keys = false
        return super.dispatchTouchEvent(ev)
    }

    /** Gatillos analógicos (ZL/ZR en muchos mandos no son botones sino ejes): se tratan como L2/R2 al pasar la mitad. */
    private var lt = false
    private var rt = false
    /** El mando manda L2/R2 como botones (además del eje): entonces el eje se ignora. */
    private var triggerKeys = false
    private var lastTrigger = 0L
    private var heldDpad = 0
    private var heldAt = 0L

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK &&
            ev.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
            val l = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE))
            val r = maxOf(ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(android.view.MotionEvent.AXIS_GAS))
            if (!triggerKeys) {
                if (l > 0.6f && !lt) { lt = true; padButton(android.view.KeyEvent.KEYCODE_BUTTON_L2) } else if (l < 0.3f) lt = false
                if (r > 0.6f && !rt) { rt = true; padButton(android.view.KeyEvent.KEYCODE_BUTTON_R2) } else if (r < 0.3f) rt = false
            }
        }
        // La cruceta (como eje) y el stick los convierte Android en flechas, que pasan por todo el camino del foco;
        // de una diagonal se queda solo la primera dirección (filtro en dispatchKeyEvent).
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Botones del mando que no son de la cruceta (devuelve true si se usaron). */
    private fun padButton(code: Int): Boolean {
        Nav.keys = true
        // Un gatillo, una vez: nada de dos cambios seguidos por el mismo apretón.
        if (code == android.view.KeyEvent.KEYCODE_BUTTON_L2 || code == android.view.KeyEvent.KEYCODE_BUTTON_R2) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTrigger < 300) return true
            lastTrigger = now
        }
        when (code) {
            android.view.KeyEvent.KEYCODE_BUTTON_L1, android.view.KeyEvent.KEYCODE_BUTTON_R1 -> {
                val tabs = Tab.entries
                val step = if (code == android.view.KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
                Nav.game = null
                Nav.tab = tabs[(tabs.indexOf(Nav.tab) + step + tabs.size) % tabs.size]
                Nav.enter(Nav.tab)
                Sound.play(Sound.Fx.MOVE)
                return true
            }
        }
        return Nav.tab == Tab.STORE && Nav.game == null && StoreState.onPadButton(code)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD)
            Nav.styleOf(event.device)?.let { if (it != Nav.padStyle) Nav.padStyle = it }
        Nav.anyKey = true
        // B/atrás va siempre directo a "volver" de la app (Compose lo usaba para salir de un grupo de foco y se perdía).
        // En el buscador escribiendo, lo maneja el buscador (cierra el teclado y suelta el campo).
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && !StoreState.searchArmed) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (!Nav.keys && event.keyCode != android.view.KeyEvent.KEYCODE_BACK) Nav.keys = true
        // L/R, ZL/ZR, X, Y, −, +: se usan al bajar el botón; al soltarlo no hacen nada más.
        val padKeys = setOf(android.view.KeyEvent.KEYCODE_BUTTON_L1, android.view.KeyEvent.KEYCODE_BUTTON_R1,
            android.view.KeyEvent.KEYCODE_BUTTON_L2, android.view.KeyEvent.KEYCODE_BUTTON_R2, android.view.KeyEvent.KEYCODE_BUTTON_Y,
            android.view.KeyEvent.KEYCODE_BUTTON_X, android.view.KeyEvent.KEYCODE_BUTTON_START, android.view.KeyEvent.KEYCODE_BUTTON_SELECT)
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R2) triggerKeys = true
        if (event.keyCode in padKeys) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 && padButton(event.keyCode)) return true
            if (event.action == android.view.KeyEvent.ACTION_UP) return true
        }
        val dpads = setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        if (event.keyCode in dpads && event.deviceId > 0) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                // Otra dirección con una ya apretada = diagonal: no cuenta (salvo que la apretada lleve rato sin noticias:
                // se perdió su soltar y no debe bloquear las demás).
                if (heldDpad != 0 && heldDpad != event.keyCode && event.eventTime - heldAt < 700) return true
                heldDpad = event.keyCode; heldAt = event.eventTime
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                if (event.keyCode != heldDpad) return true
                heldDpad = 0
            }
        }
        // L3/R3 no hacen nada en la app (no se deja que el sistema los use como toques).
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_THUMBL || event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_THUMBR) return true
        // A = elegir y B = volver también si el sistema no los traduce solo (algunos controles no lo hacen).
        val mapped = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_A -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            android.view.KeyEvent.KEYCODE_BUTTON_B -> android.view.KeyEvent.KEYCODE_BACK
            else -> 0
        }
        if (mapped != 0) return dispatchKeyEvent(android.view.KeyEvent(event.downTime, event.eventTime, event.action,
            mapped, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
        // Cada movimiento de la cruceta suena (como en Big Picture), en cualquier pantalla.
        val handled = super.dispatchKeyEvent(event)
        if (handled && event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
            Sound.play(Sound.Fx.MOVE)
        return handled
    }

    override fun onStart() {
        super.onStart()
        Sound.onForeground(true)
    }

    override fun onStop() {
        Sound.onForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTab(intent)
    }

    private fun openTab(intent: Intent?) {
        when (intent?.getStringExtra("tab")) {
            "downloads" -> Nav.tab = Tab.DOWNLOADS
            "kitchen" -> Nav.tab = Tab.KITCHEN
        }
    }

    private fun openFilesAccess() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun AppShell() {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val active = DownloadService.items.count { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
    fun go(t: Tab) {
        if (Nav.tab != t || Nav.game != null) Sound.play(Sound.Fx.MOVE)
        Nav.tab = t; Nav.game = null
        Nav.enter(t)
    }
    // Si nadie tomó el foco de entrada (sección vacía, como Descargas sin nada), va a la sección en el panel lateral.
    androidx.compose.runtime.LaunchedEffect(StoreState.focusTarget) {
        val t = StoreState.focusTarget ?: return@LaunchedEffect
        if (!t.startsWith("entrada:")) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        // La sección no tiene nada que elegir (como Descargas vacía): el foco queda en ella en el panel.
        if (StoreState.focusTarget == t && (Nav.keys || Nav.pad)) {
            StoreState.focusTarget = null
            runCatching { Nav.sidebarFocus(Nav.tab).requestFocus() }
        }
    }
    // B en la pantalla principal de una sección: al panel lateral (no sale de la app); B en el panel: de vuelta al contenido.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    BackHandler(enabled = Nav.game == null && (Nav.keys || Nav.pad)) {
        Sound.play(Sound.Fx.BACK)
        // Como en el menú de una consola: B lleva al panel y lo despliega; en el panel ya desplegado, B cierra la app.
        // Para volver al contenido, derecha (vuelve a lo último que se eligió).
        if (Nav.inSidebar) { if (Nav.sidebarShown) activity?.finish() else Nav.padOpen = true }
        else { runCatching { Nav.sidebarFocus(Nav.tab).requestFocus() }; Nav.padOpen = true }
    }

    BackHandler(enabled = Nav.game != null) { Sound.play(Sound.Fx.BACK); Nav.game = null }
    Box(Modifier.fillMaxSize()) {
        AppBackground()
        // En pantalla completa no se reserva el espacio de las barras de Android (quedaba un hueco arriba).
        Box(Modifier.fillMaxSize().then(if (UiState.fullscreen) Modifier else Modifier.safeDrawingPadding())) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(active, ::go)
                    Box(Modifier.weight(1f)) { TabContent() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { TabContent() }
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = Nav.tab == t && Nav.game == null,
                                onClick = { go(t) },
                                icon = {
                                    if (t == Tab.DOWNLOADS && active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { Icon(t.icon, null) }
                                    else Icon(t.icon, null)
                                },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Panel lateral como el de la eShop de Switch: íconos con nombre que se colapsa a solo íconos (botón de arriba, o solo
 * al abrir un juego para dejarle espacio). La pestaña elegida lleva una píldora naranja.
 */
@Composable
private fun Sidebar(active: Int, go: (Tab) -> Unit) {
    // Con el mando, B despliega el panel (y se vuelve a plegar al salir de él). Al pasar por él de paso, no.
    androidx.compose.runtime.LaunchedEffect(Nav.inSidebar) { if (!Nav.inSidebar) Nav.padOpen = false }
    val open = Nav.sidebarShown
    val width by animateDpAsState(if (open) 232.dp else 84.dp, spring(dampingRatio = 0.8f, stiffness = 400f), label = "panel")
    Column(
        Modifier.width(width).fillMaxHeight().onFocusChanged { Nav.inSidebar = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT || !Nav.inSidebar) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) { Nav.backToContent(); Sound.play(Sound.Fx.MOVE) }
                true
            }.padding(vertical = 14.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.height(56.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Solo para el dedo: con mando el panel se abre al entrar a él (y así el foco nunca cae aquí).
            IconButton(modifier = Modifier.focusProperties { canFocus = false }, onClick = {
                Sound.play(Sound.Fx.TOGGLE)
                // Solo abre o cierra el panel (antes, con una ficha abierta, la cerraba).
                if (Nav.game != null) Nav.sidebarInGame = !Nav.sidebarInGame
                else { UiState.sidebarOpen = !UiState.sidebarOpen; Prefs.sidebarOpen = UiState.sidebarOpen }
            }) { Icon(if (open) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu, "Panel") }
            AnimatedVisibility(open, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Text("RomSync", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Tab.entries.forEach { t ->
            val selected = Nav.tab == t && Nav.game == null
            val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(220), label = "bg")
            val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "fg")
            val pressed = remember { MutableInteractionSource() }
            val isPressed by pressed.collectIsPressedAsState()
            val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "s")
            // Cerrado: el ícono centrado (con el margen del panel abierto, el recuadro de foco quedaba corrido).
            Row(
                Modifier.fillMaxWidth().height(52.dp).focusProperties { canFocus = !Nav.switching }.focusRequester(Nav.sidebarFocus(t)).graphicsLayer { scaleX = scale; scaleY = scale }
                    .focusHighlight(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(bg)
                    .clickable(interactionSource = pressed, indication = null) { go(t) }
                    .padding(horizontal = if (open) 16.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (open) Arrangement.Start else Arrangement.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides fg) {
                    if (t == Tab.DOWNLOADS && active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { Icon(t.icon, null) }
                    else Icon(t.icon, null)
                    AnimatedVisibility(open, enter = fadeIn(tween(200, 80)), exit = fadeOut(tween(80))) {
                        Text(t.label, Modifier.padding(start = 16.dp), fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun TabContent() {
    // Como en una consola, la ficha se abre ENCIMA de la sección, que sigue viva debajo: al volver todo está tal cual
    // (foco, desplazamiento), sin reconstruirse ni verse el foco viajar a su lugar.
    val key = Nav.game?.id ?: Nav.tab.name
    // Mientras se cambia de pantalla el foco no pasa por el panel (lo suelta el contenido nuevo al tomarlo; si nadie lo
    // toma, se suelta solo al rato).
    androidx.compose.runtime.LaunchedEffect(Nav.switching) { if (Nav.switching) { kotlinx.coroutines.delay(800); Nav.switching = false } }
    val inGame = Nav.game != null
    val under by animateFloatAsState(if (inGame) 0f else 1f, tween(if (inGame) 200 else 260), label = "debajo")
    Box(Modifier.fillMaxSize()) {
        // La sección: debajo de la ficha queda invisible y fuera del recorrido del mando, pero sin desmontarse.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = under; val s = 0.98f + 0.02f * under; scaleX = s; scaleY = s }
            .focusProperties { canFocus = !inGame }) {
            AnimatedContent(
                targetState = Nav.tab,
                transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 20 }) togetherWith fadeOut(tween(120)) },
                label = "seccion",
            ) { tab ->
                Box(Modifier.fillMaxSize().focusProperties { enter = { if (Nav.game != null) androidx.compose.ui.focus.FocusRequester.Cancel
                    else androidx.compose.ui.focus.FocusRequester.Default } }) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalScreenKey provides tab.name) { when (tab) {
                        Tab.DOWNLOADS -> DownloadsScreen()
                        Tab.SAVES -> SavesScreen()
                        Tab.ORGANIZE -> OrganizeScreen()
                        Tab.KITCHEN -> KitchenScreen()
                        Tab.SETTINGS -> SettingsScreen()
                        else -> StoreScreen()
                    } }
                }
            }
        }
        // La ficha encima (de una ficha se puede pasar a otra: cada una entra con su animación).
        AnimatedContent(
            targetState = Nav.game?.id,
            // Sin animar el tamaño (sin esto la ficha "crecía" desde arriba a la izquierda) y con el estilo elegido.
            transitionSpec = {
                when (UiState.openAnim) {
                    "derecha" -> (fadeIn(tween(220)) + slideInHorizontally(tween(320)) { it / 6 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 6 })
                    "abajo" -> (fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 8 }) togetherWith
                        (fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 8 })
                    "ninguna" -> fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                    else -> (fadeIn(tween(240)) + scaleIn(tween(300), initialScale = 0.94f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.96f))
                } using null
            },
            label = "ficha",
        ) { id ->
            val game = id?.let { Nav.game?.takeIf { g -> g.id == it } ?: Repo.game(it) }
            if (game != null) Box(Modifier.fillMaxSize()
                // Lo de debajo no recibe toques mientras la ficha está encima.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
                GameScreen(game) { Sound.play(Sound.Fx.BACK); Nav.game = null }
            }
        }
    }
}

@Composable
private fun FilesAccessScreen(onOpen: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.height(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("RomSync necesita ver tus archivos", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Para leer y ordenar tu carpeta de ROMs y BIOS, y respaldar las partidas que guardan RetroArch, " +
                "Azahar y WatermelonDS. Activa «Permitir el acceso para administrar todos los archivos».",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpen) { Text("Dar acceso") }
        }
    }
}
