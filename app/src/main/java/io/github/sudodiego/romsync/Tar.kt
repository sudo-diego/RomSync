package io.github.sudodiego.romsync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Glob {
    /** Glob con `**` (cualquier cosa, también "/"), `*` (sin "/") y `?`. */
    fun toRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    // "**/" también vale para cero carpetas.
                    if (i + 2 < glob.length && glob[i + 2] == '/') { sb.append("(?:.*/)?"); i += 2 } else { sb.append(".*"); i++ }
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(sb.append("$").toString(), RegexOption.IGNORE_CASE)
    }
}

/** Lo mínimo de tar (ustar + nombres largos de GNU) para hablar con el tar de Android. */
object Tar {
    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) { if (v > 0) break else continue }
            if (c < '0'.code || c > '7'.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun readFully(input: InputStream, b: ByteArray, n: Int = b.size): Boolean {
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) return false
            got += r
        }
        return true
    }

    /** Lee un tar y entrega cada archivo normal. El InputStream del archivo solo vale dentro de [onFile]. */
    fun read(input: InputStream, onFile: (String, Long, InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            val name = longName ?: run {
                val n = cstr(header, 0, 100)
                val magic = cstr(header, 257, 6)
                val prefix = if (magic.startsWith("ustar")) cstr(header, 345, 155) else ""
                if (prefix.isNotEmpty()) "$prefix/$n" else n
            }
            longName = null
            when (type) {
                'L', 'x' -> {
                    val data = ByteArray(padded.toInt())
                    readFully(input, data)
                    val text = String(data, 0, size.toInt(), Charsets.UTF_8)
                    longName = if (type == 'L') text.trimEnd('\u0000')
                    else text.lineSequence().firstOrNull { it.contains(" path=") }?.substringAfter(" path=")
                }
                '0', '\u0000', '7' -> {
                    val limited = Limited(input, size)
                    onFile(name, size, limited)
                    limited.skipRest()
                    skip(input, padded - size)
                }
                else -> skip(input, padded)
            }
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }

    private class Limited(val input: InputStream, var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = input.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }

        fun skipRest() = skip(input, left).also { left = 0 }
        override fun close() {}
    }

    private fun header(name: String, size: Long, type: Char): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray(Charsets.UTF_8).copyInto(h, off)
        put(0, name.take(99))
        put(100, "0000666\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, size.toString(8).padStart(11, '0') + "\u0000")
        put(136, (System.currentTimeMillis() / 1000).toString(8).padStart(11, '0') + "\u0000")
        put(148, "        ")
        h[156] = type.code.toByte()
        put(257, "ustar  \u0000")
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    fun writeEntry(out: OutputStream, name: String, size: Long, input: InputStream) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size > 99) {
            out.write(header("././@LongLink", bytes.size + 1L, 'L'))
            out.write(bytes)
            out.write(0)
            pad(out, bytes.size + 1L)
        }
        out.write(header(name, size, '0'))
        var copied = 0L
        val buf = ByteArray(1 shl 16)
        while (copied < size) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), size - copied).toInt())
            if (r < 0) throw IOException("archivo más corto de lo esperado: $name")
            out.write(buf, 0, r)
            copied += r
        }
        pad(out, size)
    }

    private fun pad(out: OutputStream, size: Long) {
        val rest = ((512 - size % 512) % 512).toInt()
        if (rest > 0) out.write(ByteArray(rest))
    }

    fun finish(out: OutputStream) = out.write(ByteArray(1024))
}
