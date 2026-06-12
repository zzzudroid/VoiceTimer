package com.voicetimer.remind

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.voicetimer.MainActivity
import com.voicetimer.R

// Проигрывает сигнал сработавшего напоминания и показывает уведомление.
// EXACT — громкий зацикленный сигнал будильника (как у таймера),
// INEXACT — мягкий одиночный звук уведомления.
class ReminderAlarmService : Service() {

    companion object {
        const val ACTION_FIRE   = "com.voicetimer.REMIND_ALARM_FIRE"
        const val ACTION_STOP   = "com.voicetimer.REMIND_ALARM_STOP"
        const val ACTION_SNOOZE = "com.voicetimer.REMIND_ALARM_SNOOZE"
        const val EXTRA_SNOOZE_MIN = "snooze_min"

        private const val CHANNEL_EXACT   = "ch_remind_exact"
        private const val CHANNEL_INEXACT = "ch_remind_inexact"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentId: Long = -1L

    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); createChannels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIRE -> fire(intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L))
            ACTION_STOP -> stopAll(markDone = true)
            ACTION_SNOOZE -> snooze(intent.getIntExtra(EXTRA_SNOOZE_MIN, 10))
        }
        return START_NOT_STICKY
    }

    private fun fire(id: Long) {
        ReminderStore.load(this)
        val r = ReminderStore.byId(id) ?: run { stopSelf(); return }
        currentId = id
        val exact = r.type == ReminderType.EXACT

        startForeground(notifId(id), buildNotif(r))

        if (exact) acquireWakeLock()
        playSound(loud = exact)
    }

    private fun playSound(loud: Boolean) {
        val type = if (loud) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
        val uri = RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(applicationContext, uri)
            isLooping = loud                 // громкий сигнал зациклен, мягкий — один раз
            setOnCompletionListener { if (!loud) stopAll(markDone = true) }
            prepare()
            start()
        }
    }

    private fun snooze(minutes: Int) {
        val r = ReminderStore.byId(currentId)
        stopSound()
        if (r != null) {
            val next = r.copy(triggerAt = System.currentTimeMillis() + minutes * 60_000L, done = false)
            ReminderStore.upsert(this, next)
            ReminderScheduler.schedule(this, next)
        }
        nm().cancel(notifId(currentId))
        stopSelf()
    }

    private fun stopAll(markDone: Boolean) {
        if (markDone && currentId >= 0) ReminderStore.setDone(this, currentId, true)
        stopSound()
        nm().cancel(notifId(currentId))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopSound() {
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release(); mediaPlayer = null
        wakeLock?.runCatching { if (isHeld) release() }; wakeLock = null
    }

    // ── Уведомление ───────────────────────────────────────────────────────────
    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    private fun notifId(id: Long) = 1000 + id.toInt()

    private fun createChannels() {
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_EXACT, "Напоминания (точные)", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); setBypassDnd(true)
            }
        )
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_INEXACT, "Напоминания (неточные)", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppPi() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionPi(action: String, reqExtra: Int, snoozeMin: Int = 0) = PendingIntent.getService(
        this, reqExtra,
        Intent(this, ReminderAlarmService::class.java).apply {
            this.action = action
            if (snoozeMin > 0) putExtra(EXTRA_SNOOZE_MIN, snoozeMin)
        },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotif(r: Reminder): Notification {
        val exact = r.type == ReminderType.EXACT
        val channel = if (exact) CHANNEL_EXACT else CHANNEL_INEXACT
        val b = NotificationCompat.Builder(this, channel)
            .setContentTitle("⏰ Напоминание")
            .setContentText(r.text.ifBlank { "Время!" })
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(openAppPi())
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Готово", actionPi(ACTION_STOP, 10))
            .addAction(android.R.drawable.ic_popup_reminder, "Отложить 10 мин", actionPi(ACTION_SNOOZE, 11, 10))
        if (exact) {
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(openAppPi(), true)
        }
        return b.build()
    }

    private fun acquireWakeLock() {
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "VoiceTimer:remind"
        ).also { it.acquire(5 * 60 * 1000L) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSound()
    }
}
