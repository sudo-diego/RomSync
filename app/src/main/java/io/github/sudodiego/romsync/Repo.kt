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
 * Lo que muestran la app y la web: la biblioteca del PC (o la última leída, sin conexión), los archivos de la
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
    /** Qué está haciendo la lectura del PC ahora (para que no parezca pegada). */
    var scanStatus by mutableStateOf("")
        private set

    private var localIndex: Map<String, Long> = emptyMap()
    private var tabletOnlyIds: Set<String> = emptySet()
    private val mutex = Mutex()
    private lateinit var cacheFile: File

    fun init(ctx: Context) {
        if (::cacheFile.isInitialized) return
        cacheFile = File(ctx.filesDir, "library.json")
        runCatching { Json.library(JSONObject(cacheFile.readText())).let { library = it.copy(games = Names.apply(it.games)) } }
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
                    val lib = Pc.scan { msg -> scanStatus = msg }
                    library = lib
                    withContext(Dispatchers.IO) { cacheFile.writeText(Json.libraryJson(lib).toString()) }
                    conn = Conn.Online(System.currentTimeMillis())
                } catch (e: Exception) {
                    conn = Conn.Offline(e.message ?: "Sin conexión")
                } finally {
                    scanningPc = false
                    scanStatus = ""
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
        localJunk = Local.scanJunk()
        space = Local.space()
        compare()
    }

    /** Lo de la tablet que no está en el PC (agrupado por nombre) y las BIOS que faltan. */
    private fun compare() {
        val lib = library
        val known = lib?.games?.flatMap { g -> g.files.map { it.path.lowercase() } }?.toHashSet() ?: hashSetOf()
        val leftovers = localFiles.filter { it.path.lowercase() !in known }
        // Switch solo en la tablet sin [TitleID]: se lee del archivo, igual que en el PC.
        val ids = leftovers.filter { it.path.startsWith("switch/", true) && Group.needsSwitchId(it) }.mapNotNull { f ->
            val key = "local:${f.path}|${f.size}"
            val id = Prefs.switchId(key) ?: runCatching {
                Local.romFile(f.path).inputStream().use { i -> SwitchId.fromHeader(SwitchId.head(i)) }
            }.getOrNull().orEmpty().also { Prefs.setSwitchId(key, it) }
            if (id.isEmpty()) null else f.path to id
        }.toMap()
        val only = Names.apply(Group.all(leftovers, ids))
        tabletOnlyIds = only.map { it.id }.toSet()
        tabletOnly = only
        val have = localBios.map { it.path.lowercase() }.toHashSet()
        biosMissing = lib?.bios.orEmpty().filter { it.path.lowercase() !in have }
    }

    /** Borra un juego del disco del PC y lo saca de la biblioteca (sin volver a leer todo el disco). */
    suspend fun deleteFromPc(g: Game): Long {
        val freed = PcEdit.delete(g)
        library?.let { lib ->
            val games = lib.games.filter { it.id != g.id }
            val systems = games.groupBy { it.system }.map { (id, gs) -> Systems.get(id).copy(count = gs.size, size = gs.sumOf { it.size }) }
                .sortedBy { it.name.lowercase() }
            val updated = lib.copy(games = games, systems = systems)
            library = updated
            withContext(Dispatchers.IO) { cacheFile.writeText(Json.libraryJson(updated).toString()) }
        }
        compare()
        return freed
    }

    /** Pone un nombre propio a un juego (vacío = el del archivo) y vuelve a buscar su carátula con ese nombre. */
    fun rename(g: Game, name: String) {
        Names.set(g.id, name)
        library = library?.let { it.copy(games = Names.apply(it.games)) }
        tabletOnly = Names.apply(tabletOnly)
        Art.forget(g.id)
    }

    /** Archivos que sobran en la tablet (descargas a medias, copias de partidas): ver Limpieza. */
    var localJunk by mutableStateOf<List<FileEntry>>(emptyList())
        private set

    /** Borra archivos sobrantes (Limpieza). Devuelve los bytes liberados. */
    suspend fun deleteJunk(tablet: List<FileEntry>, pc: List<FileEntry>): Long {
        var freed = withContext(Dispatchers.IO) {
            tablet.sumOf { f -> Local.romFile(f.path).let { if (it.isFile && it.delete()) f.size else 0L } }
        }
        if (pc.isNotEmpty()) {
            freed += Pc.io { c -> pc.sumOf { f -> c.delete(SftpConn.join(Prefs.remoteRoms, f.path)); f.size } }
            library = library?.let { lib -> lib.copy(junk = lib.junk - pc.toSet()) }
            library?.let { lib -> withContext(Dispatchers.IO) { cacheFile.writeText(Json.libraryJson(lib).toString()) } }
        }
        rescanLocal()
        return freed
    }

    /** BIOS de la tablet que están en la carpeta de una plataforma ("ps2/USA v01.60….bin"). */
    val localStrayBios: List<FileEntry> get() = localFiles.filter { Group.isBiosFile(it.path, it.size) }

    /**
     * Mueve BIOS a la carpeta de BIOS (en la tablet y/o en el PC). Si ya hay una igual (mismo nombre y tamaño), borra
     * la de más; si hay una distinta con el mismo nombre, la deja donde está. Devuelve cuántas movió.
     */
    suspend fun moveBios(tablet: List<FileEntry>, pc: List<FileEntry>): Int {
        var moved = 0
        withContext(Dispatchers.IO) {
            val dir = File(Prefs.biosDir).apply { mkdirs() }
            for (f in tablet) {
                val src = Local.romFile(f.path)
                val dest = File(dir, f.path.substringAfterLast('/'))
                when {
                    !dest.exists() -> if (src.renameTo(dest) || runCatching { src.copyTo(dest); src.delete() }.isSuccess) moved++
                    dest.length() == src.length() -> { src.delete(); moved++ }
                }
            }
        }
        if (pc.isNotEmpty()) {
            moved += Pc.io { c ->
                var n = 0
                for (f in pc) {
                    val src = SftpConn.join(Prefs.remoteRoms, f.path)
                    val dest = SftpConn.join(Prefs.remoteBios, f.path.substringAfterLast('/'))
                    val there = c.stat(dest)
                    when {
                        there == null -> { c.rename(src, dest); n++ }
                        there.size == f.size -> { c.delete(src); n++ }
                    }
                }
                n
            }
            library = library?.let { lib -> lib.copy(strayBios = lib.strayBios - pc.toSet(),
                bios = lib.bios + pc.map { FileEntry(it.path.substringAfterLast('/'), it.size) }) }
            library?.let { lib -> withContext(Dispatchers.IO) { cacheFile.writeText(Json.libraryJson(lib).toString()) } }
        }
        rescanLocal()
        return moved
    }

    /**
     * Clave para juntar versiones del mismo juego: la plataforma y el título que sale del NOMBRE DEL ARCHIVO (no el
     * nombre propio). Así, ponerle "Wind Waker HD Plus" a la versión con mods no la separa de Wind Waker.
     */
    fun versionKey(g: Game): String =
        g.system.lowercase() + "|" + Group.cleanTitle(g.name).first.lowercase().filter { it.isLetterOrDigit() }

    /** Otras versiones del mismo juego (otra región, otro formato, una con mods). */
    fun versions(g: Game): List<Game> {
        if (g.kind == "shortcut") return emptyList()
        val key = versionKey(g)
        return allGames().filter { it.id != g.id && it.kind != "shortcut" && versionKey(it) == key }
    }

    /** Ids de juegos que tienen otra versión en la misma plataforma (filtro "Repetidos"). */
    fun duplicateIds(): Set<String> = allGames().filter { it.kind != "shortcut" }
        .groupBy { versionKey(it) }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()

    /** Borra un juego de la tablet (solo sus archivos; las partidas se quedan). Devuelve los bytes liberados. */
    suspend fun delete(g: Game): Long {
        val present = g.files.filter { has(it.path) }
        val freed = withContext(Dispatchers.IO) { Local.deleteFiles(present) }
        rescanLocal()
        return freed
    }
}
