package com.voicetimer.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// После перезагрузки устройства AlarmManager забывает все будильники —
// перепланируем будущие напоминания заново.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" ->
                ReminderScheduler.rescheduleAll(context.applicationContext)
        }
    }
}
