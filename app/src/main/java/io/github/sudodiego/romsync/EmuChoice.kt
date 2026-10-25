package io.github.sudodiego.romsync

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * Con qué app (emulador) se juega cada plataforma. La elige el usuario (hay varias: Mupen64 o RetroArch para N64,
 * melonDS o WatermelonDS para DS…); si no eligió, se sugiere una conocida que esté instalada. Su ícono se muestra en
 * la tarjeta de la plataforma en la Tienda.
 */
object EmuChoice {
    /** Sugerencias por plataforma (la primera instalada gana). */
    private val suggested = mapOf(
        "switch" to listOf("dev.eden.eden_emulator", "org.citron_emu.citron", "dev.suyu.suyu_emulator", "org.yuzu.yuzu_emu"),
        "wiiu" to listOf("info.cemu.cemu"),
        "wii" to listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.mmjr"),
        "gc" to listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.mmjr"),
        "n3ds" to listOf("org.azahar_emu.azahar", "io.github.lime3ds.android", "org.citra.emu"),
        "nds" to listOf("me.magnum.melondualds", "me.magnum.melonds", "com.dsemu.drastic", "com.retroarch"),
        "ps2" to listOf("com.armsx2", "xyz.aethersx2.android", "com.retroarch"),
        "psvita" to listOf("org.vita3k.emulator"),
        "psp" to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "com.retroarch"),
    )

    private val icons = mutableStateMapOf<String, ImageBitmap?>()
    /** Para que la Tienda se redibuje al cambiar una elección. */
    var version by mutableStateOf(0)
        private set

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun pkg(ctx: Context, system: String): String? {
        Prefs.emuFor(system)?.let { if (installed(ctx, it)) return it }
        val list = suggested[system] ?: listOf("com.retroarch") // el resto (cartuchos, PS1…) suele ir en RetroArch
        return list.firstOrNull { installed(ctx, it) }
    }

    fun set(system: String, pkg: String) { Prefs.setEmuFor(system, pkg); version++ }

    fun icon(ctx: Context, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
    }
}

/** Elegir con qué app se juega una plataforma. */
@Composable
fun EmuPickerDialog(sys: SystemInfo, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { AppsStorage.launchable(ctx) }
    var sel by remember { mutableStateOf(EmuChoice.pkg(ctx, sys.id)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("¿Con qué app juegas ${sys.name}?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(apps, key = { it.first }) { (pkg, name) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { sel = pkg }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(sel == pkg, { sel = pkg })
                        EmuChoice.icon(ctx, pkg)?.let { Image(it, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))) }
                        Text(name, fontWeight = if (sel == pkg) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel == pkg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sel != null, onClick = { Sound.play(Sound.Fx.DONE); EmuChoice.set(sys.id, sel!!); onClose() }) { Text("Listo") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}
