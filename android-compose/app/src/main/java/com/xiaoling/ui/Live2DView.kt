package com.xiaoling.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
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
fun Avatar3DView(
    state: MascotState,
    talking: Boolean,
    voiceLevel: Float,
    mouthWide: Float,
    mouthRound: Float,
    emphasisTick: Int,
    caption: String,
    modifier: Modifier = Modifier,
) {
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
    val currentVoiceLevel by rememberUpdatedState(voiceLevel)
    val currentMouthWide by rememberUpdatedState(mouthWide)
    val currentMouthRound by rememberUpdatedState(mouthRound)
    val currentEmphasisTick by rememberUpdatedState(emphasisTick)
    val currentEmotion by rememberUpdatedState(avatarEmotion(state, caption))
    var modelReady by remember { mutableStateOf(false) }
    fun applyState(
        web: WebView,
        name: String,
        isTalking: Boolean,
        level: Float,
        wide: Float,
        round: Float,
        emphasis: Int,
        emotion: String,
    ) {
        web.evaluateJavascript("window.XLAvatar&&XLAvatar.setState('$name')", null)
        web.evaluateJavascript("window.XLAvatar&&XLAvatar.setTalking($isTalking)", null)
        web.evaluateJavascript(
            "window.XLAvatar&&XLAvatar.setMouthShape(" +
                "${level.coerceIn(0f, 1f)},${wide.coerceIn(0f, 1f)}," +
                "${round.coerceIn(0f, 1f)},$emphasis)",
            null,
        )
        web.evaluateJavascript("window.XLAvatar&&XLAvatar.setEmotion('$emotion')", null)
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
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    isClickable = false
                    isFocusable = false
                    setBackgroundColor(Color.TRANSPARENT)
                    addJavascriptInterface(AvatarLoadBridge(
                        onReady = { modelReady = true },
                        onFailed = { modelReady = false }
                    ), "XiaolingNative")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.scheme != "file"

                        override fun onPageFinished(view: WebView, url: String) {
                            applyState(
                                view, currentState, currentTalking, currentVoiceLevel,
                                currentMouthWide, currentMouthRound, currentEmphasisTick, currentEmotion,
                            )
                        }
                    }
                    loadUrl("file:///android_asset/avatar3d/index.html")
                }
            },
            update = { web ->
                applyState(
                    web, stateName, talking, voiceLevel, mouthWide, mouthRound,
                    emphasisTick, avatarEmotion(state, caption),
                )
            },
            onRelease = { web ->
                web.removeJavascriptInterface("XiaolingNative")
                web.destroy()
            }
        )
        if (!modelReady) Avatar(state, voiceLevel, Modifier.fillMaxSize())
    }
}

private fun avatarEmotion(state: MascotState, text: String): String {
    if (state == MascotState.Alarm) return "serious"
    if (state == MascotState.Caring) return "caring"
    val value = text.lowercase()
    return when {
        listOf("危险", "诈骗", "报警", "立即", "警告", "不要转账").any(value::contains) -> "serious"
        listOf("难过", "伤心", "担心", "害怕", "不舒服", "抱歉").any(value::contains) -> "caring"
        listOf("太好了", "开心", "恭喜", "哈哈", "真棒").any(value::contains) -> "happy"
        listOf("原来", "竟然", "真的吗", "没想到").any(value::contains) -> "surprised"
        else -> "neutral"
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
