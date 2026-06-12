package com.voicetimer.remind

// Разбор русских числительных (в т.ч. составных «двадцать пять» = 25).
// Отдельный объект, чтобы переиспользовать и в парсере напоминаний, и при разборе времени.
object RuNumbers {

    val words = mapOf(
        "ноль" to 0,
        "один" to 1, "одну" to 1, "одна" to 1, "первого" to 1,
        "два" to 2, "две" to 2, "второго" to 2,
        "три" to 3, "третьего" to 3,
        "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8,
        "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60
    )

    // Одно число: цифры или слово (слова — по убыванию длины, чтобы
    // «девятнадцать» не матчилось как «девять»).
    val singleNum: String = buildString {
        append("""(?:\d+|""")
        append(words.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        append(")")
    }

    // Составное число перед чем-либо: «сорок девять», «двадцать пять».
    val numberGroup = """($singleNum(?:\s+$singleNum)*)"""

    // Складывает токены: «сорок девять» → 40 + 9 = 49
    fun parseCompound(s: String): Int? {
        val tokens = s.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        var sum = 0
        for (t in tokens) {
            val v = t.toIntOrNull() ?: words[t] ?: return null
            sum += v
        }
        return sum
    }
}
