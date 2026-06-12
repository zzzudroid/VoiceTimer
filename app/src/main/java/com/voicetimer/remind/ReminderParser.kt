package com.voicetimer.remind

import java.util.Calendar

data class ParsedReminder(val text: String, val triggerAt: Long, val type: ReminderType)

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
        if (!dayResolved && !explicitTime && dayPart == null) return null

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
        return ParsedReminder(cleanText, cal.timeInMillis, type)
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
