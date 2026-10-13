package io.github.sudodiego.romsync

import org.json.JSONArray
import org.json.JSONObject

// Lo mismo que el servidor (server/group.go), leído con org.json.

data class FileEntry(val path: String, val size: Long)

data class Part(
    val kind: String, // base, update, dlc
    val name: String,
    val titleId: String,
    val version: Long,
    val size: Long,
    val files: List<FileEntry>,
)

data class Game(
    val id: String,
    val system: String,
    val title: String,
    val name: String,
    val tags: List<String>,
    val kind: String,
    val primary: String,
    val size: Long,
    val files: List<FileEntry>,
    val parts: List<Part>,
    val serial: String,
)

data class SystemInfo(
    val id: String,
    val name: String,
    val short: String,
    val color: Long,
    val heavy: Boolean,
    val count: Int = 0,
    val size: Long = 0,
)

data class Library(
    val version: Long,
    val scanning: Boolean,
    val error: String,
    val systems: List<SystemInfo>,
    val games: List<Game>,
    val bios: List<FileEntry>,
    /** Archivos que sobran en la carpeta de ROMs del PC (descargas a medias, copias de partidas): ver Limpieza. */
    val junk: List<FileEntry> = emptyList(),
)

data class SaveBackupInfo(
    val id: String,
    val device: String,
    val emulator: String,
    val at: String,
    val size: Long,
    val hash: String,
    val files: Int,
    val auto: Boolean,
    val keep: Boolean,
)

/** Estado de un juego en la tablet, calculado aquí (también sin conexión). */
enum class Presence { INSTALLED, PARTIAL, MISSING, TABLET_ONLY }

object Json {
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun file(o: JSONObject) = FileEntry(o.getString("path"), o.optLong("size"))

    fun files(a: JSONArray?) = a.objects().map(::file)

    fun game(o: JSONObject) = Game(
        id = o.getString("id"),
        system = o.getString("system"),
        title = o.optString("title"),
        name = o.optString("name"),
        tags = o.optJSONArray("tags").strings(),
        kind = o.optString("kind"),
        primary = o.optString("primary"),
        size = o.optLong("size"),
        files = files(o.optJSONArray("files")),
        parts = o.optJSONArray("parts").objects().map {
            Part(it.optString("kind"), it.optString("name"), it.optString("titleId"), it.optLong("version"),
                it.optLong("size"), files(it.optJSONArray("files")))
        },
        serial = o.optString("serial"),
    )

    fun color(hex: String): Long = runCatching { 0xFF000000 or hex.removePrefix("#").toLong(16) }.getOrDefault(0xFF64748B)

    fun system(o: JSONObject, id: String = o.optString("id")) = SystemInfo(
        id = id,
        name = o.optString("name", id),
        short = o.optString("short", id.uppercase()),
        color = color(o.optString("color", "#64748b")),
        heavy = o.optBoolean("heavy"),
        count = o.optInt("count"),
        size = o.optLong("size"),
    )

    fun library(o: JSONObject) = Library(
        version = o.optLong("version"),
        scanning = o.optBoolean("scanning"),
        error = o.optString("error"),
        systems = o.optJSONArray("systems").objects().map { system(it) },
        games = o.optJSONArray("games").objects().map(::game),
        bios = files(o.optJSONArray("bios")),
        junk = files(o.optJSONArray("junk")),
    )

    fun backups(a: JSONArray) = a.objects().map {
        SaveBackupInfo(it.optString("id"), it.optString("device"), it.optString("emulator"), it.optString("at"),
            it.optLong("size"), it.optString("hash"), it.optInt("files"), it.optBoolean("auto"), it.optBoolean("keep"))
    }

    fun gameJson(g: Game): JSONObject = JSONObject()
        .put("id", g.id).put("system", g.system).put("title", g.title).put("name", g.name)
        .put("tags", JSONArray(g.tags)).put("kind", g.kind).put("primary", g.primary).put("size", g.size)
        .put("files", filesJson(g.files)).put("serial", g.serial)
        .put("parts", JSONArray().apply {
            g.parts.forEach {
                put(JSONObject().put("kind", it.kind).put("name", it.name).put("titleId", it.titleId)
                    .put("version", it.version).put("size", it.size).put("files", filesJson(it.files)))
            }
        })

    fun libraryJson(l: Library): JSONObject = JSONObject()
        .put("version", l.version).put("scanning", false).put("error", l.error)
        .put("systems", JSONArray().apply {
            l.systems.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("short", it.short)
                    .put("color", "#%06x".format(it.color and 0xFFFFFF)).put("heavy", it.heavy).put("count", it.count).put("size", it.size))
            }
        })
        .put("games", JSONArray().apply { l.games.forEach { put(gameJson(it)) } })
        .put("bios", filesJson(l.bios))
        .put("junk", filesJson(l.junk))

    fun filesJson(files: List<FileEntry>) = JSONArray().apply {
        files.forEach { put(JSONObject().put("path", it.path).put("size", it.size)) }
    }
}
