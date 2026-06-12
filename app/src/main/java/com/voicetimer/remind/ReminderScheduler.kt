package com.voicetimer.remind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

// Планирует/снимает срабатывания напоминаний через AlarmManager.
// Точные напоминания — setExactAndAllowWhileIdle (будят устройство из Doze),
// неточные — setAndAllowWhileIdle (системе разрешено сдвигать ради экономии).
object ReminderScheduler {

    const val ACTION_FIRE = "com.voicetimer.REMIND_FIRE"
    const val EXTRA_ID    = "reminder_id"

    private fun pendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, r: Reminder) {
        if (r.done || r.triggerAt <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, r.id)
        val exact = r.type == ReminderType.EXACT && canScheduleExact(am)
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
    }

    // Перепланировать все будущие напоминания (после перезагрузки или смены настроек)
    fun rescheduleAll(context: Context) {
        ReminderStore.load(context)
        for (r in ReminderStore.pending()) schedule(context, r)
    }

    fun canScheduleExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
}
