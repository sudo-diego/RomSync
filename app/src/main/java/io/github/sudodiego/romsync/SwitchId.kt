package io.github.sudodiego.romsync

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * El TitleID de un .nsp/.nsz sin el código en el nombre. Un NSP es un contenedor PFS0: al principio trae la lista de
 * archivos de adentro, y el ticket se llama "<RightsID>.tik", donde los primeros 16 caracteres son el TitleID.
 * Basta con leer los primeros KB del archivo (no hace falta ninguna clave).
 */
object SwitchId {
    const val HEADER_BYTES = 64 * 1024

    /** Lee el comienzo de un archivo (readNBytes es de Android 13 en adelante). */
    fun head(input: java.io.InputStream, n: Int = HEADER_BYTES): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return out.copyOf(got)
    }

    /** Los nombres de los archivos de adentro de un PFS0, o null si no es un PFS0. */
    fun pfs0Names(head: ByteArray): List<String>? {
        if (head.size < 16 || String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getInt(4)
        val strSize = b.getInt(8)
        if (count <= 0 || count > 4096) return null
        val strStart = 16 + count * 24
        if (strStart + strSize > head.size) return null
        return (0 until count).map { i ->
            val off = b.getInt(16 + i * 24 + 16)
            var end = strStart + off
            while (end < strStart + strSize && head[end] != 0.toByte()) end++
            String(head, strStart + off, end - strStart - off, Charsets.UTF_8)
        }
    }

    /** TitleID (16 hex en mayúsculas) a partir del comienzo de un .nsp/.nsz. */
    fun fromHeader(head: ByteArray): String? {
        val names = pfs0Names(head) ?: return null
        val tik = names.firstOrNull { Regex("^[0-9a-fA-F]{32}\\.(tik|cert)$").matches(it) && it.endsWith(".tik") }
            ?: names.firstOrNull { Regex("^[0-9a-fA-F]{32}\\.cert$").matches(it) }
        return tik?.take(16)?.uppercase()?.takeIf { it.startsWith("01") }
    }
}
