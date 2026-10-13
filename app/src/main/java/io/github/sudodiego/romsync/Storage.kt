package io.github.sudodiego.romsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/**
 * Leer y escribir carpetas de partidas. En el almacenamiento compartido, directo (con "Acceso a todos los
 * archivos"); en Android/data, con Shizuku y el tar de Android (toybox): `tar -cf - -T -` para sacar archivos
 * y `tar -xof -` para dejarlos, sin archivos intermedios.
 */
object Storage {
    data class Stat(val rel: String, val size: Long, val mtime: Long)

    class NeedsShizuku : IOException("Hace falta Shizuku para leer esta carpeta (Android/data)")

    suspend fun readText(path: String): String? = withContext(Dispatchers.IO) {
        if (Emulators.needsShell(path)) Shell.run("cat ${Shell.q(path)}", 5000)
        else runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (Emulators.needsShell(path)) Shell.run("[ -d ${Shell.q(path)} ] && echo si || true", 5000)?.trim() == "si"
        else File(path).isDirectory
    }

    fun matcher(src: SaveSource): (String) -> Boolean {
        val inc = src.include.map(Glob::toRegex)
        val exc = src.exclude.map(Glob::toRegex)
        return { rel -> inc.any { it.matches(rel) } && exc.none { it.matches(rel) } }
    }

    /** Los archivos de una carpeta de partidas que pasan sus patrones. Vacía si la carpeta no existe. */
    suspend fun list(src: SaveSource): List<Stat> = withContext(Dispatchers.IO) {
        val match = matcher(src)
        val all: List<Stat> = if (Emulators.needsShell(src.root)) {
            if (!Shell.ready) throw NeedsShizuku()
            val root = src.root.trimEnd('/')
            val out = Shell.run(
                "[ -d ${Shell.q(root)} ] || exit 0; cd ${Shell.q(root)} && find . -type f -exec stat -c '%s|%Y|%n' {} +",
                60000,
            ) ?: throw IOException("No se pudo leer ${src.root}")
            out.lineSequence().mapNotNull { line ->
                val p = line.split('|', limit = 3)
                if (p.size < 3) null else Stat(p[2].removePrefix("./"), p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0)
            }.toList()
        } else {
            val root = File(src.root)
            if (!root.isDirectory) return@withContext emptyList()
            root.walkTopDown().onEnter { it == root || !it.name.startsWith(".") || it.name == ".hidden" }
                .filter { it.isFile }
                .map { Stat(it.relativeTo(root).path.replace('\\', '/'), it.length(), it.lastModified() / 1000) }
                .toList()
        }
        all.filter { match(it.rel) }
    }

    /** Copia los archivos pedidos a [sink] (ruta relativa, tamaño, contenido). */
    suspend fun read(src: SaveSource, files: List<Stat>, sink: (String, Long, InputStream) -> Unit) = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext
        if (!Emulators.needsShell(src.root)) {
            for (f in files) File(src.root, f.rel).inputStream().use { sink(f.rel, f.size, it) }
            return@withContext
        }
        val p = Shell.process("cd ${Shell.q(src.root)} && tar -cf - -T -") ?: throw NeedsShizuku()
        // La lista va por la entrada en otro hilo: si no, tar puede llenar su salida mientras se le escribe.
        val writer = Thread {
            runCatching { p.outputStream.bufferedWriter().use { w -> files.forEach { w.write(it.rel); w.write("\n") } } }
        }
        writer.start()
        try {
            Tar.read(p.inputStream) { name, size, input -> sink(name.removePrefix("./"), size, input) }
        } finally {
            writer.join(2000)
            val code = Shell.waitFor(p, 30000)
            if (code != 0) throw IOException("tar salió con $code")
        }
    }

    /** Escribe archivos en una carpeta de partidas (crea las carpetas que falten). */
    suspend fun write(root: String, entries: Sequence<Triple<String, Long, () -> InputStream>>) = withContext(Dispatchers.IO) {
        if (!Emulators.needsShell(root)) {
            val base = File(root)
            for ((rel, _, open) in entries) {
                val f = File(base, rel)
                require(f.canonicalPath.startsWith(base.canonicalPath)) { "ruta inválida: $rel" }
                f.parentFile?.mkdirs()
                open().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
            return@withContext
        }
        val p = Shell.process("mkdir -p ${Shell.q(root)} && cd ${Shell.q(root)} && tar -xof -") ?: throw NeedsShizuku()
        val err = StringBuilder()
        val errReader = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        errReader.start()
        try {
            p.outputStream.buffered(1 shl 16).use { out ->
                for ((rel, size, open) in entries) {
                    require(!rel.startsWith("/") && !rel.split('/').contains("..")) { "ruta inválida: $rel" }
                    open().use { Tar.writeEntry(out, rel, size, it) }
                }
                Tar.finish(out)
            }
        } finally {
            val code = Shell.waitFor(p, 120000)
            errReader.join(500)
            if (code != 0) throw IOException("tar salió con $code: ${err.toString().take(200)}")
        }
    }

    /** Tamaño de una carpeta en bytes (para ver cuánto ocupan los datos de un emulador). */
    suspend fun sizeOf(path: String): Long? = withContext(Dispatchers.IO) {
        if (Emulators.needsShell(path)) {
            Shell.run("du -sk ${Shell.q(path)} 2>/dev/null | cut -f1", 60000)?.trim()?.toLongOrNull()?.times(1024)
        } else {
            val f = File(path)
            if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else null
        }
    }
}
