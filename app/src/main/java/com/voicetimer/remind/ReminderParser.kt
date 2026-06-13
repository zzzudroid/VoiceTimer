package com.voicetimer.remind

import java.util.Calendar

data class ParsedReminder(
    val text: String,
    val triggerAt: Long,
    val type: ReminderType,
    val recurrence: RecurrenceType = RecurrenceType.NONE
)

// Разбирает фразу вида «позвонить маме завтра в 9 утра» →
//   текст = «Позвонить маме», время = завтра 09:00, тип = EXACT.
// Поддержка: «через N минут/часов/дней», «через полчаса», «сегодня/завтра/послезавтра»,
// дни недели («в среду», «в следующий понедельник»), части суток
// («утром/днём/вечером/ночью»), точное время («в 9», «в 18:30», «в 9 часов»).
object ReminderParser {

    private val weekdays = mapOf(
        "понедельник" to Calendar.MONDAY, "вторник" to Calendar.TUESDAY,
        "среда" to Calendar.WEDNESDAY, "среду" to Calendar.WEDNESDAY,
        "четверг" to Calendar.THURSDAY, "пятница" to Calendar.FRIDAY, "пятницу" to Calendar.FRIDAY,
        "суббота" to Calendar.SATURDAY, "субботу" to Calendar.SATURDAY,
        "воскресенье" to Calendar.SUNDAY,
        // формы после «в»
        "понедельник," to Calendar.MONDAY
    )

    // Месяцы (родительный падеж, как в датах: «5 декабря»)
    private val months = mapOf(
        "января" to Calendar.JANUARY, "февраля" to Calendar.FEBRUARY, "марта" to Calendar.MARCH,
        "апреля" to Calendar.APRIL, "мая" to Calendar.MAY, "июня" to Calendar.JUNE,
        "июля" to Calendar.JULY, "августа" to Calendar.AUGUST, "сентября" to Calendar.SEPTEMBER,
        "октября" to Calendar.OCTOBER, "ноября" to Calendar.NOVEMBER, "декабря" to Calendar.DECEMBER
    )

    // Порядковые дни месяца (родительный падеж): «пятого», «двадцать пятого»
    private val dayOrdinal = mapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвёртого" to 4, "четвертого" to 4,
        "пятого" to 5, "шестого" to 6, "седьмого" to 7, "восьмого" to 8, "девятого" to 9, "десятого" to 10,
        "одиннадцатого" to 11, "двенадцатого" to 12, "тринадцатого" to 13, "четырнадцатого" to 14,
        "пятнадцатого" to 15, "шестнадцатого" to 16, "семнадцатого" to 17, "восемнадцатого" to 18,
        "девятнадцатого" to 19, "двадцатого" to 20, "двадцать" to 20, "тридцатого" to 30, "тридцать" to 30
    )

    // Часть суток → имя слота расписания
    private enum class DayPart { MORNING, DAY, EVENING, NIGHT }

    fun parse(input: String, now: Long, sched: ScheduleHours): ParsedReminder? {
        val original = input.trim()
        if (original.isBlank()) return null
        var text = original.lowercase()

        // Хранит, какие куски текста вырезать (диапазоны в lowercase-строке)
        val cuts = mutableListOf<IntRange>()
        fun cut(r: IntRange) { cuts.add(r) }

        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var type = ReminderType.INEXACT
        var explicitTime = false
        var dayResolved = false
        var recurrence = RecurrenceType.NONE

        // ── 0. Повторение: «каждый день/вторник», «каждую неделю», «ежедневно»… ───
        run {
            Regex("""ежедневн\w*""").find(text)?.let { recurrence = RecurrenceType.DAILY; cut(it.range) }
            if (recurrence == RecurrenceType.NONE) Regex("""еженедельн\w*""").find(text)?.let { recurrence = RecurrenceType.WEEKLY; cut(it.range) }
            if (recurrence == RecurrenceType.NONE) Regex("""ежемесячн\w*""").find(text)?.let { recurrence = RecurrenceType.MONTHLY; cut(it.range) }
            if (recurrence == RecurrenceType.NONE) Regex("""ежегодн\w*""").find(text)?.let { recurrence = RecurrenceType.YEARLY; cut(it.range) }
            if (recurrence == RecurrenceType.NONE) {
                Regex("""кажд\w+(?:\s+(день|дня|неделю|недел\w+|месяц\w*|год\w*))?""").find(text)?.let { m ->
                    val unit = m.groupValues[1]
                    recurrence = when {
                        unit.startsWith("день") || unit.startsWith("дня") -> RecurrenceType.DAILY
                        unit.startsWith("недел")                          -> RecurrenceType.WEEKLY
                        unit.startsWith("месяц")                          -> RecurrenceType.MONTHLY
                        unit.startsWith("год")                            -> RecurrenceType.YEARLY
                        else -> RecurrenceType.WEEKLY   // «каждый <день недели>» → еженедельно
                    }
                    cut(m.range)
                }
            }
        }

        // ── 1. «через N минут/часов/дней», «через полчаса», «через час» ───────────
        Regex("""через\s+полчаса""").find(text)?.let { m ->
            cal.add(Calendar.MINUTE, 30); explicitTime = true; dayResolved = true; cut(m.range)
        }
        if (!explicitTime) Regex("""через\s+(полтора\s+часа)""").find(text)?.let { m ->
            cal.add(Calendar.MINUTE, 90); explicitTime = true; dayResolved = true; cut(m.range)
        }
        if (!explicitTime) {
            val relUnit = Regex("""через\s+${RuNumbers.numberGroup}?\s*(минут\w*|мин\.?|час\w*|дн\w*|недел\w*)""")
            relUnit.find(text)?.let { m ->
                val n = m.groupValues[1].ifBlank { "1" }.let { RuNumbers.parseCompound(it) ?: 1 }
                val unit = m.groupValues[2]
                when {
                    unit.startsWith("мин") -> { cal.add(Calendar.MINUTE, n); explicitTime = true }
                    unit.startsWith("час") -> { cal.add(Calendar.HOUR_OF_DAY, n); explicitTime = true }
                    unit.startsWith("дн")  -> cal.add(Calendar.DAY_OF_MONTH, n)
                    unit.startsWith("недел")-> cal.add(Calendar.DAY_OF_MONTH, n * 7)
                }
                dayResolved = true; cut(m.range)
            }
        }

        // ── 2. Относительные дни ──────────────────────────────────────────────────
        if (!dayResolved) {
            Regex("""послезавтра""").find(text)?.let { m -> cal.add(Calendar.DAY_OF_MONTH, 2); dayResolved = true; cut(m.range) }
            if (!dayResolved) Regex("""завтра""").find(text)?.let { m -> cal.add(Calendar.DAY_OF_MONTH, 1); dayResolved = true; cut(m.range) }
            if (!dayResolved) Regex("""сегодня""").find(text)?.let { m -> dayResolved = true; cut(m.range) }
        }

        // ── 2.5. Дата по месяцу: «25 декабря», «в последнюю субботу декабря» ──────
        if (!dayResolved) {
            val monthAlt = months.keys.joinToString("|")
            val wdAlt = weekdays.keys.joinToString("|") { Regex.escape(it.trimEnd(',')) }

            // Порядковый день недели в месяце: «(первую|…|последнюю) субботу декабря»
            val ordW = Regex("""(перв\w+|втор\w+|трет\w+|четверт\w+|пят\w+|последн\w+)\s+($wdAlt)\s+($monthAlt)""")
                .find(text)
            if (ordW != null) {
                val ordinal = ordinalIndex(ordW.groupValues[1])
                val dow = dowOf(ordW.groupValues[2])
                val month = months[ordW.groupValues[3]]!!
                if (dow != null) {
                    setOrdinalWeekdayInMonth(cal, now, month, dow, ordinal)
                    dayResolved = true; cut(ordW.range)
                }
            }

            // Конкретный день месяца: «25 декабря», «пятого декабря»
            if (!dayResolved) {
                val dayWord = (dayOrdinal.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
                val dm = Regex("""((?:\d{1,2}|$dayWord)(?:\s+(?:\d{1,2}|$dayWord))?)\s+($monthAlt)""").find(text)
                if (dm != null) {
                    val day = parseDayOfMonth(dm.groupValues[1])
                    val month = months[dm.groupValues[2]]
                    if (day in 1..31 && month != null) {
                        setMonthDay(cal, now, month, day)
                        dayResolved = true; cut(dm.range)
                    }
                }
            }
        }

        // ── 3. День недели: «в среду», «в следующий понедельник» ──────────────────
        if (!dayResolved) {
            val wdAlternation = weekdays.keys.joinToString("|") { Regex.escape(it.trimEnd(',')) }
            val wdRegex = Regex("""(?:в\s+|во\s+)?(следующ\w+\s+)?($wdAlternation)""")
            wdRegex.find(text)?.let { m ->
                val targetDow = weekdays.entries.first { it.key.trimEnd(',') == m.groupValues[2] }.value
                val nextWeek = m.groupValues[1].isNotBlank()
                advanceToWeekday(cal, targetDow, nextWeek)
                dayResolved = true; cut(m.range)
            }
        }

        // ── 4. Точное время: «в 18:30», «в 9 часов», «в 9» ────────────────────────
        run {
            val hm = Regex("""(?:в\s+)?(\d{1,2})[:.](\d{2})""").find(text)
            if (hm != null) {
                cal.set(Calendar.HOUR_OF_DAY, hm.groupValues[1].toInt().coerceIn(0, 23))
                cal.set(Calendar.MINUTE, hm.groupValues[2].toInt().coerceIn(0, 59))
                explicitTime = true; cut(hm.range)
            } else if (!explicitTime) {
                val h = Regex("""в\s+${RuNumbers.numberGroup}(\s+час\w*)?""").find(text)
                if (h != null) {
                    RuNumbers.parseCompound(h.groupValues[1])?.let { hour ->
                        if (hour in 0..23) {
                            cal.set(Calendar.HOUR_OF_DAY, hour)
                            cal.set(Calendar.MINUTE, 0)
                            explicitTime = true; cut(h.range)
                        }
                    }
                }
            }
        }

        // ── 5. Часть суток: утром/днём/вечером/ночью (а также «N вечера» = PM) ────
        var dayPart: DayPart? = null
        Regex("""утром|утра|с\s*утра""").find(text)?.let { dayPart = DayPart.MORNING; cut(it.range) }
        Regex("""днём|днем|дня|в\s*обед""").find(text)?.let { if (dayPart == null) { dayPart = DayPart.DAY; cut(it.range) } }
        Regex("""вечером|вечера""").find(text)?.let { if (dayPart == null) { dayPart = DayPart.EVENING; cut(it.range) } }
        Regex("""ночью|ночи""").find(text)?.let { if (dayPart == null) { dayPart = DayPart.NIGHT; cut(it.range) } }

        if (explicitTime && dayPart != null) {
            // «в 8 вечера» → 20:00, «в 3 дня» → 15:00 (для часов 1..11)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            if (h in 1..11 && (dayPart == DayPart.EVENING || dayPart == DayPart.NIGHT || dayPart == DayPart.DAY)) {
                cal.set(Calendar.HOUR_OF_DAY, h + 12)
            }
        } else if (dayPart != null) {
            // Время не задано — берём час из расписания дня
            val hour = when (dayPart) {
                DayPart.MORNING -> sched.morning
                DayPart.DAY     -> sched.day
                DayPart.EVENING -> sched.evening
                DayPart.NIGHT   -> sched.night
                null            -> sched.defaultHour
            }
            cal.set(Calendar.HOUR_OF_DAY, hour); cal.set(Calendar.MINUTE, 0)
        }

        // Если совсем ничего временно́го не нашли — это не напоминание
        // (но «каждый день» без времени допустим — сработает в час по умолчанию)
        if (!dayResolved && !explicitTime && dayPart == null && recurrence == RecurrenceType.NONE) return null

        // Тип: точное время → EXACT, иначе INEXACT (день/часть суток)
        type = if (explicitTime) ReminderType.EXACT else ReminderType.INEXACT

        // Для «неточных» без части суток ставим час по умолчанию
        if (!explicitTime && dayPart == null) {
            cal.set(Calendar.HOUR_OF_DAY, sched.defaultHour); cal.set(Calendar.MINUTE, 0)
        }
        if (!explicitTime) { cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0) }
        else if (Regex("""через""").containsMatchIn(text).not()) {
            // для точного «в 9» обнуляем секунды; для «через …» оставляем как есть
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        }

        // Если получившийся момент уже в прошлом (например «в 9», а сейчас 10) — переносим на завтра
        if (!dayResolved && cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val cleanText = buildCleanText(text, cuts, original)
        return ParsedReminder(cleanText, cal.timeInMillis, type, recurrence)
    }

    // «первую/вторую/…/последнюю» → 1..5, последняя = -1
    private fun ordinalIndex(w: String): Int = when {
        w.startsWith("перв")    -> 1
        w.startsWith("втор")    -> 2
        w.startsWith("трет")    -> 3
        w.startsWith("четверт") -> 4
        w.startsWith("пят")     -> 5
        w.startsWith("последн") -> -1
        else -> 1
    }

    private fun dowOf(word: String): Int? =
        weekdays.entries.firstOrNull { it.key.trimEnd(',') == word }?.value

    private fun parseDayOfMonth(s: String): Int {
        var sum = 0
        for (t in s.trim().split(Regex("""\s+"""))) {
            val v = t.toIntOrNull() ?: dayOrdinal[t] ?: return -1
            sum += v
        }
        return sum
    }

    // Устанавливает год/месяц/день для «N <месяца>», выбирая ближайший будущий год
    private fun setMonthDay(cal: Calendar, now: Long, month: Int, day: Int) {
        val today = Calendar.getInstance().apply { timeInMillis = now; zeroTime() }
        var year = today.get(Calendar.YEAR)
        repeat(2) {
            val c = Calendar.getInstance().apply { clear(); set(year, month, 1); zeroTime() }
            c.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            if (c.timeInMillis >= today.timeInMillis) {
                cal.set(Calendar.YEAR, year); cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, c.get(Calendar.DAY_OF_MONTH))
                return
            }
            year++
        }
    }

    // N-й (или последний) день недели в месяце, ближайший будущий год
    private fun setOrdinalWeekdayInMonth(cal: Calendar, now: Long, month: Int, dow: Int, ordinal: Int) {
        val today = Calendar.getInstance().apply { timeInMillis = now; zeroTime() }
        var year = today.get(Calendar.YEAR)
        repeat(2) {
            val c = Calendar.getInstance().apply { clear(); set(year, month, 1); zeroTime() }
            if (ordinal == -1) {
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
                while (c.get(Calendar.DAY_OF_WEEK) != dow) c.add(Calendar.DAY_OF_MONTH, -1)
            } else {
                while (c.get(Calendar.DAY_OF_WEEK) != dow) c.add(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.DAY_OF_MONTH, (ordinal - 1) * 7)
            }
            if (c.get(Calendar.MONTH) == month && c.timeInMillis >= today.timeInMillis) {
                cal.set(Calendar.YEAR, c.get(Calendar.YEAR)); cal.set(Calendar.MONTH, c.get(Calendar.MONTH))
                cal.set(Calendar.DAY_OF_MONTH, c.get(Calendar.DAY_OF_MONTH))
                return
            }
            year++
        }
    }

    private fun Calendar.zeroTime() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    // Переводит календарь на ближайший указанный день недели (или +неделя для «следующий»)
    private fun advanceToWeekday(cal: Calendar, targetDow: Int, nextWeek: Boolean) {
        var diff = (targetDow - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (diff == 0) diff = 7        // «в среду» в среду = следующая среда
        if (nextWeek && diff <= 7) diff += 7
        cal.add(Calendar.DAY_OF_MONTH, diff)
    }

    // Вырезает временны́е куски из исходного текста, оставляя суть напоминания
    private fun buildCleanText(lower: String, cuts: List<IntRange>, original: String): String {
        if (cuts.isEmpty()) return original.replaceFirstChar { it.uppercase() }
        val chars = original.toCharArray()
        for (r in cuts) for (i in r) if (i in chars.indices) chars[i] = ' '
        var s = String(chars).replace(" ", " ")
        // также убираем «осиротевшие» предлоги времени
        s = s.replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?<!\S)(в|во|на|к|через|до|часов|часа|час|минут)(?!\S)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '.', '-', ':')
        return s.trim().replaceFirstChar { it.uppercase() }
    }
}
