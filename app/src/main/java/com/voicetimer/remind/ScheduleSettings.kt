package com.voicetimer.remind

import android.content.Context

// Расписание дня: в какой час показывать «неточные» напоминания
// (когда сказан только день или часть суток, без точного времени).
// Хранится в SharedPreferences, редактируется на экране настроек.
data class ScheduleHours(
    val morning: Int = 9,   // «утром»
    val day: Int = 14,      // «днём»
    val evening: Int = 19,  // «вечером»
    val night: Int = 22,    // «ночью»
    // куда отнести «неточное» напоминание без части суток (просто «завтра»)
    val defaultHour: Int = 9,
    val calendarByDefault: Boolean = false,
    // распознавание: облачное (Google) при наличии интернета, иначе локальный Vosk
    val cloudWhenOnline: Boolean = true
)

object ScheduleSettings {
    private const val PREFS = "remind_prefs"

    fun load(context: Context): ScheduleHours {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ScheduleHours(
            morning     = p.getInt("morning", 9),
            day         = p.getInt("day", 14),
            evening     = p.getInt("evening", 19),
            night       = p.getInt("night", 22),
            defaultHour = p.getInt("defaultHour", 9),
            calendarByDefault = p.getBoolean("calendarByDefault", false),
            cloudWhenOnline = p.getBoolean("cloudWhenOnline", true)
        )
    }

    fun save(context: Context, h: ScheduleHours) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("morning", h.morning)
            putInt("day", h.day)
            putInt("evening", h.evening)
            putInt("night", h.night)
            putInt("defaultHour", h.defaultHour)
            putBoolean("calendarByDefault", h.calendarByDefault)
            putBoolean("cloudWhenOnline", h.cloudWhenOnline)
            apply()
        }
    }
}
