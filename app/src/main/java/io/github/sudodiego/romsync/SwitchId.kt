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

    data class PfsEntry(val name: String, val offset: Long, val size: Long)

    /** Archivos del PFS0 con su posición (relativa al inicio del NSP). */
    fun pfs0Entries(head: ByteArray): List<PfsEntry>? {
        if (head.size < 16 || String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val count = b.getInt(4)
        val strSize = b.getInt(8)
        if (count <= 0 || count > 4096) return null
        val strStart = 16 + count * 24
        if (strStart + strSize > head.size) return null
        val dataStart = (strStart + strSize).toLong()
        val names = pfs0Names(head) ?: return null
        return (0 until count).map { i ->
            PfsEntry(names[i], dataStart + b.getLong(16 + i * 24), b.getLong(16 + i * 24 + 8))
        }
    }

    /** Dónde están las llaves (en la carpeta bios), si están. */
    val keysFile get() = java.io.File(Prefs.biosDir, "prod.keys")

    /**
     * Busca un prod.keys en los lugares de siempre (Descargas, carpetas de emuladores) y lo copia a bios.
     * Devuelve true si quedó.
     */
    fun findKeys(): Boolean {
        if (headerKey != null) return true
        val sd = android.os.Environment.getExternalStorageDirectory()
        val found = sd.walkTopDown().maxDepth(5).onEnter { !it.name.startsWith(".") && it.name != "Android" }
            .firstOrNull { it.isFile && it.name.equals("prod.keys", true) && it != keysFile } ?: return false
        return useKeys(found.readBytes())
    }

    /** Guarda unas llaves elegidas por el usuario (si de verdad traen header_key). */
    fun useKeys(bytes: ByteArray): Boolean {
        if (parseHeaderKey(String(bytes)) == null) return false
        keysFile.parentFile?.mkdirs()
        keysFile.writeBytes(bytes)
        cachedKey = null
        return headerKey != null
    }

    private fun parseHeaderKey(text: String): ByteArray? = text.lines().firstOrNull { it.trim().startsWith("header_key") && !it.contains("source") }
        ?.substringAfter('=')?.trim()?.takeIf { it.length == 64 }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    private var cachedKey: ByteArray? = null

    /** header_key de prod.keys (en la carpeta bios). Null si no está. */
    val headerKey: ByteArray? get() {
        cachedKey?.let { return it }
        cachedKey = runCatching { parseHeaderKey(keysFile.readText()) }.getOrNull()
        return cachedKey
    }

    /**
     * TitleID leído de la cabecera de un NCA (los primeros 0x400 bytes, cifrados con AES-XTS y la header_key). Sirve para
     * los NSP sin ticket (convertidos o "sin licencia"), donde el nombre del .tik no está.
     */
    fun fromNcaHeader(enc: ByteArray): String? {
        val key = headerKey ?: return null
        if (enc.size < 0x400) return null
        val k1 = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.copyOfRange(0, 16), "AES"))
        }
        val k2 = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.copyOfRange(16, 32), "AES"))
        }
        val out = ByteArray(0x400)
        for (sector in 0 until 2) {
            // Ajuste de Nintendo: el número de sector va en big-endian.
            var tweak = k2.doFinal(ByteArray(16).also { it[15] = sector.toByte() })
            for (blk in 0 until 32) {
                val off = sector * 0x200 + blk * 16
                val x = ByteArray(16) { (enc[off + it].toInt() xor tweak[it].toInt()).toByte() }
                val p = k1.doFinal(x)
                for (i in 0 until 16) out[off + i] = (p[i].toInt() xor tweak[i].toInt()).toByte()
                // Siguiente tweak: multiplicar por x en GF(2^128) (little-endian).
                var carry = 0
                val nt = ByteArray(16)
                for (i in 0 until 16) { val v = (tweak[i].toInt() and 0xFF); nt[i] = ((v shl 1) or carry).toByte(); carry = v ushr 7 }
                if (carry != 0) nt[0] = (nt[0].toInt() xor 0x87).toByte()
                tweak = nt
            }
        }
        if (String(out, 0x200, 4, Charsets.US_ASCII) != "NCA3") return null
        val tid = ByteBuffer.wrap(out, 0x210, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return "%016X".format(tid).takeIf { it.startsWith("01") }
    }

    /**
     * TitleID de un NSP con cualquier lector de bytes (archivo suelto o dentro de un RAR): primero el ticket; si no hay,
     * la cabecera del NCA de metadatos (.cnmt.nca) o del más chico.
     */
    fun identify(read: (Long, Int) -> ByteArray): String? {
        val head = read(0, HEADER_BYTES)
        fromHeader(head)?.let { return it }
        val entries = pfs0Entries(head) ?: return null
        val nca = entries.firstOrNull { it.name.endsWith(".cnmt.nca") } ?: entries.filter { it.name.endsWith(".nca") }.minByOrNull { it.size }
            ?: return null
        val tid = fromNcaHeader(read(nca.offset, 0x400)) ?: return null
        // El NCA de metadatos de una actualización ya trae su propio TitleID (…800); el de un programa, el del juego.
        return tid
    }

    /** TitleID (16 hex en mayúsculas) a partir del comienzo de un .nsp/.nsz. */
    fun fromHeader(head: ByteArray): String? {
        val names = pfs0Names(head) ?: return null
        val tik = names.firstOrNull { Regex("^[0-9a-fA-F]{32}\\.(tik|cert)$").matches(it) && it.endsWith(".tik") }
            ?: names.firstOrNull { Regex("^[0-9a-fA-F]{32}\\.cert$").matches(it) }
        return tik?.take(16)?.uppercase()?.takeIf { it.startsWith("01") }
    }
}
