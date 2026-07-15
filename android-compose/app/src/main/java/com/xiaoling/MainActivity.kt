package com.xiaoling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoling.core.AppState
import com.xiaoling.service.WakeService
import com.xiaoling.ui.XiaolingApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动常驻守护服务(退出后瞬间唤起 / 离线语音唤起的载体)
        try { WakeService.start(this) } catch (e: Exception) {}
        setContent {
            val vm: AppState = viewModel()
            XiaolingApp(vm)
        }
    }
}
