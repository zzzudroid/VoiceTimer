package com.voicetimer.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

// Срабатывает по будильнику AlarmManager в назначенное время.
// Запускает foreground-сервис, который проигрывает сигнал и показывает уведомление.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        if (id < 0) return

        val svc = Intent(context, ReminderAlarmService::class.java).apply {
            action = ReminderAlarmService.ACTION_FIRE
            putExtra(ReminderScheduler.EXTRA_ID, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
