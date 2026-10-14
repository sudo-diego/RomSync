package io.github.sudodiego.romsync

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
        entries.groupBy { (s, f) -> unitOf(emu.id, "${s.label}/${f.rel}") }
            .map { (k, es) -> Unit(k.first, titleFor(k.first, k.second), es.first().second.rel.substringAfterLast('/'), es.size,
                es.sumOf { it.second.size }, es.maxOf { it.second.mtime } * 1000) }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun backup(ctx: Context, emu: Emulator, auto: Boolean, force: Boolean = false, only: Set<String>? = null): Result = withContext(Dispatchers.IO) {
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
            busy[emu.id] = "Enviando al PC…"
            Pc.use { Backups.upload(it, emu.id, zip, hash, entries.size, auto) }
            Prefs.setLastBackup(emu.id, hash, System.currentTimeMillis())
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

    /** A qué juego pertenece un archivo del respaldo: (clave, nombre para mostrar). */
    fun unitOf(emu: String, name: String): Pair<String, String> {
        val rel = name.substringAfter('/')
        val parts = rel.split('/')
        val file = parts.last()
        fun byName(): Pair<String, String> {
            var base = file
            val i = base.lowercase().indexOf(".state")
            base = if (i > 0) base.substring(0, i) else base.substringBeforeLast('.')
            return "g:" + base.lowercase() to Group.cleanTitle(base).first
        }
        return when (emu) {
            "dolphin" -> when {
                parts[0] == "Wii" && parts.size > 3 -> "id:" + hexAscii(parts[3]).take(4) to "Wii · " + hexAscii(parts[3])
                parts[0] == "StateSaves" -> "id:" + file.take(4) to "Estado · " + file.take(6)
                parts[0] == "GC" -> Regex("^[0-9A-Fa-f]{2}-([A-Z0-9]{4})").find(file)?.let { "id:" + it.groupValues[1] to "GameCube · " + it.groupValues[1] }
                    ?: ("card:$rel" to "Tarjeta de memoria · " + parts.drop(1).joinToString(" / "))
                else -> "otros:" + parts[0] to parts[0]
            }
            "eden" -> if (rel.contains("/user/save/") && parts.size > 5) "tid:" + parts[5].uppercase() to parts[5].uppercase()
                else "perfiles" to "Perfiles de usuario"
            "vita3k" -> if (parts.size > 4 && parts[3] == "savedata") "id:" + parts[4] to parts[4] else "trofeos" to "Trofeos y otros"
            "armsx2", "nethersx2" -> if (parts[0] == "memcards") "card:$file" to "Tarjeta de memoria · $file"
                else file.substringBefore(" (").substringBefore('.').let { "id:$it" to "Estado · $it" }
            "azahar" -> {
                val t = parts.indexOf("title")
                val e = parts.indexOf("extdata")
                when {
                    t >= 0 && parts.size > t + 2 -> "id:" + parts[t + 2].lowercase() to "3DS · " + parts[t + 2].uppercase()
                    e >= 0 && parts.size > e + 2 -> "ext:" + parts[e + 2].lowercase() to "Datos extra · " + parts[e + 2].uppercase()
                    else -> byName()
                }
            }
            else -> byName()
        }
    }

    /** Nombre del juego en la biblioteca para un TitleID de Switch o un ID de Vita, si se conoce. */
    private fun titleFor(key: String, fallback: String): String {
        val id = key.substringAfter(':')
        val g = Repo.allGames().firstOrNull { it.serial.isNotEmpty() && it.serial.equals(id, true) }
        return g?.title?.let { "$it · $id" } ?: fallback
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
                .groupBy { unitOf(emu.id, it.name) }
                .map { (k, es) -> Unit(k.first, titleFor(k.first, k.second), es.first().name.substringAfterLast('/'), es.size,
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
            when (val r = backup(ctx, emu, auto = false, force = false)) {
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
                busy["nethersx2"] = "Respaldando en el PC…"
                backup(ctx, nether, auto = false, force = true)
                backup(ctx, arm, auto = false, force = true)
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
