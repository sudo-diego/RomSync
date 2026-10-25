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

    private fun strings(a: org.json.JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun meta(o: JSONObject) = SaveBackupInfo(o.optString("id"), o.optString("device"), o.optString("emulator"),
        o.optString("at"), o.optLong("size"), o.optString("hash"), o.optInt("files"), o.optBoolean("auto"), o.optBoolean("keep"),
        o.optString("kind"), strings(o.optJSONArray("games")), strings(o.optJSONArray("keys")))

    fun json(b: SaveBackupInfo): JSONObject = JSONObject().put("id", b.id).put("device", b.device).put("emulator", b.emulator)
        .put("at", b.at).put("size", b.size).put("hash", b.hash).put("files", b.files).put("auto", b.auto).put("keep", b.keep)
        .put("kind", b.kind).put("games", org.json.JSONArray(b.games)).put("keys", org.json.JSONArray(b.keys))

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

    /**
     * Respaldos de antes de que se guardara qué juegos traen: se lee el zip una vez (solo la lista de archivos, al
     * final del zip) y se anota en su .json. Si trae todo lo que había en ese momento se llama "Completo".
     */
    fun describeOld(c: SftpConn, emu: String, list: List<SaveBackupInfo>, cache: java.io.File): List<SaveBackupInfo> =
        list.map { b ->
            if (b.kind.isNotEmpty() || b.size > 300L * 1024 * 1024) return@map b
            runCatching {
                val tmp = java.io.File(cache, "describir.zip")
                download(c, emu, b.id, tmp)
                val names = java.util.zip.ZipFile(tmp).use { z -> z.entries().asSequence().filter { !it.isDirectory && it.name.contains('/') }.map { it.name }.toList() }
                tmp.delete()
                val keys = names.groupBy { Saves.unitOf(emu, it).first }
                val games = keys.map { (k, ns) -> Saves.describeUnit(emu, k, ns) }.sorted()
                val kind = if (b.auto) "auto" else if (keys.size <= 3 && list.any { o -> o.files > b.files }) "juego" else "todo"
                b.copy(kind = kind, games = games, keys = keys.keys.toList()).also { nb ->
                    c.upload("${dir(c, emu)}/${safe(b.id)}.json") { it.write(json(nb).toString(2).toByteArray()) }
                }
            }.getOrDefault(b)
        }

    fun upload(c: SftpConn, emu: String, zip: File, hash: String, files: Int, auto: Boolean,
               kind: String = "", games: List<String> = emptyList(), keys: List<String> = emptyList()): SaveBackupInfo {
        val d = dir(c, emu)
        val now = Date()
        val id = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(now)
        val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(now)
        c.upload("$d/$id.zip") { out -> zip.inputStream().use { it.copyTo(out, 1 shl 16) } }
        val b = SaveBackupInfo(id, Prefs.deviceName, emu, at, zip.length(), hash, files, auto, false, kind, games, keys)
        c.upload("$d/$id.json") { it.write(json(b).toString(2).toByteArray()) }
        prune(c, emu)
        return b
    }

    /**
     * Qué respaldos se borran (los más viejos de cada tipo). Antes eran los últimos 30 juntos: uno de un solo juego (6 KB)
     * gastaba el mismo cupo que uno completo (134 MB) y Dolphin podía llegar a 4 GB.
     * - completos y automáticos: los últimos [Prefs.keepBackups] (10);
     * - de un juego: los últimos 5 de CADA juego;
     * - copias de seguridad: las últimas 5;
     * - los marcados "conservar" nunca.
     */
    fun toPrune(all: List<SaveBackupInfo>): List<SaveBackupInfo> {
        val free = all.filter { !it.keep }.sortedByDescending { it.id }
        val full = free.filter { it.kind == "todo" || it.kind == "auto" || it.kind.isEmpty() }.drop(Prefs.keepBackups)
        val safety = free.filter { it.kind == "antes" || it.kind == "traspaso" }.drop(5)
        val perGame = free.filter { it.kind == "juego" }.groupBy { it.keys.sorted().joinToString("|") }.values.flatMap { it.drop(5) }
        return full + safety + perGame
    }

    private fun prune(c: SftpConn, emu: String) {
        val d = dir(c, emu)
        toPrune(list(c, emu)).forEach {
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

    /** Sube un zip que viene de la web como respaldo nuevo; devuelve su id. */
    fun importZip(c: SftpConn, emu: String, zip: File): SaveBackupInfo = upload(c, emu, zip, "importado-${zip.length()}", 0, false)
}
