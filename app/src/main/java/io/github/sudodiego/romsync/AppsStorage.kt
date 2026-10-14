package io.github.sudodiego.romsync

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cuánto ocupan las apps que el usuario elige (GameNative, emuladores…): app + datos + caché, con un botón que abre la
 * página de la app en Ajustes (ahí se borra caché o datos) y otro para abrirla. Android solo da estos números con el
 * permiso "Acceso a uso".
 */
object AppsStorage {
    /** Las que se muestran si el usuario aún no eligió: juegos y emuladores conocidos. */
    val known = listOf("app.gamenative", "dev.eden.eden_emulator", "info.cemu.cemu", "org.dolphinemu.dolphinemu",
        "org.azahar_emu.azahar", "com.retroarch", "com.armsx2", "org.vita3k.emulator", "me.magnum.melondualds")

    data class AppInfo(val pkg: String, val name: String, val icon: android.graphics.Bitmap?, val bytes: Long?)

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Apps que se pueden abrir (para elegir). */
    fun launchable(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }

    fun watched(ctx: Context): Set<String> = Prefs.watchedApps ?: run {
        val installed = launchable(ctx).map { it.first }.toSet()
        (known.filter { it in installed } + installed.filter { "droideck" in it.lowercase() }).toSet()
    }

    suspend fun load(ctx: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val access = hasAccess(ctx)
        watched(ctx).mapNotNull { pkg ->
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val bytes = if (access) runCatching {
                ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()).let { it.appBytes + it.dataBytes }
            }.getOrNull() else null
            val icon = runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96) }.getOrNull()
            AppInfo(pkg, pm.getApplicationLabel(ai).toString(), icon, bytes)
        }.sortedByDescending { it.bytes ?: 0 }
    }

    fun openSettings(ctx: Context, pkg: String) =
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun open(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun askAccess(ctx: Context) {
        val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Tarjeta de Organizar: espacio de las apps elegidas. Se actualiza al entrar y al volver de Ajustes. */
@Composable
fun AppsStorageCard() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppsStorage.AppInfo>?>(null) }
    var access by remember { mutableStateOf(AppsStorage.hasAccess(ctx)) }
    var choosing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                access = AppsStorage.hasAccess(ctx); tick++
                Repo.space = Local.space() // el espacio libre también, por si se borró algo en otra app
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) { apps = AppsStorage.load(ctx) }

    Section("Apps y emuladores") {
        if (!access) SpacedRow {
            Text("Para ver cuánto ocupa cada app, Android pide el permiso «Acceso a uso».", Modifier.weight(1f))
            FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); AppsStorage.askAccess(ctx) }) { Text("Dar permiso") }
        }
        apps?.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                a.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold)
                    Muted(a.bytes?.let { formatSize(it) } ?: "—")
                }
                TextButton(onClick = { Sound.play(Sound.Fx.OPEN); AppsStorage.open(ctx, a.pkg) }) { Text("Abrir") }
                FilledTonalButton(onClick = { Sound.play(Sound.Fx.MOVE); AppsStorage.openSettings(ctx, a.pkg) }) { Text("Gestionar") }
            }
        }
        TextButton(onClick = { Sound.play(Sound.Fx.MOVE); choosing = true }) { Text("Elegir apps…") }
    }

    if (choosing) {
        val all = remember { AppsStorage.launchable(ctx) }
        var sel by remember { mutableStateOf(AppsStorage.watched(ctx)) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Apps que se muestran") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(all, key = { it.first }) { (pkg, name) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { sel = if (pkg in sel) sel - pkg else sel + pkg },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(pkg in sel, { c -> sel = if (c) sel + pkg else sel - pkg })
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Prefs.watchedApps = sel; choosing = false; tick++ }) { Text("Listo") } },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancelar") } },
        )
    }
}
