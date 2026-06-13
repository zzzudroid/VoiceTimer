package com.voicetimer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.voicetimer.MainActivity
import com.voicetimer.RemindViewModel
import com.voicetimer.TimerViewModel

private data class TabDef(val key: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabDef(MainActivity.TAB_TIMER, "Таймер", Icons.Filled.Timer),
    TabDef(MainActivity.TAB_REMINDERS, "Напоминания", Icons.Filled.Notifications),
    TabDef(MainActivity.TAB_SETTINGS, "Настройки", Icons.Filled.Settings)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(
    timerViewModel: TimerViewModel,
    remindViewModel: RemindViewModel,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onTimerMic: () -> Unit,
    onRemindMic: () -> Unit
) {
    val startIndex = TABS.indexOfFirst { it.key == selectedTab }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { TABS.size })

    // Внешняя смена вкладки (нижняя навигация, интент виджета) → листаем pager
    LaunchedEffect(selectedTab) {
        val idx = TABS.indexOfFirst { it.key == selectedTab }
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }
    // Свайп завершён → фиксируем выбранную вкладку
    LaunchedEffect(pagerState.settledPage) {
        val key = TABS[pagerState.settledPage].key
        if (key != selectedTab) onSelectTab(key)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == i,
                        onClick = { onSelectTab(t.key) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            beyondBoundsPageCount = 1   // держим соседние страницы готовыми — свайп без рывков
        ) { page ->
            when (TABS[page].key) {
                MainActivity.TAB_REMINDERS -> RemindersScreen(viewModel = remindViewModel, onMicClick = onRemindMic)
                MainActivity.TAB_SETTINGS  -> SettingsScreen(viewModel = remindViewModel)
                else                       -> MainScreen(viewModel = timerViewModel, onMicClick = onTimerMic)
            }
        }
    }
}
