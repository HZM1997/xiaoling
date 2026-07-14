package com.xiaoling.ui

import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoling.core.MascotState

/**
 * Live2D 形象内嵌视图:WebView 加载 assets/live2d/index.html,
 * 原生层按状态/说话经 JS 桥驱动表情与口型。缺模型/运行时会显示提示,不影响 App。
 */
@Composable
fun Live2DView(state: MascotState, talking: Boolean, modifier: Modifier = Modifier) {
    val stateName = when (state) {
        MascotState.Alarm -> "alarm"
        MascotState.Listening -> "listen"
        MascotState.Thinking -> "think"
        MascotState.Talking -> "talk"
        MascotState.Caring -> "caring"
        else -> "idle"
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/live2d/index.html")
            }
        },
        update = { web ->
            web.evaluateJavascript("window.XLAvatar&&XLAvatar.setState('$stateName')", null)
            web.evaluateJavascript("window.XLAvatar&&XLAvatar.setTalking(${if (talking) "true" else "false"})", null)
        }
    )
}
