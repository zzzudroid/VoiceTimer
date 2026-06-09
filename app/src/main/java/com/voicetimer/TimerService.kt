package com.voicetimer

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

class TimerService : Service() {

    companion object {
        const val ACTION_START      = "com.voicetimer.START"
        const val ACTION_PAUSE      = "com.voicetimer.PAUSE"
        const val ACTION_RESUME     = "com.voicetimer.RESUME"
        const val ACTION_RESET      = "com.voicetimer.RESET"
        const val ACTION_STOP_ALARM = "com.voicetimer.STOP_ALARM"
        const val ACTION_SNOOZE     = "com.voicetimer.SNOOZE"
        const val EXTRA_DURATION    = "duration_ms"
        const val EXTRA_SNOOZE_MS   = "snooze_ms"

        private const val CHANNEL_TIMER  = "ch_timer"
        private const val CHANNEL_ALARM  = "ch_alarm"
        private const val NOTIF_TIMER    = 1
        private const val NOTIF_ALARM    = 2

        val timeLeft       = MutableStateFlow(0L)
        val totalTime      = MutableStateFlow(0L)
        val timerState     = MutableStateFlow(TimerState.IDLE)
        val isAlarmRinging = MutableStateFlow(false)
        val actionLabel    = MutableStateFlow("")
    }

    private var countDownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); createChannels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START      -> intent.getLongExtra(EXTRA_DURATION, 0L).takeIf { it > 0 }?.let { startCountdown(it) }
            ACTION_PAUSE      -> pauseCountdown()
            ACTION_RESUME     -> resumeCountdown()
            ACTION_RESET      -> { stopAlarmSound(); stopCountdown(); actionLabel.value = ""; stopSelf() }
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_SNOOZE     -> snooze(intent.getLongExtra(EXTRA_SNOOZE_MS, 60_000L))
        }
        return START_NOT_STICKY
    }

    // ── Таймер ────────────────────────────────────────────────────────────────

    private fun startCountdown(ms: Long) {
        totalTime.value  = ms
        timeLeft.value   = ms
        timerState.value = TimerState.RUNNING
        startForeground(NOTIF_TIMER, buildTimerNotif(ms))
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(ms, 500L) {
            override fun onTick(msLeft: Long) {
                timeLeft.value = msLeft
                nm().notify(NOTIF_TIMER, buildTimerNotif(msLeft))
            }
            override fun onFinish() {
                timeLeft.value   = 0L
                timerState.value = TimerState.FINISHED
                fireAlarm()
            }
        }.start()
    }

    private fun pauseCountdown() { countDownTimer?.cancel(); timerState.value = TimerState.PAUSED }
    private fun resumeCountdown() = startCountdown(timeLeft.value)
    private fun stopCountdown()   { countDownTimer?.cancel(); timeLeft.value = totalTime.value; timerState.value = TimerState.IDLE }

    // ── Сигнал ────────────────────────────────────────────────────────────────

    private fun fireAlarm() {
        acquireWakeLock()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            setDataSource(applicationContext, uri)
            isLooping = true
            prepare()
            start()
        }
        isAlarmRinging.value = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm().notify(NOTIF_ALARM, buildAlarmNotif())
    }

    private fun stopAlarmSound() {
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        wakeLock?.release(); wakeLock = null
        isAlarmRinging.value = false
        nm().cancel(NOTIF_ALARM)
    }

    private fun stopAlarm() {
        stopAlarmSound()
        stopSelf()
    }

    // ── Snooze ────────────────────────────────────────────────────────────────

    private fun snooze(delayMs: Long) {
        stopAlarmSound()
        timerState.value = TimerState.SNOOZED
        timeLeft.value   = 0L

        // Держим сервис живым через foreground-уведомление
        val mins = delayMs / 60_000L
        val secs = (delayMs % 60_000L) / 1000L
        val label = if (mins > 0) "${mins} мин" else "${secs} сек"
        startForeground(NOTIF_TIMER, buildSnoozeNotif(label))

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(delayMs, 1000L) {
            override fun onTick(ms: Long) {}
            override fun onFinish() { stopForeground(STOP_FOREGROUND_REMOVE); fireAlarm() }
        }.start()
    }

    // ── Уведомления ───────────────────────────────────────────────────────────

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Таймер", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm().createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Сигнал таймера", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); setBypassDnd(true)
            }
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopAlarmPi() = PendingIntent.getService(
        this, 1,
        Intent(this, TimerService::class.java).apply { action = ACTION_STOP_ALARM },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildTimerNotif(ms: Long) =
        NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setContentTitle("⏱ VoiceTimer${if (actionLabel.value.isNotEmpty()) " · ${actionLabel.value}" else ""}")
            .setContentText(formatTime(ms))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .build()

    private fun buildSnoozeNotif(delayLabel: String) =
        NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setContentTitle("😴 Отложено на $delayLabel")
            .setContentText(if (actionLabel.value.isNotEmpty()) actionLabel.value else "Сигнал повторится")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .build()

    private fun buildAlarmNotif() =
        NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("⏰ ${if (actionLabel.value.isNotEmpty()) actionLabel.value else "Время вышло!"}")
            .setContentText("Нажми чтобы остановить сигнал")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(stopAlarmPi())
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopAlarmPi())
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(openApp(), true)
            .build()

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "VoiceTimer:alarm"
        )
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        mediaPlayer?.release()
        wakeLock?.release()
        isAlarmRinging.value = false
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms + 999L) / 1000L
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
