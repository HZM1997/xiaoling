package com.xiaoling.ui

import androidx.activity.compose.BackHandler
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
            // 系统返回键:在设置页时先回主页,而不是退出 App
            BackHandler(enabled = ui.screen == Screen.Settings) { vm.showScreen(Screen.Home) }
            when (ui.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}
