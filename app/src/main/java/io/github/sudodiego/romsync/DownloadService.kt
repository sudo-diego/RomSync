package io.github.sudodiego.romsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class DlStatus { QUEUED, RUNNING, PAUSED, FAILED, DONE }

/** Una descarga: los archivos que faltan de un juego (o BIOS). El avance se lee desde Compose. */
class Download(
    val id: String,
    val title: String,
    val system: String,
    val files: List<FileEntry>,
    val bios: Boolean = false,
) {
    val total = files.sumOf { it.size }
    var status by mutableStateOf(DlStatus.QUEUED)
    var done by mutableLongStateOf(0L)
    var speed by mutableLongStateOf(0L) // bytes/s
    var error by mutableStateOf<String?>(null)
    var current by mutableStateOf("")

    fun toJson() = JSONObject().put("id", id).put("title", title).put("system", system).put("bios", bios)
        .put("files", Json.filesJson(files)).put("status", status.name)

    companion object {
        fun fromJson(o: JSONObject) = Download(o.getString("id"), o.optString("title"), o.optString("system"),
            Json.files(o.optJSONArray("files")), o.optBoolean("bios")).apply {
            status = runCatching { DlStatus.valueOf(o.optString("status")) }.getOrDefault(DlStatus.QUEUED)
            if (status == DlStatus.RUNNING) status = DlStatus.QUEUED
        }
    }
}

/**
 * Descarga juegos del PC en primer plano (sigue con la pantalla apagada). Uno a la vez, archivo por archivo,
 * a un .part que se retoma donde quedó (Range) y se renombra al terminar.
 */
class DownloadService : Service() {
    companion object {
        val items = mutableStateListOf<Download>()
        private const val CHANNEL = "descargas"
        private const val NOTIF = 1
        private var file: File? = null

        fun load(ctx: Context) {
            if (file != null) return
            file = File(ctx.filesDir, "downloads.json")
            runCatching {
                val a = JSONArray(file!!.readText())
                items.clear()
                for (i in 0 until a.length()) items += Download.fromJson(a.getJSONObject(i))
            }
            Thread { runCatching { cleanOrphans() } }.start()
        }

        fun save() {
            val a = JSONArray()
            items.filter { it.status != DlStatus.DONE }.forEach { a.put(it.toJson()) }
            runCatching { file?.writeText(a.toString()) }
        }

        fun enqueue(ctx: Context, d: Download) {
            load(ctx)
            items.removeAll { it.id == d.id && it.status != DlStatus.RUNNING }
            if (items.any { it.id == d.id }) return
            items += d
            save()
            start(ctx)
        }

        fun start(ctx: Context) {
            if (items.none { it.status == DlStatus.QUEUED }) return
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        }

        fun pause(d: Download) {
            if (d.status == DlStatus.QUEUED || d.status == DlStatus.RUNNING) d.status = DlStatus.PAUSED
            save()
        }

        fun resume(ctx: Context, d: Download) {
            d.status = DlStatus.QUEUED
            d.error = null
            save()
            start(ctx)
        }

        /**
         * Cancela y no deja nada a medias: borra los .part y también los archivos que esta descarga alcanzó a completar
         * (una descarga solo incluye archivos que faltaban, así que no se toca nada que ya estuviera).
         */
        fun cancel(d: Download) {
            d.status = DlStatus.FAILED
            items.remove(d)
            d.files.forEach { f ->
                val dest = if (d.bios) Local.biosFile(f.path) else Local.romFile(f.path)
                File(dest.path + ".part").delete()
                if (d.bios && dest.isFile && dest.length() == f.size) dest.delete()
            }
            if (!d.bios) Local.deleteFiles(d.files.filter { f -> Local.romFile(f.path).let { it.isFile && it.length() == f.size } })
            save()
            appScope.launch { runCatching { Repo.rescanLocal() } }
        }

        /** .part que no son de ninguna descarga pendiente (una descarga que se cortó o se quitó): se borran al abrir. */
        private fun cleanOrphans() {
            val keep = items.flatMap { d -> d.files.map { f -> (if (d.bios) Local.biosFile(f.path) else Local.romFile(f.path)).path + ".part" } }.toHashSet()
            for (root in listOf(File(Prefs.romsDir), File(Prefs.biosDir))) {
                if (!root.isDirectory) continue
                root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
                    .filter { it.isFile && it.name.endsWith(".part") && it.path !in keep }
                    .forEach { it.delete() }
            }
        }

        fun clearDone() {
            items.removeAll { it.status == DlStatus.DONE }
            save()
        }

        /** Bytes que todavía faltan de las descargas pendientes (para el cálculo de espacio). */
        fun pendingBytes() = items.filter { it.status != DlStatus.DONE && it.status != DlStatus.FAILED }.sumOf { it.total - it.done }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        load(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF, notification("Preparando…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RomSync:descargas").apply { acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RomSync:descargas").apply { acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        wake = null
        wifi = null
    }

    private fun notification(text: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", "downloads"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RomSync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total == 0L)
            .build()
    }

    private fun notify(text: String, done: Long, total: Long) =
        getSystemService(NotificationManager::class.java).notify(NOTIF, notification(text, done, total))

    private suspend fun runQueue() {
        acquireLocks()
        var finished = 0
        try {
            while (scope.isActive) {
                val d = items.firstOrNull { it.status == DlStatus.QUEUED } ?: break
                d.status = DlStatus.RUNNING
                save()
                try {
                    download(d)
                    if (d.status == DlStatus.RUNNING) {
                        d.status = DlStatus.DONE
                        finished++
                        if (!d.bios) { Notice.show(d.id, d.title, "Descarga terminada"); DownloadArt.wrap(d.id) }
                        // Las terminadas se van solas de la lista (ya avisó el aviso de arriba).
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (d.status == DlStatus.DONE) { items.remove(d); save() }
                        }, 12000) // tiempo para ver el aviso y, en estilo 3DS, el regalo
                    }
                } catch (e: Exception) {
                    if (d.status == DlStatus.RUNNING) {
                        d.status = DlStatus.FAILED
                        d.error = e.message ?: e.javaClass.simpleName
                    }
                }
                save()
            }
        } finally {
            releaseLocks()
            runCatching { Repo.rescanLocal() }
            val nm = getSystemService(NotificationManager::class.java)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (finished > 0) {
                nm.notify(NOTIF + 1, Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Descargas listas")
                    .setContentText(if (finished == 1) "1 juego listo para jugar" else "$finished juegos listos para jugar")
                    .setAutoCancel(true)
                    .build())
            }
            stopSelf()
        }
    }

    private fun download(d: Download) {
        // Espacio: lo que falta de este juego más la reserva elegida.
        val remaining = d.files.sumOf { f ->
            val dest = if (d.bios) Local.biosFile(f.path) else Local.romFile(f.path)
            if (dest.length() == f.size) 0L else f.size - File(dest.path + ".part").length().coerceAtMost(f.size)
        }
        val free = Local.space().free - Prefs.reserveGb * 1_000_000_000L
        if (remaining > free) throw IOException("Sin espacio: faltan ${formatSize(remaining - free)}")

        d.done = d.files.sumOf { f ->
            val dest = if (d.bios) Local.biosFile(f.path) else Local.romFile(f.path)
            if (dest.length() == f.size) f.size else File(dest.path + ".part").length().coerceAtMost(f.size)
        }
        var lastUi = 0L
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        Pc.use { c ->
            for (f in d.files) {
                if (d.status != DlStatus.RUNNING) return@use
                val dest = if (d.bios) Local.biosFile(f.path) else Local.romFile(f.path)
                if (dest.isFile && dest.length() == f.size) continue
                d.current = f.path.substringAfterLast('/')
                dest.parentFile?.mkdirs()
                val part = File(dest.path + ".part")
                if (part.length() > f.size) part.delete()
                val start = part.length()
                val remote = SftpConn.join(if (d.bios) Prefs.remoteBios else Prefs.remoteRoms, f.path)
                // Escribir de a 4 MB: de a 128 KB el almacenamiento (FUSE) frenaba la descarga a ratos.
                java.io.BufferedOutputStream(FileOutputStream(part, start > 0), 4 shl 20).use { out ->
                    c.open(remote, start).use { input ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (d.status != DlStatus.RUNNING) return@use
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            d.done += n
                            windowBytes += n
                            val now = System.currentTimeMillis()
                            if (now - windowStart >= 1000) {
                                // Promedio suave: la velocidad del WiFi salta mucho de un segundo a otro.
                                val inst = windowBytes * 1000 / (now - windowStart)
                                d.speed = if (d.speed == 0L) inst else (d.speed * 0.75 + inst * 0.25).toLong()
                                windowStart = now
                                windowBytes = 0
                            }
                            if (now - lastUi > 1000) {
                                lastUi = now
                                notify("${d.title} · ${formatSize(d.done)} de ${formatSize(d.total)}", d.done, d.total)
                            }
                        }
                    }
                }
                if (d.status != DlStatus.RUNNING) return@use
                if (part.length() != f.size) throw IOException("Descarga incompleta de ${f.path.substringAfterLast('/')}")
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("No se pudo guardar ${dest.name}")
            }
        }
        d.speed = 0
    }
}
