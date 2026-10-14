package io.github.sudodiego.romsync

import android.os.StatFs
import java.io.File

/**
 * Los archivos de la tablet: ROMs, BIOS y espacio. Las reglas para ignorar archivos son las de [Group] (las mismas
 * para el PC): las partidas que WatermelonDS o RetroArch guardan junto al ROM nunca se cuentan ni se borran.
 */
object Local {
    fun isIgnored(name: String) = Group.isIgnored(name)
    private val ignoredDirs get() = Group.ignoredDirs

    /** Todos los ROMs bajo la carpeta de ROMs, con rutas relativas ("psx/Juego.chd"), sin BIOS ni medios. */
    fun scanRoms(romsDir: String = Prefs.romsDir, biosDir: String = Prefs.biosDir): List<FileEntry> {
        val root = File(romsDir)
        val bios = File(biosDir).absoluteFile
        val out = ArrayList<FileEntry>()
        root.listFiles()?.forEach { sys ->
            if (!sys.isDirectory || sys.name.startsWith(".") || sys.name.lowercase() in ignoredDirs) return@forEach
            if (sys.absoluteFile == bios) return@forEach
            walk(root, sys, out)
        }
        return out
    }

    /** Lo que sobra en la carpeta de ROMs (ver [Group.junkKind]): descargas a medias, opciones, copias de partidas. */
    fun scanJunk(romsDir: String = Prefs.romsDir): List<FileEntry> {
        val root = File(romsDir)
        val bios = File(Prefs.biosDir).absoluteFile
        return root.walkTopDown()
            .onEnter { it == root || (it.name.lowercase() !in ignoredDirs && !it.name.equals("bios", true) && it.absoluteFile != bios) }
            .filter { it.isFile && Group.junkKind(it.name) != null && !Group.inBiosDir(it.relativeTo(root).path) }
            .map { FileEntry(it.relativeTo(root).path.replace('\\', '/'), it.length()) }.toList()
    }

    fun scanBios(biosDir: String = Prefs.biosDir): List<FileEntry> {
        val root = File(biosDir)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<FileEntry>()
        walk(root, root, out, skipMedia = false, keep = { !Group.isSystemJunk(it) })
        return out
    }

    private fun walk(root: File, dir: File, out: MutableList<FileEntry>, skipMedia: Boolean = true,
                     keep: (String) -> Boolean = { !isIgnored(it) }) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (!skipMedia || f.name.lowercase() !in ignoredDirs) walk(root, f, out, skipMedia, keep)
            } else if (keep(f.name)) {
                out.add(FileEntry(f.relativeTo(root).path.replace('\\', '/'), f.length()))
            }
        }
    }

    data class Space(val free: Long, val total: Long)

    fun space(path: String = Prefs.romsDir): Space {
        var dir = File(path)
        while (!dir.exists() && dir.parentFile != null) dir = dir.parentFile!!
        return runCatching { StatFs(dir.absolutePath).let { Space(it.availableBytes, it.totalBytes) } }
            .getOrDefault(Space(0, 0))
    }

    fun romFile(path: String) = File(Prefs.romsDir, path)
    fun biosFile(path: String) = File(Prefs.biosDir, path)

    /**
     * Borra los archivos de un juego y las carpetas que quedan vacías (nunca la de la plataforma). Solo toca los
     * archivos de la lista: las partidas guardadas junto al ROM se quedan.
     */
    fun deleteFiles(files: List<FileEntry>): Long {
        val root = File(Prefs.romsDir).absoluteFile
        var freed = 0L
        val dirs = mutableSetOf<File>()
        for (f in files) {
            val file = File(root, f.path)
            if (!file.absolutePath.startsWith(root.absolutePath + "/")) continue
            val size = file.length()
            if (file.isFile && file.delete()) {
                freed += size
                file.parentFile?.let { dirs.add(it) }
            }
            File(file.path + ".part").delete()
        }
        // De la más profunda a la menos: borrar las que quedaron vacías, sin subir a la carpeta de la plataforma.
        for (d in dirs.sortedByDescending { it.absolutePath.length }) {
            var cur: File? = d
            while (cur != null && cur.absolutePath.removePrefix(root.absolutePath).count { it == '/' } > 1) {
                if (cur.listFiles()?.isEmpty() == true) cur.delete() else break
                cur = cur.parentFile
            }
        }
        return freed
    }
}
