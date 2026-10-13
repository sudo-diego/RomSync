package io.github.sudodiego.romsync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Para no escribir IP ni rutas: buscar el PC en la red (quién responde SFTP en el puerto 22) y, ya conectados,
 * buscar la carpeta de ROMs en sus discos. Sin Android: se prueba en la JVM.
 */
object PcFinder {
    data class Found(val ip: String, val windows: Boolean, val banner: String)
    data class RomsDir(val path: String, val platforms: Int)

    /** Prueba las 254 direcciones de la red de [myIp] (por ejemplo 192.168.1.x). Las de Windows primero. */
    suspend fun scan(myIp: String, port: Int = 22, timeoutMs: Int = 400): List<Found> = withContext(Dispatchers.IO) {
        val prefix = myIp.substringBeforeLast('.')
        val sem = Semaphore(64)
        coroutineScope {
            (1..254).map { "$prefix.$it" }.filter { it != myIp }.map { ip ->
                async { sem.withPermit { probe(ip, port, timeoutMs) } }
            }.awaitAll().filterNotNull().sortedByDescending { it.windows }
        }
    }

    /** ¿Hay un servidor SSH en esa dirección? Lee su saludo ("SSH-2.0-OpenSSH_for_Windows_9.5"). */
    fun probe(ip: String, port: Int = 22, timeoutMs: Int = 400): Found? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = 1500
            val buf = ByteArray(256)
            val n = s.getInputStream().read(buf)
            val banner = if (n > 0) String(buf, 0, n).lineSequence().first().trim() else ""
            if (!banner.startsWith("SSH-")) null else Found(ip, banner.contains("Windows", true), banner)
        }
    } catch (e: Exception) {
        null
    }

    private val platformNames by lazy { Systems.all.keys + setOf("3ds", "psp", "psx") }

    /** Cuántas carpetas de plataformas (psx, ps2, switch…) tiene una carpeta. */
    fun platformCount(c: SftpConn, dir: String) = c.list(dir).count { it.isDir && it.name.lowercase() in platformNames }

    /** Los discos del PC: Windows los muestra en "/" como "C:", "D:"…; si no, se prueban las letras. */
    fun drives(c: SftpConn): List<String> {
        val listed = runCatching { c.list("/") }.getOrDefault(emptyList())
            .filter { it.isDir && Regex("^[A-Za-z]:$").matches(it.name) }.map { "/${it.name}" }
        if (listed.isNotEmpty()) return listed
        val probed = ('C'..'Z').map { "/$it:" }.filter { runCatching { c.stat("$it/")?.isDir == true }.getOrDefault(false) }
        // No es Windows (por ejemplo un Linux con SFTP): se busca desde la carpeta de inicio.
        return probed.ifEmpty { listOf(runCatching { c.ch.home }.getOrDefault("/")) }
    }

    private val skip = setOf("windows", "program files", "program files (x86)", "programdata", "\$recycle.bin",
        "system volume information", "appdata", "recovery", "perflogs", "msocache", "node_modules", ".git")

    /**
     * Busca carpetas con al menos dos carpetas de plataformas adentro (la de ROMs al estilo ES-DE), hasta [depth]
     * niveles bajo cada disco. Primero las que se llaman "ROMs".
     */
    fun findRoms(c: SftpConn, depth: Int = 3, roots: List<String> = drives(c)): List<RomsDir> {
        val out = ArrayList<RomsDir>()
        fun walk(dir: String, level: Int) {
            val entries = runCatching { c.list(dir) }.getOrDefault(emptyList()).filter { it.isDir }
            val platforms = entries.count { it.name.lowercase() in platformNames }
            if (platforms >= 2) {
                out += RomsDir(dir, platforms)
                return
            }
            if (level >= depth) return
            for (e in entries) {
                val n = e.name.lowercase()
                if (n.startsWith(".") || n in skip) continue
                walk(SftpConn.join(dir, e.name), level + 1)
            }
        }
        for (r in roots) walk(r.trimEnd('/').ifEmpty { "/" }, 0)
        return out.sortedWith(compareByDescending<RomsDir> { it.path.substringAfterLast('/').equals("roms", true) }
            .thenByDescending { it.platforms })
    }

    /** "/D:/Emulacion/ROMs" → "D:\Emulacion\ROMs" (como lo escribe Windows, para mostrar y guardar). */
    fun windowsPath(p: String): String =
        if (Regex("^/[A-Za-z]:.*").matches(p)) p.removePrefix("/").replace('/', '\\') else p
}
