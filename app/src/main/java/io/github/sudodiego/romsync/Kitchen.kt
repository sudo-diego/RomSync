package io.github.sudodiego.romsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * RomSync Web (antes "Cocina"): la web de la tablet, como Nonna's Kitchen de Cannoli. Se enciende desde la app, muestra un QR, la
 * dirección y un PIN de 6 caracteres que cambia cada vez. Desde el navegador del PC (o del teléfono) se administra
 * la tablet: plataformas y juegos, BIOS, partidas y herramientas (archivos e instalar APK).
 */
object Kitchen {
    const val PORT = 8642
    var running by mutableStateOf(false)
        private set
    var pin by mutableStateOf("")
        private set
    var url by mutableStateOf("")
        private set
    /** IP del último equipo que abrió la web (casi siempre el PC): Ajustes la ofrece para conectarse. */
    var lastClientIp by mutableStateOf("")
        private set

    private var server: Server? = null
    private val sessions = HashSet<String>()
    private val rnd = SecureRandom()
    private lateinit var app: Context

    fun start(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, KitchenService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, KitchenService::class.java))
    }

    internal fun startServer(ctx: Context) {
        if (server != null) return
        app = ctx.applicationContext
        // Sin letras ni números que se confundan (0/O, 1/I).
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        pin = (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        synchronized(sessions) { sessions.clear() }
        val s = Server()
        s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = s
        url = "http://${lanAddress() ?: "IP-de-la-tablet"}:$PORT"
        running = true
    }

    internal fun stopServer() {
        server?.stop()
        server = null
        running = false
        pin = ""
    }

    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private class Server : NanoHTTPD(PORT) {
        override fun serve(session: IHTTPSession): Response = try {
            route(session)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- Utilidades ----

    private fun json(status: NanoHTTPD.Response.IStatus, body: Any): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun ok(body: Any = JSONObject().put("ok", true)) = json(NanoHTTPD.Response.Status.OK, body)
    private fun err(code: NanoHTTPD.Response.Status, msg: String) = json(code, JSONObject().put("error", msg))

    private fun param(s: NanoHTTPD.IHTTPSession, k: String) = s.parameters[k]?.firstOrNull().orEmpty()

    private fun bodyText(s: NanoHTTPD.IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = s.inputStream.read(buf, got, len - got)
            if (n < 0) break
            got += n
        }
        return String(buf, 0, got)
    }

    private fun bodyJson(s: NanoHTTPD.IHTTPSession) = runCatching { JSONObject(bodyText(s)) }.getOrDefault(JSONObject())

    /** Guarda el cuerpo de la petición en un archivo (a .part y al final se renombra). Devuelve los bytes. */
    private fun bodyToFile(s: NanoHTTPD.IHTTPSession, dest: File): Long {
        val len = s.headers["content-length"]?.toLongOrNull() ?: throw IOException("Falta el tamaño")
        val free = Local.space(dest.parent ?: Prefs.storageRoot).free
        if (len > free) throw IOException("No hay espacio en la tablet: faltan ${formatSize(len - free)}")
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        part.outputStream().buffered(1 shl 17).use { out ->
            val buf = ByteArray(1 shl 17)
            var left = len
            while (left > 0) {
                val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
            if (left > 0) {
                out.close(); part.delete()
                throw IOException("La subida se cortó")
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("No se pudo guardar ${dest.name}")
        return len
    }

    private fun mime(name: String): String = when (Group.ext(name)) {
        ".html" -> "text/html; charset=utf-8"
        ".js" -> "text/javascript; charset=utf-8"
        ".css" -> "text/css; charset=utf-8"
        ".svg" -> "image/svg+xml"
        ".json" -> "application/json"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(Group.ext(name).removePrefix(".")) ?: "application/octet-stream"
    }

    private fun fileResponse(f: File, download: Boolean = true): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime(f.name), FileInputStream(f), f.length()).apply {
            if (download) addHeader("Content-Disposition", "attachment; filename=\"${f.name.replace("\"", "")}\"")
        }

    /** Ruta relativa segura dentro de una carpeta (sin salir de ella). */
    private fun inside(root: String, rel: String): File {
        val base = File(root).canonicalFile
        val f = File(base, rel.replace('\\', '/').trimStart('/')).canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw IOException("Ruta inválida")
        return f
    }

    private fun authed(s: NanoHTTPD.IHTTPSession): Boolean {
        val token = s.cookies.read("cocina") ?: s.headers["x-cocina"]
        return token != null && synchronized(sessions) { token in sessions }
    }

    // ---- Rutas ----

    private fun route(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = s.uri
        val m = s.method
        if (!uri.startsWith("/api/")) return asset(uri)
        if (uri == "/api/login") {
            val got = bodyJson(s).optString("pin").trim().uppercase()
            if (got != pin) return err(NanoHTTPD.Response.Status.UNAUTHORIZED, "PIN incorrecto")
            val token = (1..24).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
            synchronized(sessions) { sessions += token }
            return ok().apply { addHeader("Set-Cookie", "cocina=$token; Path=/; HttpOnly; SameSite=Strict") }
        }
        if (!authed(s)) return err(NanoHTTPD.Response.Status.UNAUTHORIZED, "Escribe el PIN que aparece en la tablet")
        s.remoteIpAddress?.takeIf { it.count { c -> c == '.' } == 3 && !it.startsWith("127.") }?.let { if (it != lastClientIp) lastClientIp = it }

        val parts = uri.removePrefix("/api/").split('/')
        return when (parts[0]) {
            "state" -> ok(state())
            "games" -> ok(games())
            "refresh" -> { runBlocking { Repo.refresh(pc = param(s, "pc") != "0") }; ok(state()) }
            "art" -> art(s, parts)
            "art-search" -> ok(JSONArray().apply {
                runBlocking { Art.search(q = param(s, "q")) }.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) }
            })
            "install" -> install(bodyJson(s))
            "delete" -> {
                val g = Repo.game(bodyJson(s).optString("id")) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
                val freed = runBlocking { Repo.delete(g) }
                ok(JSONObject().put("freed", freed))
            }
            "rename" -> {
                val b = bodyJson(s)
                val g = Repo.game(b.optString("id")) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
                Repo.rename(g, b.optString("name"))
                ok()
            }
            "delete-pc" -> {
                val g = Repo.game(bodyJson(s).optString("id")) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
                val freed = runBlocking { Repo.deleteFromPc(g) }
                ok(JSONObject().put("freed", freed))
            }
            "upload" -> {
                val bios = param(s, "bios") == "1"
                val rel = param(s, "path")
                if (Group.isIgnored(rel.substringAfterLast('/')) && !bios) return err(NanoHTTPD.Response.Status.BAD_REQUEST, "Ese archivo no es un juego")
                val dest = inside(if (bios) Prefs.biosDir else Prefs.romsDir, rel)
                bodyToFile(s, dest)
                runBlocking { Repo.rescanLocal() }
                ok()
            }
            "download" -> {
                val bios = param(s, "bios") == "1"
                val f = inside(if (bios) Prefs.biosDir else Prefs.romsDir, param(s, "path"))
                if (!f.isFile) err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe") else fileResponse(f)
            }
            "bios" -> bios(s, m)
            "downloads" -> downloads(s, parts)
            "emulators" -> emulators(s, parts)
            "backups" -> backups(s, parts)
            "files" -> files(s, m, parts)
            "apk" -> apk(s)
            else -> err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
        }
    }

    private fun asset(uri: String): NanoHTTPD.Response {
        val path = uri.trimStart('/').ifEmpty { "index.html" }
        if (path.contains("..")) return err(NanoHTTPD.Response.Status.BAD_REQUEST, "Ruta inválida")
        return try {
            val input = app.assets.open("web/$path")
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime(path), input).apply {
                if (path.endsWith(".bat")) addHeader("Content-Disposition", "attachment; filename=\"$path\"")
            }
        } catch (e: IOException) {
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime("index.html"), app.assets.open("web/index.html"))
        }
    }

    private fun state(): JSONObject {
        val conn = Repo.conn
        return JSONObject()
            .put("device", Prefs.deviceName)
            .put("model", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            .put("free", Repo.space.free).put("total", Repo.space.total)
            .put("romsDir", Prefs.romsDir).put("biosDir", Prefs.biosDir)
            .put("pcConfigured", Prefs.configured).put("pcRoms", Prefs.pcRoms).put("clientIp", lastClientIp)
            .put("pc", when (conn) {
                is Conn.Online -> "online"
                Conn.Connecting -> "connecting"
                is Conn.Offline -> "offline"
                Conn.Idle -> "idle"
            })
            .put("pcError", (conn as? Conn.Offline)?.message ?: "")
            .put("scanning", Repo.scanningPc)
            .put("hasArtKey", Prefs.sgdbKey.isNotBlank())
            .put("shizuku", Shell.state.name)
            .put("systems", JSONObject().apply { Systems.all.values.forEach { put(it.id, JSONObject().put("name", it.name).put("short", it.short).put("color", "#%06x".format(it.color and 0xFFFFFF))) } })
    }

    private fun games(): JSONObject {
        val arr = JSONArray()
        for (g in Repo.allGames()) {
            val w = Repo.where(g)
            val dl = DownloadService.items.firstOrNull { it.id == g.id && it.status != DlStatus.DONE }
            arr.put(Json.gameJson(g).put("presence", w.presence.name).put("localSize", w.localSize).put("differs", w.differs)
                .put("missing", Json.filesJson(w.missing))
                .put("download", dl?.let { JSONObject().put("status", it.status.name).put("done", it.done).put("total", it.total) }))
        }
        val lib = Repo.library
        return JSONObject().put("games", arr)
            .put("pcSystems", JSONArray().apply { lib?.systems?.forEach { put(JSONObject().put("id", it.id).put("count", it.count).put("size", it.size)) } })
            .put("biosMissing", Json.filesJson(Repo.biosMissing))
            .put("bios", Json.filesJson(Repo.localBios))
    }

    private fun art(s: NanoHTTPD.IHTTPSession, parts: List<String>): NanoHTTPD.Response {
        if (parts.size < 3 || parts[2] !in Art.kinds) return err(NanoHTTPD.Response.Status.BAD_REQUEST, "Ruta inválida")
        val g = Repo.game(parts[1]) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
        val kind = parts[2]
        return when (s.method) {
            NanoHTTPD.Method.POST, NanoHTTPD.Method.PUT -> {
                if (param(s, "sgdb").isNotEmpty()) Art.choose(g.id, param(s, "sgdb").toInt())
                else if (param(s, "clear") == "1") Art.clear(g.id)
                else {
                    val tmp = File(app.cacheDir, "art-upload")
                    bodyToFile(s, tmp)
                    Art.setCustom(g.id, kind, tmp.readBytes())
                    tmp.delete()
                }
                ok()
            }
            else -> {
                val f = runBlocking { Art.get(g, kind) }
                if (f == null) NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", "")
                else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/*", FileInputStream(f), f.length())
                    .apply { addHeader("Cache-Control", "no-cache") }
            }
        }
    }

    private fun install(body: JSONObject): NanoHTTPD.Response {
        val g = Repo.game(body.optString("id")) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
        val w = Repo.where(g)
        val wanted = body.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val files = if (wanted != null && g.parts.isNotEmpty()) g.parts.filter { it.titleId in wanted }.flatMap { it.files }
            .filter { !Repo.has(it.path) } else w.missing
        if (files.isEmpty()) return err(NanoHTTPD.Response.Status.BAD_REQUEST, "Ya está en la tablet")
        val problem = startDownload(app, g, files)
        return if (problem != null) err(NanoHTTPD.Response.Status.BAD_REQUEST, problem) else ok()
    }

    private fun bios(s: NanoHTTPD.IHTTPSession, m: NanoHTTPD.Method): NanoHTTPD.Response = when (m) {
        NanoHTTPD.Method.DELETE -> {
            inside(Prefs.biosDir, param(s, "path")).delete()
            runBlocking { Repo.rescanLocal() }
            ok()
        }
        NanoHTTPD.Method.POST -> {
            val missing = Repo.biosMissing
            if (missing.isEmpty()) err(NanoHTTPD.Response.Status.BAD_REQUEST, "No falta ninguna BIOS")
            else { DownloadService.enqueue(app, Download("bios", "BIOS", "bios", missing, bios = true)); ok() }
        }
        else -> ok(JSONObject().put("bios", Json.filesJson(Repo.localBios)).put("missing", Json.filesJson(Repo.biosMissing)))
    }

    private fun downloads(s: NanoHTTPD.IHTTPSession, parts: List<String>): NanoHTTPD.Response {
        if (parts.size >= 3) {
            val d = DownloadService.items.firstOrNull { it.id == parts[1] } ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
            when (parts[2]) {
                "pause" -> DownloadService.pause(d)
                "resume" -> DownloadService.resume(app, d)
                "cancel" -> DownloadService.cancel(d)
            }
        }
        return ok(JSONArray().apply {
            DownloadService.items.forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("system", it.system).put("status", it.status.name)
                    .put("done", it.done).put("total", it.total).put("speed", it.speed).put("error", it.error ?: ""))
            }
        })
    }

    private fun emulators(s: NanoHTTPD.IHTTPSession, parts: List<String>): NanoHTTPD.Response {
        if (parts.size >= 3) {
            val emu = Emulators.byId(parts[1]) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
            return when (parts[2]) {
                "backup" -> when (val r = runBlocking { Saves.backup(app, emu, auto = false, force = true) }) {
                    is Saves.Result.Done -> ok(JSONObject().put("message", "Respaldado: ${r.files} archivos"))
                    Saves.Result.Unchanged -> ok(JSONObject().put("message", "Sin cambios"))
                    Saves.Result.Empty -> ok(JSONObject().put("message", "No hay partidas"))
                    is Saves.Result.Failed -> err(NanoHTTPD.Response.Status.BAD_REQUEST, r.message)
                }
                "export" -> exportZip(emu)
                "restore" -> {
                    val zip = File(app.cacheDir, "web-restore.zip")
                    val id = param(s, "id")
                    val e = runBlocking {
                        if (id.isNotEmpty()) Saves.restore(app, emu, id)
                        else { bodyToFile(s, zip); Saves.restore(app, emu, "", zipFromWeb = zip) }
                    }
                    zip.delete()
                    if (e != null) err(NanoHTTPD.Response.Status.BAD_REQUEST, e) else ok()
                }
                else -> err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
            }
        }
        val arr = JSONArray()
        for (emu in Emulators.all) {
            val st = runBlocking { Saves.inspect(app, emu) }
            arr.put(JSONObject().put("id", emu.id).put("name", emu.name).put("installed", st.installed)
                .put("supported", st.supported).put("what", emu.what).put("note", emu.note).put("exportHint", emu.exportHint)
                .put("files", st.files).put("bytes", st.bytes).put("needsShell", st.needsShell).put("error", st.error ?: "")
                .put("lastBackup", Prefs.lastBackupAt(emu.id))
                .put("sources", JSONArray().apply { st.sources.forEach { put(it.root) } }))
        }
        return ok(arr)
    }

    /** Las partidas de un emulador, comprimidas al vuelo y bajadas directo al navegador. */
    private fun exportZip(emu: Emulator): NanoHTTPD.Response {
        val pkg = Emulators.installedPackage(app, emu) ?: return err(NanoHTTPD.Response.Status.NOT_FOUND, "No está instalado")
        val sources = runBlocking { Emulators.sources(emu, pkg) }
        val pin = PipedInputStream(1 shl 16)
        val pout = PipedOutputStream(pin)
        Thread {
            runCatching {
                ZipOutputStream(pout).use { z ->
                    runBlocking {
                        for (src in sources) {
                            val files = Storage.list(src)
                            Storage.read(src, files) { rel, _, input ->
                                z.putNextEntry(ZipEntry("${src.label}/$rel"))
                                input.copyTo(z)
                                z.closeEntry()
                            }
                        }
                    }
                }
            }.onFailure { runCatching { pout.close() } }
        }.start()
        val name = "${emu.id}_${java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.ROOT).format(java.util.Date())}.zip"
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/zip", pin).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$name\"")
        }
    }

    private fun backups(s: NanoHTTPD.IHTTPSession, parts: List<String>): NanoHTTPD.Response {
        if (!Prefs.configured) return ok(JSONArray())
        if (parts.size >= 3) {
            val emu = parts[1]
            val id = parts[2].removeSuffix(".zip")
            return when (param(s, "do")) {
                "keep" -> { Pc.use { Backups.setKeep(it, emu, id, param(s, "v") == "1") }; ok() }
                "delete" -> { Pc.use { Backups.delete(it, emu, id) }; ok() }
                else -> {
                    val tmp = File(app.cacheDir, "web-backup.zip")
                    Pc.use { Backups.download(it, emu, id, tmp) }
                    fileResponse(tmp).also { tmp.deleteOnExit() }.apply {
                        addHeader("Content-Disposition", "attachment; filename=\"${emu}_$id.zip\"")
                    }
                }
            }
        }
        val list = Pc.use { Backups.listAll(it) }
        return ok(JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("emulator", it.emulator).put("at", it.at).put("size", it.size)
                    .put("files", it.files).put("auto", it.auto).put("keep", it.keep))
            }
        })
    }

    // ---- Herramientas: archivos y APK ----

    private fun files(s: NanoHTTPD.IHTTPSession, m: NanoHTTPD.Method, parts: List<String>): NanoHTTPD.Response {
        val root = Prefs.storageRoot
        val f = inside(root, param(s, "path"))
        return when {
            parts.getOrNull(1) == "download" -> if (f.isFile) fileResponse(f) else err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
            parts.getOrNull(1) == "mkdir" -> { f.mkdirs(); ok() }
            m == NanoHTTPD.Method.PUT -> { bodyToFile(s, f); ok() }
            m == NanoHTTPD.Method.DELETE -> {
                if (f.path == File(root).canonicalPath) return err(NanoHTTPD.Response.Status.BAD_REQUEST, "No")
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                ok()
            }
            else -> {
                if (!f.isDirectory) return err(NanoHTTPD.Response.Status.NOT_FOUND, "No existe")
                val arr = JSONArray()
                f.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
                    arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", if (it.isFile) it.length() else 0)
                        .put("mtime", it.lastModified()))
                }
                ok(JSONObject().put("path", f.path.removePrefix(File(root).canonicalPath).trimStart('/')).put("entries", arr)
                    .put("free", Local.space(f.path).free))
            }
        }
    }

    /** Instala un APK subido desde el navegador: Android pide confirmar en la tablet. */
    private fun apk(s: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val tmp = File(app.cacheDir, "subido.apk")
        bodyToFile(s, tmp)
        val pi = app.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { session ->
            session.openWrite("app.apk", 0, tmp.length()).use { out -> tmp.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            val intent = Intent(app, ApkResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
        tmp.delete()
        return ok(JSONObject().put("message", "Confirma la instalación en la tablet"))
    }
}

/** Recibe el resultado de instalar un APK: si Android pide confirmación, abre su diálogo. */
class ApkResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Mantiene la web encendida con la pantalla apagada (servicio en primer plano). */
class KitchenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cocina", "RomSync Web", NotificationManager.IMPORTANCE_LOW))
        runCatching { Kitchen.startServer(this) }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", "kitchen"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "cocina")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Web encendida · PIN ${Kitchen.pin}")
            .setContentText(Kitchen.url)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Kitchen.stopServer()
        super.onDestroy()
    }
}
