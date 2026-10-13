package io.github.sudodiego.romsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lo que se hace en el PC: leer la biblioteca, BIOS y respaldos. Cada operación abre su propia conexión SFTP. */
object Pc {
    /** Conecta con lo guardado en Ajustes; la primera vez guarda la huella del PC. */
    fun <T> use(block: (SftpConn) -> T): T {
        if (!Prefs.configured) throw java.io.IOException("Falta configurar el PC (Ajustes)")
        return SftpConn.connect(Prefs.sftp()).use { c ->
            if (Prefs.fingerprint.isEmpty()) Prefs.fingerprint = c.fingerprint
            block(c)
        }
    }

    suspend fun <T> io(block: (SftpConn) -> T): T = withContext(Dispatchers.IO) { use(block) }

    private val descriptors = setOf(".cue", ".m3u", ".gdi")

    /** Lee la carpeta de ROMs del PC y arma los juegos (lee los .cue/.m3u/.gdi para saber qué archivos usan). */
    suspend fun scan(): Library = io { c ->
        val root = Prefs.remoteRoms
        if (c.stat(root)?.isDir != true) throw java.io.IOException("No existe la carpeta $root en el PC")
        val bios = Prefs.remoteBios
        val files = ArrayList<FileEntry>()
        for (e in c.list(root)) {
            if (!e.isDir || e.name.startsWith(".") || e.name.lowercase() in Group.ignoredDirs) continue
            val dir = SftpConn.join(root, e.name)
            if (dir.equals(bios, ignoreCase = true)) continue
            c.walk(root, dir, out = files)
        }
        val games = Group.all(files) { p ->
            if (Group.ext(p) in descriptors) c.readText(SftpConn.join(root, p), 256 * 1024) else null
        }
        val biosFiles = if (c.stat(bios)?.isDir == true) c.walk(bios, skipMedia = false) else emptyList()
        val systems = games.groupBy { it.system }.map { (id, gs) ->
            Systems.get(id).copy(count = gs.size, size = gs.sumOf { it.size })
        }.sortedBy { it.name.lowercase() }
        Library(System.currentTimeMillis(), false, "", systems, games, biosFiles)
    }
}
