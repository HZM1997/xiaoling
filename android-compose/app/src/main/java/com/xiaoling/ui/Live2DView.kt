package com.xiaoling.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.view.View
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
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
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .build()
                var retries = 0
                lateinit var modelWebView: WebView
                modelWebView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = false
                    isClickable = false
                    isFocusable = false
                    // MIUI WebView can leave a transparent hardware canvas
                    // blank; software compositing is reliable for this small
                    // avatar and still keeps the rest of the UI accelerated.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    alpha = 1f
                    setBackgroundColor(Color.TRANSPARENT)
                    addJavascriptInterface(AvatarLoadBridge(
                        onReady = { modelReady = true },
                        onFailed = { reason ->
                            Log.e("XiaolingAvatar", "3D model load failed: $reason")
                            modelReady = false
                            if (retries < 1) {
                                retries++
                                postDelayed({ modelWebView.reload() }, 800L)
                            }
                        }
                    ), "XiaolingNative")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            Log.d(
                                "XiaolingAvatar",
                                "${message.message()} @${message.sourceId()}:${message.lineNumber()}",
                            )
                            return true
                        }
                    }
                    webViewClient = object : WebViewClientCompat() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.url.host != "appassets.androidplatform.net"

                        override fun onPageFinished(view: WebView, url: String) {
                            modelReady = true
                            applyState(
                                view, currentState, currentTalking, currentVoiceLevel,
                                currentMouthWide, currentMouthRound, currentEmphasisTick, currentEmotion,
                            )
                        }
                    }
                    loadUrl("https://appassets.androidplatform.net/assets/avatar3d/index.html")
                }
                modelWebView
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
        if (!modelReady) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(42.dp),
                color = ComposeColor(0xFF147D8F),
                strokeWidth = 3.dp,
            )
        }
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
    private val onFailed: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun ready() { main.post(onReady) }
    @JavascriptInterface fun failed(reason: String?) {
        main.post { onFailed(reason.orEmpty().take(300)) }
    }
}
