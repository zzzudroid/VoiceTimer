package com.voicetimer.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

// Golden-корпус: фиксирует ТЕКУЩЕЕ корректное поведение разбора фраз и повторов.
// Служит страховкой от регрессий при последующих правках парсера.
// Время разбора (`now`) передаётся явно → тесты детерминированы.
class ReminderParserTest {

    private val sched = ScheduleHours()  // дефолты: утро 9, день 14, вечер 19, ночь 22, default 9

    // Фиксированный момент: 2026-06-15, 08:00 (ранний час, чтобы «в 9» не уезжало на завтра)
    private fun now(): Long = cal(2026, Calendar.JUNE, 15, 8, 0)

    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun field(t: Long, f: Int): Int = Calendar.getInstance().apply { timeInMillis = t }.get(f)

    private fun parse(s: String) = ReminderParser.parse(s, now(), sched)

    // ── Относительное время «через …» ────────────────────────────────────────────
    @Test fun relativeMinutes() {
        val p = parse("позвонить маме через 30 минут")!!
        assertEquals("Позвонить маме", p.text)
        assertEquals(now() + 30 * 60_000L, p.triggerAt)
        assertEquals(ReminderType.EXACT, p.type)
    }

    @Test fun relativeHours() {
        val p = parse("позвонить через 2 часа")!!
        assertEquals(now() + 2 * 60 * 60_000L, p.triggerAt)
        assertEquals(ReminderType.EXACT, p.type)
    }

    @Test fun relativeHalfHour() {
        val p = parse("выйти через полчаса")!!
        assertEquals(now() + 30 * 60_000L, p.triggerAt)
    }

    // ── Относительные дни ─────────────────────────────────────────────────────────
    @Test fun tomorrowAtTime() {
        val p = parse("купить хлеб завтра в 9")!!
        assertEquals("Купить хлеб", p.text)
        assertEquals(Calendar.JUNE, field(p.triggerAt, Calendar.MONTH))
        assertEquals(16, field(p.triggerAt, Calendar.DAY_OF_MONTH))
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertEquals(ReminderType.EXACT, p.type)
    }

    @Test fun dayAfterTomorrowInexact() {
        val p = parse("полить цветы послезавтра")!!
        assertEquals(17, field(p.triggerAt, Calendar.DAY_OF_MONTH))
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))  // defaultHour
        assertEquals(ReminderType.INEXACT, p.type)
    }

    // ── Точное время ──────────────────────────────────────────────────────────────
    @Test fun explicitColonTime() {
        val p = parse("встреча в 18:30")!!
        assertEquals(18, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertEquals(30, field(p.triggerAt, Calendar.MINUTE))
        assertEquals(ReminderType.EXACT, p.type)
    }

    @Test fun spacedTime() {
        val p = parse("митинг в 20 00")!!
        assertEquals(20, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertEquals(0, field(p.triggerAt, Calendar.MINUTE))
    }

    @Test fun wordHour() {
        val p = parse("позвонить в 9 часов")!!
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertEquals(0, field(p.triggerAt, Calendar.MINUTE))
    }

    // ── Части суток (берут час из расписания) ─────────────────────────────────────
    @Test fun eveningUsesScheduleHour() {
        val p = parse("позвонить вечером")!!
        assertEquals(19, field(p.triggerAt, Calendar.HOUR_OF_DAY))  // sched.evening
        assertEquals(ReminderType.INEXACT, p.type)
    }

    @Test fun eightInTheEveningIsPm() {
        val p = parse("позвонить в 8 вечера")!!
        assertEquals(20, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    // ── Дата по месяцу ────────────────────────────────────────────────────────────
    @Test fun dateByMonth() {
        val p = parse("сдать отчёт 25 декабря")!!
        assertEquals(Calendar.DECEMBER, field(p.triggerAt, Calendar.MONTH))
        assertEquals(25, field(p.triggerAt, Calendar.DAY_OF_MONTH))
        assertEquals(2026, field(p.triggerAt, Calendar.YEAR))
    }

    // ── День недели ───────────────────────────────────────────────────────────────
    @Test fun weekday() {
        val p = parse("позвонить в среду")!!
        assertEquals(Calendar.WEDNESDAY, field(p.triggerAt, Calendar.DAY_OF_WEEK))
        assertTrue(p.triggerAt > now())
    }

    // ── Повторы (текущее поведение) ───────────────────────────────────────────────
    @Test fun dailyRecurrence() {
        val p = parse("каждый день в 8")!!
        assertEquals(RecurrenceType.DAILY, p.recurrence)
        assertEquals(1, p.recurrenceInterval)
        assertEquals(8, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    @Test fun everyNDays() {
        val p = parse("полить цветок каждые 3 дня в 20:00")!!
        assertEquals(RecurrenceType.DAILY, p.recurrence)
        assertEquals(3, p.recurrenceInterval)
        assertEquals(20, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    @Test fun monthlyKeyword() {
        val p = parse("ежемесячно снять показания")!!
        assertEquals(RecurrenceType.MONTHLY, p.recurrence)
    }

    @Test fun notAReminderReturnsNull() {
        // нет ничего временно́го → не напоминание
        assertEquals(null, ReminderParser.parse("просто какой-то текст", now(), sched))
    }

    // ── Исправленные баги P1–P4 ───────────────────────────────────────────────────

    @Test fun p1_weeklyWeekdayAnchorsFirstTrigger() {
        val p = parse("позвонить каждый вторник в 10")!!
        assertEquals(RecurrenceType.WEEKLY, p.recurrence)
        assertEquals(Calendar.TUESDAY, field(p.triggerAt, Calendar.DAY_OF_WEEK))
        assertEquals(10, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertTrue(p.triggerAt > now())
    }

    @Test fun p2_todayPastRollsToFuture() {
        // сейчас 10:00, «сегодня в 8» уже прошло → переносится в будущее, не теряется
        val nowMs = cal(2026, Calendar.JUNE, 15, 10, 0)
        val p = ReminderParser.parse("позвонить сегодня в 8", nowMs, sched)!!
        assertTrue("должно быть в будущем", p.triggerAt > nowMs)
        assertEquals(8, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    @Test fun p3_numericDateIsNotTime() {
        val p = parse("сдать отчёт 25.12")!!
        assertEquals(Calendar.DECEMBER, field(p.triggerAt, Calendar.MONTH))
        assertEquals(25, field(p.triggerAt, Calendar.DAY_OF_MONTH))
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))   // defaultHour, НЕ 23:12
        assertEquals(ReminderType.INEXACT, p.type)
        assertEquals("Сдать отчёт", p.text)
    }

    @Test fun p4_contentPrepositionsKept() {
        val p = parse("купить молоко на рынке завтра")!!
        assertEquals("Купить молоко на рынке", p.text)
    }

    // ── Циклические события: наборы дней, число месяца, части суток (R1/R2/R4) ─────

    private val weekdaySet = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
    )

    @Test fun r1_everyWeekday() {
        val p = parse("полить цветы каждый будний день в 12")!!
        assertEquals(RecurrenceType.WEEKLY, p.recurrence)
        assertEquals(weekdaySet, p.daysOfWeek)
        assertEquals(12, field(p.triggerAt, Calendar.HOUR_OF_DAY))
        assertTrue(field(p.triggerAt, Calendar.DAY_OF_WEEK) in weekdaySet)
        assertTrue(p.triggerAt > now())
    }

    @Test fun r1_everyWeekend() {
        val p = parse("отдых по выходным в 11")!!
        assertEquals(RecurrenceType.WEEKLY, p.recurrence)
        assertEquals(setOf(Calendar.SATURDAY, Calendar.SUNDAY), p.daysOfWeek)
        assertTrue(field(p.triggerAt, Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY))
    }

    @Test fun r2_monthlyByDayOfMonth() {
        val p = parse("снять показания 5 числа каждый месяц")!!
        assertEquals(RecurrenceType.MONTHLY, p.recurrence)
        assertEquals(5, p.dayOfMonth)
        assertEquals(5, field(p.triggerAt, Calendar.DAY_OF_MONTH))
    }

    @Test fun r2_everyNthDayOfMonth() {
        val p = parse("заплатить каждое 10 число в 9")!!
        assertEquals(RecurrenceType.MONTHLY, p.recurrence)
        assertEquals(10, p.dayOfMonth)
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    @Test fun r4_everyMorning() {
        val p = parse("зарядка каждое утро")!!
        assertEquals(RecurrenceType.DAILY, p.recurrence)
        assertEquals(9, field(p.triggerAt, Calendar.HOUR_OF_DAY))   // sched.morning
        assertEquals("Зарядка", p.text)
    }

    @Test fun r4_everyEveningAtTime() {
        val p = parse("таблетки каждый вечер в 21:00")!!
        assertEquals(RecurrenceType.DAILY, p.recurrence)
        assertEquals(21, field(p.triggerAt, Calendar.HOUR_OF_DAY))
    }

    // ── Независимость от порядка слов (W1/W2) ─────────────────────────────────────

    @Test fun w1_dateNotLostWhenTimeFirst() {
        // «в 9 30 декабря» — дата не должна теряться (раньше превращалась в 09:30 сегодня)
        val p = parse("в 9 30 декабря поздравить")!!
        assertEquals(Calendar.DECEMBER, field(p.triggerAt, Calendar.MONTH))
        assertEquals(30, field(p.triggerAt, Calendar.DAY_OF_MONTH))
    }

    @Test fun w1_dateConsistentBothOrders() {
        val a = parse("30 декабря в 9 поздравить")!!
        assertEquals(Calendar.DECEMBER, field(a.triggerAt, Calendar.MONTH))
        assertEquals(30, field(a.triggerAt, Calendar.DAY_OF_MONTH))
        assertEquals(9, field(a.triggerAt, Calendar.HOUR_OF_DAY))
    }

    @Test fun w2_commandVerbsStripped() {
        assertEquals("Купить хлеб", parse("напомни купить хлеб завтра")!!.text)
        assertEquals("Позвонить маме", parse("напомни мне позвонить маме завтра")!!.text)
    }
}
