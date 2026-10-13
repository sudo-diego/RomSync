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
 * Respaldo automático: cada N horas, con WiFi y batería suficiente, si el PC responde. Solo sube lo que cambió.
 * Si el PC está apagado no pasa nada: lo intenta en la próxima vuelta.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        Shell.init(applicationContext)
        if (!Prefs.autoBackup || !Prefs.configured) return Result.success()
        // PC apagado o fuera de casa: se intenta en la próxima vuelta.
        if (runCatching { Pc.io { } }.isFailure) return Result.success()
        Saves.backupAll(applicationContext, auto = true)
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
            val req = PeriodicWorkRequestBuilder<BackupWorker>(Prefs.autoBackupHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
