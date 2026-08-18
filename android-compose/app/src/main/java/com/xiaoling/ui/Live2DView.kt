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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Path
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
    val currentEmotion by rememberUpdatedState(avatarEmotion(state, caption, emphasisTick))
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
            emotion = currentEmotion,
            talking = currentTalking,
            voiceLevel = currentVoiceLevel,
            mouthWide = currentMouthWide,
            mouthRound = currentMouthRound,
            emphasisTick = currentEmphasisTick,
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
                        emphasisTick, avatarEmotion(state, caption, emphasisTick),
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
    emotion: String,
    talking: Boolean,
    voiceLevel: Float,
    mouthWide: Float,
    mouthRound: Float,
    emphasisTick: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "avatar-fallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(3100)),
        label = "avatar-fallback-phase",
    )
    val microPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(5300)),
        label = "avatar-fallback-micro-phase",
    )
    val pose = remember(state, emotion) { nativeAvatarPose(state, emotion) }
    val poseTilt by animateFloatAsState(pose.headTilt, tween(340), label = "avatar-head-tilt")
    val poseLean by animateFloatAsState(pose.bodyLean, tween(420), label = "avatar-body-lean")
    val poseEnergy by animateFloatAsState(pose.energy, tween(300), label = "avatar-pose-energy")
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * 0.49f)
        val scale = minOf(size.width, size.height) / 360f
        val breath = kotlin.math.sin(phase.toDouble()).toFloat()
        val conversationalBeat = if (talking) kotlin.math.sin((microPhase * 2.7f).toDouble()).toFloat() else 0f
        val bob = (breath * (2.6f + poseEnergy * 2.2f) + conversationalBeat * 1.8f) * scale
        val bodyShift = poseLean * scale + kotlin.math.sin((microPhase * 0.43f).toDouble()).toFloat() * 1.3f * scale
        val headCenter = center + Offset(bodyShift * 0.45f, -52f * scale + bob)
        val bodyTop = center.y + 34f * scale + bob
        val teal = ComposeColor(0xFF167C87)
        val lightTeal = ComposeColor(0xFF5AD6C3)
        val dark = ComposeColor(0xFF202C3A)
        val skin = ComposeColor(0xFFF0C5B1)
        val mouthOpen = if (talking) {
            (6f + voiceLevel.coerceIn(0f, 1f) * 16f + mouthRound.coerceIn(0f, 1f) * 5f +
                kotlin.math.abs(conversationalBeat) * 1.6f) * scale
        } else 4f * scale
        val emphasisPose = when (emphasisTick % 4) { 1 -> -2.5f; 2 -> 2f; 3 -> -1f; else -> 0f }
        val headTilt = (poseTilt + emphasisPose +
            kotlin.math.sin((microPhase * 0.61f).toDouble()).toFloat() * poseEnergy * 1.4f) * scale
        val faceCenter = headCenter + Offset(headTilt, 0f)

        drawCircle(color = ComposeColor(0x1422A6A2), radius = 130f * scale, center = center + Offset(0f, bob))
        val shoulderY = bodyTop + 34f * scale
        val leftShoulder = Offset(center.x - 66f * scale + bodyShift, shoulderY)
        val rightShoulder = Offset(center.x + 66f * scale + bodyShift, shoulderY)
        val leftHand = Offset(
            center.x + pose.leftHandX * scale + bodyShift,
            center.y + pose.leftHandY * scale + bob,
        )
        val rightHand = Offset(
            center.x + pose.rightHandX * scale + bodyShift,
            center.y + pose.rightHandY * scale + bob,
        )
        val gesture = if (talking) conversationalBeat * 5f * scale else breath * 1.5f * scale
        drawLine(teal, leftShoulder, leftHand + Offset(0f, gesture), 24f * scale, StrokeCap.Round)
        drawLine(teal, rightShoulder, rightHand + Offset(0f, -gesture), 24f * scale, StrokeCap.Round)
        drawCircle(skin, 10f * scale, leftHand + Offset(0f, gesture))
        drawCircle(skin, 10f * scale, rightHand + Offset(0f, -gesture))
        drawRoundRect(
            color = teal,
            topLeft = Offset(center.x - (82f + breath * 1.2f) * scale + bodyShift, bodyTop),
            size = Size((164f + breath * 2.4f) * scale, 154f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(52f * scale, 52f * scale),
        )
        drawCircle(color = dark, radius = 74f * scale, center = faceCenter)
        drawCircle(color = skin, radius = 62f * scale, center = faceCenter + Offset(0f, 7f * scale))
        drawArc(
            color = dark, startAngle = 184f, sweepAngle = 172f, useCenter = true,
            topLeft = faceCenter + Offset(-68f * scale, -72f * scale),
            size = Size(136f * scale, 95f * scale),
        )
        val naturalGaze = kotlin.math.sin((microPhase * 0.39f).toDouble()).toFloat() * 2.2f
        val eyeShift = when {
            emotion == "thinking" -> Offset(4f + naturalGaze, -4f)
            emotion == "shy" -> Offset(7f, 3f)
            state == "listen" -> Offset(3f + naturalGaze, 0f)
            else -> Offset(naturalGaze, kotlin.math.sin((phase * 0.31f).toDouble()).toFloat())
        }
        val blinkSignal = kotlin.math.sin((phase * 1.17f).toDouble()) +
            kotlin.math.sin((microPhase * 2.13f).toDouble()) * 0.72
        val blink = blinkSignal > 1.56
        listOf(-1f, 1f).forEachIndexed { index, side ->
            val eyeCenter = faceCenter + Offset(side * 24f * scale + eyeShift.x * scale, (5f + eyeShift.y) * scale)
            val closed = blink || emotion == "sleepy" || (emotion == "playful" && index == 1)
            when {
                emotion == "love" -> drawHeart(eyeCenter, 12f * scale, ComposeColor(0xFFE64D74))
                emotion == "happy" || emotion == "proud" -> drawArc(
                    color = dark, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = eyeCenter - Offset(13f * scale, 5f * scale),
                    size = Size(26f * scale, 15f * scale), style = Stroke(4f * scale, cap = StrokeCap.Round),
                )
                closed -> drawLine(
                    color = dark,
                    start = eyeCenter - Offset(10f * scale, 0f),
                    end = eyeCenter + Offset(10f * scale, if (emotion == "playful") -3f * scale else 0f),
                    strokeWidth = 4f * scale, cap = StrokeCap.Round,
                )
                else -> {
                    val eyeHeight = when (emotion) {
                        "surprised", "curious" -> 15f
                        "serious", "warning" -> 8f
                        "sad", "caring" -> 11f
                        else -> 13f
                    } * scale
                    drawOval(
                        color = ComposeColor.White,
                        topLeft = eyeCenter - Offset(13f * scale, eyeHeight),
                        size = Size(26f * scale, eyeHeight * 2f),
                    )
                    val pupilY = if (emotion == "sad") 3f else 0f
                    drawCircle(color = ComposeColor(0xFF237F8A), radius = 7f * scale,
                        center = eyeCenter + Offset(0f, pupilY * scale))
                    drawCircle(color = ComposeColor.White, radius = 2f * scale,
                        center = eyeCenter + Offset(-2f * scale, -2f * scale + pupilY * scale))
                }
            }
            val browLift = when (emotion) {
                "surprised", "curious" -> -10f
                "sad", "caring" -> if (side < 0) -2f else -2f
                "serious", "warning" -> 3f
                "confused" -> if (side < 0) -10f else 2f
                else -> -5f
            }
            val browSlope = when (emotion) {
                "sad", "caring" -> side * 5f
                "serious", "warning" -> -side * 5f
                "confused" -> side * 3f
                else -> 0f
            }
            drawLine(
                color = dark,
                start = faceCenter + Offset((side * 24f - 10f) * scale, browLift * scale),
                end = faceCenter + Offset((side * 24f + 10f) * scale, (browLift + browSlope) * scale),
                strokeWidth = 4f * scale, cap = StrokeCap.Round,
            )
            if ((emotion == "sad" || emotion == "caring") && index == 1) {
                drawOval(ComposeColor(0xFF69BDE5).copy(alpha = 0.82f),
                    topLeft = eyeCenter + Offset(4f * scale, 13f * scale),
                    size = Size(5f * scale, 10f * scale))
            }
        }
        if (emotion == "shy" || emotion == "love") {
            drawCircle(ComposeColor(0x55ED6E8C), 10f * scale, faceCenter + Offset(-42f * scale, 24f * scale))
            drawCircle(ComposeColor(0x55ED6E8C), 10f * scale, faceCenter + Offset(42f * scale, 24f * scale))
        }
        if (talking || emotion in setOf("surprised", "warning")) {
            val width = when (emotion) {
                "surprised" -> 21f
                "warning" -> 27f
                else -> 28f + mouthWide.coerceIn(0f, 1f) * 10f - mouthRound.coerceIn(0f, 1f) * 5f
            } * scale
            drawRoundRect(
                color = ComposeColor(0xFFA94459),
                topLeft = faceCenter + Offset(-width / 2f, 31f * scale),
                size = Size(width, if (emotion == "surprised") 18f * scale else mouthOpen),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * scale, 10f * scale),
            )
        } else {
            val smile = emotion in setOf("happy", "love", "proud", "playful", "shy")
            drawArc(
                color = ComposeColor(0xFFA94459), startAngle = if (smile) 15f else 200f,
                sweepAngle = if (smile) 150f else 140f, useCenter = false,
                topLeft = faceCenter + Offset(-17f * scale, 26f * scale),
                size = Size(34f * scale, 18f * scale), style = Stroke(4f * scale, cap = StrokeCap.Round),
            )
        }
        drawCircle(color = lightTeal, radius = (10f + voiceLevel * 5f) * scale, center = Offset(center.x, bodyTop + 56f * scale))
        drawCircle(color = ComposeColor(0x3326A79B), radius = 14f * scale, center = Offset(center.x, bodyTop + 56f * scale), style = Stroke(2f * scale))
        if (state == "listen" || talking) {
            val radius = (100f + kotlin.math.sin(phase.toDouble()).toFloat() * 8f) * scale
            drawCircle(color = lightTeal.copy(alpha = 0.38f), radius = radius, center = center + Offset(0f, bob), style = Stroke(2f * scale, cap = StrokeCap.Round))
        }
    }
}

private data class NativeAvatarPose(
    val headTilt: Float = 0f,
    val bodyLean: Float = 0f,
    val energy: Float = 0.35f,
    val leftHandX: Float = -88f,
    val leftHandY: Float = 105f,
    val rightHandX: Float = 88f,
    val rightHandY: Float = 105f,
)

private fun nativeAvatarPose(state: String, emotion: String): NativeAvatarPose = when (emotion) {
    "happy", "love" -> NativeAvatarPose(-2f, 0f, 1f, -108f, 48f, 108f, 45f)
    "playful", "shy" -> NativeAvatarPose(-6f, -3f, 0.72f, -92f, 94f, 82f, 58f)
    "surprised", "curious" -> NativeAvatarPose(4f, 2f, 0.9f, -103f, 67f, 103f, 67f)
    "confused" -> NativeAvatarPose(8f, 4f, 0.52f, -86f, 102f, 76f, 31f)
    "thinking" -> NativeAvatarPose(5f, 3f, 0.28f, -86f, 103f, 48f, -5f)
    "explaining" -> NativeAvatarPose(-2f, -2f, 0.82f, -94f, 88f, 112f, 48f)
    "warning", "serious" -> NativeAvatarPose(0f, 0f, 0.76f, -82f, 100f, 105f, 28f)
    "sad", "caring" -> NativeAvatarPose(4f, 3f, 0.20f, -72f, 91f, 72f, 91f)
    "sleepy" -> NativeAvatarPose(5f, 4f, 0.08f, -70f, 108f, 70f, 108f)
    "proud", "warm" -> NativeAvatarPose(-2f, -1f, 0.62f, -98f, 74f, 96f, 75f)
    "attentive" -> NativeAvatarPose(-3f, -2f, 0.46f, -84f, 99f, 68f, 23f)
    else -> when (state) {
        "talk" -> NativeAvatarPose(-1f, -1f, 0.58f, -94f, 86f, 96f, 74f)
        "listen" -> NativeAvatarPose(-3f, -2f, 0.45f, -86f, 100f, 68f, 24f)
        else -> NativeAvatarPose()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeart(
    center: Offset,
    radius: Float,
    color: ComposeColor,
) {
    val path = Path().apply {
        moveTo(center.x, center.y + radius * 0.82f)
        cubicTo(center.x - radius * 1.35f, center.y, center.x - radius * 0.72f, center.y - radius,
            center.x, center.y - radius * 0.34f)
        cubicTo(center.x + radius * 0.72f, center.y - radius, center.x + radius * 1.35f, center.y,
            center.x, center.y + radius * 0.82f)
        close()
    }
    drawPath(path, color)
}

private fun avatarEmotion(state: MascotState, text: String, emphasisTick: Int): String {
    if (state == MascotState.Alarm) return "warning"
    val value = text.lowercase()
    return when {
        listOf("危险", "诈骗", "报警", "立即停止", "警告", "不要转账", "可疑链接").any(value::contains) -> "warning"
        listOf("难过", "伤心", "想哭", "失去", "遗憾", "去世").any(value::contains) -> "sad"
        listOf("担心", "害怕", "不舒服", "疼", "抱歉", "陪着您", "别着急").any(value::contains) -> "caring"
        listOf("爱你", "想你", "喜欢你", "么么", "亲爱的", "真暖心").any(value::contains) -> "love"
        listOf("害羞", "不好意思", "夸得", "脸红").any(value::contains) -> "shy"
        listOf("哈哈", "逗你", "开玩笑", "调皮", "嘿嘿").any(value::contains) -> "playful"
        listOf("太好了", "开心", "恭喜", "真棒", "成功了", "完成了").any(value::contains) -> "happy"
        listOf("放心", "没问题", "交给我", "当然可以", "已经办好").any(value::contains) -> "proud"
        listOf("原来", "竟然", "真的吗", "没想到", "哇", "居然").any(value::contains) -> "surprised"
        listOf("什么意思", "不太明白", "再说一遍", "没听清", "哪个", "怎么会").any(value::contains) -> "confused"
        value.contains("?") || value.contains("？") || listOf("您是说", "您想", "要不要").any(value::contains) -> "curious"
        listOf("休息", "晚安", "困了", "睡觉", "做个好梦").any(value::contains) -> "sleepy"
        state == MascotState.Thinking -> "thinking"
        state == MascotState.Listening -> "attentive"
        state == MascotState.Caring -> "caring"
        state == MascotState.Talking && (value.contains("首先") || value.contains("可以这样") ||
            value.contains("简单来说") || value.length > 70) -> "explaining"
        state == MascotState.Talking && emphasisTick % 3 == 1 -> "warm"
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
