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

/** Cambios en el disco del PC pedidos desde la tablet (borrar un juego, crear un .m3u). */
object PcEdit {
    /** Borra los archivos de un juego en el PC y las carpetas que queden vacías (nunca la de la plataforma). */
    suspend fun delete(g: Game): Long = Pc.io { c ->
        val root = Prefs.remoteRoms
        var freed = 0L
        val dirs = HashSet<String>()
        for (f in g.files) {
            val p = SftpConn.join(root, f.path)
            if (c.stat(p) != null) {
                c.delete(p)
                freed += f.size
                dirs += f.path.substringBeforeLast('/', "")
            }
        }
        // De la más profunda a la menos, sin llegar a la carpeta de la plataforma ("psx").
        for (d in dirs.sortedByDescending { it.length }) {
            var cur = d
            while (cur.contains('/')) {
                val full = SftpConn.join(root, cur)
                if (c.list(full).isNotEmpty() || !c.rmdir(full)) break
                cur = cur.substringBeforeLast('/')
            }
        }
        freed
    }

    /** Escribe "Juego.m3u" junto a los discos, en el PC (y la tablet lo baja con el juego). */
    suspend fun createM3u(g: Game): String = Pc.io { c ->
        val dir = g.primary.substringBeforeLast('/')
        val name = Group.discOf(Group.stem(g.primary))?.first ?: g.name
        val rel = "$dir/$name.m3u"
        val text = g.files.joinToString("\n") { it.path.removePrefix("$dir/") } + "\n"
        c.upload(SftpConn.join(Prefs.remoteRoms, rel)) { it.write(text.toByteArray()) }
        rel
    }
}
