package io.github.sudodiego.romsync

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Ordenar juegos: busca en ROMs (en cualquier carpeta) y en Descargas los juegos que están comprimidos (.rar, .zip),
 * fuera de su carpeta, con nombres raros ("[Tendoku.com] WUNGU.nsp") o repetidos, y arma un plan:
 * - sacar el juego del comprimido (RAR guardado sin comprimir: se copian los bytes; ZIP: se descomprime);
 * - dejarlo en su carpeta con el nombre real (Switch: por TitleID, el nombre oficial; "Juego [TitleID].nsp" y las
 *   actualizaciones/DLC en "switch/Actualizaciones y DLC");
 * - borrar las copias exactas que sobran (mismo tamaño y mismo contenido en tres trozos);
 * - si el archivo también está en el servidor con el nombre viejo, renombrarlo allá también.
 * Nada se hace sin que el usuario lo vea y confirme.
 */
object Tidy {
    enum class Kind { EXTRACT, MOVE, DELETE, RENAME_SERVER, INFO }

    data class Item(
        val id: String,
        val kind: Kind,
        val title: String,
        val from: String,        // ruta legible de origen
        val to: String,          // destino relativo a ROMs ("" si se borra o es solo información)
        val size: Long,
        val note: String,
        val source: File? = null,
        val rar: Rar5.Entry? = null,
        val zipEntry: String? = null,
        val serverFrom: String? = null,
        val keep: File? = null,
        val keepTarget: String? = null,
    )

    var plan by mutableStateOf<List<Item>?>(null)
        private set
    var scanning by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set

    /** Extensión → carpeta por defecto. Solo formatos que no se confunden entre plataformas (los de disco, como .iso o
     * .chd, sirven para varias y no se mueven de carpeta). */
    private val defaultFolder = mapOf(
        ".nsp" to "switch", ".xci" to "switch", ".nsz" to "switch", ".xcz" to "switch",
        ".3ds" to "n3ds", ".cia" to "n3ds", ".nds" to "nds", ".gba" to "gba", ".gbc" to "gbc", ".gb" to "gb",
        ".nes" to "nes", ".sfc" to "snes", ".smc" to "snes", ".n64" to "n64", ".z64" to "n64", ".v64" to "n64",
        ".gen" to "genesis", ".sms" to "mastersystem", ".gg" to "gamegear", ".pce" to "pcengine",
        ".ws" to "wonderswan", ".wsc" to "wonderswancolor", ".ngp" to "ngp", ".ngc" to "ngpc", ".wua" to "wiiu",
    )
    /** Igual, pero con la carpeta que USA el usuario para esa extensión (ej. sus SNES están en "snesna"). */
    private var romExt: Map<String, String> = defaultFolder
    private val discExt = setOf(".iso", ".cue", ".bin", ".chd", ".gdi", ".rvz", ".gcm", ".wbfs", ".cso", ".m3u")
    private val reTid = Regex("\\[(0100[0-9a-fA-F]{12})]")
    private val reVer = Regex("\\[v(\\d+)]", RegexOption.IGNORE_CASE)
    private val reJunkTags = Regex("\\[(tendoku[^]]*|us|usa|eu|eur|jp|base|conv|update[^]]*|upd|dlc|v\\d+|0100[0-9a-fA-F]{12})]|\\((\\d+(\\.\\d+)? ?gb)\\)",
        RegexOption.IGNORE_CASE)

    private val downloads get() = File(Environment.getExternalStorageDirectory(), "Download")
    private val roms get() = File(Prefs.romsDir)

    /** Nombre limpio para un archivo (sin caracteres que Windows no acepta). */
    private fun safeName(s: String) = s.replace("™", "").replace("®", "").replace(":", " -").replace(Regex("[\\\\/*?\"<>|]"), "")
        .replace(Regex("\\s+"), " ").trim()

    /** Nombre oficial de un juego de Switch (por el TitleID del juego base), guardado para no preguntar dos veces. */
    private fun officialName(baseTid: String): String? {
        Prefs.switchId("name:$baseTid")?.let { return it.ifEmpty { null } }
        val name = runCatching {
            val c = URL("https://api.nlib.cc/nx/$baseTid").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).optString("name") else null }
            finally { c.disconnect() }
        }.getOrNull()?.let(::safeName)
        if (name != null) Prefs.setSwitchId("name:$baseTid", name)
        return name
    }

    private fun baseOf(tid: String): String = if (tid.endsWith("000")) tid else Repo.switchBase(tid) ?: tid

    /** Nombre que tiene el archivo de adentro, limpio ("[Tendoku.com] Pokemon Legends Arceus [0100…][v262144]" → "Pokemon Legends Arceus"). */
    private fun cleanInner(name: String) = safeName(Group.cleanTitle(reJunkTags.replace(Group.stem(name), "")).first)
        .removePrefix("-").trim()

    /** Dónde y con qué nombre va un archivo de Switch. */
    private fun switchTarget(tid: String, innerName: String, ext: String): String {
        val base = baseOf(tid)
        val name = officialName(base) ?: cleanInner(innerName).ifBlank { tid }
        val ver = reVer.find(innerName)?.groupValues?.get(1)
        return when {
            tid.endsWith("000") -> "switch/$name [$tid]$ext"
            tid.endsWith("800") -> "switch/Actualizaciones y DLC/$name [UPD] [$tid]" + (ver?.let { "[v$it]" } ?: "") + ext
            else -> {
                // El nombre del DLC sale del archivo ("[DLC Megadimension]", "[Wave 1-6 DLC Unlocker]").
                val label = Regex("\\[([^]]*dlc[^]]*)]", RegexOption.IGNORE_CASE).find(innerName)?.groupValues?.get(1)
                    ?.replace(Regex("\\bdlc\\b", RegexOption.IGNORE_CASE), "")?.replace('_', ' ')?.trim()?.let(::safeName)
                "switch/Actualizaciones y DLC/$name [DLC]" + (label?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") + " [$tid]$ext"
            }
        }
    }

    /**
     * Cómo debería llamarse y dónde debería estar un archivo (ruta relativa a ROMs), y qué tenía mal. Igual para el
     * dispositivo y el servidor, así los dos quedan con la misma ruta.
     */
    private fun proposal(rel: String, tid: String?, e: String): Pair<String, List<String>> {
        val why = ArrayList<String>()
        val folder = rel.substringBefore('/')
        val want = romExt[e] ?: folder
        val name = rel.substringAfterLast('/')
        if (want == "switch" && tid != null) {
            val target = switchTarget(tid, name, e)
            if (folder != "switch") why += "estaba en la carpeta $folder"
            if (reTid.find(name) == null) why += "le faltaba el código del juego"
            if (name.contains("tendoku", true)) why += "tenía la marca de la página"
            if (!tid.endsWith("000") && !rel.startsWith("switch/Actualizaciones y DLC/")) why += "es una actualización o DLC: va con las otras"
            val wellPlaced = why.isEmpty()
            return (if (wellPlaced) rel else target) to why
        }
        var stem = Group.stem(name)
        val original = stem
        stem = stem.replace(Regex("\\[(tendoku[^]]*)]", RegexOption.IGNORE_CASE), "")
        if (stem != original) why += "tenía la marca de la página"
        stem = stem.replace(Regex("\\s+"), " ").trim()
        if (folder != want) why += "estaba en la carpeta $folder"
        if (why.isEmpty()) return rel to why
        return "$want/$stem$e" to why
    }

    /** Si hay un formato mejor para ese sistema y cómo llegar a él (no se convierte solo: hace falta otro programa). */
    private fun betterFormat(g: Game): String? {
        val e = Group.ext(g.primary)
        return when {
            (g.system == "gc" || g.system == "wii") && e in setOf(".iso", ".gcm", ".wbfs") ->
                "Mejor formato: RVZ (ocupa hasta la mitad). Dolphin lo convierte: mantén presionado el juego → Convertir archivo"
            g.system in setOf("ps2", "psx", "saturn", "dreamcast", "segacd", "pcenginecd") && e in setOf(".iso", ".cue", ".bin", ".gdi") ->
                "Mejor formato: CHD (un solo archivo, comprimido sin perder nada). Se convierte en el dispositivo con la app CHDroid"
            g.system == "n3ds" && e == ".cia" -> "Mejor formato: .3ds (se abre sin instalar)"
            g.system == "wiiu" && g.kind == "folder" -> "Mejor formato: WUA (un solo archivo). Cemu lo convierte: Herramientas → Convertir a WUA"
            else -> null
        }
    }

    /** ¿Son el mismo archivo? Mismo tamaño y mismos bytes al principio, al medio y al final (rápido aun con 15 GB). */
    private fun sameContent(a: (Long, Int) -> ByteArray, b: (Long, Int) -> ByteArray, size: Long): Boolean {
        val n = 1 shl 20
        for (off in listOf(0L, (size / 2).coerceAtLeast(0), (size - n).coerceAtLeast(0))) {
            if (!a(off, n).contentEquals(b(off, n))) return false
        }
        return true
    }

    private fun fileReader(f: File) = { off: Long, n: Int ->
        RandomAccessFile(f, "r").use { r -> r.seek(off); val b = ByteArray(minOf(n.toLong(), f.length() - off).toInt()); r.readFully(b); b }
    }

    private fun rarReader(f: File, e: Rar5.Entry) = { off: Long, n: Int ->
        RandomAccessFile(f, "r").use { r -> r.seek(e.dataOffset + off); val b = ByteArray(minOf(n.toLong(), e.size - off).toInt()); r.readFully(b); b }
    }

    /** Arma el plan (lee cabeceras; no cambia nada). */
    suspend fun scan() = withContext(Dispatchers.IO) {
        if (scanning || running) return@withContext
        scanning = true
        try {
            val items = ArrayList<Item>()
            val sources = ArrayList<File>()
            val allFiles = Repo.library?.games.orEmpty().flatMap { it.files }.map { it.path } + Repo.localFiles.map { it.path }
            romExt = defaultFolder.mapValues { (e, def) ->
                allFiles.filter { Group.ext(it) == e && it.contains('/') }.groupingBy { it.substringBefore('/') }.eachCount()
                    .maxByOrNull { it.value }?.key ?: def
            }
            fun collect(dir: File, depth: Int) {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory) {
                        val n = f.name.lowercase()
                        if (depth < 4 && !f.name.startsWith(".") && n !in Group.ignoredDirs && n != "bios") collect(f, depth + 1)
                    } else sources += f
                }
            }
            collect(roms, 0)
            if (downloads.isDirectory) collect(downloads, 1)

            // Lo que ya está bien en su carpeta (para no duplicar y para ver repetidos): destino → archivo.
            val taken = HashMap<String, File>()
            for (f in sources) {
                val e = Group.ext(f.name)
                val sys = romExt[e] ?: continue
                val rel = f.relativeTo(roms).path.replace('\\', '/')
                if (rel.startsWith("..")) continue
                if (rel.substringBefore('/') != sys) continue
                if (sys == "switch") {
                    val tid = reTid.find(f.name)?.groupValues?.get(1)?.uppercase()
                        ?: runCatching { SwitchId.identify(fileReader(f)) }.getOrNull()
                    if (tid != null) {
                        val target = switchTarget(tid, f.name, e)
                        if (target != rel) { if (!taken.containsKey(target)) taken[target] = f } else taken[target] = f
                    }
                } else taken[rel] = f
            }

            for (f in sources) {
                val e = Group.ext(f.name)
                val shown = if (f.startsWith(downloads)) "Descargas/" + f.relativeTo(downloads).path else f.relativeTo(roms).path
                when {
                    e == ".rar" && Rar5.isRar5(f) -> {
                        progress = "Leyendo ${f.name}…"
                        val info = runCatching { Rar5.read(f) }.getOrNull() ?: continue
                        val games = info.entries.filter { !it.dir && Group.ext(it.name) in romExt }
                        if (games.isEmpty()) continue
                        if (info.encrypted || info.multiVolume) {
                            items += Item("i:$shown", Kind.INFO, f.name, shown, "", f.length(), "Tiene contraseña o está en varias partes: ábrelo en el PC")
                            continue
                        }
                        val extract = ArrayList<Item>()
                        var dupOf: File? = null
                        var dupTarget: String? = null
                        var dupEntry: Rar5.Entry? = null
                        var blocked = false
                        for (g in games) {
                            val ge = Group.ext(g.name)
                            if (!g.storable) {
                                items += Item("i:$shown/${g.name}", Kind.INFO, g.name.substringAfterLast('/'), shown, "", g.size,
                                    "Está comprimido: descomprímelo en el PC")
                                blocked = true
                                continue
                            }
                            val sys = romExt.getValue(ge)
                            val target = if (sys == "switch") {
                                val tid = reTid.find(g.name)?.groupValues?.get(1)?.uppercase()
                                    ?: runCatching { SwitchId.identify(rarReader(f, g)) }.getOrNull()
                                if (tid == null) { blocked = true; continue }
                                switchTarget(tid, g.name, ge)
                            } else "$sys/${g.name.substringAfterLast('/')}"
                            val existing = taken[target] ?: File(roms, target).takeIf { it.isFile }
                            if (existing != null && existing.isFile && existing.length() == g.size && sameContent(fileReader(existing), rarReader(f, g), g.size)) {
                                dupOf = existing; dupTarget = target; dupEntry = g
                            } else if (existing != null) {
                                items += Item("i:$shown/${g.name}", Kind.INFO, target.substringAfterLast('/'), shown, "", g.size,
                                    "Ya hay otro archivo con ese nombre y no es igual: revísalo a mano")
                                blocked = true
                            } else {
                                extract += Item("x:$shown/${g.name}", Kind.EXTRACT, target.substringAfterLast('/'), shown, target, g.size,
                                    "Se saca del RAR (ya estaba sin comprimir) y se borra el RAR", source = f, rar = g)
                                taken[target] = File(roms, target)
                            }
                        }
                        // Si algo no se pudo sacar, el RAR se queda (con lo que sí se sacó).
                        items += if (blocked) extract.map { it.copy(note = "Se saca del RAR (el RAR se queda: trae otras cosas)", id = it.id + ":keep") } else extract
                        if (extract.isEmpty() && dupOf != null && !blocked)
                            items += Item("d:$shown", Kind.DELETE, dupTarget!!.substringAfterLast('/'), shown, "", f.length(),
                                "Copia del juego que ya tienes (${dupOf.relativeTo(roms).path}): se borra el comprimido",
                                source = f, rar = dupEntry, keep = dupOf, keepTarget = dupTarget)
                    }
                    e == ".zip" && f.length() > 1_000_000 && !f.relativeTo(roms).path.startsWith("bios") -> {
                        // Solo lo que tiene tamaño de juego (un zip de una app trae README.md y cosas así).
                        val entries = runCatching { ZipFile(f).use { z -> z.entries().toList().filter { !it.isDirectory && Group.ext(it.name) in romExt && it.size >= 32 * 1024 } } }
                            .getOrNull().orEmpty()
                        // Un .zip de un juego de cartucho DENTRO de su carpeta ya está bien (RetroArch los abre así).
                        val inPlatform = !f.startsWith(downloads) && entries.all { romExt[Group.ext(it.name)] == f.relativeTo(roms).path.substringBefore('/') }
                        if (entries.isEmpty() || inPlatform) continue
                        val extract = ArrayList<Item>()
                        var dupOf: File? = null
                        var dupTarget: String? = null
                        var blocked = false
                        for (z in entries) {
                            val sys = romExt.getValue(Group.ext(z.name))
                            if (sys == "switch") { blocked = true; continue } // los NSP en zip son raros; se dejan
                            val target = "$sys/${z.name.substringAfterLast('/')}"
                            val existing = taken[target] ?: File(roms, target).takeIf { it.isFile }
                            if (existing != null && existing.isFile && existing.length() == z.size && crc32(existing) == z.crc) {
                                dupOf = existing; dupTarget = target
                            } else if (existing != null) {
                                items += Item("i:$shown/${z.name}", Kind.INFO, target.substringAfterLast('/'), shown, "", z.size,
                                    "Ya hay otro archivo con ese nombre y no es igual: revísalo a mano")
                                blocked = true
                            } else {
                                extract += Item("x:$shown/${z.name}", Kind.EXTRACT, target.substringAfterLast('/'), shown, target, z.size,
                                    "Se descomprime en su carpeta y se borra el .zip", source = f, zipEntry = z.name)
                                taken[target] = File(roms, target)
                            }
                        }
                        items += if (blocked) extract.map { it.copy(note = "Se descomprime en su carpeta (el .zip se queda: trae otras cosas)", id = it.id + ":keep") } else extract
                        if (extract.isEmpty() && dupOf != null && !blocked)
                            items += Item("d:$shown", Kind.DELETE, dupTarget!!.substringAfterLast('/'), shown, "", f.length(),
                                "Ya lo tienes en ${dupOf.relativeTo(roms).path} (idéntico): se borra el .zip", keep = dupOf, keepTarget = dupTarget)
                    }
                    e in romExt -> {
                        val rel = f.relativeTo(roms).path.replace('\\', '/')
                        val inRoms = !rel.startsWith("..")
                        val tid = if (romExt[e] == "switch") reTid.find(f.name)?.groupValues?.get(1)?.uppercase()
                            ?: runCatching { SwitchId.identify(fileReader(f)) }.getOrNull() else null
                        val (target, why) = proposal(if (inRoms) rel else romExt.getValue(e) + "/" + f.name, tid, e)
                        val reasons = (if (!inRoms) listOf("está en Descargas") else emptyList()) + why
                        if (inRoms && target == rel) continue
                        val keeper = taken[target]
                        if (keeper != null && keeper != f && keeper.isFile && keeper.length() == f.length() &&
                            sameContent(fileReader(keeper), fileReader(f), f.length())) {
                            items += Item("d:$shown", Kind.DELETE, target.substringAfterLast('/'), shown, "", f.length(),
                                "Copia exacta de ${keeper.relativeTo(roms).path}", source = f, keep = keeper, keepTarget = target)
                        } else if (keeper == null || keeper == f || !keeper.isFile) {
                            val server = inRoms && Repo.library?.games.orEmpty().any { g -> g.files.any { it.path.equals(rel, true) } }
                            items += Item("m:$shown", Kind.MOVE, target.substringAfterLast('/'), shown, target, f.length(),
                                reasons.joinToString(", ").replaceFirstChar { it.uppercase() } + if (server) " (se cambia también en el servidor)" else "",
                                source = f, serverFrom = if (server) rel else null)
                            taken[target] = f
                        }
                    }
                }
            }
            // En el servidor: lo que está mal nombrado y NO está en el dispositivo (lo que sí, ya se cambia arriba en los dos).
            Repo.library?.games.orEmpty().forEach { g ->
                g.files.forEach { f ->
                    if (Repo.has(f.path)) return@forEach
                    val e = Group.ext(f.path)
                    if (e !in romExt) return@forEach
                    val tid = if (g.kind == "switch") g.parts.firstOrNull { p -> p.files.any { it.path == f.path } }?.titleId?.uppercase() else null
                    val (target, why) = proposal(f.path, tid, e)
                    if (target != f.path) items += Item("s:${f.path}", Kind.RENAME_SERVER, target.substringAfterLast('/'),
                        "Servidor: ${f.path}", target, f.size, why.joinToString(", ").replaceFirstChar { it.uppercase() } + " (en el servidor)",
                        serverFrom = f.path)
                }
            }
            // Formatos que se pueden mejorar (no se cambian solos: se dice cómo).
            Repo.allGames().filter { it.kind != "shortcut" }.forEach { g ->
                betterFormat(g)?.let { tip -> items += Item("f:${g.id}", Kind.INFO, g.title, g.primary, "", g.size, tip) }
            }
            plan = items.distinctBy { it.id }
        } finally {
            scanning = false
            progress = ""
        }
    }

    /** Ejecuta los elementos elegidos. Devuelve un resumen. */
    suspend fun run(chosen: List<Item>): String = withContext(Dispatchers.IO) {
        running = true
        var ok = 0
        val errors = ArrayList<String>()
        val doneSources = HashSet<File>()
        try {
            for ((n, it) in chosen.withIndex()) {
                progress = "${n + 1} de ${chosen.size}: ${it.title}"
                runCatching {
                    when (it.kind) {
                        Kind.EXTRACT -> {
                            val dest = File(roms, it.to)
                            dest.parentFile?.mkdirs()
                            val free = Local.space().free
                            if (it.size > free - 1_000_000_000L) error("No hay espacio para ${it.title}")
                            val part = File(dest.path + ".part")
                            if (it.rar != null) Rar5.extract(it.source!!, it.rar, part) { b -> progress = "${n + 1} de ${chosen.size}: ${it.title} · ${formatSize(b)} de ${formatSize(it.size)}" }
                            else ZipFile(it.source!!).use { z ->
                                z.getInputStream(z.getEntry(it.zipEntry)).use { input -> part.outputStream().use { out -> input.copyTo(out, 1 shl 20) } }
                            }
                            if (part.length() != it.size) { part.delete(); error("Quedó incompleto") }
                            check(!dest.exists()) { part.delete(); "Ya existe ${it.to}" }
                            check(part.renameTo(dest)) { "No se pudo guardar" }
                            if (!it.id.endsWith(":keep")) {
                                doneSources += it.source!!
                                val all = chosen.filter { o -> o.source == it.source && o.kind == Kind.EXTRACT }
                                if (all.all { o -> File(roms, o.to).isFile }) { it.source.delete(); removeEmptyParents(it.source) }
                            }
                        }
                        Kind.MOVE -> {
                            val dest = File(roms, it.to)
                            dest.parentFile?.mkdirs()
                            check(!dest.exists()) { "Ya existe ${it.to}" }
                            check(it.source!!.renameTo(dest)) { "No se pudo mover" }
                            moveSaves(it.source, dest)
                            copyMedia(it.source, dest, it.to.substringBefore('/'))
                            it.serverFrom?.let { from -> Pc.io { c -> c.rename(SftpConn.join(Prefs.remoteRoms, from), SftpConn.join(Prefs.remoteRoms, it.to)) } }
                            removeEmptyParents(it.source)
                        }
                        Kind.DELETE -> {
                            val f = if (it.from.startsWith("Descargas/")) File(downloads, it.from.removePrefix("Descargas/")) else File(roms, it.from)
                            // La copia buena tiene que seguir ahí (donde estaba o ya movida a su lugar), y no ser este mismo archivo.
                            val keep = listOfNotNull(it.keep, it.keepTarget?.let { t -> File(roms, t) }).firstOrNull { k -> k.isFile }
                            check(keep != null && keep.canonicalPath != f.canonicalPath) { "No se encontró la copia que se conserva: no se borró" }
                            // Última revisión, completa (byte a byte), justo antes de borrar: si algo difiere, no se borra.
                            if (it.zipEntry == null && !f.name.endsWith(".zip", true)) {
                                val other = if (it.rar != null) rarReader(f, it.rar) else fileReader(f)
                                check((it.rar?.size ?: f.length()) == keep.length()) { "No pesa lo mismo que ${keep.name}: no se borró" }
                                check(fullSame(fileReader(keep), other, keep.length()) { b ->
                                    progress = "${n + 1} de ${chosen.size}: comprobando ${it.title} · ${formatSize(b)} de ${formatSize(keep.length())}"
                                }) { "No es idéntico a ${keep.name}: no se borró" }
                            }
                            check(f.delete()) { "No se pudo borrar" }
                            removeEmptyParents(f)
                        }
                        Kind.RENAME_SERVER -> Pc.io { c -> c.rename(SftpConn.join(Prefs.remoteRoms, it.serverFrom!!), SftpConn.join(Prefs.remoteRoms, it.to)) }
                        Kind.INFO -> {}
                    }
                    ok++
                }.onFailure { e -> errors += "${it.title}: ${e.message}" }
            }
        } finally {
            running = false
            progress = ""
            plan = null
        }
        runCatching { Repo.refresh() }
        if (errors.isEmpty()) "Listo: $ok cambio(s)" else "$ok listos · ${errors.size} con problemas: ${errors.first()}"
    }

    /**
     * Las partidas y estados que se llaman como el juego (junto al ROM, como WatermelonDS, o en RetroArch) se renombran
     * igual que él: los emuladores las buscan por el nombre del archivo.
     */
    private fun moveSaves(old: File, new: File) {
        val oldStem = Group.stem(old.name)
        val newStem = Group.stem(new.name)
        if (oldStem == newStem) return
        val dirs = listOfNotNull(old.parentFile, File(Environment.getExternalStorageDirectory(), "RetroArch/saves"),
            File(Environment.getExternalStorageDirectory(), "RetroArch/states"))
        for (d in dirs) {
            d.walkTopDown().maxDepth(2).filter { it.isFile && it.name.startsWith("$oldStem.") && it.name != old.name }.forEach { f ->
                val to = File(if (f.parentFile == old.parentFile) new.parentFile else f.parentFile, newStem + f.name.substring(oldStem.length))
                if (!to.exists()) f.renameTo(to)
            }
        }
    }

    private fun copyMedia(old: File, new: File, folder: String) {
        val oldStem = Group.stem(old.name)
        val newStem = Group.stem(new.name)
        if (oldStem == newStem) return
        val sd = Environment.getExternalStorageDirectory()
        for (root in listOf("Cucun/downloaded_media", "ES-DE/downloaded_media", "RomSync/media")) {
            File(sd, "$root/$folder").walkTopDown().maxDepth(2).filter { it.isFile && it.name.startsWith(oldStem) }.forEach { f ->
                val to = File(f.parentFile, newStem + f.name.substring(oldStem.length))
                if (!to.exists()) runCatching { f.copyTo(to) }
            }
        }
    }

    /** Compara todo, en trozos de 4 MB (6 GB tardan un par de minutos). */
    private fun fullSame(a: (Long, Int) -> ByteArray, b: (Long, Int) -> ByteArray, size: Long, progress: (Long) -> Unit): Boolean {
        val n = 4 shl 20
        var off = 0L
        while (off < size) {
            if (!a(off, n).contentEquals(b(off, n))) return false
            off += n
            progress(minOf(off, size))
        }
        return true
    }

    private fun crc32(f: File): Long = java.util.zip.CRC32().also { c ->
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; c.update(b, 0, n) } }
    }.value

    /** Borra las carpetas que quedaron vacías (sin subir más allá de ROMs/<plataforma> ni de Descargas). */
    private fun removeEmptyParents(f: File) {
        var d = f.parentFile
        while (d != null && d != roms && d != downloads && d.parentFile != roms && d.listFiles()?.isEmpty() == true) {
            d.delete(); d = d.parentFile
        }
    }
}
