package com.voicetimer.remind

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

// Добавляет напоминание событием в системный календарь (Google Calendar и т.п.)
// через CalendarContract. Требует разрешений READ_CALENDAR/WRITE_CALENDAR.
object CalendarHelper {

    // Возвращает id первого доступного для записи календаря или null
    private fun primaryCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    // Создаёт событие; возвращает true при успехе. На время события — 30 минут,
    // с уведомлением календаря в момент начала.
    fun addEvent(context: Context, title: String, startMs: Long): Boolean {
        val calId = primaryCalendarId(context) ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title.ifBlank { "Напоминание" })
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, startMs + 30 * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return false
        val eventId = ContentUris.parseId(uri)

        // Уведомление календаря в момент начала
        runCatching {
            context.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 0)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
            )
        }
        return true
    }
}
