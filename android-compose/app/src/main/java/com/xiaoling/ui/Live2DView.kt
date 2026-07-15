package com.xiaoling.ui

import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
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
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.domStorageEnabled = true
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/avatar3d/index.html")
            }
        },
        update = { web ->
            web.evaluateJavascript("window.XLAvatar&&XLAvatar.setState('$stateName')", null)
            web.evaluateJavascript("window.XLAvatar&&XLAvatar.setTalking(${if (talking) "true" else "false"})", null)
        }
    )
}
