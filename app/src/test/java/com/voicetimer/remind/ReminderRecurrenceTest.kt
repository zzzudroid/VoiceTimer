package com.voicetimer.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import org.junit.Test

// Перепланирование повторяющихся напоминаний (nextTrigger).
// nextTrigger опирается на System.currentTimeMillis, поэтому проверяем СВОЙСТВА
// результата (в будущем, сохранён час/день недели), а не абсолютный момент.
class ReminderRecurrenceTest {

    private fun pastTrigger(daysAgo: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun field(t: Long, f: Int) = Calendar.getInstance().apply { timeInMillis = t }.get(f)

    private fun reminder(rec: RecurrenceType, interval: Int, trigger: Long) =
        Reminder(id = 1, text = "t", triggerAt = trigger, type = ReminderType.EXACT,
            recurrence = rec, recurrenceInterval = interval)

    @Test fun dailyMovesToFutureSameTime() {
        val trigger = pastTrigger(daysAgo = 3, hour = 8, minute = 30)
        val next = reminder(RecurrenceType.DAILY, 1, trigger).nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        assertEquals(8, field(next, Calendar.HOUR_OF_DAY))
        assertEquals(30, field(next, Calendar.MINUTE))
    }

    @Test fun weeklyKeepsWeekday() {
        val trigger = pastTrigger(daysAgo = 14, hour = 10, minute = 0)
        val expectedDow = field(trigger, Calendar.DAY_OF_WEEK)
        val next = reminder(RecurrenceType.WEEKLY, 1, trigger).nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        assertEquals(expectedDow, field(next, Calendar.DAY_OF_WEEK))
    }

    @Test fun monthlyKeepsDayOfMonth() {
        // 10-е число месяца, гарантированно ≤ 28, чтобы не было clamp
        val trigger = Calendar.getInstance().apply {
            add(Calendar.MONTH, -2); set(Calendar.DAY_OF_MONTH, 10)
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val next = reminder(RecurrenceType.MONTHLY, 1, trigger).nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        assertEquals(10, field(next, Calendar.DAY_OF_MONTH))
    }

    // ── Новое: наборы дней недели и число месяца ──────────────────────────────────

    private val weekdays = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
    )

    @Test fun weekdaySetSkipsWeekend() {
        // повтор «по будням» от прошлой пятницы → следующий срок тоже будний день
        val trigger = pastTrigger(daysAgo = 10, hour = 12, minute = 0)
        val r = Reminder(id = 1, text = "t", triggerAt = trigger, type = ReminderType.EXACT,
            recurrence = RecurrenceType.WEEKLY, daysOfWeek = weekdays)
        val next = r.nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        assertTrue("должен быть будним днём", field(next, Calendar.DAY_OF_WEEK) in weekdays)
        assertEquals(12, field(next, Calendar.HOUR_OF_DAY))
    }

    @Test fun weekdaySetSingleDayIsWeekly() {
        // {вторник} → следующий срок во вторник
        val trigger = pastTrigger(daysAgo = 20, hour = 10, minute = 0)
        val r = Reminder(id = 1, text = "t", triggerAt = trigger, type = ReminderType.EXACT,
            recurrence = RecurrenceType.WEEKLY, daysOfWeek = setOf(Calendar.TUESDAY))
        val next = r.nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        assertEquals(Calendar.TUESDAY, field(next, Calendar.DAY_OF_WEEK))
    }

    // ── Снуз (snoozedUntil) — разовое смещение поверх базы ────────────────────────

    @Test fun snoozeDoesNotShiftRecurringBase() {
        // Ежедневное в фиксированный час; пользователь отложил «на 10 минут».
        // Снуз НЕ должен менять час следующего периода серии.
        val base = pastTrigger(daysAgo = 0, hour = 21, minute = 15)
        val r = Reminder(id = 1, text = "покормить рыбок", triggerAt = base,
            type = ReminderType.EXACT, recurrence = RecurrenceType.DAILY,
            snoozedUntil = System.currentTimeMillis() + 10 * 60_000L)
        // эффективное срабатывание = отложенный момент
        assertEquals(r.snoozedUntil, r.effectiveTrigger())
        // следующий период по-прежнему в 21:15, а не в 21:25
        val next = r.nextTrigger()
        assertEquals(21, field(next, Calendar.HOUR_OF_DAY))
        assertEquals(15, field(next, Calendar.MINUTE))
    }

    @Test fun effectiveTriggerFallsBackToBase() {
        val base = pastTrigger(daysAgo = 0, hour = 8, minute = 0)
        val r = Reminder(id = 1, text = "t", triggerAt = base, type = ReminderType.EXACT)
        assertEquals(base, r.effectiveTrigger())
    }

    @Test fun monthlyDay31ClampsAndRecovers() {
        // dayOfMonth=31: в коротких месяцах берётся последний день, без «залипания»
        val trigger = Calendar.getInstance().apply {
            add(Calendar.MONTH, -3); set(Calendar.DAY_OF_MONTH, 1)  // старт неважен
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val r = Reminder(id = 1, text = "t", triggerAt = trigger, type = ReminderType.EXACT,
            recurrence = RecurrenceType.MONTHLY, dayOfMonth = 31)
        val next = r.nextTrigger()
        assertTrue(next > System.currentTimeMillis())
        val day = field(next, Calendar.DAY_OF_MONTH)
        val maxDay = Calendar.getInstance().apply { timeInMillis = next }
            .getActualMaximum(Calendar.DAY_OF_MONTH)
        // либо 31, либо последний день более короткого месяца
        assertTrue("день=$day, макс=$maxDay", day == 31 || day == maxDay)
    }
}
