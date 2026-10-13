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

    /**
     * En Windows, la lista completa de archivos en una sola respuesta (PowerShell por SSH): con carpetas de Wii U de
     * miles de archivos, pedirlas carpeta por carpeta por SFTP tardaba minutos. Null si no se puede (no es Windows,
     * o el usuario no puede correr comandos): entonces se recorre por SFTP.
     */
    private fun windowsList(c: SftpConn, root: String): List<Pair<String, Long>>? {
        val win = Regex("^/([A-Za-z]):(/.*)?$").matchEntire(root) ?: return null
        val path = (win.groupValues[1] + ":" + win.groupValues[2].ifEmpty { "/" }).replace('/', '\\').replace("'", "''")
        val ps = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-ChildItem -LiteralPath '$path' -Recurse -File -Force " +
            "-ErrorAction SilentlyContinue | ForEach-Object { [string]\$_.Length + '|' + \$_.FullName }"
        val out = c.exec("powershell -NoProfile -NonInteractive -Command \"$ps\"", 300_000) ?: return null
        val base = path.trimEnd('\\') + "\\"
        return out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val full = line.substring(i + 1).trim()
            if (!full.startsWith(base, ignoreCase = true)) return@mapNotNull null
            full.substring(base.length).replace('\\', '/') to (line.substring(0, i).toLongOrNull() ?: 0L)
        }.toList()
    }

    /** Lee la carpeta de ROMs del PC y arma los juegos (lee los .cue/.m3u/.gdi para saber qué archivos usan). */
    suspend fun scan(progress: (String) -> Unit = {}): Library = io { c ->
        val root = Prefs.remoteRoms
        if (c.stat(root)?.isDir != true) throw java.io.IOException("No existe la carpeta $root en el PC")
        val bios = Prefs.remoteBios
        val biosRel = if (bios.startsWith("$root/", true)) bios.substring(root.length + 1).lowercase() + "/" else null
        val files = ArrayList<FileEntry>()
        val junk = ArrayList<FileEntry>()
        progress("Pidiendo la lista de archivos al PC…")
        val listed = windowsList(c, root)
        if (listed != null) {
            progress("Revisando ${listed.size} archivos…")
            for ((rel, size) in listed) {
                if (!rel.contains('/')) continue
                if (biosRel != null && rel.lowercase().startsWith(biosRel)) continue
                val dirs = rel.split('/').dropLast(1)
                if (dirs.first().startsWith(".") || dirs.any { it.lowercase() in Group.ignoredDirs }) continue
                val name = rel.substringAfterLast('/')
                when {
                    !Group.isIgnored(name) -> files += FileEntry(rel, size)
                    Group.junkKind(name) != null && !Group.inBiosDir(rel) -> junk += FileEntry(rel, size)
                }
            }
        } else {
            var dirsRead = 0
            for (e in c.list(root)) {
                if (!e.isDir || e.name.startsWith(".") || e.name.lowercase() in Group.ignoredDirs) continue
                val dir = SftpConn.join(root, e.name)
                if (dir.equals(bios, ignoreCase = true)) continue
                progress("Leyendo ${e.name}… (${files.size} archivos)")
                c.walk(root, dir, out = files, junk = junk)
                dirsRead++
            }
        }
        // Switch sin [TitleID] en el nombre: se lee del propio archivo (una vez; queda guardado).
        val ids = HashMap<String, String>()
        val pending = files.filter { it.path.startsWith("switch/", true) && Group.needsSwitchId(it) }
        pending.forEachIndexed { n, f ->
            val key = "${f.path}|${f.size}"
            val known = Prefs.switchId(key)
            if (known == null) progress("Leyendo juegos de Switch sin código (${n + 1} de ${pending.size})…")
            val id = known ?: runCatching { SwitchId.fromHeader(c.head(SftpConn.join(root, f.path), SwitchId.HEADER_BYTES)) }.getOrNull().orEmpty()
            if (known == null) Prefs.setSwitchId(key, id)
            if (id.isNotEmpty()) ids[f.path] = id
        }
        progress("Armando la biblioteca (${files.size} archivos)…")
        val games = Group.all(files, ids) { p ->
            if (Group.ext(p) in descriptors) c.readText(SftpConn.join(root, p), 256 * 1024) else null
        }
        val biosFiles = if (c.stat(bios)?.isDir == true) {
            listed?.takeIf { biosRel != null }?.filter { it.first.lowercase().startsWith(biosRel!!) }
                ?.map { FileEntry(it.first.substring(biosRel!!.length), it.second) }
                ?: c.walk(bios, skipMedia = false)
        } else emptyList()
        val systems = games.groupBy { it.system }.map { (id, gs) ->
            Systems.get(id).copy(count = gs.size, size = gs.sumOf { it.size })
        }.sortedBy { it.name.lowercase() }
        Library(System.currentTimeMillis(), false, "", systems, Names.apply(games), biosFiles, junk,
            files.filter { Group.isBiosFile(it.path, it.size) })
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
