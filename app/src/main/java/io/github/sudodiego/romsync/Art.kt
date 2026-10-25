package io.github.sudodiego.romsync

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Carátulas (grid), fondos (hero) y logos de SteamGridDB, guardados en la tablet. También las que se suben a mano
 * desde la web. Un juego sin resultado no se vuelve a buscar en 7 días.
 */
object Art {
    private const val API = "https://www.steamgriddb.com/api/v2"
    val kinds = mapOf("grid" to "grids", "hero" to "heroes", "logo" to "logos", "icon" to "icons")

    private lateinit var dir: File
    private lateinit var indexFile: File
    private val index = HashMap<String, JSONObject>() // id del juego → {sgdbId, name, checked_<kind>}
    // SteamGridDB aguanta bien varias a la vez: con 3 la primera pasada de una biblioteca grande tardaba mucho.
    private val sem = Semaphore(8)
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

    /** Ya se buscó hace poco y SteamGridDB no tiene nada: no cuenta como "falta buscar" (hay que ponerla a mano). */
    fun notFound(id: String, kind: String = "grid"): Boolean {
        if (cached(id, kind) != null) return false
        val t = synchronized(index) { index[id]?.optLong("checked_$kind") } ?: return false
        return t > 0 && System.currentTimeMillis() - t < 7L * 24 * 3600 * 1000
    }

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
            // Un juego de Switch que no se encontró por su nombre de archivo se reintenta una vez con el nombre oficial.
            val retryOfficial = g.kind == "switch" && g.serial.length == 16 && entry.optInt("sgdbId") == -1 && !entry.optBoolean("officialTried")
            if (retryOfficial) { entry.put("sgdbId", 0); entry.put("officialTried", true) }
            else if (checked > 0 && System.currentTimeMillis() - checked < 7L * 24 * 3600 * 1000) return@withLockCompat null
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

    /** El título que sale del NOMBRE DEL ARCHIVO (no el oficial ni el propio): con eso se valida lo que encuentra. */
    private fun fileTitle(g: Game) = norm(Group.cleanTitle(g.name).first)

    /**
     * Si un juego de SteamGridDB es el mismo que el del archivo: mismo nombre, o el nombre completo termina igual
     * ("The Legend of Zelda: The Wind Waker HD" para "The Wind Waker HD.wua", "Disney's Aladdin" para "Aladdin").
     */
    private fun sameGame(sgdbName: String, want: String): Boolean {
        val n = norm(sgdbName)
        return n == want || (want.length >= 4 && n.endsWith(want))
    }

    private suspend fun fetch(key: String, g: Game, kind: String, entry: JSONObject): File? = withContext(Dispatchers.IO) {
        var sgdbId = entry.optInt("sgdbId")
        if (sgdbId == 0) {
            // Switch: se busca por el nombre oficial (sacado del código del juego). Los archivos de algunas páginas
            // vienen con nombres disfrazados ("Zel Air Mata Kerajaan" = Tears of the Kingdom) y no se encontraba nada.
            val official = if (g.kind == "switch" && g.serial.length == 16) Tidy.officialNameFor(g.serial.uppercase()) else null
            val found = search(key, official ?: g.title)
            val want = official?.let(::norm) ?: fileTitle(g)
            // Antes se tomaba el primero si no había uno idéntico: "Aladdin" daba "Aladdin - Hidden Objects Game".
            val best = found.firstOrNull { norm(it.second) == want } ?: found.firstOrNull { sameGame(it.second, want) }
            sgdbId = best?.first ?: found.firstOrNull()?.first ?: -1
            entry.put("sgdbId", sgdbId)
            if (best != null) entry.put("name", best.second).put("nameOk", true) else entry.put("nameOk", false)
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
            shrink(tmp, to.name)
            tmp.renameTo(to)
        } finally {
            c.disconnect()
        }
    }

    /** Todas las carátulas (o fondos, logos) de un juego de SteamGridDB: (miniatura, url). */
    suspend fun options(sgdbId: Int, kind: String = "grid"): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val key = Prefs.sgdbKey
        var path = "/${kinds[kind]}/game/$sgdbId?types=static&nsfw=false&humor=false"
        if (kind == "grid") path += "&dimensions=600x900,342x482,660x930"
        val a = getJson(key, path)?.optJSONArray("data") ?: return@withContext emptyList()
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("thumb").ifBlank { it.optString("url") } to it.optString("url") }
    }

    /** Usa una imagen elegida a mano (de [options]) para un juego. */
    suspend fun useUrl(gameId: String, kind: String, url: String) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${gameId}_$kind.dl")
        download(url, tmp)
        custom(gameId, kind).delete()
        tmp.renameTo(custom(gameId, kind))
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    /** Prueba la clave: null si sirve, o el error para mostrar. */
    suspend fun testKey(key: String): String? = try {
        search(key, "Mario")
        null
    } catch (e: IOException) {
        if (e.message?.contains("clave") == true) "SteamGridDB rechazó la clave" else "No se pudo conectar con SteamGridDB (${e.message})"
    }

    // ---- Búsqueda de todas las carátulas de una vez (con barra de avance) ----
    var bulkDone by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var bulkTotal by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    val bulkRunning get() = bulkTotal > 0 && bulkDone < bulkTotal

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** Lo que se baja de cada juego de una vez: la carátula, el fondo de la ficha y el logo (antes solo la carátula). */
    private val bulkKinds = listOf("grid", "hero", "logo")

    /** Si a un juego le falta alguna imagen que todavía no se buscó. */
    fun needsFetch(g: Game) = g.kind != "shortcut" && (needsName(g) || bulkKinds.any { cached(g.id, it) == null && !notFound(g.id, it) })

    /** Búsqueda automática (al terminar de leer la biblioteca): sin ventana, solo un texto en la tienda. */
    var bulkQuiet by androidx.compose.runtime.mutableStateOf(false)
        private set

    /** Igual que [fetchAll] pero sigue aunque se cambie de pantalla. */
    fun startFetchAll(games: List<Game>, quiet: Boolean = false) {
        if (bulkRunning || Prefs.sgdbKey.isBlank()) return
        if (games.none { needsFetch(it) }) return
        bulkQuiet = quiet
        scope.launch { fetchAll(games) }
    }

    /** Busca las imágenes que faltan, varios juegos a la vez. */
    suspend fun fetchAll(games: List<Game>) = kotlinx.coroutines.coroutineScope {
        val todo = games.filter { needsFetch(it) }
        bulkDone = 0
        bulkTotal = todo.size
        todo.map { g ->
            launch {
                if (needsName(g)) runCatching { sem.withPermit { checkName(Prefs.sgdbKey, g) } }
                for (k in bulkKinds) runCatching { get(g, k) }
                bulkDone++
            }
        }.forEach { it.join() }
        bulkTotal = 0
        Repo.reapplyNames()
    }

    /** El nombre oficial (de SteamGridDB) si se confirmó que es el mismo juego que el del archivo, o si se eligió a mano. */
    fun officialName(id: String): String? = synchronized(index) {
        index[id]?.takeIf { it.optBoolean("nameOk") }?.optString("name")?.takeIf { it.isNotBlank() }
    }

    /** Coincidencias viejas (de antes de validar el nombre) que todavía no se revisaron. */
    private fun needsName(g: Game): Boolean = g.kind != "shortcut" && synchronized(index) {
        index[g.id]?.let { it.optInt("sgdbId") > 0 && !it.has("nameOk") } == true
    }

    /**
     * Revisa una coincidencia vieja: pide el nombre del juego a SteamGridDB. Si es el mismo juego, queda como nombre
     * oficial; si era otro (se tomaba el primer resultado), se olvida y se vuelve a buscar con el criterio nuevo.
     */
    private suspend fun checkName(key: String, g: Game) = withContext(Dispatchers.IO) {
        val entry = synchronized(index) { index[g.id] } ?: return@withContext
        val name = runCatching { getJson(key, "/games/id/${entry.optInt("sgdbId")}")?.optJSONObject("data")?.optString("name") }
            .getOrNull() ?: return@withContext
        if (sameGame(name, fileTitle(g))) entry.put("name", name).put("nameOk", true)
        else {
            synchronized(index) { index[g.id] = JSONObject() }
            kinds.keys.forEach { file(g.id, it).delete() }
            versions[g.id] = (versions[g.id] ?: 0) + 1
        }
        saveIndex()
    }

    /** Fija a mano el juego de SteamGridDB (desde la ficha o la web) y borra lo descargado. */
    fun choose(gameId: String, sgdbId: Int, name: String = "") {
        synchronized(index) {
            index[gameId] = JSONObject().put("sgdbId", sgdbId).apply { if (name.isNotBlank()) put("name", name).put("nameOk", true) }
        }
        kinds.keys.forEach { file(gameId, it).delete() }
        saveIndex()
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    /** Olvida lo encontrado para un juego (no las imágenes elegidas a mano): se vuelve a buscar con su nombre actual. */
    fun forget(gameId: String) {
        synchronized(index) { index.remove(gameId) }
        kinds.keys.forEach { file(gameId, it).delete() }
        saveIndex()
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    /** Ancho máximo con que se guarda cada tipo (lo que se ve en la pantalla de la tablet; más es peso y lag). */
    private val maxWidth = mapOf("grid" to 600, "hero" to 1920, "logo" to 800, "icon" to 256)

    /**
     * Deja la imagen del tamaño en que se muestra, en WebP (con transparencia para logos e íconos). Un fondo de
     * SteamGridDB viene a 3840 px y varios MB; así queda en unos cientos de KB y se dibuja mucho más rápido.
     */
    fun shrink(f: File, name: String = f.name) {
        runCatching {
            val kind = kinds.keys.firstOrNull { name.contains("_$it.") } ?: return
            val max = maxWidth.getValue(kind)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return
            // Ya está chica y es WebP: nada que hacer.
            if (o.outWidth <= max && o.outMimeType == "image/webp") return
            var sample = 1
            while (o.outWidth / (sample * 2) >= max) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return
            val scaled = if (bmp.width > max) android.graphics.Bitmap.createScaledBitmap(bmp, max, bmp.height * max / bmp.width, true) else bmp
            val out = File(f.path + ".webp")
            out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            if (out.length() in 1 until f.length()) { f.delete(); out.renameTo(f) } else out.delete()
        }
    }

    /** Una sola vez: achica las imágenes que se bajaron antes de existir [shrink]. */
    suspend fun shrinkAll() = withContext(Dispatchers.IO) {
        if (Prefs.flag("artShrunk")) return@withContext
        val before = dir.walkTopDown().sumOf { it.length() }
        dir.listFiles()?.filter { it.name.endsWith(".img") || it.name.endsWith(".custom") }?.forEach { f ->
            val before = f.length()
            shrink(f)
            if (f.length() != before) {
                val id = f.name.substringBefore('_')
                withContext(Dispatchers.Main) { versions[id] = (versions[id] ?: 0) + 1 }
            }
        }
        android.util.Log.i("RomSync", "imágenes: ${before shr 20} MB → ${dir.walkTopDown().sumOf { it.length() } shr 20} MB")
        Prefs.setFlag("artShrunk", true)
    }

    /** Imagen subida desde la web (gana sobre SteamGridDB). */
    fun setCustom(gameId: String, kind: String, bytes: ByteArray) {
        custom(gameId, kind).writeBytes(bytes)
        shrink(custom(gameId, kind))
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }

    fun clear(gameId: String) {
        kinds.keys.forEach { file(gameId, it).delete(); custom(gameId, it).delete() }
        synchronized(index) { index[gameId] = JSONObject().put("checked_grid", System.currentTimeMillis()) }
        saveIndex()
        versions[gameId] = (versions[gameId] ?: 0) + 1
    }
}
