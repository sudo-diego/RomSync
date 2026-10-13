package io.github.sudodiego.romsync

import java.security.MessageDigest

/**
 * Arma los juegos de una plataforma a partir de sus archivos: juegos de varios archivos (.cue/.bin, .gdi, .m3u con
 * sus discos), carpetas que son un juego entero (PS3, Wii U, "Juego/Juego.chd") y, en Switch, el juego base con su
 * actualización y sus DLC por TitleID. Sin Android: se prueba en la JVM (GroupTest).
 */
object Group {
    private val reBracket = Regex("\\[[^]]*]")
    private val reParen = Regex("\\(([^)]*)\\)")
    private val reSpaces = Regex("\\s{2,}")
    private val reSwitchTid = Regex("\\[(0100[0-9a-fA-F]{12})]")
    private val reSwitchVer = Regex("\\[v(\\d+)]", RegexOption.IGNORE_CASE)
    private val reVitaId = Regex("\\b(PCS[A-H]\\d{5})\\b")

    private val descriptorExt = setOf(".m3u", ".cue", ".gdi", ".ccd", ".mds")
    private val gameDirMarkers = setOf("ps3_game", "usrdir", "code", "content", "meta", "sce_sys", "eboot.bin")

    // Lo que nunca es un juego (partidas, imágenes, textos). Igual en la tablet y en el PC.
    private val ignoredExt = setOf(
        ".sav", ".srm", ".state", ".auto", ".mcr", ".mcd", ".dsv", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp",
        ".txt", ".nfo", ".xml", ".db", ".ini", ".cfg", ".json", ".lnk", ".url", ".pdf", ".mp4", ".mkv", ".log", ".part",
        ".rtc", ".eep", ".fla", ".mpk", ".sra", ".ldci",
    )
    private val ignoredNames = setOf("thumbs.db", "desktop.ini", ".ds_store", "systeminfo.txt", "gamelist.xml", ".nomedia")
    val ignoredDirs = setOf(
        "media", "images", "videos", "manuals", "downloaded_media", "screenshots", "covers", "snap", "boxart",
        "marquees", "saves", "states", ".romsync",
    )

    fun ext(name: String): String {
        val base = name.substringAfterLast('/')
        val i = base.lastIndexOf('.')
        if (i <= 0 || i == base.length - 1) return ""
        val e = base.substring(i)
        return if (e.any { it == ' ' }) "" else e.lowercase()
    }

    fun stem(path: String): String {
        val base = path.substringAfterLast('/')
        val e = ext(base)
        return if (e.isEmpty()) base else base.dropLast(e.length)
    }

    // Archivos de otras apps que quedan junto a los ROMs: opciones por juego de DraStic/melonDS (.opts), descargas a
    // medias (.parts, .crdownload), respaldos de partidas con número (".SAV(1)(0)").
    private val junkExt = setOf(".opts", ".parts", ".crdownload", ".tmp", ".bak", ".cht", ".pch", ".dst", ".nomedia")

    fun isIgnored(name: String): Boolean {
        val lower = name.lowercase()
        // Archivos ocultos (".8686A2…parts"): siempre de otra app, nunca un juego.
        if (lower in ignoredNames || lower.startsWith(".")) return true
        val e = ext(lower)
        if (e in ignoredExt || e in junkExt || e.startsWith(".state")) return true
        if (e.startsWith(".sav") || e.startsWith(".srm") || e.startsWith(".dsv")) return true
        if (e.startsWith(".ml") && e.length > 3 && e.substring(3).all { it.isDigit() }) return true
        return false
    }

    // BIOS que a veces quedan en la carpeta de una plataforma (scph5501.bin en psx…): no son juegos.
    private val reBios = Regex(
        "^(.*scph[-_ ]?\\d{3,5}.*|ps1_rom|psxonpsp660|ps-\\d{2}[a-z]|ps2-?\\d{4}.*|dc_(boot|flash)|bios\\d?|gba_bios|firmware|" +
            "syscard\\d|sega_101|mpr-\\d+|neogeo|.*bios.*)\\.(bin|rom|img|pce|zip|nvm|mec|erom|rom0|rom1)$",
        RegexOption.IGNORE_CASE,
    )

    fun isBios(name: String) = reBios.matches(name.substringAfterLast('/'))

    /** Plataformas de accesos directos (ES-DE, GameNative): no son ROMs, no se "descargan". */
    val shortcutSystems = setOf("androidapps", "androidgames", "android", "steam", "epic", "gog", "lutris", "heroic")
    private val shortcutExt = setOf(".app", ".desktop", ".steam", ".lnk", ".url", ".psvita", ".dpt", ".shortcut")

    fun isShortcut(system: String, path: String, size: Long) =
        system.lowercase() in shortcutSystems || ext(path) in shortcutExt ||
            (system.equals("psvita", true) && size < 4096)

    private val reDisc = Regex("\\s*[(\\[](disc|disk|cd)\\s*(\\d+)(\\s*of\\s*\\d+)?[)\\]]", RegexOption.IGNORE_CASE)

    /** "Final Fantasy VII (USA) (Disc 2)" → ("Final Fantasy VII (USA)", 2); sin disco → null. */
    fun discOf(name: String): Pair<String, Int>? {
        val m = reDisc.find(name) ?: return null
        return name.removeRange(m.range).trim() to (m.groupValues[2].toIntOrNull() ?: 0)
    }

    fun cleanTitle(name: String): Pair<String, List<String>> {
        val tags = reParen.findAll(name).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        var t = reParen.replace(name, "")
        t = reBracket.replace(t, "")
        t = reSpaces.replace(t.replace('_', ' ').trim(), " ").trimEnd(' ', '-')
        return (t.ifEmpty { name }) to tags
    }

    fun gameId(system: String, key: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest("$system:$key".lowercase().toByteArray())
        return d.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun clean(p: String): String {
        val out = ArrayList<String>()
        for (part in p.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += part
            }
        }
        return out.joinToString("/")
    }

    private fun dirOf(p: String) = p.substringBeforeLast('/', "")

    fun refs(ext: String, content: String): List<String> {
        val out = ArrayList<String>()
        for (raw in content.replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when (ext) {
                ".m3u" -> out += line
                ".cue" -> if (line.uppercase().startsWith("FILE ")) {
                    val rest = line.substring(5).trim()
                    if (rest.startsWith("\"")) {
                        val j = rest.indexOf('"', 1)
                        if (j > 0) out += rest.substring(1, j)
                    } else rest.split(Regex("\\s+")).firstOrNull()?.let { out += it }
                }
                ".gdi" -> if (line.contains('"')) {
                    val a = line.indexOf('"')
                    val b = line.indexOf('"', a + 1)
                    if (b > a) out += line.substring(a + 1, b)
                } else {
                    val f = line.split(Regex("\\s+"))
                    if (f.size >= 5) out += f[4]
                }
            }
        }
        return out
    }

    private class Ctx(files: List<FileEntry>, val readText: ((String) -> String?)?) {
        val all = files.sortedBy { it.path.lowercase() }
        val byKey = all.associateBy { clean(it.path).lowercase() }
        val taken = HashSet<String>()
        fun lookup(p: String) = byKey[clean(p).lowercase()]
        fun free() = all.filter { it.path.lowercase() !in taken }
    }

    /** Los archivos que usa un descriptor: por su contenido si se puede leer; si no, por nombre. */
    private fun attached(c: Ctx, desc: FileEntry): List<FileEntry> {
        val e = ext(desc.path)
        val dir = dirOf(desc.path)
        val out = LinkedHashMap<String, FileEntry>()
        fun add(f: FileEntry?) { if (f != null && !f.path.equals(desc.path, true)) out.putIfAbsent(f.path.lowercase(), f) }
        if (e == ".ccd" || e == ".mds") {
            val s = stem(desc.path)
            listOf(".img", ".sub", ".mdf").forEach { add(c.lookup("$dir/$s$it")) }
            return out.values.toList()
        }
        val content = c.readText?.invoke(desc.path)
        if (content != null) {
            for (r in refs(e, content)) {
                val f = c.lookup("$dir/$r") ?: continue
                add(f)
                val fe = ext(f.path)
                if (fe in descriptorExt && fe != ".m3u") attached(c, f).forEach(::add)
            }
            if (out.isNotEmpty()) return out.values.toList()
        }
        val s = stem(desc.path).lowercase()
        for (f in c.all) {
            if (f === desc) continue
            val fe = ext(f.path)
            val fs = stem(f.path).lowercase()
            val fd = dirOf(f.path)
            when (e) {
                ".cue", ".gdi" -> if (fd == dir && fs.startsWith(s) && fe in setOf(".bin", ".img", ".raw", ".wav", ".iso", ".sub")) add(f)
                ".m3u" -> if ((fd == dir || fd.startsWith("$dir/")) && fe != ".m3u" && fs.startsWith(s)) add(f)
            }
        }
        return out.values.toList()
    }

    private fun isGameDir(d: String, files: List<FileEntry>, prefix: String): Boolean {
        val ld = d.lowercase()
        if (ld in setOf(".hidden", "updates", "update", "dlc", "dlcs")) return false
        val e = ext(d)
        if (e.isNotEmpty() && e.length <= 6) return true // "Juego.ps3": ES-DE lo trata como un archivo
        for (f in files) {
            val parts = f.path.removePrefix("$prefix$d/").split('/')
            if (parts.dropLast(1).any { it.lowercase() in gameDirMarkers }) return true
            if (parts.size == 1) {
                val n = parts[0]
                if (ext(n) in descriptorExt || stem(n).equals(d, true) || n.lowercase() in gameDirMarkers) return true
            }
        }
        return false
    }

    fun newGame(system: String, name: String, primary: String, kind: String, files: List<FileEntry>): Game {
        val (title, tags) = cleanTitle(name)
        val sorted = files.sortedBy { it.path }
        val k = if (kind == "file" && isShortcut(system, primary, sorted.sumOf { it.size })) "shortcut" else kind
        return Game(gameId(system, primary), system, title, name, tags, k, primary, sorted.sumOf { it.size }, sorted,
            emptyList(), reVitaId.find(name)?.groupValues?.get(1) ?: "")
    }

    /** files: rutas relativas a la carpeta de ROMs, todas dentro de "system/". readText lee un descriptor (o null). */
    fun system(system: String, files: List<FileEntry>, readText: ((String) -> String?)? = null): List<Game> {
        val c = Ctx(files.filter { !isBios(it.path) }, readText)
        val prefix = "$system/"
        val games = ArrayList<Game>()

        // 1) Carpetas que son un juego entero.
        val dirFiles = LinkedHashMap<String, MutableList<FileEntry>>()
        for (f in c.all) {
            val rel = f.path.removePrefix(prefix)
            val i = rel.indexOf('/')
            if (i > 0) dirFiles.getOrPut(rel.substring(0, i)) { ArrayList() } += f
        }
        for (d in dirFiles.keys.sorted()) {
            val fs = dirFiles[d]!!
            if (!isGameDir(d, fs, prefix)) continue
            fs.forEach { c.taken += it.path.lowercase() }
            games += newGame(system, d, prefix + d, "folder", fs)
        }

        // 2) Descriptores: primero .cue/.gdi/.ccd/.mds, después .m3u (que se lleva los juegos de sus discos).
        for (pass in listOf(setOf(".cue", ".gdi", ".ccd", ".mds"), setOf(".m3u"))) {
            for (d in c.free().filter { ext(it.path) in pass }) {
                if (d.path.lowercase() in c.taken) continue
                val parts = LinkedHashMap<String, FileEntry>()
                parts[d.path.lowercase()] = d
                attached(c, d).forEach { parts.putIfAbsent(it.path.lowercase(), it) }
                if (ext(d.path) == ".m3u") {
                    val absorbed = games.filter { g -> g.kind == "multi" && parts.containsKey(g.primary.lowercase()) }
                    absorbed.forEach { g -> g.files.forEach { parts.putIfAbsent(it.path.lowercase(), it) } }
                    games.removeAll(absorbed.toSet())
                }
                parts.keys.forEach { c.taken += it }
                games += newGame(system, stem(d.path), d.path, "multi", parts.values.toList())
            }
        }

        // 3) Discos sueltos sin .m3u: "Juego (Disc 1).chd", "Juego (Disc 2).chd" → un juego.
        val discs = c.free().mapNotNull { f -> discOf(stem(f.path))?.let { (base, n) -> Triple(f, "${dirOf(f.path)}/$base|${ext(f.path)}".lowercase(), n) } }
            .groupBy { it.second }.filter { it.value.size > 1 }
        for ((_, list) in discs) {
            val sorted = list.sortedBy { it.third }
            val first = sorted.first().first
            sorted.forEach { c.taken += it.first.path.lowercase() }
            games += newGame(system, discOf(stem(first.path))!!.first, first.path, "discs", sorted.map { it.first })
        }

        // 4) El resto: un archivo, un juego (Switch: agrupados por TitleID).
        val loose = c.free()
        if (system.equals("switch", true)) games += switch(system, loose)
        else loose.forEach { games += newGame(system, stem(it.path), it.path, "file", listOf(it)) }
        return games.sortedBy { it.title.lowercase() }
    }

    /** Base (…000), actualización (…800) o DLC (base + 0x1000 + n). */
    fun switchKind(tid: Long): Pair<String, Long> {
        val low = tid and 0xFFF
        return when (low) {
            0L -> "base" to tid
            0x800L -> "update" to (tid and 0xFFF.inv().toLong())
            else -> "dlc" to ((tid and 0xFFF.inv().toLong()) - 0x1000)
        }
    }

    private fun switch(system: String, files: List<FileEntry>): List<Game> {
        val buckets = LinkedHashMap<Long, MutableList<Part>>()
        val out = ArrayList<Game>()
        for (f in files) {
            val name = stem(f.path)
            val tid = reSwitchTid.find(name)?.groupValues?.get(1)?.toLongOrNull(16)
            if (tid == null) {
                out += newGame(system, name, f.path, "file", listOf(f))
                continue
            }
            val (kind, base) = switchKind(tid)
            val ver = reSwitchVer.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            buckets.getOrPut(base) { ArrayList() } += Part(kind, name, tid.toString(16).uppercase().padStart(16, '0'), ver, f.size, listOf(f))
        }
        val rank = mapOf("base" to 0, "update" to 1, "dlc" to 2)
        for ((base, parts) in buckets) {
            val sorted = parts.sortedWith(compareBy<Part> { rank[it.kind] }.thenByDescending { it.version }.thenBy { it.name })
            val head = sorted.first()
            val (title, tags) = cleanTitle(head.name)
            val tid = base.toString(16).uppercase().padStart(16, '0')
            out += Game(gameId(system, "tid:$tid"), system, title, head.name, tags, "switch", head.files.first().path,
                sorted.sumOf { it.size }, sorted.flatMap { it.files }, sorted, tid)
        }
        return out
    }

    /** Agrupa todo un listado (rutas relativas a la carpeta de ROMs) por plataforma. */
    fun all(files: List<FileEntry>, readText: ((String) -> String?)? = null): List<Game> =
        files.filter { it.path.contains('/') }.groupBy { it.path.substringBefore('/') }
            .flatMap { (sys, fs) -> system(sys, fs, readText) }
}
