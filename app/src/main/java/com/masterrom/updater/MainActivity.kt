package com.masterrom.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterrom.updater.ui.home.HomeScreen
import com.masterrom.updater.ui.home.HomeViewModel
import com.masterrom.updater.ui.settings.AppChangelogScreen
import com.masterrom.updater.ui.settings.SettingsScreen
import com.masterrom.updater.ui.theme.MasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MasterTheme {
                AppRoot()
            }
        }
    }
}

/** Start screen and settings, with a One UI style horizontal slide between them. */
@Composable
private fun AppRoot() {
    val vm: HomeViewModel = viewModel()
    // 0 = start screen, 1 = settings, 2 = updater changelog, 3 = ROM version history
    var screen by rememberSaveable { mutableStateOf(0) }
    // where the version history was opened from (start screen or settings)
    var historyFrom by rememberSaveable { mutableStateOf(1) }
    BackHandler(enabled = screen > 0) { screen = if (screen == 3) historyFrom else screen - 1 }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val duration = 380
            if (targetState > initialState) {
                (slideInHorizontally(tween(duration)) { it } + fadeIn(tween(duration))) togetherWith
                    (slideOutHorizontally(tween(duration)) { -it / 4 } + fadeOut(tween(duration)))
            } else {
                (slideInHorizontally(tween(duration)) { -it / 4 } + fadeIn(tween(duration))) togetherWith
                    (slideOutHorizontally(tween(duration)) { it } + fadeOut(tween(duration)))
            }
        },
        label = "screen",
    ) { current ->
        when (current) {
            3 -> com.masterrom.updater.ui.settings.RomHistoryScreen(onBack = { screen = historyFrom })
            2 -> AppChangelogScreen(onBack = { screen = 1 })
            1 -> SettingsScreen(
                vm,
                onBack = { screen = 0 },
                onOpenChangelog = { screen = 2 },
                onOpenHistory = { historyFrom = 1; screen = 3 },
            )
            else -> HomeScreen(vm, onOpenSettings = { screen = 1 }, onOpenHistory = { historyFrom = 0; screen = 3 })
        }
    }
}
