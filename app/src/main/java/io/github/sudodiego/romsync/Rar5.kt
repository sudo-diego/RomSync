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

    fun read(r: RandomAccessFile): Info {
        val sig = ByteArray(8); r.seek(0); r.readFully(sig)
        require(SIGNATURE.contentEquals(sig)) { "No es un RAR5" }
        val out = ArrayList<Entry>()
        var encrypted = false
        var multi = false
        var pos = 8L
        val len = r.length()
        while (pos + 7 < len) {
            r.seek(pos + 4) // CRC32 del encabezado
            val headerSize = vint(r)
            val headerStart = r.filePointer
            val type = vint(r).toInt()
            val flags = vint(r)
            if (flags and 1L != 0L) vint(r) // tamaño del área extra
            val dataSize = if (flags and 2L != 0L) vint(r) else 0L
            when (type) {
                1 -> { if (vint(r) and 1L != 0L) multi = true } // encabezado principal: ¿es un volumen?
                4 -> { encrypted = true; break } // encabezado de cifrado: todo lo demás está cifrado
                2 -> {
                    val fileFlags = vint(r)
                    val unpacked = vint(r)
                    vint(r) // atributos
                    if (fileFlags and 2L != 0L) r.readInt() // fecha
                    val crc = if (fileFlags and 4L != 0L) Integer.reverseBytes(r.readInt()).toLong() and 0xFFFFFFFFL else null
                    val comp = vint(r)
                    vint(r) // sistema de origen
                    val nameLen = vint(r).toInt()
                    val nameBytes = ByteArray(nameLen); r.readFully(nameBytes)
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

    /** Entero de largo variable de RAR5: 7 bits por byte, el bit alto dice si sigue. */
    private fun vint(r: RandomAccessFile): Long {
        var v = 0L
        var shift = 0
        while (shift < 64) {
            val b = r.read()
            if (b < 0) throw java.io.EOFException()
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return v
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
