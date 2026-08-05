package com.xiaoling

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
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
    override fun onStart() {
        super.onStart()
        AppForeground.active = true
        AppForeground.registerCompanionMode(::enterVoiceCompanionMode)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeService.pause(this)
        }
    }
    override fun onStop() {
        AppForeground.active = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeService.start(this)
        }
        super.onStop()
    }

    override fun onDestroy() {
        AppForeground.registerCompanionMode(null)
        super.onDestroy()
    }

    private fun enterVoiceCompanionMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing || isDestroyed) return false
        return try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(true)
                }
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Throwable) {
            false
        }
    }
}
