package io.github.sudodiego.romsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Respaldo automático. Android lo despierta cada hora (con WiFi y batería suficiente, sin abrir la app); si pasaron N
 * horas desde el último respaldo que salió bien y el PC responde, respalda (solo sube lo que cambió). Si el PC estaba
 * apagado, lo vuelve a intentar a la hora siguiente: antes esperaba otras N horas.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        Shell.init(applicationContext)
        if (!Prefs.autoBackup || !Prefs.configured) return Result.success()
        val due = Prefs.lastAutoBackupOk + Prefs.autoBackupHours * 3_600_000L
        if (System.currentTimeMillis() < due) return Result.success()
        // PC apagado o fuera de casa: se intenta otra vez en una hora.
        if (runCatching { Pc.io { } }.isFailure) {
            Prefs.lastAutoBackupNote = "El servidor no respondía; se reintenta cada hora"
            return Result.success()
        }
        val msg = Saves.backupAll(applicationContext, auto = true)
        if (": " !in msg) Prefs.lastAutoBackupOk = System.currentTimeMillis() // sin errores de ningún emulador
        Prefs.lastAutoBackupNote = msg
        return Result.success()
    }

    companion object {
        private const val NAME = "respaldo-automatico"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!Prefs.autoBackup) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
