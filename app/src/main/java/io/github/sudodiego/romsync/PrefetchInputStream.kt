package io.github.sudodiego.romsync

import java.io.File
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue

/**
 * Lee un archivo por adelantado en otro hilo (bloques de 4 MB, hasta [depth] en cola) mientras se envía lo ya leído.
 * Al subir, JSch lee y manda por turnos: leer del almacenamiento frenaba la subida a la mitad.
 */
class PrefetchInputStream(file: File, private val chunk: Int = 4 shl 20, depth: Int = 6) : InputStream() {
    private val queue = ArrayBlockingQueue<ByteArray>(depth)
    private val end = ByteArray(0)
    @Volatile private var error: Throwable? = null
    @Volatile private var closed = false
    private var cur: ByteArray = ByteArray(0)
    private var pos = 0
    private val reader = Thread {
        try {
            file.inputStream().use { input ->
                while (!closed) {
                    val buf = ByteArray(chunk)
                    var n = 0
                    while (n < chunk) { val r = input.read(buf, n, chunk - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    queue.put(if (n == chunk) buf else buf.copyOf(n))
                    if (n < chunk) break
                }
            }
        } catch (e: Throwable) { error = e }
        finally { runCatching { queue.put(end) } }
    }.apply { isDaemon = true; name = "lectura-anticipada"; start() }

    private fun next(): Boolean {
        if (pos < cur.size) return true
        val b = queue.take()
        error?.let { throw java.io.IOException(it.message, it) }
        if (b === end) { queue.offer(end); return false }
        cur = b; pos = 0
        return true
    }

    override fun read(): Int = if (!next()) -1 else cur[pos++].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!next()) return -1
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() { closed = true; queue.clear() }
}
