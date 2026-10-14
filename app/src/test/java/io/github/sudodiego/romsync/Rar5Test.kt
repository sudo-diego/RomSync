package io.github.sudodiego.romsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Rar5Test {
    private fun vint(out: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (true) {
            val b = (v and 0x7F).toInt(); v = v shr 7
            if (v == 0L) { out.write(b); return } else out.write(b or 0x80)
        }
    }

    private fun block(out: ByteArrayOutputStream, header: ByteArray, data: ByteArray = ByteArray(0)) {
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC (el lector no lo revisa)
        vint(out, header.size.toLong()); out.write(header); out.write(data)
    }

    /** Arma un RAR5 "store" como los de Tendoku: carpeta + un .nsp adentro. */
    private fun storeRar(name: String, data: ByteArray, method: Int = 0, crc: Long = java.util.zip.CRC32().apply { update(data) }.value): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(Rar5.SIGNATURE)
        ByteArrayOutputStream().also { h -> vint(h, 1); vint(h, 0); vint(h, 0); block(o, h.toByteArray()) }
        ByteArrayOutputStream().also { h ->
            vint(h, 2); vint(h, 2); vint(h, data.size.toLong())        // tipo archivo, con datos
            vint(h, 4); vint(h, data.size.toLong()); vint(h, 0x20)      // flags (CRC), tamaño, atributos
            h.write(ByteArray(4) { (crc shr (8 * it)).toByte() })        // CRC de los datos (little-endian)
            vint(h, (method shl 7).toLong()); vint(h, 0)                 // compresión, sistema
            val n = name.toByteArray(); vint(h, n.size.toLong()); h.write(n)
            block(o, h.toByteArray(), data)
        }
        ByteArrayOutputStream().also { h -> vint(h, 5); vint(h, 0); vint(h, 0); block(o, h.toByteArray()) }
        return o.toByteArray()
    }

    @Test fun storedEntry() {
        val pfs0 = "PFS0".toByteArray() + ByteArray(60) { it.toByte() }
        val f = File.createTempFile("rar5", ".rar").apply { writeBytes(storeRar("Juego" + Char(92) + "Juego.nsp", pfs0)); deleteOnExit() }
        val info = Rar5.read(f)
        assertFalse(info.encrypted)
        val e = info.entries.single()
        assertEquals("Juego/Juego.nsp", e.name)
        assertEquals(pfs0.size.toLong(), e.size)
        assertEquals(java.util.zip.CRC32().apply { update(pfs0) }.value, e.crc)
        assertTrue(e.storable)
        assertEquals("PFS0", String(Rar5.head(f, e, 4)))
        val out = File.createTempFile("rar5", ".nsp").apply { deleteOnExit() }
        Rar5.extract(f, e, out)
        assertTrue(pfs0.contentEquals(out.readBytes()))
    }

    @Test fun damagedEntryIsNotKept() {
        val data = ByteArray(100) { it.toByte() }
        val f = File.createTempFile("rar5", ".rar").apply { writeBytes(storeRar("a.nsp", data, crc = 0x12345678L)); deleteOnExit() }
        val e = Rar5.read(f).entries.single()
        val out = File.createTempFile("rar5", ".nsp").apply { deleteOnExit() }
        assertTrue(runCatching { Rar5.extract(f, e, out) }.isFailure)
        assertFalse(out.exists())
    }

    @Test fun compressedIsNotStorable() {
        val f = File.createTempFile("rar5", ".rar").apply { writeBytes(storeRar("a.nsp", ByteArray(10), method = 3)); deleteOnExit() }
        assertFalse(Rar5.read(f).entries.single().storable)
    }
}
