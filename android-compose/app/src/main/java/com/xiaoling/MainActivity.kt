package com.xiaoling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoling.core.AppState
import com.xiaoling.service.AppForeground
import com.xiaoling.ui.XiaolingApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppState = viewModel()
            XiaolingApp(vm)
        }
    }

    // App 在前台时,常驻服务让位(由 App 自己听);退到后台时,服务接管监听唤醒词
    override fun onStart() { super.onStart(); AppForeground.active = true }
    override fun onStop() { super.onStop(); AppForeground.active = false }
}
