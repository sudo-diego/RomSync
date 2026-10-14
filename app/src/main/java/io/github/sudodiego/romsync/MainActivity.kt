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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
    KITCHEN("Web", Icons.Rounded.Language),
    SETTINGS("Ajustes", Icons.Rounded.Settings),
}

/** Estado de navegación de la app (pestaña y juego abierto). */
object Nav {
    var tab by mutableStateOf(Tab.STORE)
    var game by mutableStateOf<Game?>(null)
    var hasFilesAccess by mutableStateOf(true)
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
        Repo.init(this)
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

        setContent {
            RomSyncTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!Nav.hasFilesAccess) FilesAccessScreen { openFilesAccess() } else AppShell()
                }
            }
        }
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
    BackHandler(enabled = Nav.game != null) { Nav.game = null }

    @Composable
    fun TabIcon(t: Tab) {
        if (t == Tab.DOWNLOADS && active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { Icon(t.icon, null) }
        else Icon(t.icon, null)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = Nav.tab == t && Nav.game == null,
                            onClick = { Nav.tab = t; Nav.game = null },
                            icon = { TabIcon(t) },
                            label = { Text(t.label) },
                        )
                    }
                }
                Box(Modifier.weight(1f)) { TabContent() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { TabContent() }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = Nav.tab == t && Nav.game == null,
                            onClick = { Nav.tab = t; Nav.game = null },
                            icon = { TabIcon(t) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent() {
    val game = Nav.game
    if (game != null) {
        GameScreen(game) { Nav.game = null }
        return
    }
    when (Nav.tab) {
        Tab.STORE -> StoreScreen()
        Tab.DOWNLOADS -> DownloadsScreen()
        Tab.SAVES -> SavesScreen()
        Tab.KITCHEN -> KitchenScreen()
        Tab.SETTINGS -> SettingsScreen()
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
