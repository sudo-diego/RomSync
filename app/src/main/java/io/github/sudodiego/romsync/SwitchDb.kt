package io.github.sudodiego.romsync

import android.content.Context
import android.util.JsonReader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Qué actualizaciones y DLC existen para cada juego de Switch, según titledb (github.com/blawar/titledb):
 * - versions.json (1,7 MB): todas las versiones de cada juego, con fecha → la última actualización.
 * - cnmts.json (50 MB): todo el contenido publicado → los DLC de cada juego. Se lee de a poco (JsonReader) y solo se
 *   guarda lo de los juegos de la biblioteca, así que en la tablet queda un archivo de pocos KB.
 * Se baja una vez por semana, con WiFi; sin internet se usa lo último guardado.
 */
object SwitchDb {
    data class Info(val latest: Long, val latestDate: String, val dlcs: List<String>)

    private const val BASE = "https://raw.githubusercontent.com/blawar/titledb/master/"
    private const val WEEK = 7L * 24 * 3600 * 1000
    private lateinit var file: File
    private var data: Map<String, Info> = emptyMap()
    var updatedAt by mutableStateOf(0L)
        private set
    var busy by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "switchdb.json")
        runCatching {
            val o = JSONObject(file.readText())
            updatedAt = o.optLong("at")
            val g = o.getJSONObject("games")
            data = g.keys().asSequence().associateWith { k ->
                val v = g.getJSONObject(k)
                val d = v.optJSONArray("dlcs") ?: JSONArray()
                Info(v.optLong("latest"), v.optString("date"), (0 until d.length()).map { d.getString(it) })
            }
        }
    }

    fun info(baseTid: String): Info? = data[baseTid.lowercase()]

    /** "Versión 12" (los números de versión de Switch van de 65536 en 65536). */
    fun label(version: Long) = if (version <= 0) "sin actualizar" else "versión ${version / 65536}"

    /** Baja los datos si pasaron 7 días (o si se fuerza). Solo guarda los juegos de [bases] (TitleID del juego base). */
    suspend fun refresh(bases: Set<String>, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!::file.isInitialized || busy || bases.isEmpty()) return@withContext
        if (!force && System.currentTimeMillis() - updatedAt < WEEK && bases.all { data.containsKey(it.lowercase()) }) return@withContext
        busy = true
        try {
            val want = bases.map { it.lowercase() }.toSet()
            val latest = HashMap<String, Pair<Long, String>>()
            open("versions.json").use { input ->
                val r = JsonReader(input.reader())
                r.beginObject()
                while (r.hasNext()) {
                    val tid = r.nextName().lowercase()
                    if (tid !in want) { r.skipValue(); continue }
                    var best = 0L to ""
                    r.beginObject()
                    while (r.hasNext()) {
                        val v = r.nextName().toLongOrNull() ?: 0L
                        val date = r.nextString()
                        if (v > best.first) best = v to date
                    }
                    r.endObject()
                    latest[tid] = best
                }
                r.endObject()
            }
            val dlcs = HashMap<String, MutableList<String>>()
            open("cnmts.json").use { input ->
                val r = JsonReader(input.reader())
                r.beginObject()
                while (r.hasNext()) {
                    val tid = r.nextName().lowercase()
                    r.skipValue()
                    // DLC: base + 0x1000 + n (el TitleID del juego es el del DLC sin los últimos 3 dígitos y uno menos en el 4.º).
                    val n = tid.toULongOrNull(16) ?: continue
                    if (n and 0xFFFu == 0uL) continue
                    val base = "%016x".format(((n - 0x1000u) and 0xFFFFFFFFFFFFF000u).toLong())
                    if (base in want && !tid.endsWith("800")) dlcs.getOrPut(base) { ArrayList() } += tid.uppercase()
                }
                r.endObject()
            }
            val out = HashMap(data)
            for (b in want) {
                val (v, d) = latest[b] ?: (0L to "")
                out[b] = Info(v, d, dlcs[b].orEmpty().sorted())
            }
            val games = JSONObject()
            out.forEach { (b, i) -> games.put(b, JSONObject().put("latest", i.latest).put("date", i.latestDate).put("dlcs", JSONArray(i.dlcs))) }
            data = out
            updatedAt = System.currentTimeMillis()
            file.writeText(JSONObject().put("at", updatedAt).put("games", games).toString())
        } catch (_: Exception) {
            // Sin internet o GitHub caído: se queda lo que había.
        } finally {
            busy = false
        }
    }

    private fun open(name: String) = (URL(BASE + name).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
    }.inputStream.buffered(1 shl 16)
}
