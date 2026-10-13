package io.github.sudodiego.romsync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Comandos de shell con Shizuku, para lo único que una app normal no puede: leer y escribir en Android/data, donde
 * Dolphin, Eden, ARMSX2 y Vita3K guardan las partidas. El shell de Android (com.android.shell) sí tiene acceso.
 *
 * Igual que en TBF Companion: `Shizuku.newProcess` por reflexión (es privado desde la API 13), sin UserService
 * (en ZUI no arranca si la app de Shizuku no está abierta).
 */
object Shell {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "RomSync"
    private const val REQUEST = 7

    var state by mutableStateOf(State.NOT_RUNNING)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    private val newProcess: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "Shizuku sin newProcess", it) }.getOrNull()
    }

    private val onBinder = Shizuku.OnBinderReceivedListener { main.post(::refresh) }
    private val onDead = Shizuku.OnBinderDeadListener { main.post(::refresh) }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post(::refresh) }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        refresh()
    }

    fun refresh() {
        val installed = app?.let { runCatching { it.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess } ?: true
        state = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
            else -> State.READY
        }
    }

    val ready: Boolean get() { refresh(); return state == State.READY }

    fun requestPermission() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(REQUEST)
    }

    fun launchIntent(ctx: Context): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Comillas simples para sh: cualquier ruta (con espacios, paréntesis o apóstrofos) llega tal cual. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Abre un proceso `sh -c script` como el shell. Null si Shizuku no está listo. Quien llama lo cierra. */
    fun process(script: String): Process? {
        if (!ready) return null
        val m = newProcess ?: return null
        return runCatching { m.invoke(null, arrayOf("sh", "-c", script), null, null) as Process }
            .onFailure { Log.w(TAG, "Shizuku: no se pudo abrir el proceso", it) }.getOrNull()
    }

    /** Espera a un proceso de Shizuku. `waitFor(tiempo)` no sirve con él (ver TBF): se espera en otro hilo. */
    fun waitFor(p: Process, timeoutMs: Long): Int? {
        var code: Int? = null
        val waiter = Thread { code = runCatching { p.waitFor() }.getOrNull() }
        waiter.start()
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            p.destroy()
            return null
        }
        return code
    }

    /** Corre un script y devuelve su salida, o null si falló o Shizuku no está listo. */
    suspend fun run(script: String, timeoutMs: Long = 20000): String? = withContext(Dispatchers.IO) {
        val p = process(script) ?: return@withContext null
        val out = StringBuilder()
        val err = StringBuilder()
        val r1 = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
        val r2 = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { synchronized(err) { err.appendLine(it) } } } }
        r1.start(); r2.start()
        val code = waitFor(p, timeoutMs)
        r1.join(1000); r2.join(200)
        if (code != 0) {
            Log.w(TAG, "Shizuku: salió con $code: ${err.toString().take(300)}")
            return@withContext null
        }
        synchronized(out) { out.toString() }
    }

    /** ¿Está corriendo una app? (para no restaurar partidas con el emulador abierto). */
    suspend fun isRunning(pkg: String): Boolean = run("pidof -s ${q(pkg)} || true")?.isNotBlank() == true
}
