package io.github.sudodiego.romsync

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** SFTP de punta a punta contra un servidor SSH de prueba (Apache MINA), como si fuera el OpenSSH de Windows. */
class SftpTest {
    private lateinit var server: SshServer
    private lateinit var root: File

    @Before fun start() {
        root = Files.createTempDirectory("pc").toFile()
        File(root, "ROMs/psx").mkdirs()
        File(root, "ROMs/psx/Crash (USA).chd").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(root, "ROMs/psx/Crash (USA).sav").writeText("partida")
        File(root, "ROMs/psx/media/covers").mkdirs()
        File(root, "ROMs/psx/media/covers/x.png").writeText("png")
        File(root, "ROMs/bios").mkdirs()
        File(root, "ROMs/bios/scph5501.bin").writeText("bios")
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("host", ".ser"))
            setPasswordAuthenticator { u, p, _ -> u == "diego" && p == "clave" }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            start()
        }
    }

    @After fun stop() { server.stop(true); root.deleteRecursively() }

    private fun cfg(pass: String = "clave", fp: String = "") = SftpConfig("127.0.0.1", server.port, "diego", pass, fp)

    @Test fun walkDownloadResumeUpload() {
        SftpConn.connect(cfg()).use { c ->
            val files = c.walk("/ROMs")
            assertEquals(setOf("psx/Crash (USA).chd", "bios/scph5501.bin"), files.map { it.path }.toSet())
            // Retomar desde la mitad.
            val half = c.open("/ROMs/psx/Crash (USA).chd", 150_000).use { it.readBytes() }
            assertEquals(150_000, half.size)
            assertEquals(((150_000) % 251).toByte(), half[0])
            c.upload("/RomSync/respaldos/tablet/dolphin/1.zip") { it.write("zip".toByteArray()) }
            assertEquals("zip", File(root, "RomSync/respaldos/tablet/dolphin/1.zip").readText())
            assertTrue(c.list("/RomSync/respaldos/tablet/dolphin").none { it.name.endsWith(".part") })
            c.delete("/RomSync/respaldos/tablet/dolphin/1.zip")
            assertEquals(0, c.list("/RomSync/respaldos/tablet/dolphin").size)
        }
    }

    @Test fun wrongPasswordAndHostKey() {
        val e = runCatching { SftpConn.connect(cfg("mala")) }.exceptionOrNull()
        assertEquals("Usuario o contraseña incorrectos", e?.message)
        val fp = SftpConn.connect(cfg()).use { it.fingerprint }
        SftpConn.connect(cfg(fp = fp)).close()
        assertTrue(runCatching { SftpConn.connect(cfg(fp = "otra")) }.exceptionOrNull() is HostKeyChanged)
    }

    @Test fun findPcAndRoms() {
        File(root, "ROMs/snes").mkdirs()
        File(root, "Juegos/Emulacion/ROMs/ps2").mkdirs()
        File(root, "Juegos/Emulacion/ROMs/switch").mkdirs()
        File(root, "Juegos/Otra/fotos").mkdirs()
        val found = PcFinder.probe("127.0.0.1", server.port)
        assertTrue(found != null && found.banner.startsWith("SSH-"))
        assertEquals(null, PcFinder.probe("127.0.0.1", 1, 200))
        SftpConn.connect(cfg()).use { c ->
            val dirs = PcFinder.findRoms(c, roots = listOf("/"))
            assertEquals(setOf("/ROMs", "/Juegos/Emulacion/ROMs"), dirs.map { it.path }.toSet())
            assertTrue(dirs.none { it.path.contains("Otra") })
        }
        assertEquals("D:\\Emulacion\\ROMs", PcFinder.windowsPath("/D:/Emulacion/ROMs"))
    }

    @Test fun windowsPaths() {
        assertEquals("/D:/Emulacion/ROMs", SftpConn.remotePath("D:\\Emulacion\\ROMs\\"))
        assertEquals("/D:/", SftpConn.remotePath("D:"))
        assertEquals("/home/diego/roms", SftpConn.remotePath("/home/diego/roms/"))
    }
}
