package com.xiaoling

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.xiaoling.core.AppState
import com.xiaoling.core.TactileFeedback
import com.xiaoling.service.AppForeground
import com.xiaoling.service.WakeService
import com.xiaoling.ui.XiaolingApp

class MainActivity : ComponentActivity() {
    private var wakeRequest by mutableIntStateOf(0)
    var voicePictureInPicture by mutableStateOf(false)
        private set

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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Wear OS sends normal touch events. Give hardware-touch feedback there
        // without changing the phone experience or requiring a watch-only API.
        TactileFeedback.onWatchTouch(this, event)
        return super.dispatchTouchEvent(event)
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
        AppForeground.registerCompanionMode(::enterVoiceCompanionMode, ::returnFromVoiceCompanionMode)
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
        AppForeground.registerCompanionMode(null, null)
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        voicePictureInPicture = isInPictureInPictureMode
        AppForeground.updateCompanionMode(isInPictureInPictureMode)
    }

    private fun enterVoiceCompanionMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing || isDestroyed) return false
        return try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(1, 1))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(true)
                }
                .build()
            enterPictureInPictureMode(params).also { entered ->
                voicePictureInPicture = entered
                AppForeground.updateCompanionMode(entered)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun returnFromVoiceCompanionMode(): Boolean = try {
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
        true
    } catch (_: Throwable) {
        false
    }
}
