package io.github.sudodiego.romsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Respaldo y restauración de partidas. Un respaldo es un zip por emulador: una carpeta por [SaveSource]
 * ("user/GC/USA/Card A/…") y romsync.json con de dónde salió cada cosa. Las partidas nunca se borran de la tablet:
 * restaurar sobrescribe los archivos del respaldo y deja los demás.
 */
object Saves {
    data class Status(
        val emu: Emulator,
        val pkg: String?,
        val sources: List<SaveSource> = emptyList(),
        val files: Int = 0,
        val bytes: Long = 0,
        val hash: String = "",
        val needsShell: Boolean = false,
        val error: String? = null,
        val dataSize: Long? = null,
    ) {
        val installed get() = pkg != null
        val supported get() = true
    }

    /** Lo último que se sabe de cada emulador (para la UI y el reporte al PC). */
    val statuses = mutableStateMapOf<String, Status>()
    /** Cambia cada vez que se respalda algo: las pantallas que muestran "al día / con cambios" se vuelven a calcular. */
    var backupTick by androidx.compose.runtime.mutableIntStateOf(0)
    val busy = mutableStateMapOf<String, String>() // emulador → qué está haciendo

    private fun hashOf(entries: List<Pair<SaveSource, Storage.Stat>>): String {
        val md = MessageDigest.getInstance("SHA-256")
        entries.map { (s, f) -> "${s.label}/${f.rel}|${f.size}|${f.mtime}" }.sorted().forEach { md.update(it.toByteArray()); md.update(10) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private suspend fun collect(emu: Emulator, pkg: String): Pair<List<SaveSource>, List<Pair<SaveSource, Storage.Stat>>> {
        val sources = Emulators.sources(emu, pkg)
        val entries = mutableListOf<Pair<SaveSource, Storage.Stat>>()
        for (s in sources) Storage.list(s).forEach { entries += s to it }
        return sources to entries
    }

    suspend fun inspect(ctx: Context, emu: Emulator, withSize: Boolean = false): Status {
        val pkg = Emulators.installedPackage(ctx, emu)
        if (pkg == null) return Status(emu, pkg).also { statuses[emu.id] = it }
        val st = try {
            val (sources, entries) = collect(emu, pkg)
            val dataSize = if (withSize && Shell.ready) Storage.sizeOf("${Emulators.DATA}/$pkg") else null
            Status(emu, pkg, sources, entries.size, entries.sumOf { it.second.size }, hashOf(entries),
                sources.any { Emulators.needsShell(it.root) }, dataSize = dataSize)
        } catch (e: Storage.NeedsShizuku) {
            Status(emu, pkg, Emulators.sources(emu, pkg), needsShell = true, error = e.message)
        } catch (e: Exception) {
            Status(emu, pkg, error = e.message ?: e.javaClass.simpleName)
        }
        statuses[emu.id] = st
        return st
    }

    sealed class Result {
        data class Done(val files: Int, val bytes: Long) : Result()
        data object Unchanged : Result()
        data object Empty : Result()
        data class Failed(val message: String) : Result()
    }

    /** Respalda un emulador en el PC. Si no cambió nada desde el último respaldo (mismo hash) no sube nada. */
    /** Las partidas que hay ahora en la tablet, juego por juego (para respaldar solo uno). */
    suspend fun currentUnits(ctx: Context, emu: Emulator): List<Unit> = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext emptyList()
        val (_, entries) = collect(emu, pkg)
        // Por clave: la partida de la tarjeta (01-GZLP-…gci) y los estados (GZLP01.s00) son del mismo juego.
        entries.groupBy { (s, f) -> unitOf(emu.id, "${s.label}/${f.rel}").first }
            .map { (k, es) -> Unit(k, titleFor(k, unitTitle(emu.id, es.map { (s, f) -> "${s.label}/${f.rel}" })),
                es.first().second.rel.substringAfterLast('/'), es.size, es.sumOf { it.second.size }, es.maxOf { it.second.mtime } * 1000) }
            .sortedBy { it.title.lowercase() }
    }

    /** [reason]: "antes" (antes de restaurar) o "traspaso"; si no, sale de [auto] y [only]. Queda en el respaldo para la lista. */
    suspend fun backup(ctx: Context, emu: Emulator, auto: Boolean, force: Boolean = false, only: Set<String>? = null,
                       reason: String = ""): Result = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext Result.Failed("${emu.name} no está instalado")
        busy[emu.id] = "Leyendo partidas…"
        val zip = File(ctx.cacheDir, "respaldo_${emu.id}.zip")
        try {
            val (sources, all) = collect(emu, pkg)
            // Respaldo de un juego (o algunos): solo sus archivos.
            val entries = if (only == null) all else all.filter { (s, f) -> unitOf(emu.id, "${s.label}/${f.rel}").first in only }
            if (entries.isEmpty()) return@withContext Result.Empty
            val hash = hashOf(entries)
            if (!force && only == null) {
                val remote = runCatching { Pc.use { Backups.latestHash(it, emu.id) } }.getOrDefault("")
                if (remote == hash) {
                    Prefs.setLastBackup(emu.id, hash, System.currentTimeMillis())
            backupTick++
                    return@withContext Result.Unchanged
                }
            }
            busy[emu.id] = "Empaquetando ${entries.size} archivos…"
            ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { out ->
                for (s in sources) {
                    val files = entries.filter { it.first == s }.map { it.second }
                    val byRel = files.associateBy { it.rel }
                    Storage.read(s, files) { rel, _, input ->
                        val e = ZipEntry("${s.label}/$rel")
                        byRel[rel]?.let { e.time = it.mtime * 1000 }
                        out.putNextEntry(e)
                        input.copyTo(out, 1 shl 16)
                        out.closeEntry()
                    }
                }
                val manifest = JSONObject()
                    .put("app", "RomSync").put("version", 1).put("emulator", emu.id).put("package", pkg)
                    .put("device", Prefs.deviceName).put("created", System.currentTimeMillis())
                    .put("sources", JSONArray().apply {
                        sources.forEach { put(JSONObject().put("label", it.label).put("root", it.root).put("include", JSONArray(it.include))) }
                    })
                out.putNextEntry(ZipEntry("romsync.json"))
                out.write(manifest.toString(2).toByteArray())
                out.closeEntry()
            }
            busy[emu.id] = "Enviando al servidor…"
            val units = entries.groupBy { (s, f) -> unitOf(emu.id, "${s.label}/${f.rel}").first }
            val kind = reason.ifEmpty { if (only != null) "juego" else if (auto) "auto" else "todo" }
            val games = units.map { (k, es) -> titleFor(k, unitTitle(emu.id, es.map { (s, f) -> "${s.label}/${f.rel}" })) }.sorted()
            Pc.use { Backups.upload(it, emu.id, zip, hash, entries.size, auto, kind, games, units.keys.toList()) }
            Prefs.setLastBackup(emu.id, hash, System.currentTimeMillis())
            backupTick++
            Result.Done(entries.size, entries.sumOf { it.second.size })
        } catch (e: Storage.NeedsShizuku) {
            Result.Failed("Necesita Shizuku")
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            zip.delete()
            busy.remove(emu.id)
        }
    }

    /**
     * Restaura un respaldo del PC. Antes respalda lo que hay (por si acaso) y exige que el emulador esté cerrado.
     * Devuelve null si salió bien o el error para mostrar.
     */
    // ---- Restaurar por juego ----

    /** Un juego (o una tarjeta de memoria, o los perfiles) dentro de un respaldo. */
    data class Unit(val key: String, val title: String, val detail: String, val files: Int, val bytes: Long, val newest: Long)

    private fun hexAscii(hex: String): String = runCatching {
        hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("").takeIf { t -> t.all { it.isLetterOrDigit() } } ?: hex
    }.getOrDefault(hex)

    private val reCodeName = Regex("^(GameCube|Wii) · ([A-Z0-9]{4,6})$")
    private val reCode2 = Regex("^Juego de (GameCube|Wii|3DS|Switch|Vita) \\(([0-9A-Za-z]+)\\)$")

    /** Un nombre guardado en un respaldo como código ("GameCube · GP7P") con el nombre del juego, si ya se conoce. */
    fun prettyUnitName(s: String): String {
        reCodeName.matchEntire(s)?.let { return titleFor("id:" + it.groupValues[2].take(4), s) }
        reCode2.matchEntire(s)?.let { m ->
            val code = m.groupValues[2]
            return titleFor(if (m.groupValues[1] == "Switch") "tid:$code" else "id:" + if (m.groupValues[1] in setOf("GameCube", "Wii")) code.take(4) else code.lowercase(), s)
        }
        if (s == "Estado al cerrar Dolphin") return "Guardado rápido automático (al cerrar Dolphin)"
        return s
    }

    /** Nombre para mostrar de una unidad (para anotar respaldos viejos). */
    fun describeUnit(emu: String, key: String, names: List<String>) = titleFor(key, unitTitle(emu, names))

    /** Nombre de una unidad con varios archivos: el de la partida ("GameCube · GZLP") antes que el de un estado. */
    private fun unitTitle(emu: String, names: List<String>): String {
        val titles = names.map { unitOf(emu, it).second }.distinct()
        return titles.firstOrNull { !it.startsWith("Estado") } ?: titles.first()
    }

    /** A qué juego pertenece un archivo del respaldo: (clave, nombre para mostrar). */
    fun unitOf(emu: String, name: String): Pair<String, String> {
        val rel = name.substringAfter('/')
        val parts = rel.split('/')
        val file = parts.last()
        fun byName(): Pair<String, String> {
            var base = file
            val i = base.lowercase().indexOf(".state")
            base = if (i > 0) base.substring(0, i) else base.substringBeforeLast('.')
            if (base.equals("backup", true) && rel.contains("yaba", true)) return "otros:saturn" to "Memoria interna de Saturn"
            return "g:" + base.lowercase() to Group.cleanTitle(base).first
        }
        return when (emu) {
            "dolphin" -> when {
                parts[0] == "Wii" && parts.size > 3 && parts[2] == "00000001" -> "sistema" to "Ajustes de la consola Wii"
                parts[0] == "Wii" && parts.size > 3 -> "id:" + hexAscii(parts[3]).take(4) to "Juego de Wii (" + hexAscii(parts[3]) + ")"
                parts[0] == "StateSaves" && file.startsWith("lastState") -> "otros:laststate" to "Guardado rápido automático (al cerrar Dolphin)"
                parts[0] == "StateSaves" -> "id:" + file.take(4) to "Guardado rápido (" + file.take(6) + ")"
                parts[0] == "GC" && (file.equals("IPL.bin", true) || file.equals("SRAM.raw", true)) -> "sistema" to "Archivos del sistema (BIOS y ajustes de GameCube)"
                parts[0] == "GC" -> Regex("^[0-9A-Fa-f]{2}-([A-Z0-9]{4})").find(file)?.let { "id:" + it.groupValues[1] to "Juego de GameCube (" + it.groupValues[1] + ")" }
                    ?: ("card:$rel" to "Tarjeta de memoria (" + parts.drop(1).joinToString(" / ") + ")")
                else -> "otros:" + parts[0] to parts[0]
            }
            "eden" -> when {
                rel.contains("/user/save/") && parts.size > 5 -> "tid:" + parts[5].uppercase() to "Juego de Switch (" + parts[5].uppercase() + ")"
                parts[0] == "keys" -> "sistema" to "Claves de Switch"
                else -> "perfiles" to "Perfiles de usuario de Switch"
            }
            "vita3k" -> if (parts.size > 4 && parts[3] == "savedata") "id:" + parts[4] to "Juego de Vita (" + parts[4] + ")" else "trofeos" to "Trofeos y perfil de Vita"
            "armsx2", "nethersx2" -> if (parts[0] == "memcards") "card:$file" to "Tarjeta de memoria $file (todas las partidas de PS2)"
                else file.substringBefore(" (").substringBefore('.').let { "id:$it" to "Guardado rápido ($it)" }
            "azahar" -> {
                val t = parts.indexOf("title")
                val e = parts.indexOf("extdata")
                when {
                    t >= 0 && parts.size > t + 2 -> "id:" + parts[t + 2].lowercase() to "Juego de 3DS (" + parts[t + 2].uppercase() + ")"
                    // Los datos extra (extdata) de un juego llevan su código del medio: 0000055D → 00055d00.
                    e >= 0 && parts.size > e + 2 -> {
                        val id = (parts[e + 2].takeLast(5).lowercase() + "00").padStart(8, '0')
                        "id:$id" to "Juego de 3DS ($id)"
                    }
                    // Los guardados rápidos de Azahar se llaman con el TitleID completo: van con su juego.
                    parts[0] == "states" && file.startsWith("00040000") -> "id:" + file.substring(8, 16).lowercase() to "Juego de 3DS (" + file.substring(8, 16) + ")"
                    else -> byName()
                }
            }
            else -> byName()
        }
    }

    /**
     * Nombre del juego en la biblioteca para una clave de partidas: el TitleID de Switch, el ID de Vita o el código del
     * disco de GameCube/Wii ("GP7P" → "Mario Party 7 · GameCube · GP7P"), si el juego está en la tablet.
     */
    private fun titleFor(key: String, fallback: String): String {
        if (key.startsWith("g:")) return Repo.allGames().firstOrNull { Group.stem(it.primary).equals(key.removePrefix("g:"), true) }?.title ?: fallback
        if (!key.startsWith("id:") && !key.startsWith("tid:")) return fallback
        val id = key.substringAfter(':')
        val title = Repo.allGames().firstOrNull { it.serial.isNotEmpty() && (it.serial.equals(id, true) ||
                (id.length == 4 && it.serial.length == 6 && it.serial.startsWith(id, true))) }?.title
            ?: Repo.allGames().firstOrNull { it.system == "switch" && it.parts.any { p -> p.kind == "base" && p.titleId.equals(id, true) } }?.title
            ?: Repo.allGames().firstOrNull { it.system == "n3ds" && GameSaves.threeDsTitleLow(it).equals(id, true) }?.title
            ?: discTitles()[id.uppercase()]
            // Partida de otra región que el disco (GP7P europea, disco GP7E de USA): las 3 primeras letras son el juego.
            ?: if (id.length == 4) Repo.allGames().firstOrNull { (it.system == "gc" || it.system == "wii") &&
                it.serial.length == 6 && it.serial.take(3).equals(id.take(3), true) }?.title else null
        // Códigos bajos de 3DS sin juego en la biblioteca: datos del sistema (menú, Mii…), no de un juego.
        if (title == null && fallback.startsWith("Juego de 3DS") && (id.toLongOrNull(16) ?: Long.MAX_VALUE) < 0x30000L)
            return "Datos del sistema de 3DS"
        return title ?: fallback
    }

    private var discCache: Pair<Int, Map<String, String>>? = null

    /** Código de 4 letras → nombre, de los juegos de GameCube/Wii que están en la tablet (se lee la cabecera una vez). */
    private fun discTitles(): Map<String, String> {
        val games = Repo.allGames().filter { (it.system == "gc" || it.system == "wii") && it.serial.isEmpty() && Repo.has(it.primary) }
        discCache?.takeIf { it.first == games.size }?.let { return it.second }
        val map = games.mapNotNull { g -> GameSaves.discId(g)?.take(4)?.let { it.uppercase() to g.title } }.toMap()
        discCache = games.size to map
        return map
    }

    private fun cachedZip(ctx: Context, emu: Emulator, id: String) = File(ctx.cacheDir, "ver_${emu.id}_${Backups.safe(id)}.zip")

    /** Lo que trae un respaldo, juego por juego (baja el zip una vez y lo deja en caché mientras se elige). */
    suspend fun units(ctx: Context, emu: Emulator, backupId: String): List<Unit> = withContext(Dispatchers.IO) {
        val zip = cachedZip(ctx, emu, backupId)
        if (!zip.isFile) {
            ctx.cacheDir.listFiles()?.filter { it.name.startsWith("ver_") }?.forEach { it.delete() }
            val tmp = File(zip.path + ".tmp")
            Pc.use { Backups.download(it, emu.id, backupId, tmp) }
            tmp.renameTo(zip)
        }
        ZipFile(zip).use { z ->
            z.entries().asSequence().filter { !it.isDirectory && it.name.contains('/') }
                .groupBy { unitOf(emu.id, it.name).first }
                .map { (k, es) -> Unit(k, titleFor(k, unitTitle(emu.id, es.map { it.name })), es.first().name.substringAfterLast('/'), es.size,
                    es.sumOf { it.size }, es.maxOf { it.time }) }
                .sortedBy { it.title.lowercase() }
        }
    }

    suspend fun restore(ctx: Context, emu: Emulator, backupId: String, zipFromWeb: File? = null, only: Set<String>? = null): String? = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext "${emu.name} no está instalado"
        if (Shell.ready && Shell.isRunning(pkg)) return@withContext "Cierra ${emu.name} antes de restaurar (Recientes → deslizar)."
        val zipFile = File(ctx.cacheDir, "restaurar_${emu.id}.zip")
        try {
            busy[emu.id] = "Respaldando lo actual…"
            when (val r = backup(ctx, emu, auto = false, force = false, reason = "antes")) {
                is Result.Failed -> return@withContext "No se pudo respaldar lo actual antes de restaurar: ${r.message}"
                else -> {}
            }
            val cached = cachedZip(ctx, emu, backupId)
            if (zipFromWeb != null) zipFromWeb.copyTo(zipFile, overwrite = true)
            else if (backupId.isNotEmpty() && cached.isFile) cached.copyTo(zipFile, overwrite = true)
            else {
                busy[emu.id] = "Descargando el respaldo…"
                Pc.use { Backups.download(it, emu.id, backupId, zipFile) }
            }
            busy[emu.id] = "Restaurando…"
            val sources = Emulators.sources(emu, pkg).associateBy { it.label }
            ZipFile(zipFile).use { zip ->
                val byLabel = zip.entries().asSequence().filter { !it.isDirectory && it.name.contains('/') }
                    .filter { only == null || unitOf(emu.id, it.name).first in only }
                    .groupBy { it.name.substringBefore('/') }
                for ((label, entries) in byLabel) {
                    // La carpeta de hoy para esa etiqueta; si el emulador ya no la tiene, la ruta original del respaldo.
                    val root = sources[label]?.root ?: originalRoot(zip, label) ?: continue
                    Storage.write(root, entries.asSequence().map { e ->
                        Triple(e.name.substringAfter('/'), e.size, { zip.getInputStream(e) })
                    })
                }
            }
            inspect(ctx, emu)
            null
        } catch (e: Storage.NeedsShizuku) {
            "Necesita Shizuku"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            zipFile.delete()
            busy.remove(emu.id)
        }
    }

    private fun originalRoot(zip: ZipFile, label: String): String? = runCatching {
        val m = JSONObject(zip.getInputStream(zip.getEntry("romsync.json")).bufferedReader().readText())
        val arr = m.getJSONArray("sources")
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("label") == label }?.getString("root")
    }.getOrNull()

    /**
     * NetherSX2 → ARMSX2: copia las tarjetas de memoria (.ps2, mismo formato). Antes guarda una copia de las tarjetas de
     * los DOS emuladores en la tablet (RomSync/antes-del-traspaso) y, si el PC está conectado, un respaldo en el PC.
     * Devuelve null si salió bien o el error.
     */
    /**
     * Borra del dispositivo las partidas de UN juego (clave de [unitOf]). Antes las respalda en el servidor si se puede:
     * así "borrar también las partidas" nunca las pierde de verdad.
     */
    suspend fun deleteUnit(ctx: Context, emu: Emulator, key: String): String? = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext null
        if (Prefs.configured) when (val r = backup(ctx, emu, auto = false, force = true, only = setOf(key), reason = "antes")) {
            is Result.Failed -> return@withContext "No se pudieron respaldar antes de borrar: ${r.message}"
            else -> {}
        }
        runCatching {
            val (sources, all) = collect(emu, pkg)
            for (s in sources) {
                val rels = all.filter { (src, f) -> src == s && unitOf(emu.id, "${s.label}/${f.rel}").first == key }.map { it.second.rel }
                Storage.delete(s.root, rels)
            }
            inspect(ctx, emu)
            null
        }.getOrElse { it.message ?: "Error" }
    }

    /** Las tarjetas que pasó el último traspaso ("Mcd001.ps2", "hola123.ps2"…), para decir cuál poner en el slot 1. */
    var transferredCards: List<String> = emptyList()
        private set

    suspend fun transferToArmsx2(ctx: Context): String? = withContext(Dispatchers.IO) {
        val nether = Emulators.byId("nethersx2")!!
        val arm = Emulators.byId("armsx2")!!
        val npkg = Emulators.installedPackage(ctx, nether) ?: return@withContext "NetherSX2 no está instalado"
        val apkg = Emulators.installedPackage(ctx, arm) ?: return@withContext "ARMSX2 no está instalado"
        if (Shell.ready && (Shell.isRunning(npkg) || Shell.isRunning(apkg))) return@withContext "Cierra NetherSX2 y ARMSX2 antes de seguir."
        busy["nethersx2"] = "Guardando una copia de las tarjetas…"
        try {
            val from = Emulators.sources(nether, npkg).first()
            val to = Emulators.sources(arm, apkg).first()
            val cards = Storage.list(from.copy(include = listOf("memcards/**")))
            if (cards.isEmpty()) return@withContext "NetherSX2 no tiene tarjetas de memoria"
            // Copia de seguridad local de los dos lados, sin depender del PC.
            val safe = File(Prefs.storageRoot, "RomSync/antes-del-traspaso/" +
                java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
            for ((src, name) in listOf(from to "NetherSX2", to to "ARMSX2")) {
                val list = Storage.list(src.copy(include = listOf("memcards/**")))
                Storage.read(src.copy(include = listOf("memcards/**")), list) { rel, _, input ->
                    val f = File(safe, "$name/$rel").apply { parentFile?.mkdirs() }
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            if (Prefs.configured) {
                busy["nethersx2"] = "Respaldando en el servidor…"
                backup(ctx, nether, auto = false, force = true, reason = "traspaso")
                backup(ctx, arm, auto = false, force = true, reason = "traspaso")
            }
            busy["nethersx2"] = "Copiando ${cards.size} tarjeta(s) a ARMSX2…"
            val tmp = File(ctx.cacheDir, "traspaso").apply { deleteRecursively(); mkdirs() }
            Storage.read(from.copy(include = listOf("memcards/**")), cards) { rel, _, input ->
                File(tmp, rel).apply { parentFile?.mkdirs() }.outputStream().use { input.copyTo(it) }
            }
            Storage.write(to.root, cards.asSequence().map { c ->
                val f = File(tmp, c.rel)
                Triple(c.rel, f.length(), { f.inputStream() as java.io.InputStream })
            })
            tmp.deleteRecursively()
            inspect(ctx, arm)
            transferredCards = cards.mapNotNull { it.rel.split('/').getOrNull(1) }.distinct().sorted()
            null
        } catch (e: Storage.NeedsShizuku) {
            "Necesita Shizuku"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            busy.remove("nethersx2")
        }
    }

    /** Respalda todos los emuladores instalados a los que se puede llegar. Devuelve un resumen corto. */
    suspend fun backupAll(ctx: Context, auto: Boolean): String {
        var done = 0
        var same = 0
        val failed = mutableListOf<String>()
        for (emu in Emulators.all) {
            if (Emulators.installedPackage(ctx, emu) == null) continue
            when (val r = backup(ctx, emu, auto)) {
                is Result.Done -> done++
                Result.Unchanged -> same++
                Result.Empty -> {}
                is Result.Failed -> failed += "${emu.name}: ${r.message}"
            }
            inspect(ctx, emu)
        }
        return buildString {
            append(if (done > 0) "$done respaldado(s)" else "Nada nuevo que respaldar")
            if (same > 0 && done > 0) append(" · $same sin cambios")
            if (failed.isNotEmpty()) append(" · ").append(failed.joinToString(" · "))
        }
    }

    /** Lo que se le cuenta al PC de cada emulador (lo muestra la web en Tablet). */
    fun report(): JSONArray = JSONArray().apply {
        for (emu in Emulators.all) {
            val s = statuses[emu.id]
            put(JSONObject().put("id", emu.id).put("name", emu.name).put("installed", s?.installed == true)
                .put("size", s?.dataSize ?: 0).put("saves", s?.files ?: 0)
                .apply { Prefs.lastBackupAt(emu.id).takeIf { it > 0 }?.let { put("lastBackup", java.time.Instant.ofEpochMilli(it).toString()) } })
        }
    }
}
