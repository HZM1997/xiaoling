package com.xiaoling.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.XiaolingTheme

@Composable
fun XiaolingApp(vm: AppState) {
    XiaolingTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val ui by vm.state.collectAsStateWithLifecycle()
            // 系统返回键:设置/登录页先回上一层,而不是退出 App
            BackHandler(enabled = ui.screen == Screen.Settings) { vm.showScreen(Screen.Home) }
            BackHandler(enabled = ui.screen == Screen.Login) { vm.showScreen(Screen.Settings) }
            AnimatedContent(
                targetState = ui.screen,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val direction = if (forward) 1 else -1
                    (slideInHorizontally { it * direction / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * direction / 7 } + fadeOut())
                },
                label = "screen-transition"
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Login -> LoginScreen(vm)
                }
            }
        }
    }
}
