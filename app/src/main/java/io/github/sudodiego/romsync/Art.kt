package io.github.sudodiego.romsync

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Carátulas (grid), fondos (hero) y logos de SteamGridDB, guardados en la tablet. También las que se suben a mano
 * desde la web (Cocina). Un juego sin resultado no se vuelve a buscar en 7 días.
 */
object Art {
    private const val API = "https://www.steamgriddb.com/api/v2"
    val kinds = mapOf("grid" to "grids", "hero" to "heroes", "logo" to "logos", "icon" to "icons")

    private lateinit var dir: File
    private lateinit var indexFile: File
    private val index = HashMap<String, JSONObject>() // id del juego → {sgdbId, name, checked_<kind>}
    private val sem = Semaphore(3)
    private val locks = HashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Versión de cada imagen: cambia al reemplazarla para que Compose la vuelva a cargar. */
    val versions = mutableStateMapOf<String, Int>()

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "art").apply { mkdirs() }
        indexFile = File(dir, "index.json")
        runCatching {
            val o = JSONObject(indexFile.readText())
            o.keys().forEach { index[it] = o.getJSONObject(it) }
        }
    }

    private fun saveIndex() = synchronized(index) {
        val o = JSONObject()
        index.forEach { (k, v) -> o.put(k, v) }
        runCatching { indexFile.writeText(o.toString()) }
    }

    fun file(id: String, kind: String) = File(dir, "${id}_$kind.img")
    private fun custom(id: String, kind: String) = File(dir, "${id}_$kind.custom")

    /** La imagen si ya está en la tablet (sin buscar). La subida a mano gana. */
    fun cached(id: String, kind: String = "grid"): File? =
        custom(id, kind).takeIf { it.isFile } ?: file(id, kind).takeIf { it.isFile && it.length() > 0 }

    /** Busca y baja la imagen si hace falta. Null si no hay (o no hay clave / internet). */
    suspend fun get(g: Game, kind: String = "grid"): File? {
        cached(g.id, kind)?.let { return it }
        val key = Prefs.sgdbKey
        if (key.isBlank()) return null
        val lock = synchronized(locks) { locks.getOrPut("${g.id}/$kind") { kotlinx.coroutines.sync.Mutex() } }
        return lock.withLockCompat {
            cached(g.id, kind)?.let { return@withLockCompat it }
            val entry = synchronized(index) { index.getOrPut(g.id) { JSONObject() } }
            val checked = entry.optLong("checked_$kind")
            if (checked > 0 && System.currentTimeMillis() - checked < 7L * 24 * 3600 * 1000) return@withLockCompat null
            try {
                sem.withPermit { fetch(key, g, kind, entry) }
            } catch (e: IOException) {
                null // sin internet o SteamGridDB caído: se intenta la próxima vez
            }
        }
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockCompat(block: suspend () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    private suspend fun fetch(key: String, g: Game, kind: String, entry: JSONObject): File? = withContext(Dispatchers.IO) {
        var sgdbId = entry.optInt("sgdbId")
        if (sgdbId == 0) {
            val found = search(key, g.title)
            sgdbId = found.firstOrNull { norm(it.second) == norm(g.title) }?.first ?: found.firstOrNull()?.first ?: -1
            entry.put("sgdbId", sgdbId)
        }
        var out: File? = null
        if (sgdbId > 0) {
            val url = firstImage(key, kind, sgdbId)
            if (url != null) {
                val f = file(g.id, kind)
                download(url, f)
                out = f
            }
        }
        entry.put("checked_$kind", if (out == null) System.currentTimeMillis() else 0)
        saveIndex()
        out?.also { versions[g.id] = (versions[g.id] ?: 0) + 1 }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun getJson(key: String, path: String): JSONObject? {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer $key")
            return when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().readText())
                404 -> null
                401, 403 -> throw IOException("SteamGridDB rechazó la clave")
                else -> throw IOException("SteamGridDB respondió ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    /** Autocompletado de SteamGridDB: (id, nombre). */
    suspend fun search(key: String = Prefs.sgdbKey, q: String): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val o = getJson(key, "/search/autocomplete/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")) ?: return@withContext emptyList()
        val a = o.optJSONArray("data") ?: return@withContext emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.getInt("id") to it.optString("name") }
    }

    private fun firstImage(key: String, kind: String, id: Int): String? {
        var path = "/${kinds[kind]}/game/$id?types=static&nsfw=false&humor=false"
        if (kind == "grid") path += "&dimensions=600x900,342x482,660x930"
        var a = getJson(key, path)?.optJSONArray("data")
        if ((a == null || a.length() == 0) && kind == "grid") a = getJson(key, "/grids/game/$id?types=static&nsfw=false&humor=false")?.optJSONArray("data")
        if (a == null || a.length() == 0) return null
        return a.getJSONObject(0).optString("url").ifBlank { null }
    }

    private fun download(url: String, to: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            if (c.responseCode != 200) throw IOException("imagen: ${c.responseCode}")
            val tmp = File(to.path + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(to)
        } finally {
            c.disconnect()
        }
    }

    /** Fija a mano el juego de SteamGridDB (desde la web) y borra lo descargado. */
    fun choose(gameId: String, sgdbId: Int) {
        synchronized(index) { index[gameId] = JSONObject().put("sgdbId", sgdbId) }
        kinds.keys.forEach { file(gameId, it).delete() }
        saveIndex()
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    /** Imagen subida desde la web (gana sobre SteamGridDB). */
    fun setCustom(gameId: String, kind: String, bytes: ByteArray) {
        custom(gameId, kind).writeBytes(bytes)
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    fun clear(gameId: String) {
        kinds.keys.forEach { file(gameId, it).delete(); custom(gameId, it).delete() }
        synchronized(index) { index[gameId] = JSONObject().put("checked_grid", System.currentTimeMillis()) }
        saveIndex()
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }
}
