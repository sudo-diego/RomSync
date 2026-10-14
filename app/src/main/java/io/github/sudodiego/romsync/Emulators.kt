package io.github.sudodiego.romsync

import android.content.Context
import java.io.File

/**
 * Dónde guarda cada emulador sus partidas (investigado en su código fuente, oct 2026; ver docs/INVESTIGACION.md).
 * Cada [SaveSource] es una carpeta y los patrones de lo que se respalda dentro de ella: solo partidas y estados,
 * nunca juegos instalados, cachés de shaders ni firmware.
 */
data class SaveSource(
    /** Nombre fijo de la carpeta dentro del zip (sirve para restaurar en otra ruta). */
    val label: String,
    val root: String,
    val include: List<String>,
    val exclude: List<String> = emptyList(),
)

data class Emulator(
    val id: String,
    val name: String,
    val packages: List<String>,
    /** Qué guarda y dónde, en una línea (para la pantalla Partidas). */
    val what: String,
    /** Cómo exportar desde el propio emulador si no hay Shizuku. */
    val exportHint: String,
    val note: String = "",
)

object Emulators {
    val DATA = "/storage/emulated/0/Android/data"
    private val SDCARD = "/storage/emulated/0"

    val all = listOf(
        Emulator(
            "retroarch", "RetroArch", listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"),
            "Partidas (.srm) y estados de todos los núcleos",
            "Las carpetas se ven en RetroArch → Ajustes → Directorio → Partidas guardadas / Estados.",
        ),
        Emulator(
            "dolphin", "Dolphin", listOf("org.dolphinemu.dolphinemu"),
            "Tarjetas de memoria de GameCube, partidas de Wii y estados",
            "Dolphin → Ajustes → Configuración → Datos de usuario → Exportar datos de usuario.",
        ),
        Emulator(
            "armsx2", "ARMSX2", listOf("com.armsx2", "come.nanodata.armsx2", "com.armsx2.nightly"),
            "Tarjetas de memoria (memcards) y estados (sstates)",
            "ARMSX2 → Ajustes → Respaldo y restauración (crea un zip con tarjetas, estados y ajustes).",
            note = "Si elegiste otra carpeta de datos en ARMSX2, ponla abajo en «Carpeta».",
        ),
        Emulator(
            "eden", "Eden", listOf("dev.eden.eden_emulator"),
            "Partidas de la NAND (nand/user/save) y los perfiles",
            "Eden → Ajustes → Administrar datos → Partidas guardadas → Exportar.",
            note = "Actualizaciones y DLC: déjalos como archivos en la carpeta de contenido externo (Eden 0.2+), así RomSync los maneja como el juego.",
        ),
        Emulator(
            "vita3k", "Vita3K", listOf("org.vita3k.emulator"),
            "Partidas (ux0/user) y trofeos",
            "Vita3K aparece en el explorador de archivos de Android como «Vita3K»: copia ux0/user.",
            note = "Los juegos de Vita se instalan dentro de Vita3K (ux0/app): el archivo del ROM se puede borrar después.",
        ),
        Emulator(
            "watermelonds", "WatermelonDS", listOf("me.magnum.melondualds", "me.magnum.melondualds.nightly", "me.magnum.melonds"),
            "Partidas (.sav) junto a los ROMs de DS y en su carpeta de datos",
            "Las partidas están junto a cada ROM de DS (misma carpeta, extensión .sav).",
        ),
        Emulator(
            "azahar", "Azahar", listOf("org.azahar_emu.azahar", "io.github.lime3ds.android"),
            "Partidas y datos extra de 3DS (sdmc) y estados",
            "La carpeta de usuario la elegiste al configurar Azahar (por defecto «Azahar» en el almacenamiento).",
            note = "No se respaldan los juegos, actualizaciones ni DLC instalados en sdmc, solo las partidas.",
        ),
        Emulator(
            "nethersx2", "NetherSX2", listOf("xyz.aethersx2.android"),
            "Tarjetas de memoria (memcards) y estados (sstates)",
            "NetherSX2 → Ajustes → Transfer Data → Export (solo pasa las tarjetas de memoria).",
            note = "Para pasarte a ARMSX2: «Pasar tarjetas a ARMSX2» copia tus tarjetas de memoria (el mismo formato .ps2). " +
                "Los estados guardados no sirven entre emuladores: guarda dentro del juego antes.",
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun installedPackage(ctx: Context, emu: Emulator): String? = emu.packages.firstOrNull { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun needsShell(path: String) = path.startsWith("$DATA/") || path.startsWith("/sdcard/Android/data/")

    /** Carpeta "vita" de Vita3K: la del almacenamiento si tiene partidas o juegos; si no, la de Android/data. */
    fun vitaRoot(files: String): String =
        listOf("$SDCARD/Vita", "$SDCARD/vita3k", "$SDCARD/Vita3K").firstOrNull { d ->
            listOf("ux0/user", "ux0/app").any { File("$d/$it").list()?.isNotEmpty() == true }
        } ?: "$files/vita"

    /** Las carpetas de partidas de un emulador instalado (vacía si no aplica). */
    suspend fun sources(emu: Emulator, pkg: String): List<SaveSource> {
        val custom = Prefs.emuPath(emu.id)
        val files = "$DATA/$pkg/files"
        return when (emu.id) {
            "retroarch" -> retroArch(pkg)
            "dolphin" -> {
                val root = custom.ifBlank {
                    if (File("$SDCARD/dolphin-emu/GC").isDirectory) "$SDCARD/dolphin-emu" else files
                }
                listOf(SaveSource("user", root, listOf("GC/**", "Wii/title/*/*/data/**", "StateSaves/**")))
            }
            "armsx2", "nethersx2" -> {
                // Solo ARMSX2 puede tener su carpeta en el almacenamiento (la elegida en su asistente). NetherSX2 siempre
                // usa Android/data: si buscara también en /sdcard/ARMSX2, "Pasar tarjetas" copiaría de ARMSX2 a ARMSX2.
                val root = custom.ifBlank {
                    if (emu.id != "armsx2") files
                    else listOf("$SDCARD/ARMSX2", "$SDCARD/armsx2").firstOrNull { File("$it/memcards").isDirectory } ?: files
                }
                listOf(SaveSource("data", root, listOf("memcards/**", "sstates/**")))
            }
            "eden" -> listOf(
                // keys/: prod.keys y title.keys_autogenerated (las claves de lo que Eden instaló; sin ellas no abren).
                SaveSource("files", custom.ifBlank { files }, listOf("nand/user/save/**", "nand/system/save/8000000000000010/**", "keys/**")),
            )
            // Vita3K deja elegir dónde guarda todo; en la tablet del usuario es /sdcard/Vita (Android/data/…/vita quedó
            // vacía y los respaldos salían sin partidas). Gana la que tenga algo en ux0/user.
            "vita3k" -> listOf(SaveSource("vita", custom.ifBlank { vitaRoot(files) }, listOf("ux0/user/**")))
            "watermelonds" -> buildList {
                add(SaveSource("roms", "${Prefs.romsDir}/nds", listOf("**/*.sav", "**/*.srm", "**/*.dsv", "**/*.ml?")))
                add(SaveSource("app", custom.ifBlank { files }, listOf("saves/**", "*state*/**", "*State*/**")))
            }
            "azahar" -> {
                val root = custom.ifBlank {
                    listOf("$SDCARD/Azahar", "$SDCARD/azahar", "$SDCARD/Lime3DS", "$SDCARD/citra-emu")
                        .firstOrNull { File("$it/sdmc").isDirectory } ?: files
                }
                listOf(SaveSource("user", root, listOf(
                    "sdmc/Nintendo 3DS/*/*/title/00040000/*/data/**",
                    "sdmc/Nintendo 3DS/*/*/extdata/**",
                    "states/**",
                )))
            }
            else -> emptyList()
        }
    }

    /** RetroArch: las carpetas salen de retroarch.cfg; si no se puede leer, las de siempre. */
    private suspend fun retroArch(pkg: String): List<SaveSource> {
        val files = "$DATA/$pkg/files"
        val custom = Prefs.emuPath("retroarch")
        val cfgPaths = listOf("$files/retroarch.cfg", "$SDCARD/RetroArch/retroarch.cfg")
        var cfg: Map<String, String> = emptyMap()
        for (p in cfgPaths) {
            val text = Storage.readText(p) ?: continue
            cfg = parseCfg(text)
            break
        }
        fun dir(key: String, fallbacks: List<String>): List<String> {
            val v = cfg[key].orEmpty()
            return when {
                v.isBlank() || v == "default" -> fallbacks
                v.startsWith(":") -> listOf(files + v.removePrefix(":"))
                else -> listOf(v.trimEnd('/'))
            }
        }
        val out = mutableListOf<SaveSource>()
        if (custom.isNotBlank()) {
            out += SaveSource("saves", "$custom/saves", listOf("**"))
            out += SaveSource("states", "$custom/states", listOf("**"))
        } else {
            dir("savefile_directory", listOf("$SDCARD/RetroArch/saves", "$files/saves")).forEachIndexed { i, d ->
                out += SaveSource(if (i == 0) "saves" else "saves$i", d, listOf("**"))
            }
            dir("savestate_directory", listOf("$SDCARD/RetroArch/states", "$files/states")).forEachIndexed { i, d ->
                out += SaveSource(if (i == 0) "states" else "states$i", d, listOf("**"))
            }
        }
        if (cfg["savefiles_in_content_dir"] == "true" || cfg["savestates_in_content_dir"] == "true") {
            out += SaveSource("roms", Prefs.romsDir, listOf("**/*.srm", "**/*.state", "**/*.state?", "**/*.state.auto"))
        }
        return out
    }

    fun parseCfg(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0 || line.trimStart().startsWith("#")) null
        else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
    }.toMap()
}
