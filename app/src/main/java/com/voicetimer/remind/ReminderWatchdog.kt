package com.voicetimer.remind

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// Периодический «сторож» надёжности. AlarmManager — основной механизм, но
// агрессивные прошивки (Xiaomi/Huawei/Samsung/Oppo) умеют убить процесс и
// заодно отменить наши будильники. WorkManager переживает такие чистки
// (его перезапускает система) и раз в ~15 минут переустанавливает все
// срабатывания заново. rescheduleAll идемпотентен, так что лишний тик безвреден,
// а просроченные за время простоя напоминания тут же зазвонят с опозданием.
class ReminderWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        runCatching { ReminderScheduler.rescheduleAll(applicationContext) }
        return Result.success()
    }
}

object ReminderWatchdog {
    private const val UNIQUE_NAME = "reminder_watchdog"

    // Ставит периодический сторож, если он ещё не запущен (KEEP — не плодим копии).
    // Вызывать при старте приложения и после загрузки/обновления.
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWatchdogWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(Constraints.NONE).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
