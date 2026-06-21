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

    // Низкоуровневая постановка будильника на конкретный момент.
    private fun armAt(context: Context, r: Reminder, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, r.id)
        val exact = r.type == ReminderType.EXACT && canScheduleExact(am)
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun schedule(context: Context, r: Reminder) {
        if (r.done) return
        // Звоним по эффективному времени: активный снуз имеет приоритет над базой.
        val target = r.effectiveTrigger()
        // Просроченное (телефон был выключен/процесс убит) — не теряем, а звоним
        // почти сразу. Так напоминание не может «молча исчезнуть».
        val at = if (target <= System.currentTimeMillis())
            System.currentTimeMillis() + 1_000L
        else target
        armAt(context, r, at)
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, id))
    }

    // Перепланировать все активные напоминания (после перезагрузки, обновления
    // приложения, смены времени или по тику сторожа). Идемпотентно — можно
    // звать сколько угодно: один и тот же PendingIntent просто переустанавливается.
    fun rescheduleAll(context: Context) {
        ReminderStore.load(context)
        val now = System.currentTimeMillis()
        for (r in ReminderStore.active()) {
            val eff = r.effectiveTrigger()
            when {
                eff > now ->
                    armAt(context, r, eff)
                // Пропущенный снуз (база/серия не трогаются) — звоним с опозданием,
                // сам снуз погасит fire().
                r.snoozedUntil != null ->
                    armAt(context, r, now + 1_000L)
                r.recurrence != RecurrenceType.NONE -> {
                    // Пропущенный период серии — перескакиваем на ближайший будущий.
                    val next = r.copy(triggerAt = r.nextTrigger(), done = false, notified = false)
                    ReminderStore.upsert(context, next)
                    armAt(context, next, next.triggerAt)
                }
                // Разовое просрочено и не выполнено. Если оно НИ РАЗУ не звонило
                // (телефон был выключен / процесс убит до срабатывания) — звоним с
                // опозданием. Если уже звонило — не долбим повторно по таймеру сторожа,
                // ждём либо «Готово», либо следующего запуска программы (replayUnacknowledged).
                !r.notified ->
                    armAt(context, r, now + 1_000L)
            }
        }
    }

    // Перезвонить все неподтверждённые просроченные напоминания — вызывается при
    // запуске приложения. Реализует «пока не нажал Готово — при открытии программы
    // снова напомнит». В отличие от сторожа, звонит и по уже звонившим (notified).
    fun replayUnacknowledged(context: Context) {
        ReminderStore.load(context)
        val now = System.currentTimeMillis()
        for (r in ReminderStore.active()) {
            // Учитываем активный снуз: если отложенный момент ещё в будущем —
            // не звоним раньше времени.
            if (r.recurrence == RecurrenceType.NONE && r.effectiveTrigger() <= now) {
                armAt(context, r, now + 1_000L)
            }
        }
    }

    fun canScheduleExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
}
