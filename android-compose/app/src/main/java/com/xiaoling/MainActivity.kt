package com.xiaoling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoling.core.AppState
import com.xiaoling.ui.XiaolingApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppState = viewModel()
            XiaolingApp(vm)
        }
    }
}
