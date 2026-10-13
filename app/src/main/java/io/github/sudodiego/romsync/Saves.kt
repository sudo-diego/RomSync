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
        val supported get() = emu.id != "gamenative"
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
        if (pkg == null || emu.id == "gamenative") return Status(emu, pkg).also { statuses[emu.id] = it }
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
    suspend fun backup(ctx: Context, emu: Emulator, auto: Boolean, force: Boolean = false): Result = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext Result.Failed("${emu.name} no está instalado")
        busy[emu.id] = "Leyendo partidas…"
        val zip = File(ctx.cacheDir, "respaldo_${emu.id}.zip")
        try {
            val (sources, entries) = collect(emu, pkg)
            if (entries.isEmpty()) return@withContext Result.Empty
            val hash = hashOf(entries)
            if (!force) {
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
    suspend fun restore(ctx: Context, emu: Emulator, backupId: String, zipFromWeb: File? = null): String? = withContext(Dispatchers.IO) {
        val pkg = Emulators.installedPackage(ctx, emu) ?: return@withContext "${emu.name} no está instalado"
        if (Shell.ready && Shell.isRunning(pkg)) return@withContext "Cierra ${emu.name} antes de restaurar (Recientes → deslizar)."
        val zipFile = File(ctx.cacheDir, "restaurar_${emu.id}.zip")
        try {
            busy[emu.id] = "Respaldando lo actual…"
            when (val r = backup(ctx, emu, auto = false, force = false)) {
                is Result.Failed -> return@withContext "No se pudo respaldar lo actual antes de restaurar: ${r.message}"
                else -> {}
            }
            if (zipFromWeb != null) zipFromWeb.copyTo(zipFile, overwrite = true) else {
                busy[emu.id] = "Descargando el respaldo…"
                Pc.use { Backups.download(it, emu.id, backupId, zipFile) }
            }
            busy[emu.id] = "Restaurando…"
            val sources = Emulators.sources(emu, pkg).associateBy { it.label }
            ZipFile(zipFile).use { zip ->
                val byLabel = zip.entries().asSequence().filter { !it.isDirectory && it.name.contains('/') }
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

    /** Respalda todos los emuladores instalados a los que se puede llegar. Devuelve un resumen corto. */
    suspend fun backupAll(ctx: Context, auto: Boolean): String {
        var done = 0
        var same = 0
        val failed = mutableListOf<String>()
        for (emu in Emulators.all) {
            if (emu.id == "gamenative" || Emulators.installedPackage(ctx, emu) == null) continue
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
