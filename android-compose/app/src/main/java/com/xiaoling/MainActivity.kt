package com.xiaoling

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.xiaoling.core.AppState
import com.xiaoling.service.AppForeground
import com.xiaoling.service.WakeService
import com.xiaoling.ui.XiaolingApp

class MainActivity : ComponentActivity() {
    private var wakeRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptWakeRequest(intent)
        setContent {
            val vm: AppState = viewModel()
            val request = wakeRequest
            LaunchedEffect(request) {
                if (request > 0) vm.startVoiceConversation()
            }
            XiaolingApp(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptWakeRequest(intent)
    }

    private fun acceptWakeRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(WakeService.EXTRA_WAKE, false) == true) {
            intent.removeExtra(WakeService.EXTRA_WAKE)
            wakeRequest++
        }
    }

    // App 在前台时,常驻服务让位(由 App 自己听);退到后台时,服务接管监听唤醒词
    override fun onStart() { super.onStart(); AppForeground.active = true }
    override fun onStop() {
        AppForeground.active = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeService.start(this)
        }
        super.onStop()
    }
}
