package io.github.sudodiego.romsync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

sealed class Conn {
    data object Idle : Conn()
    data object Connecting : Conn()
    data class Online(val at: Long) : Conn()
    data class Offline(val message: String) : Conn()
}

/**
 * Lo que muestran la app y la Cocina: la biblioteca del PC (o la última leída, sin conexión), los archivos de la
 * tablet y el espacio. La comparación se hace aquí, por ruta (sin distinguir mayúsculas).
 */
object Repo {
    var library by mutableStateOf<Library?>(null)
        private set
    var conn by mutableStateOf<Conn>(Conn.Idle)
        private set
    var localFiles by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var localBios by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var space by mutableStateOf(Local.Space(0, 0))
        private set
    var tabletOnly by mutableStateOf<List<Game>>(emptyList())
        private set
    var biosMissing by mutableStateOf<List<FileEntry>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var scanningPc by mutableStateOf(false)
        private set

    private var localIndex: Map<String, Long> = emptyMap()
    private var tabletOnlyIds: Set<String> = emptySet()
    private val mutex = Mutex()
    private lateinit var cacheFile: File

    fun init(ctx: Context) {
        if (::cacheFile.isInitialized) return
        cacheFile = File(ctx.filesDir, "library.json")
        runCatching { library = Json.library(JSONObject(cacheFile.readText())) }
    }

    fun system(id: String): SystemInfo = library?.systems?.firstOrNull { it.id == id } ?: Systems.get(id)

    data class Where(val presence: Presence, val missing: List<FileEntry>, val localSize: Long, val differs: Boolean)

    fun where(g: Game): Where {
        if (g.id in tabletOnlyIds) return Where(Presence.TABLET_ONLY, emptyList(), g.size, false)
        var have = 0
        var localSize = 0L
        var differs = false
        val missing = ArrayList<FileEntry>()
        for (f in g.files) {
            val s = localIndex[f.path.lowercase()]
            if (s == null) missing += f else {
                have++
                localSize += s
                if (s != f.size) differs = true
            }
        }
        val p = when {
            have == 0 -> Presence.MISSING
            missing.isEmpty() -> Presence.INSTALLED
            else -> Presence.PARTIAL
        }
        return Where(p, missing, localSize, differs)
    }

    fun has(path: String) = localIndex.containsKey(path.lowercase())

    fun allGames(): List<Game> = library?.games.orEmpty() + tabletOnly

    fun game(id: String): Game? = library?.games?.firstOrNull { it.id == id } ?: tabletOnly.firstOrNull { it.id == id }

    /** Vuelve a leer la tablet y, si [pc], la biblioteca del PC. */
    suspend fun refresh(pc: Boolean = true) = mutex.withLock {
        refreshing = true
        try {
            rescanLocal()
            if (pc && Prefs.configured) {
                conn = Conn.Connecting
                scanningPc = true
                try {
                    val lib = Pc.scan()
                    library = lib
                    withContext(Dispatchers.IO) { cacheFile.writeText(Json.libraryJson(lib).toString()) }
                    conn = Conn.Online(System.currentTimeMillis())
                } catch (e: Exception) {
                    conn = Conn.Offline(e.message ?: "Sin conexión")
                } finally {
                    scanningPc = false
                }
            } else if (!Prefs.configured) {
                conn = Conn.Idle
            }
            compare()
        } finally {
            refreshing = false
        }
    }

    suspend fun rescanLocal() = withContext(Dispatchers.IO) {
        val files = Local.scanRoms()
        localIndex = files.associate { it.path.lowercase() to it.size }
        localFiles = files
        localBios = Local.scanBios()
        space = Local.space()
        compare()
    }

    /** Lo de la tablet que no está en el PC (agrupado por nombre) y las BIOS que faltan. */
    private fun compare() {
        val lib = library
        val known = lib?.games?.flatMap { g -> g.files.map { it.path.lowercase() } }?.toHashSet() ?: hashSetOf()
        val only = Group.all(localFiles.filter { it.path.lowercase() !in known })
        tabletOnlyIds = only.map { it.id }.toSet()
        tabletOnly = only
        val have = localBios.map { it.path.lowercase() }.toHashSet()
        biosMissing = lib?.bios.orEmpty().filter { it.path.lowercase() !in have }
    }

    /** Borra un juego de la tablet (solo sus archivos; las partidas se quedan). Devuelve los bytes liberados. */
    suspend fun delete(g: Game): Long {
        val present = g.files.filter { has(it.path) }
        val freed = withContext(Dispatchers.IO) { Local.deleteFiles(present) }
        rescanLocal()
        return freed
    }
}
