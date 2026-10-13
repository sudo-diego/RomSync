package io.github.sudodiego.romsync

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Conexión al PC por SFTP, como hacía Nixel: el servidor OpenSSH que trae Windows (Características opcionales →
 * Servidor OpenSSH), con el usuario y la contraseña de Windows. Sin programas extra en el PC.
 *
 * Rutas: en Windows se escriben "D:\Emulación\ROMs" y por SFTP son "/D:/Emulación/ROMs" ([remotePath]).
 */
data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val password: String,
    /** Huella del PC aceptada la primera vez (vacía = todavía no). */
    val fingerprint: String = "",
)

class HostKeyChanged(val fingerprint: String) :
    IOException("La identidad del PC cambió. Si reinstalaste Windows u OpenSSH, acéptala de nuevo en Ajustes.")

class SftpConn private constructor(private val session: Session, val ch: ChannelSftp, val fingerprint: String) : Closeable {
    data class Entry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

    companion object {
        fun connect(cfg: SftpConfig, timeoutMs: Int = 8000): SftpConn {
            val jsch = JSch()
            val s = jsch.getSession(cfg.user, cfg.host, cfg.port)
            s.setPassword(cfg.password)
            // La huella se revisa a mano (abajo): se acepta la primera y después tiene que ser la misma.
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            s.setConfig("compression.s2c", "none")
            s.setConfig("compression.c2s", "none")
            try {
                s.connect(timeoutMs)
            } catch (e: JSchException) {
                throw IOException(explain(e), e)
            }
            val fp = s.hostKey.getFingerPrint(jsch)
            if (cfg.fingerprint.isNotEmpty() && cfg.fingerprint != fp) {
                s.disconnect()
                throw HostKeyChanged(fp)
            }
            val ch = s.openChannel("sftp") as ChannelSftp
            try {
                ch.connect(timeoutMs)
            } catch (e: JSchException) {
                s.disconnect()
                throw IOException("El PC no ofrece SFTP: ${e.message}", e)
            }
            return SftpConn(s, ch, fp)
        }

        private fun explain(e: JSchException): String {
            val m = e.message.orEmpty()
            return when {
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> "Usuario o contraseña incorrectos"
                m.contains("timeout", true) || m.contains("timed out", true) -> "El PC no responde (¿encendido y en la misma red?)"
                m.contains("refused", true) -> "El PC rechazó la conexión (¿está activo el servidor OpenSSH?)"
                m.contains("UnknownHost", true) -> "No se encuentra el PC en la red"
                else -> "No se pudo conectar: $m"
            }
        }

        /** "D:\Emulación\ROMs" → "/D:/Emulación/ROMs"; "/home/x/roms" queda igual. */
        fun remotePath(p: String): String {
            var s = p.trim().replace('\\', '/').trimEnd('/')
            if (Regex("^[A-Za-z]:(/.*)?$").matches(s)) s = "/$s"
            if (Regex("^/[A-Za-z]:$").matches(s)) s += "/"
            return s
        }

        fun join(dir: String, rel: String) = if (rel.isEmpty()) dir else dir.trimEnd('/') + "/" + rel.trimStart('/')
    }

    override fun close() {
        runCatching { ch.disconnect() }
        runCatching { session.disconnect() }
    }

    fun exists(path: String): Boolean = stat(path) != null

    fun stat(path: String): SftpATTRS? = try {
        ch.stat(path)
    } catch (e: SftpException) {
        if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw IOException("No se pudo leer $path: ${e.message}", e)
    }

    fun list(dir: String): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            @Suppress("UNCHECKED_CAST")
            val v = ch.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
            for (e in v) {
                if (e.filename == "." || e.filename == "..") continue
                out += Entry(e.filename, e.attrs.isDir, e.attrs.size, e.attrs.mTime.toLong())
            }
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return emptyList()
            throw IOException("No se pudo leer $dir: ${e.message}", e)
        }
        return out
    }

    /** Todos los archivos bajo [dir], con rutas relativas a [root]; sin carpetas de medios ni archivos que no son juegos. */
    fun walk(root: String, dir: String = root, skipMedia: Boolean = true, out: MutableList<FileEntry> = ArrayList()): MutableList<FileEntry> {
        for (e in list(dir)) {
            val full = join(dir, e.name)
            if (e.isDir) {
                if (!skipMedia || e.name.lowercase() !in Group.ignoredDirs) walk(root, full, skipMedia, out)
            } else if (!Group.isIgnored(e.name)) {
                out += FileEntry(full.removePrefix(root.trimEnd('/') + "/"), e.size)
            }
        }
        return out
    }

    fun readText(path: String, max: Int = 1 shl 20): String? = try {
        ch.get(path).use { input -> String(input.readNBytesCompat(max)) }
    } catch (e: SftpException) {
        null
    }

    /** Lee desde [offset] (para retomar una descarga). Hay que cerrar el stream. */
    fun open(path: String, offset: Long = 0): InputStream = try {
        ch.get(path, null, offset)
    } catch (e: SftpException) {
        throw IOException(if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) "El archivo ya no está en el PC" else "No se pudo leer: ${e.message}", e)
    }

    fun mkdirs(path: String) {
        val parts = path.trimEnd('/').split('/')
        var cur = ""
        for ((i, p) in parts.withIndex()) {
            cur = if (i == 0) p else "$cur/$p"
            if (cur.isEmpty() || Regex("^/?[A-Za-z]:$").matches(cur)) continue
            if (stat(cur) == null) try {
                ch.mkdir(cur)
            } catch (e: SftpException) {
                if (stat(cur) == null) throw IOException("No se pudo crear $cur: ${e.message}", e)
            }
        }
    }

    /** Escribe un archivo: primero a .part y al final se renombra (un corte no deja un archivo roto). */
    fun upload(path: String, write: (OutputStream) -> Unit) {
        mkdirs(path.substringBeforeLast('/'))
        val tmp = "$path.part"
        try {
            ch.put(tmp, ChannelSftp.OVERWRITE).use(write)
            if (stat(path) != null) ch.rm(path)
            ch.rename(tmp, path)
        } catch (e: SftpException) {
            runCatching { ch.rm(tmp) }
            throw IOException("No se pudo guardar en el PC: ${e.message}", e)
        }
    }

    /** Borra una carpeta vacía. False si no se pudo (por ejemplo, no está vacía). */
    fun rmdir(path: String): Boolean = try {
        ch.rmdir(path); true
    } catch (e: SftpException) {
        false
    }

    fun delete(path: String) = try {
        ch.rm(path)
    } catch (e: SftpException) {
        if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw IOException("No se pudo borrar $path: ${e.message}", e) else Unit
    }
}

private fun InputStream.readNBytesCompat(max: Int): ByteArray {
    val buf = java.io.ByteArrayOutputStream()
    val b = ByteArray(8192)
    var total = 0
    while (total < max) {
        val n = read(b, 0, minOf(b.size, max - total))
        if (n < 0) break
        buf.write(b, 0, n)
        total += n
    }
    return buf.toByteArray()
}
