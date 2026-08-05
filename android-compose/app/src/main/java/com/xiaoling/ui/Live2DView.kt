package com.xiaoling.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoling.core.MascotState

/**
 * 3D 数字人内嵌视图:WebView + three.js/three-vrm 加载 assets/avatar3d/index.html。
 * 原生层按状态/说话经 JS 桥驱动表情 blendshape 与口型。缺模型/运行时显示提示,不影响 App。
 */
@Composable
fun Avatar3DView(state: MascotState, talking: Boolean, modifier: Modifier = Modifier) {
    val stateName = when (state) {
        MascotState.Alarm -> "alarm"
        MascotState.Listening -> "listen"
        MascotState.Thinking -> "think"
        MascotState.Talking -> "talk"
        MascotState.Caring -> "caring"
        else -> "idle"
    }
    val currentState by rememberUpdatedState(stateName)
    val currentTalking by rememberUpdatedState(talking)
    var modelReady by remember { mutableStateOf(false) }
    fun applyState(web: WebView, name: String, isTalking: Boolean) {
        web.evaluateJavascript("window.XLAvatar&&XLAvatar.setState('$name')", null)
        web.evaluateJavascript("window.XLAvatar&&XLAvatar.setTalking($isTalking)", null)
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.blockNetworkLoads = true
                    isClickable = false
                    isFocusable = false
                    setBackgroundColor(Color.TRANSPARENT)
                    addJavascriptInterface(AvatarLoadBridge(
                        onReady = { modelReady = true },
                        onFailed = { modelReady = false }
                    ), "XiaolingNative")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            applyState(view, currentState, currentTalking)
                        }
                    }
                    loadUrl("file:///android_asset/avatar3d/index.html")
                }
            },
            update = { web -> applyState(web, stateName, talking) },
            onRelease = { web ->
                web.removeJavascriptInterface("XiaolingNative")
                web.destroy()
            }
        )
        if (!modelReady) Avatar(state, Modifier.fillMaxSize())
    }
}

private class AvatarLoadBridge(
    private val onReady: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun ready() { main.post(onReady) }
    @JavascriptInterface fun failed() { main.post(onFailed) }
}
