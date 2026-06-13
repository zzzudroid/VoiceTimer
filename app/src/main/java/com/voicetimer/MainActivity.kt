package com.voicetimer

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.voicetimer.ui.AppRoot

class MainActivity : ComponentActivity() {

    companion object {
        const val TAB_TIMER = "timer"
        const val TAB_REMINDERS = "reminders"
        const val TAB_SETTINGS = "settings"
    }

    private val timerViewModel: TimerViewModel by viewModels()
    private val remindViewModel: RemindViewModel by viewModels()

    private var selectedTab by mutableStateOf(TAB_TIMER)
    private var pendingMicAction: (() -> Unit)? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingMicAction?.invoke()
        pendingMicAction = null
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* уведомления опциональны */ }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* при отказе напоминание просто не попадёт в календарь */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ensureExactAlarmPermission()
        com.voicetimer.remind.ReminderScheduler.rescheduleAll(applicationContext)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot(
                    timerViewModel = timerViewModel,
                    remindViewModel = remindViewModel,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                    onTimerMic = { withMic { timerViewModel.toggleListening() } },
                    onRemindMic = { withMic { remindViewModel.toggleListening() } }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        timerViewModel.stopListening()
        remindViewModel.stopListening()
    }

    private fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestCalendarPermission() {
        calendarPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        )
    }

    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
            }
        }
    }
}
