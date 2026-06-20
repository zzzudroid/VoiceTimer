package com.voicetimer.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Восстанавливает будильники, которые система могла забыть.
// AlarmManager теряет все срабатывания при:
//   • перезагрузке устройства (BOOT_COMPLETED),
//   • обновлении/переустановке приложения (MY_PACKAGE_REPLACED) — без этого
//     после апдейта напоминания молчат до ручного перезапуска телефона,
//   • ручной смене времени/часового пояса (TIME_SET / TIMEZONE_CHANGED) —
//     иначе абсолютный момент срабатывания «уезжает».
// Во всех случаях просто перепланируем все будущие напоминания заново.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ReminderScheduler.rescheduleAll(context.applicationContext)
                // Подстраховка: запускаем периодический сторож на случай,
                // если процесс снова выгрузят, а будильник отменят.
                ReminderWatchdog.ensureScheduled(context.applicationContext)
            }
        }
    }
}
