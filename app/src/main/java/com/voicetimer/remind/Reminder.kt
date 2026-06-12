package com.voicetimer.remind

// Тип напоминания:
//  EXACT   — указано точное время («завтра в 9:00», «через полчаса») →
//            громкий зацикленный сигнал будильника.
//  INEXACT — указан только день/часть суток («завтра», «вечером позвонить») →
//            срабатывает в удобный час по расписанию дня, мягким одиночным звуком.
enum class ReminderType { EXACT, INEXACT }

data class Reminder(
    val id: Long,
    val text: String,            // что напомнить
    val triggerAt: Long,         // когда сработает (epoch ms)
    val type: ReminderType,
    val done: Boolean = false,   // уже сработало/отмечено выполненным
    val inCalendar: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
