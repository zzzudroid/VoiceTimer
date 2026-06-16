package com.voicetimer.remind

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Резервная копия всех данных приложения: напоминания + настройки расписания.
// Формат — один JSON-файл с полем "version" для обратной совместимости.
// Сами системные будильники в копию не входят: они производные от напоминаний
// и заново выставляются через ReminderScheduler.rescheduleAll при восстановлении.
object BackupManager {
    const val VERSION = 1

    // Собирает полный бэкап в JSON-строку (с отступами для читаемости).
    fun export(context: Context): String {
        ReminderStore.load(context)
        val root = JSONObject().apply {
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("appVersion", appVersion(context))
        }

        val arr = JSONArray()
        for (r in ReminderStore.items.value) arr.put(ReminderStore.serialize(r))
        root.put("reminders", arr)

        root.put("settings", settingsToJson(ScheduleSettings.load(context)))
        return root.toString(2)
    }

    // Восстановление «заменить всё»: снимает текущие будильники, заменяет
    // напоминания и настройки данными из файла, перевзводит будильники.
    // Возвращает количество восстановленных напоминаний.
    fun restore(context: Context, text: String): Int {
        val root = JSONObject(text)
        val ver = root.optInt("version", 1)
        require(ver <= VERSION) { "Копия новее, чем поддерживает приложение (v$ver)" }

        // 1) разбираем напоминания заранее — если файл битый, упадём до изменений
        val arr = root.optJSONArray("reminders") ?: JSONArray()
        val list = ArrayList<Reminder>(arr.length())
        for (i in 0 until arr.length()) list.add(ReminderStore.deserialize(arr.getJSONObject(i)))

        // 2) снимаем все текущие запланированные будильники до замены данных
        ReminderStore.load(context)
        for (r in ReminderStore.pending()) ReminderScheduler.cancel(context, r.id)

        // 3) заменяем напоминания и настройки
        ReminderStore.replaceAll(context, list)
        root.optJSONObject("settings")?.let { ScheduleSettings.save(context, settingsFromJson(it)) }

        // 4) выставляем будильники по новым данным
        ReminderScheduler.rescheduleAll(context)
        return list.size
    }

    private fun settingsToJson(s: ScheduleHours) = JSONObject().apply {
        put("morning", s.morning)
        put("day", s.day)
        put("evening", s.evening)
        put("night", s.night)
        put("defaultHour", s.defaultHour)
        put("calendarByDefault", s.calendarByDefault)
        put("cloudWhenOnline", s.cloudWhenOnline)
    }

    private fun settingsFromJson(o: JSONObject) = ScheduleHours(
        morning = o.optInt("morning", 9),
        day = o.optInt("day", 14),
        evening = o.optInt("evening", 19),
        night = o.optInt("night", 22),
        defaultHour = o.optInt("defaultHour", 9),
        calendarByDefault = o.optBoolean("calendarByDefault", false),
        cloudWhenOnline = o.optBoolean("cloudWhenOnline", true)
    )

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
