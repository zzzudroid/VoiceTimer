package com.voicetimer

data class ParseResult(val millis: Long, val label: String)

object TimeParser {

    private val wordNumbers = mapOf(
        "ноль" to 0,
        "один" to 1, "одну" to 1, "одна" to 1,
        "два" to 2, "две" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8,
        "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60
    )

    // Одно число: цифры или слово. Слова — по убыванию длины,
    // чтобы «девятнадцать» не матчилось как «девять».
    private val singleNum = buildString {
        append("""(?:\d+|""")
        append(wordNumbers.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        append(")")
    }

    // Перед единицей измерения может стоять составное числительное:
    // «сорок девять» = 40 + 9, «двадцать пять» = 25 и т.п.
    private val numPart = """($singleNum(?:\s+$singleNum)*)"""

    fun parse(input: String): ParseResult? {
        val text = input.lowercase().trim()

        // Фиксированные фразы
        specialPhrase(input, text, "полчаса", 30 * 60_000L)?.let { return it }
        specialPhrase(input, text, "пол часа", 30 * 60_000L)?.let { return it }
        specialPhrase(input, text, "четверть часа", 15 * 60_000L)?.let { return it }

        var totalSeconds = 0L
        var found = false
        var firstMatchPos = text.length

        fun tryMatch(regex: Regex, multiplier: Long) {
            regex.find(text)?.let { m ->
                parseNum(m.groupValues[1])?.let { n ->
                    totalSeconds += n * multiplier
                    found = true
                    if (m.range.first < firstMatchPos) firstMatchPos = m.range.first
                }
            }
        }

        tryMatch(Regex("""$numPart\s*(час(?:а|ов)?)"""), 3600L)
        tryMatch(Regex("""$numPart\s*(минут(?:у|ы|а)?|мин\.?)"""), 60L)
        tryMatch(Regex("""$numPart\s*(секунд(?:у|ы|а)?|сек\.?)"""), 1L)

        // Просто цифра без единицы → минуты
        if (!found) {
            Regex("""^\s*(\d+)\s*$""").find(text)?.groupValues?.get(1)
                ?.toIntOrNull()?.let { n ->
                    totalSeconds = n * 60L
                    found = true
                    firstMatchPos = 0
                }
        }

        if (!found || totalSeconds <= 0) return null

        val label = labelBefore(input, firstMatchPos)
        return ParseResult(totalSeconds * 1000L, label)
    }

    // Всё что перед временны́м выражением — это метка действия
    private fun labelBefore(original: String, endPos: Int): String {
        if (endPos <= 0) return ""
        // endPos в lowercase, берём тот же диапазон из оригинала
        val raw = original.take(endPos).trimEnd(' ', ',', '-', ':').trim()
        return raw.replaceFirstChar { it.uppercase() }
    }

    private fun specialPhrase(original: String, lower: String, phrase: String, millis: Long): ParseResult? {
        val idx = lower.indexOf(phrase)
        if (idx < 0) return null
        val label = labelBefore(original, idx)
        return ParseResult(millis, label)
    }

    // Складывает токены составного числительного: «сорок девять» → 40 + 9 = 49
    private fun parseNum(s: String): Int? {
        val tokens = s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var sum = 0
        for (t in tokens) {
            val v = t.toIntOrNull() ?: wordNumbers[t] ?: return null
            sum += v
        }
        return sum
    }
}
