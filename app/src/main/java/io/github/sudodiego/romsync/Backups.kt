package io.github.sudodiego.romsync

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Los respaldos en el PC: <carpeta de respaldos>/<tablet>/<emulador>/<fecha>.zip con un .json al lado (hash, archivos,
 * automático, conservar). Se guardan los últimos [Prefs.keepBackups] por emulador; los marcados "conservar" no se
 * borran solos. Los zips se pueden abrir en el PC con el Explorador.
 */
object Backups {
    fun safe(s: String) = s.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("").trim('.').ifEmpty { "tablet" }

    private fun dir(c: SftpConn, emu: String) = "${Prefs.remoteBackups}/${safe(Prefs.deviceName)}/${safe(emu)}"

    private fun meta(o: JSONObject) = SaveBackupInfo(o.optString("id"), o.optString("device"), o.optString("emulator"),
        o.optString("at"), o.optLong("size"), o.optString("hash"), o.optInt("files"), o.optBoolean("auto"), o.optBoolean("keep"))

    private fun json(b: SaveBackupInfo) = JSONObject().put("id", b.id).put("device", b.device).put("emulator", b.emulator)
        .put("at", b.at).put("size", b.size).put("hash", b.hash).put("files", b.files).put("auto", b.auto).put("keep", b.keep)

    fun list(c: SftpConn, emu: String): List<SaveBackupInfo> {
        val d = dir(c, emu)
        val names = c.list(d).map { it.name }.toSet()
        return names.filter { it.endsWith(".json") && it.removeSuffix(".json") + ".zip" in names }.mapNotNull { n ->
            c.readText("$d/$n", 64 * 1024)?.let { runCatching { meta(JSONObject(it)) }.getOrNull() }
        }.sortedByDescending { it.id }
    }

    fun listAll(c: SftpConn): List<SaveBackupInfo> =
        Emulators.all.flatMap { list(c, it.id) }.sortedByDescending { it.id }

    fun latestHash(c: SftpConn, emu: String): String = list(c, emu).firstOrNull()?.hash.orEmpty()

    fun upload(c: SftpConn, emu: String, zip: File, hash: String, files: Int, auto: Boolean): SaveBackupInfo {
        val d = dir(c, emu)
        val now = Date()
        val id = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(now)
        val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(now)
        c.upload("$d/$id.zip") { out -> zip.inputStream().use { it.copyTo(out, 1 shl 16) } }
        val b = SaveBackupInfo(id, Prefs.deviceName, emu, at, zip.length(), hash, files, auto, false)
        c.upload("$d/$id.json") { it.write(json(b).toString(2).toByteArray()) }
        prune(c, emu)
        return b
    }

    private fun prune(c: SftpConn, emu: String) {
        val d = dir(c, emu)
        list(c, emu).filter { !it.keep }.drop(Prefs.keepBackups).forEach {
            c.delete("$d/${it.id}.zip")
            c.delete("$d/${it.id}.json")
        }
    }

    fun download(c: SftpConn, emu: String, id: String, to: File) {
        c.open("${dir(c, emu)}/${safe(id)}.zip").use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
    }

    fun setKeep(c: SftpConn, emu: String, id: String, keep: Boolean) {
        val b = list(c, emu).firstOrNull { it.id == id } ?: return
        c.upload("${dir(c, emu)}/${safe(id)}.json") { it.write(json(b.copy(keep = keep)).toString(2).toByteArray()) }
    }

    fun delete(c: SftpConn, emu: String, id: String) {
        c.delete("${dir(c, emu)}/${safe(id)}.zip")
        c.delete("${dir(c, emu)}/${safe(id)}.json")
    }

    /** Sube un zip que viene de la web (Cocina) como respaldo nuevo; devuelve su id. */
    fun importZip(c: SftpConn, emu: String, zip: File): SaveBackupInfo = upload(c, emu, zip, "importado-${zip.length()}", 0, false)
}
