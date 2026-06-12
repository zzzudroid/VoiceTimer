package com.voicetimer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.voicetimer.RemindViewModel
import com.voicetimer.TimerViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    TIMER("Таймер", Icons.Filled.Timer),
    REMINDERS("Напоминания", Icons.Filled.Notifications),
    SETTINGS("Настройки", Icons.Filled.Settings)
}

@Composable
fun AppRoot(
    timerViewModel: TimerViewModel,
    remindViewModel: RemindViewModel,
    onTimerMic: () -> Unit,
    onRemindMic: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.TIMER) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.TIMER     -> MainScreen(viewModel = timerViewModel, onMicClick = onTimerMic)
                Tab.REMINDERS -> RemindersScreen(viewModel = remindViewModel, onMicClick = onRemindMic)
                Tab.SETTINGS  -> SettingsScreen(viewModel = remindViewModel)
            }
        }
    }
}
