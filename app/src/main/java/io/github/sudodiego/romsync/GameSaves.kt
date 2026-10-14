package io.github.sudodiego.romsync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Las partidas de UN juego de la tienda: en qué emulador están y con qué clave las agrupa [Saves.unitOf]. Así la ficha
 * del juego puede respaldar y restaurar solo ese juego (antes eso estaba aparte, en Partidas, con códigos como "GP7P").
 *
 * La clave sale de la ROM que está en la tablet: el código de 4 letras del disco en GameCube/Wii, el TitleID en Switch y
 * 3DS, el ID de Vita, o el nombre del archivo (las partidas de DS y RetroArch se llaman igual que la ROM).
 */
object GameSaves {
    data class Link(val emu: Emulator, val key: String)

    /** Plataformas cuyas partidas van en tarjetas de memoria compartidas por todos los juegos (no hay "una por juego"). */
    val sharedCards = setOf("ps2")

    private val retroArchSystems = setOf(
        "nes", "famicom", "fds", "snes", "snesna", "sfc", "gb", "gbc", "gba", "n64", "genesis", "megadrive", "mastersystem",
        "gamegear", "segacd", "sega32x", "saturn", "dreamcast", "psx", "psp", "pcengine", "tg16", "neogeo", "arcade", "mame",
        "fbneo", "atari2600", "atarilynx", "virtualboy", "wonderswan", "wonderswancolor", "ngp", "ngpc",
    )

    suspend fun links(ctx: Context, g: Game): List<Link> = withContext(Dispatchers.IO) {
        if (g.kind == "shortcut" && g.system != "psvita") return@withContext emptyList()
        val out = ArrayList<Link>()
        fun add(emuId: String, key: String?) {
            val emu = Emulators.byId(emuId) ?: return
            if (key.isNullOrBlank() || Emulators.installedPackage(ctx, emu) == null) return
            out += Link(emu, key)
        }
        val byName = "g:" + Group.stem(g.primary).lowercase()
        when (g.system.lowercase()) {
            "gc", "wii" -> add("dolphin", discId(g)?.let { "id:" + it.take(4) })
            "nds" -> { add("watermelonds", byName); add("retroarch", byName) }
            "switch" -> add("eden", (g.parts.firstOrNull { it.kind == "base" }?.titleId ?: g.serial).takeIf { it.isNotBlank() }
                ?.let { "tid:" + it.uppercase() })
            "psvita" -> add("vita3k", vitaId(g)?.let { "id:$it" })
            "n3ds", "3ds" -> add("azahar", threeDsTitleLow(g)?.let { "id:$it" })
            in retroArchSystems -> add("retroarch", byName)
        }
        out
    }

    /** Código del disco de GameCube/Wii ("GZLP01") leído de la cabecera: ISO/GCM al principio; RVZ/WIA lo copian en 0x58. */
    fun discId(g: Game): String? {
        if (g.serial.length == 6) return g.serial // ya leído del PC al armar la biblioteca
        val f = Local.romFile(g.primary).takeIf { it.isFile } ?: return null
        return runCatching { f.inputStream().use { i -> ByteArray(0x60).also { i.read(it) } }.let(::discIdOf) }.getOrNull()
    }

    /** El código desde los primeros 0x60 bytes (sirve para la tablet y para lo que se lee del PC por SFTP). */
    fun discIdOf(head: ByteArray): String? {
        if (head.size < 0x60) return null
        val magic = String(head, 0, 4, Charsets.ISO_8859_1)
        val off = if (magic == "RVZ\u0001" || magic == "WIA\u0001") 0x58 else 0
        return String(head, off, 6, Charsets.ISO_8859_1).takeIf { id -> id.all { it.isLetterOrDigit() } }
    }

    private val reVita = Regex("PCS[A-H]\\d{5}")

    /** ID de Vita: del nombre, o de adentro del acceso directo (GravityRush.dpt dice "[vita_game_id] PCSA00011"). */
    fun vitaId(g: Game): String? {
        g.serial.takeIf { reVita.matches(it) }?.let { return it }
        reVita.find(g.name)?.let { return it.value }
        val f = Local.romFile(g.primary).takeIf { it.isFile && it.length() < 4096 } ?: return null
        return runCatching { reVita.find(f.readText())?.value }.getOrNull()
    }

    /** La parte baja del TitleID de un .3ds (en 0x108 de la cabecera NCSD), como la carpeta de Azahar ("00055d00"). */
    fun threeDsTitleLow(g: Game): String? {
        val f = Local.romFile(g.primary).takeIf { it.isFile && Group.ext(it.name) == ".3ds" } ?: return null
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { r ->
                val b = ByteArray(8)
                r.seek(0x100); r.readFully(b, 0, 4)
                if (String(b, 0, 4, Charsets.ISO_8859_1) != "NCSD") return null
                r.seek(0x108); r.readFully(b)
                "%02x%02x%02x%02x".format(b[3], b[2], b[1], b[0])
            }
        }.getOrNull()
    }

    /** Dónde está instalado un juego de Vita dentro de Vita3K (el .dpt/.vpk de ROMs es solo el instalador o un acceso). */
    suspend fun vitaInstall(ctx: Context, g: Game): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val id = vitaId(g) ?: return@withContext null
        val emu = Emulators.byId("vita3k") ?: return@withContext null
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext null
        val root = Emulators.sources(emu, pkg).firstOrNull()?.root ?: return@withContext null
        val dir = "$root/ux0/app/$id"
        Storage.sizeOf(dir)?.takeIf { it > 0 }?.let { dir to it }
    }

    /** Quita un juego instalado en Vita3K (solo ux0/app/<ID>; las partidas, en ux0/user, no se tocan). */
    suspend fun removeVitaInstall(dir: String) = withContext(Dispatchers.IO) {
        require(Regex(".*/ux0/app/PCS[A-H]\\d{5}$").matches(dir)) { "Ruta inesperada: $dir" }
        if (Emulators.needsShell(dir)) Shell.run("rm -rf ${Shell.q(dir)}", 120000)
        else check(File(dir).deleteRecursively()) { "No se pudo borrar $dir" }
    }
}
