package com.voicetimer.remind

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Постоянное хранилище списка напоминаний в JSON-файле приватной папки.
// Синглтон: один и тот же список видят и UI, и BroadcastReceiver-ы.
object ReminderStore {
    private const val FILE_NAME = "reminders.json"

    private val _items = MutableStateFlow<List<Reminder>>(emptyList())
    val items = _items.asStateFlow()

    @Volatile private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val f = File(context.filesDir, FILE_NAME)
        if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                val list = ArrayList<Reminder>(arr.length())
                for (i in 0 until arr.length()) list.add(deserialize(arr.getJSONObject(i)))
                _items.value = list.sortedBy { it.triggerAt }
            }
        }
        loaded = true
    }

    // Сериализация одного напоминания в JSON. Используется и хранилищем, и бэкапом —
    // чтобы формат не разъехался при добавлении новых полей.
    fun serialize(r: Reminder): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("text", r.text)
        put("triggerAt", r.triggerAt)
        put("type", r.type.name)
        put("done", r.done)
        put("inCalendar", r.inCalendar)
        put("recurrence", r.recurrence.name)
        put("recurrenceInterval", r.recurrenceInterval)
        put("createdAt", r.createdAt)
    }

    fun deserialize(o: JSONObject): Reminder = Reminder(
        id = o.getLong("id"),
        text = o.getString("text"),
        triggerAt = o.getLong("triggerAt"),
        type = ReminderType.valueOf(o.optString("type", "EXACT")),
        done = o.optBoolean("done", false),
        inCalendar = o.optBoolean("inCalendar", false),
        recurrence = RecurrenceType.valueOf(o.optString("recurrence", "NONE")),
        recurrenceInterval = o.optInt("recurrenceInterval", 1),
        createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )

    // Полная замена списка (для восстановления из резервной копии «заменить всё»).
    @Synchronized
    fun replaceAll(context: Context, items: List<Reminder>) {
        _items.value = items.sortedBy { it.triggerAt }
        loaded = true
        persist(context)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (r in _items.value) arr.put(serialize(r))
        File(context.filesDir, FILE_NAME).writeText(arr.toString())
    }

    @Synchronized
    fun upsert(context: Context, r: Reminder) {
        load(context)
        val list = _items.value.filter { it.id != r.id } + r
        _items.value = list.sortedBy { it.triggerAt }
        persist(context)
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        load(context)
        _items.value = _items.value.filter { it.id != id }
        persist(context)
    }

    @Synchronized
    fun setDone(context: Context, id: Long, done: Boolean) {
        load(context)
        _items.value = _items.value.map { if (it.id == id) it.copy(done = done) else it }
        persist(context)
    }

    fun byId(id: Long): Reminder? = _items.value.firstOrNull { it.id == id }

    // Будущие, ещё не выполненные — для планирования будильников
    fun pending(): List<Reminder> =
        _items.value.filter { !it.done && it.triggerAt > System.currentTimeMillis() }
}
