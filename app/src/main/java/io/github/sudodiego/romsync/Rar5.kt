package io.github.sudodiego.romsync

import java.io.File
import java.io.RandomAccessFile

/**
 * Lector mínimo de RAR5 (el formato de los .rar nuevos). Solo lee la lista de archivos: nombre, tamaño, dónde empiezan
 * los datos y si están guardados sin comprimir ("store", método 0). Muchos RAR de juegos de Switch vienen así (el NSP
 * ya está comprimido por dentro), y entonces sacar el juego es copiar esos bytes tal cual: no hace falta descompresor.
 * No sirve para RAR comprimidos, cifrados, sólidos ni partidos en volúmenes (lo dice [Entry.storable] / [Info]).
 */
object Rar5 {
    val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    data class Entry(
        val name: String, val dataOffset: Long, val packedSize: Long, val size: Long, val method: Int,
        val dir: Boolean, val solid: Boolean, val crc: Long?,
    ) {
        /** Se puede sacar copiando los bytes. */
        val storable get() = method == 0 && !solid && !dir && packedSize == size
    }

    data class Info(val entries: List<Entry>, val encrypted: Boolean, val multiVolume: Boolean)

    fun isRar5(f: File) = runCatching { f.inputStream().use { i -> SIGNATURE.contentEquals(SwitchId.head(i, 8)) } }.getOrDefault(false)

    fun read(f: File): Info = RandomAccessFile(f, "r").use { read(it) }

    fun read(r: RandomAccessFile): Info = read(r.length()) { pos, n ->
        r.seek(pos); val b = ByteArray(minOf(n.toLong(), r.length() - pos).toInt().coerceAtLeast(0)); r.readFully(b); b
    }

    /**
     * Lee la lista con un lector de bytes cualquiera ([readAt] devuelve hasta n bytes desde una posición). Sirve para un
     * RAR del servidor: solo se piden los encabezados (unos KB por archivo), no el RAR entero.
     */
    fun read(length: Long, readAt: (Long, Int) -> ByteArray): Info {
        val c = Cursor(length, readAt)
        val sig = c.bytes(8)
        require(SIGNATURE.contentEquals(sig)) { "No es un RAR5" }
        val out = ArrayList<Entry>()
        var encrypted = false
        var multi = false
        var pos = 8L
        while (pos + 7 < length) {
            c.seek(pos + 4) // CRC32 del encabezado
            val headerSize = c.vint()
            val headerStart = c.pos
            val type = c.vint().toInt()
            val flags = c.vint()
            if (flags and 1L != 0L) c.vint() // tamaño del área extra
            val dataSize = if (flags and 2L != 0L) c.vint() else 0L
            when (type) {
                1 -> { if (c.vint() and 1L != 0L) multi = true } // encabezado principal: ¿es un volumen?
                4 -> { encrypted = true; break } // encabezado de cifrado: todo lo demás está cifrado
                2 -> {
                    val fileFlags = c.vint()
                    val unpacked = c.vint()
                    c.vint() // atributos
                    if (fileFlags and 2L != 0L) c.bytes(4) // fecha
                    val crc = if (fileFlags and 4L != 0L) c.bytes(4).let { b ->
                        (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
                    } else null
                    val comp = c.vint()
                    c.vint() // sistema de origen
                    val nameLen = c.vint().toInt()
                    val nameBytes = c.bytes(nameLen)
                    val dataStart = headerStart + headerSize
                    out += Entry(String(nameBytes, Charsets.UTF_8).replace('\\', '/'), dataStart, dataSize, unpacked,
                        ((comp shr 7) and 7).toInt(), fileFlags and 1L != 0L, comp and 0x40L != 0L, crc)
                }
                5 -> break // fin del archivo
            }
            pos = headerStart + headerSize + dataSize
        }
        return Info(out, encrypted, multi)
    }

    /** Lectura secuencial con una ventana de 64 KB (así un encabezado se lee de una vez, también por SFTP). */
    private class Cursor(val length: Long, val readAt: (Long, Int) -> ByteArray) {
        var pos = 0L
        private var winStart = -1L
        private var win = ByteArray(0)
        fun seek(p: Long) { pos = p }
        private fun byteAt(p: Long): Int {
            if (winStart < 0 || p < winStart || p >= winStart + win.size) {
                winStart = p; win = readAt(p, 64 * 1024)
                if (win.isEmpty()) throw java.io.EOFException()
            }
            return win[(p - winStart).toInt()].toInt() and 0xFF
        }
        fun read(): Int = byteAt(pos++)
        fun bytes(n: Int) = ByteArray(n) { read().toByte() }
        fun vint(): Long {
            var v = 0L; var shift = 0
            while (shift < 64) {
                val b = read()
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return v
        }
    }

    /** Copia los bytes de una entrada guardada (store) a [to]. [progress] recibe los bytes copiados. */
    fun extract(f: File, e: Entry, to: File, progress: (Long) -> Unit = {}) {
        require(e.storable) { "Este RAR está comprimido: descomprímelo en el PC" }
        RandomAccessFile(f, "r").use { r ->
            r.seek(e.dataOffset)
            val crc = java.util.zip.CRC32()
            to.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 20)
                var left = e.size
                var done = 0L
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException("El RAR está cortado")
                    out.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    left -= n; done += n
                    progress(done)
                }
            }
            // El RAR guarda el CRC de cada archivo: si no coincide, lo sacado no sirve.
            if (e.crc != null && crc.value != e.crc) { to.delete(); error("El archivo dentro del RAR está dañado (CRC distinto)") }
        }
    }

    /** Los primeros [n] bytes de una entrada (para leer el TitleID del NSP sin sacarlo). */
    fun head(f: File, e: Entry, n: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
        r.seek(e.dataOffset)
        val b = ByteArray(minOf(n.toLong(), e.packedSize).toInt())
        r.readFully(b)
        b
    }
}
