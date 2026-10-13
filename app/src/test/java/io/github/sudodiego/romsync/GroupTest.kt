package io.github.sudodiego.romsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GroupTest {
    private fun fe(p: String, s: Long) = FileEntry(p, s)
    private fun byTitle(g: List<Game>) = g.associateBy { it.title }

    @Test fun cueBinWithContent() {
        val files = listOf(fe("psx/Crash (USA).cue", 1), fe("psx/Crash (USA) (Track 1).bin", 100),
            fe("psx/Crash (USA) (Track 2).bin", 50), fe("psx/Spyro (USA).chd", 300))
        val games = Group.system("psx", files) { p ->
            if (p == "psx/Crash (USA).cue") "FILE \"Crash (USA) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Crash (USA) (Track 2).bin\" BINARY\n" else null
        }
        assertEquals(2, games.size)
        val crash = byTitle(games)["Crash"]!!
        assertEquals(3, crash.files.size); assertEquals(151L, crash.size); assertEquals("multi", crash.kind)
        assertEquals("USA", byTitle(games)["Spyro"]!!.tags.first())
    }

    @Test fun cueBinByName() {
        val g = Group.system("psx", listOf(fe("psx/Crash (USA).cue", 1), fe("psx/Crash (USA) (Track 1).bin", 100), fe("psx/Crash (USA) (Track 2).bin", 50)))
        assertEquals(1, g.size); assertEquals(3, g[0].files.size)
    }

    @Test fun m3uAbsorbsCues() {
        val files = listOf(fe("psx/FF7.m3u", 1), fe("psx/.hidden/FF7 (Disc 1).cue", 1), fe("psx/.hidden/FF7 (Disc 1).bin", 10),
            fe("psx/.hidden/FF7 (Disc 2).cue", 1), fe("psx/.hidden/FF7 (Disc 2).bin", 10))
        val games = Group.system("psx", files) { p ->
            when (p) {
                "psx/FF7.m3u" -> ".hidden/FF7 (Disc 1).cue\r\n.hidden/FF7 (Disc 2).cue\r\n"
                "psx/.hidden/FF7 (Disc 1).cue" -> "FILE \"FF7 (Disc 1).bin\" BINARY"
                "psx/.hidden/FF7 (Disc 2).cue" -> "FILE \"FF7 (Disc 2).bin\" BINARY"
                else -> null
            }
        }
        assertEquals(1, games.size); assertEquals(5, games[0].files.size); assertEquals(23L, games[0].size)
    }

    @Test fun m3uByNameWithChd() {
        val g = Group.system("ps2", listOf(fe("ps2/Game.m3u", 1), fe("ps2/Game (Disc 1).chd", 10), fe("ps2/Game (Disc 2).chd", 10), fe("ps2/Other.chd", 5)))
        assertEquals(2, g.size)
    }

    @Test fun foldersAndCategories() {
        val ps3 = Group.system("ps3", listOf(fe("ps3/Demon's Souls.ps3/PS3_GAME/USRDIR/EBOOT.BIN", 10), fe("ps3/Demon's Souls.ps3/PS3_DISC.SFB", 1)))
        assertEquals("folder", ps3.single().kind); assertEquals(2, ps3.single().files.size)
        val gba = Group.system("gba", listOf(fe("gba/Hacks/Pokemon Unbound.gba", 30), fe("gba/Hacks/Pokemon Radical Red.gba", 30)))
        assertEquals(2, gba.size)
        val psx = Group.system("psx", listOf(fe("psx/Crash Bandicoot/Crash Bandicoot.chd", 40)))
        assertEquals("folder", psx.single().kind)
    }

    @Test fun switchParts() {
        val g = byTitle(Group.system("switch", listOf(
            fe("switch/Zelda TOTK [0100F2C0115B6000][v0].nsp", 16000),
            fe("switch/updates/Zelda TOTK [0100F2C0115B6800][v393216].nsp", 1000),
            fe("switch/Mario Kart 8 Deluxe [0100152000022000][v0].xci", 7000),
            fe("switch/Mario Kart 8 Deluxe [0100152000023001][DLC].nsp", 500),
            fe("switch/Mario Kart 8 Deluxe [0100152000023002][DLC].nsp", 600),
            fe("switch/Homebrew.nro", 5),
        )))
        assertEquals(3, g.size)
        val z = g["Zelda TOTK"]!!
        assertEquals(listOf("base", "update"), z.parts.map { it.kind }); assertEquals(393216L, z.parts[1].version)
        val mk = g["Mario Kart 8 Deluxe"]!!
        assertEquals(3, mk.parts.size); assertEquals(8100L, mk.size); assertEquals("0100152000022000", mk.serial)
    }

    @Test fun sameIdsAsBefore() {
        // Mismo id que el servidor Go (sha1 de "sistema:ruta" en minúsculas, 6 bytes): no cambia entre versiones.
        assertEquals(12, Group.gameId("psx", "psx/Crash.chd").length)
        assertEquals(Group.gameId("PSX", "psx/crash.chd"), Group.gameId("psx", "psx/Crash.chd"))
    }

    @Test fun ignored() {
        listOf("Game.sav", "Game.srm", "Game.state1", "Game.ml2", "gamelist.xml", "Thumbs.db").forEach { assertTrue(it, Group.isIgnored(it)) }
        listOf("Game.nds", "Game.chd", "Game.iso", "Game.m3u", "Game.3ds").forEach { assertFalse(it, Group.isIgnored(it)) }
    }

    @Test fun junkFromUserLibrary() {
        // Lo que apareció en la biblioteca real (oct 2026).
        listOf(".8686A228806624EF2D1C01D897EFC240DFA384980.parts", "The Legend of Zelda Spirit Tracks.opts",
            "The Legend of Zelda Spirit Tracks.SAV(1)(0)", "The Legend of Zelda Spirit Tracks.SAV(2)(0)").forEach { assertTrue(it, Group.isIgnored(it)) }
        val psx = Group.system("psx", listOf(fe("psx/scph5501.bin", 512), fe("psx/SCPH-1001.BIN", 512), fe("psx/PSXONPSP660.BIN", 512),
            fe("psx/ps-22a.bin", 512), fe("psx/Persona 3 FES.chd", 4000)))
        assertEquals(listOf("Persona 3 FES"), psx.map { it.title })
    }

    @Test fun discsWithoutM3u() {
        val g = Group.system("psx", listOf(fe("psx/Final Fantasy VII (USA) (Disc 1).chd", 10), fe("psx/Final Fantasy VII (USA) (Disc 2).chd", 10),
            fe("psx/Final Fantasy VII (USA) (Disc 3).chd", 10), fe("psx/Crash (USA).chd", 5)))
        assertEquals(2, g.size)
        val ff = g.first { it.title == "Final Fantasy VII" }
        assertEquals("discs", ff.kind); assertEquals(3, ff.files.size); assertEquals("psx/Final Fantasy VII (USA) (Disc 1).chd", ff.primary)
    }

    @Test fun shortcuts() {
        val g = Group.system("androidgames", listOf(fe("androidgames/Genshin.app", 40)))
        assertEquals("shortcut", g.single().kind)
        assertEquals("shortcut", Group.system("psvita", listOf(fe("psvita/Gravity Rush.psvita", 9))).single().kind)
        assertEquals("file", Group.system("atari2600", listOf(fe("atari2600/Pitfall.a26", 4096))).single().kind)
    }

    /** Un PFS0 mínimo con estos nombres, como el comienzo de un .nsp. */
    private fun pfs0(vararg names: String): ByteArray {
        val strings = java.io.ByteArrayOutputStream()
        val offsets = names.map { n -> strings.size().also { strings.write(n.toByteArray()); strings.write(0) } }
        val b = java.nio.ByteBuffer.allocate(16 + names.size * 24 + strings.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("PFS0".toByteArray()).putInt(names.size).putInt(strings.size()).putInt(0)
        offsets.forEach { b.putLong(0).putLong(0).putInt(it).putInt(0) }
        b.put(strings.toByteArray())
        return b.array()
    }

    @Test fun switchIdFromNsp() {
        val head = pfs0("abc.cnmt.nca", "0100F2C0115B68000000000000000008.tik", "0100F2C0115B68000000000000000008.cert", "x.nca")
        assertEquals("0100F2C0115B6800", SwitchId.fromHeader(head))
        assertEquals(null, SwitchId.fromHeader("NCA3 no es un pfs0".toByteArray()))
        // Sin [TitleID] en el nombre, con el id leído del archivo: se agrupa igual.
        val g = Group.system("switch", listOf(fe("switch/Zelda TOTK.nsp", 100), fe("switch/Zelda update 1.2.1.nsp", 10)),
            switchIds = mapOf("switch/Zelda TOTK.nsp" to "0100F2C0115B6000", "switch/Zelda update 1.2.1.nsp" to "0100F2C0115B6800"))
        assertEquals(1, g.size); assertEquals(listOf("base", "update"), g[0].parts.map { it.kind })
    }

    @Test fun junkKinds() {
        assertEquals("Descarga a medias", Group.junkKind(".8686A228806624EF2D1C01D897EFC240DFA384980.parts"))
        assertEquals("Copia vieja de una partida", Group.junkKind("Spirit Tracks.SAV(1)(0)"))
        assertEquals("Opciones o trucos del emulador", Group.junkKind("Spirit Tracks.opts"))
        assertEquals(null, Group.junkKind("Spirit Tracks.sav"))
        assertEquals(null, Group.junkKind("gamelist.xml"))
    }

    @Test fun globs() {
        val r = Glob.toRegex("sdmc/Nintendo 3DS/*/*/title/00040000/*/data/**")
        assertTrue(r.matches("sdmc/Nintendo 3DS/abc/def/title/00040000/0012ab00/data/00000001/main"))
        assertFalse(r.matches("sdmc/Nintendo 3DS/abc/def/title/0004000e/0012ab00/content/x.app"))
        assertTrue(Glob.toRegex("**/*.sav").matches("Pokemon.sav"))
        assertTrue(Glob.toRegex("**/*.sav").matches("hacks/Pokemon.sav"))
        assertTrue(Glob.toRegex("GC/**").matches("GC/USA/Card A/x.gci"))
    }

    @Test fun tarRoundTrip() {
        val out = ByteArrayOutputStream()
        val long = "Wii/title/00010000/" + "a".repeat(120) + "/data/banner.bin"
        Tar.writeEntry(out, "GC/USA/MemoryCardA.USA.raw", 5, ByteArrayInputStream("hola!".toByteArray()))
        Tar.writeEntry(out, long, 3, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        Tar.finish(out)
        val got = mutableMapOf<String, ByteArray>()
        Tar.read(ByteArrayInputStream(out.toByteArray())) { name, _, input -> got[name] = input.readBytes() }
        assertEquals("hola!", String(got["GC/USA/MemoryCardA.USA.raw"]!!))
        assertNotNull(got[long]); assertEquals(3, got[long]!!.size)
    }
}
