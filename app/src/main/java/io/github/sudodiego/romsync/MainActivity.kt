package io.github.sudodiego.romsync

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
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.Button
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
object Nav {
    var tab by mutableStateOf(Tab.STORE)
    var game by mutableStateOf<Game?>(null)
    var hasFilesAccess by mutableStateOf(true)
    /** Se está usando control o teclado (no el dedo): entonces al abrir una pantalla el foco va a su botón principal. */
    var keys by mutableStateOf(false)
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
        UiState.sidebarOpen = Prefs.sidebarOpen
        // La tablet baja a 60 Hz cuando no se toca la pantalla y el carrusel automático se veía a saltos: se pide la
        // frecuencia más alta de la pantalla (misma resolución).
        runCatching {
            val d = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
            val cur = d!!.mode
            val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
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
            RomSyncTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!Nav.hasFilesAccess) FilesAccessScreen { openFilesAccess() } else androidx.compose.foundation.layout.Box {
                        AppShell()
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

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!Nav.keys && event.keyCode != android.view.KeyEvent.KEYCODE_BACK) Nav.keys = true
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val step = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> -1
                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> 1
                else -> 0
            }
            if (step != 0) {
                val tabs = Tab.entries
                Nav.game = null
                Nav.tab = tabs[(tabs.indexOf(Nav.tab) + step + tabs.size) % tabs.size]
                Sound.play(Sound.Fx.MOVE)
                return true
            }
        }
        // A = elegir y B = volver también si el sistema no los traduce solo (algunos controles no lo hacen).
        val mapped = when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_BUTTON_A -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            android.view.KeyEvent.KEYCODE_BUTTON_B -> android.view.KeyEvent.KEYCODE_BACK
            else -> 0
        }
        if (mapped != 0) return super.dispatchKeyEvent(android.view.KeyEvent(event.downTime, event.eventTime, event.action,
            mapped, event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
        return super.dispatchKeyEvent(event)
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
fun AppShell() {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val active = DownloadService.items.count { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
    BackHandler(enabled = Nav.game != null) { Sound.play(Sound.Fx.BACK); Nav.game = null }

    fun go(t: Tab) {
        if (Nav.tab != t || Nav.game != null) Sound.play(Sound.Fx.MOVE)
        Nav.tab = t; Nav.game = null
    }

    Box(Modifier.fillMaxSize()) {
        AppBackground()
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
    val open = UiState.sidebarOpen && Nav.game == null
    val width by animateDpAsState(if (open) 232.dp else 84.dp, spring(dampingRatio = 0.8f, stiffness = 400f), label = "panel")
    Column(
        Modifier.width(width).fillMaxHeight().padding(vertical = 14.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.height(56.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                Sound.play(Sound.Fx.TOGGLE)
                if (Nav.game != null) Nav.game = null else UiState.sidebarOpen = !UiState.sidebarOpen
                Prefs.sidebarOpen = UiState.sidebarOpen
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
            Row(
                Modifier.fillMaxWidth().height(52.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .focusHighlight(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(bg)
                    .clickable(interactionSource = pressed, indication = null) { go(t) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
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
private fun TabContent() {
    // Clave de la pantalla: la pestaña, o el juego abierto. Al abrir un juego entra desde la derecha; al volver, sale.
    val key = Nav.game?.id ?: Nav.tab.name
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            val isTab = { k: String -> Tab.entries.any { it.name == k } }
            when {
                !isTab(targetState) -> (fadeIn(tween(260)) + scaleIn(tween(300), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                !isTab(initialState) -> fadeIn(tween(260)) togetherWith (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.98f))
                else -> (fadeIn(tween(260)) + slideInVertically(tween(300)) { it / 20 }) togetherWith fadeOut(tween(120))
            }
        },
        label = "pantalla",
    ) { k ->
        val tab = Tab.entries.firstOrNull { it.name == k }
        val game = if (tab == null) (Nav.game?.takeIf { it.id == k } ?: Repo.game(k)) else null
        when {
            game != null -> GameScreen(game) { Sound.play(Sound.Fx.BACK); Nav.game = null }
            tab == Tab.DOWNLOADS -> DownloadsScreen()
            tab == Tab.SAVES -> SavesScreen()
            tab == Tab.ORGANIZE -> OrganizeScreen()
            tab == Tab.KITCHEN -> KitchenScreen()
            tab == Tab.SETTINGS -> SettingsScreen()
            else -> StoreScreen()
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
