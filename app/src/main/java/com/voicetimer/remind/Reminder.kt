package com.voicetimer.remind

// Тип напоминания:
//  EXACT   — указано точное время («завтра в 9:00», «через полчаса») →
//            громкий зацикленный сигнал будильника.
//  INEXACT — указан только день/часть суток («завтра», «вечером позвонить») →
//            срабатывает в удобный час по расписанию дня, мягким одиночным звуком.
enum class ReminderType { EXACT, INEXACT }

// Периодичность повторения: NONE — разовое; иначе после срабатывания
// напоминание переносится на следующий период.
enum class RecurrenceType { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

data class Reminder(
    val id: Long,
    val text: String,            // что напомнить
    val triggerAt: Long,         // когда сработает (epoch ms)
    val type: ReminderType,
    val done: Boolean = false,   // уже сработало/отмечено выполненным
    val inCalendar: Boolean = false,
    val recurrence: RecurrenceType = RecurrenceType.NONE,
    val recurrenceInterval: Int = 1,   // «каждые N …»: шаг периода (1 = каждый)
    val createdAt: Long = System.currentTimeMillis()
) {
    // Следующий момент срабатывания для повторяющегося напоминания
    fun nextTrigger(): Long {
        if (recurrence == RecurrenceType.NONE) return triggerAt
        val step = recurrenceInterval.coerceAtLeast(1)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAt }
        fun advance() {
            when (recurrence) {
                RecurrenceType.DAILY   -> cal.add(java.util.Calendar.DAY_OF_MONTH, step)
                RecurrenceType.WEEKLY  -> cal.add(java.util.Calendar.DAY_OF_MONTH, 7 * step)
                RecurrenceType.MONTHLY -> cal.add(java.util.Calendar.MONTH, step)
                RecurrenceType.YEARLY  -> cal.add(java.util.Calendar.YEAR, step)
                RecurrenceType.NONE    -> {}
            }
        }
        advance()
        // если вдруг отстали на несколько периодов — догоняем до будущего
        val now = System.currentTimeMillis()
        while (cal.timeInMillis <= now) advance()
        return cal.timeInMillis
    }
}
