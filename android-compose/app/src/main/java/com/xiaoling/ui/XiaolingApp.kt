package com.xiaoling.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
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
            val ui by vm.state.collectAsState()
            // 系统返回键:设置/登录页先回上一层,而不是退出 App
            BackHandler(enabled = ui.screen == Screen.Settings) { vm.showScreen(Screen.Home) }
            BackHandler(enabled = ui.screen == Screen.Login) { vm.showScreen(Screen.Settings) }
            BackHandler(enabled = ui.screen == Screen.Camera) { vm.exitCamera() }
            Box(Modifier.fillMaxSize()) {
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
                        Screen.Camera -> CameraScreen(vm)
                    }
                }
                // The main screen keeps its original waveform. Every other
                // voice-driven surface gets the persistent Dynamic-Island bar.
                if (ui.screen != Screen.Home) {
                    VoiceActivityIndicator(
                        listening = ui.listening,
                        speaking = ui.speaking,
                        thinking = ui.busy,
                        pip = true,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
