package com.xiaoling.ui

import android.graphics.Color
import android.os.Build
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
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val useWebGlAvatar = remember { supportsWebGlAvatar() }
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
        // Always keep a native animated avatar underneath the WebView. Older
        // MIUI WebView versions can execute JavaScript but fail to composite a
        // transparent WebGL canvas; hiding this fallback after JS startup left
        // the whole assistant area blank on those devices.
        AvatarFallback(
            state = currentState,
            talking = currentTalking,
            voiceLevel = currentVoiceLevel,
            modifier = Modifier.fillMaxSize(),
        )
        if (useWebGlAvatar) {
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
                    // Three.js needs a hardware-backed WebGL canvas. The
                    // native avatar below remains visible if a legacy WebView
                    // cannot produce that canvas.
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    alpha = 1f
                    setBackgroundColor(Color.TRANSPARENT)
                    addJavascriptInterface(AvatarLoadBridge(
                        onReady = {
                            Log.d("XiaolingAvatar", "3D avatar renderer ready")
                        },
                        onFailed = { reason ->
                            Log.e("XiaolingAvatar", "3D model load failed: $reason")
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
        }
    }
}

private fun supportsWebGlAvatar(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val version = WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
    val major = version.substringBefore('.').toIntOrNull() ?: return false
    // Redmi A9 is currently on Android System WebView 97. It starts the JS
    // module but leaves transparent WebGL canvases empty, so keep its native
    // animated avatar visible instead of replacing it with a blank surface.
    return major >= 110
}

@Composable
private fun AvatarFallback(
    state: String,
    talking: Boolean,
    voiceLevel: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "avatar-fallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(2300)),
        label = "avatar-fallback-phase",
    )
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * 0.49f)
        val scale = minOf(size.width, size.height) / 360f
        val bob = kotlin.math.sin(phase.toDouble()).toFloat() * 5f * scale
        val headCenter = center + Offset(0f, -52f * scale + bob)
        val bodyTop = center.y + 34f * scale + bob
        val teal = ComposeColor(0xFF167C87)
        val lightTeal = ComposeColor(0xFF5AD6C3)
        val dark = ComposeColor(0xFF202C3A)
        val skin = ComposeColor(0xFFF0C5B1)
        val mouthOpen = if (talking) (10f + voiceLevel.coerceIn(0f, 1f) * 14f) * scale else 4f * scale

        drawCircle(color = ComposeColor(0x1422A6A2), radius = 130f * scale, center = center + Offset(0f, bob))
        drawRoundRect(
            color = teal,
            topLeft = Offset(center.x - 82f * scale, bodyTop),
            size = Size(164f * scale, 154f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(52f * scale, 52f * scale),
        )
        drawCircle(color = dark, radius = 74f * scale, center = headCenter)
        drawCircle(color = skin, radius = 62f * scale, center = headCenter + Offset(0f, 7f * scale))
        drawArc(
            color = dark, startAngle = 184f, sweepAngle = 172f, useCenter = true,
            topLeft = headCenter + Offset(-68f * scale, -72f * scale),
            size = Size(136f * scale, 95f * scale),
        )
        val eyeShift = if (state == "listen") 3f * scale else 0f
        listOf(-24f, 24f).forEach { x ->
            drawCircle(color = ComposeColor.White, radius = 13f * scale, center = headCenter + Offset(x * scale + eyeShift, 5f * scale))
            drawCircle(color = ComposeColor(0xFF237F8A), radius = 7f * scale, center = headCenter + Offset(x * scale + eyeShift, 5f * scale))
        }
        drawRoundRect(
            color = ComposeColor(0xFFA94459),
            topLeft = headCenter + Offset(-16f * scale, 31f * scale),
            size = Size(32f * scale, mouthOpen),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale),
        )
        drawCircle(color = lightTeal, radius = (10f + voiceLevel * 5f) * scale, center = Offset(center.x, bodyTop + 56f * scale))
        drawCircle(color = ComposeColor(0x3326A79B), radius = 14f * scale, center = Offset(center.x, bodyTop + 56f * scale), style = Stroke(2f * scale))
        if (state == "listen" || talking) {
            val radius = (100f + kotlin.math.sin(phase.toDouble()).toFloat() * 8f) * scale
            drawCircle(color = lightTeal.copy(alpha = 0.38f), radius = radius, center = center + Offset(0f, bob), style = Stroke(2f * scale, cap = StrokeCap.Round))
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
