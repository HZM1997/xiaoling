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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    val pose = remember(state, emotion) { robotFacePose(state, emotion) }
    val poseEnergy by animateFloatAsState(pose.energy, tween(300), label = "avatar-pose-energy")
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * 0.48f)
        val scale = minOf(size.width, size.height) / 360f
        val breath = kotlin.math.sin(phase.toDouble()).toFloat()
        val conversationalBeat = if (talking) kotlin.math.sin((microPhase * 2.7f).toDouble()).toFloat() else 0f
        val bob = (breath * (2.2f + poseEnergy * 2f) + conversationalBeat * 1.2f) * scale
        val faceCenter = center + Offset(0f, bob)
        val frame = ComposeColor(0xFFDDF2FF)
        val frameLight = ComposeColor(0xFF8FD8FF)
        val screen = ComposeColor(0xFF050607)
        val white = ComposeColor(0xFFF9FFF9)
        val cyanGlow = ComposeColor(0xFF7FFFE8)
        val warmGlow = ComposeColor(0xFFFFC268)
        val faceSize = Size(238f * scale, 205f * scale)
        val faceTopLeft = faceCenter - Offset(faceSize.width / 2f, faceSize.height / 2f)
        val outerRadius = androidx.compose.ui.geometry.CornerRadius(72f * scale, 72f * scale)
        val innerInset = 13f * scale
        val innerRadius = androidx.compose.ui.geometry.CornerRadius(60f * scale, 60f * scale)
        val naturalGaze = kotlin.math.sin((microPhase * 0.39f).toDouble()).toFloat() * 2.2f
        val emphasisGaze = when (emphasisTick % 3) { 1 -> 1.6f; 2 -> -1.2f; else -> 0f }
        val eyeShift = when {
            emotion == "thinking" -> Offset(4f + naturalGaze + emphasisGaze, -4f)
            emotion == "shy" -> Offset(7f, 3f)
            state == "listen" -> Offset(3f + naturalGaze + emphasisGaze, 0f)
            else -> Offset(naturalGaze + emphasisGaze, kotlin.math.sin((phase * 0.31f).toDouble()).toFloat())
        }
        val blinkSignal = kotlin.math.sin((phase * 1.17f).toDouble()) +
            kotlin.math.sin((microPhase * 2.13f).toDouble()) * 0.72
        val blink = blinkSignal > 1.56
        // Keep the fallback face straight-on; expression changes are drawn in
        // the eyes, brows and mouth, with only breathing and gaze motion.
        rotate(0f, faceCenter) {
            drawRoundRect(brush = Brush.linearGradient(listOf(frame, frameLight)), topLeft = faceTopLeft,
                size = faceSize, cornerRadius = outerRadius)
            drawRoundRect(color = screen, topLeft = faceTopLeft + Offset(innerInset, innerInset),
                size = Size(faceSize.width - innerInset * 2, faceSize.height - innerInset * 2),
                cornerRadius = innerRadius)
            drawRoundRect(color = ComposeColor.White.copy(alpha = 0.035f),
                topLeft = faceTopLeft + Offset(innerInset + 8f * scale, innerInset + 7f * scale),
                size = Size(faceSize.width - (innerInset + 8f * scale) * 2, 28f * scale),
                cornerRadius = innerRadius)

            listOf(-1f, 1f).forEachIndexed { index, side ->
                val eyeCenter = faceCenter + Offset(side * 50f * scale + eyeShift.x * scale,
                    (-31f + eyeShift.y + if (emotion == "confused" && index == 1) 5f else 0f) * scale)
                val closed = blink || emotion == "sleepy" || (emotion == "playful" && index == 1)
                when {
                    emotion == "love" -> {
                        drawHeart(eyeCenter, 17f * scale, warmGlow.copy(alpha = .24f))
                        drawHeart(eyeCenter, 13f * scale, white)
                    }
                    emotion in setOf("happy", "proud", "delighted", "calm") || closed -> {
                        drawArc(cyanGlow.copy(alpha = .22f), 195f, 150f, false,
                            eyeCenter - Offset(20f * scale, 7f * scale), Size(40f * scale, 22f * scale),
                            style = Stroke(10f * scale, cap = StrokeCap.Round))
                        drawArc(white, 195f, 150f, false,
                            eyeCenter - Offset(18f * scale, 6f * scale), Size(36f * scale, 19f * scale),
                            style = Stroke(6f * scale, cap = StrokeCap.Round))
                    }
                    else -> {
                        val eyeWidth = (if (emotion in setOf("surprised", "curious", "excited", "delighted")) 38f else 34f) * scale
                        val eyeHeight = when (emotion) {
                            "surprised", "excited", "delighted" -> 38f
                            "curious" -> 34f
                            "warning", "serious", "focused" -> 22f
                            "sad", "caring", "apologetic" -> 27f
                            "skeptical" -> if (index == 0) 27f else 19f
                            else -> 31f
                        } * scale * pose.eyeOpen
                        drawGlowOval(eyeCenter, Size(eyeWidth, eyeHeight), white, cyanGlow, warmGlow)
                    }
                }
                if (emotion in setOf("warning", "serious", "focused", "skeptical", "confused")) {
                    val browY = eyeCenter.y - 27f * scale
                    val slope = when (emotion) {
                        "confused", "skeptical" -> if (index == 0) -7f else 4f
                        else -> if (index == 0) 8f else -8f
                    } * scale
                    drawLine(white.copy(alpha = .30f), eyeCenter + Offset(-16f * scale, -26f * scale),
                        Offset(eyeCenter.x + 16f * scale, browY + slope), 8f * scale, StrokeCap.Round)
                    drawLine(white, eyeCenter + Offset(-15f * scale, -26f * scale),
                        Offset(eyeCenter.x + 15f * scale, browY + slope), 4f * scale, StrokeCap.Round)
                }
                if ((emotion == "sad" || emotion == "caring" || emotion == "apologetic") && index == 0) {
                    drawOval(cyanGlow.copy(alpha = .32f), eyeCenter + Offset(-25f * scale, 20f * scale), Size(10f * scale, 22f * scale))
                    drawOval(white.copy(alpha = .9f), eyeCenter + Offset(-23f * scale, 21f * scale), Size(6f * scale, 17f * scale))
                }
            }

            if (emotion == "shy" || emotion == "love" || emotion == "delighted") {
                drawCircle(frameLight.copy(alpha = .18f), 17f * scale, faceCenter + Offset(-78f * scale, 20f * scale))
                drawCircle(frameLight.copy(alpha = .18f), 17f * scale, faceCenter + Offset(78f * scale, 20f * scale))
            }

            val mouthCenter = faceCenter + Offset(0f, 46f * scale)
            val audioOpen = (voiceLevel.coerceIn(0f, 1f) * 32f + kotlin.math.abs(conversationalBeat) * 4f)
            when {
                talking -> {
                    val width = (56f + mouthWide.coerceIn(0f, 1f) * 35f - mouthRound.coerceIn(0f, 1f) * 18f) * scale
                    val height = (12f + audioOpen + mouthRound.coerceIn(0f, 1f) * 15f).coerceAtMost(54f) * scale
                    drawGlowRoundRect(mouthCenter, Size(width, height), white, cyanGlow, warmGlow)
                }
                emotion == "surprised" -> drawGlowRoundRect(mouthCenter, Size(30f * scale, 55f * scale), white, cyanGlow, warmGlow)
                emotion in setOf("warning", "serious", "focused") -> drawGlowRoundRect(mouthCenter, Size(88f * scale, 18f * scale), white, cyanGlow, warmGlow)
                emotion in setOf("sad", "caring", "confused", "skeptical", "apologetic") -> {
                    drawLine(cyanGlow.copy(alpha = .25f), mouthCenter - Offset(25f * scale, 0f),
                        mouthCenter + Offset(25f * scale, 0f), 10f * scale, StrokeCap.Round)
                    drawLine(white, mouthCenter - Offset(22f * scale, 0f), mouthCenter + Offset(22f * scale, 0f),
                        5f * scale, StrokeCap.Round)
                }
                else -> {
                    val smileWidth = if (emotion in setOf("happy", "love", "playful", "proud", "warm", "excited", "delighted")) 86f else if (emotion == "calm") 58f else 68f
                    drawArc(cyanGlow.copy(alpha = .22f), 8f, 164f, false,
                        mouthCenter - Offset(smileWidth * .5f * scale, 18f * scale),
                        Size(smileWidth * scale, 36f * scale), style = Stroke(11f * scale, cap = StrokeCap.Round))
                    drawArc(white, 8f, 164f, false,
                        mouthCenter - Offset(smileWidth * .5f * scale, 17f * scale),
                        Size(smileWidth * scale, 34f * scale), style = Stroke(6f * scale, cap = StrokeCap.Round))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlowOval(
    center: Offset, size: Size, core: ComposeColor, cool: ComposeColor, warm: ComposeColor,
) {
    drawOval(
        warm.copy(alpha = .12f),
        center - Offset(size.width * .66f, size.height * .66f),
        Size(size.width * 1.32f, size.height * 1.32f),
    )
    drawOval(
        cool.copy(alpha = .22f),
        center - Offset(size.width * .58f, size.height * .58f),
        Size(size.width * 1.16f, size.height * 1.16f),
    )
    drawOval(core, center - Offset(size.width / 2f, size.height / 2f), size)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlowRoundRect(
    center: Offset, size: Size, core: ComposeColor, cool: ComposeColor, warm: ComposeColor,
) {
    val radius = androidx.compose.ui.geometry.CornerRadius(size.height * .46f)
    drawRoundRect(warm.copy(alpha = .12f), center - Offset(size.width * .57f, size.height * .67f),
        Size(size.width * 1.14f, size.height * 1.34f), radius)
    drawRoundRect(cool.copy(alpha = .24f), center - Offset(size.width * .54f, size.height * .60f),
        Size(size.width * 1.08f, size.height * 1.20f), radius)
    drawRoundRect(core, center - Offset(size.width / 2f, size.height / 2f), size, radius)
}

private data class RobotFacePose(
    val headTilt: Float = 0f,
    val energy: Float = 0.35f,
    val eyeOpen: Float = 1f,
)

private fun robotFacePose(state: String, emotion: String): RobotFacePose = when (emotion) {
    "happy", "love" -> RobotFacePose(-2f, 1f, .9f)
    "excited" -> RobotFacePose(-4f, 1.12f, 1.16f)
    "relieved" -> RobotFacePose(-1f, .32f, .96f)
    "playful", "shy" -> RobotFacePose(-6f, .72f, .88f)
    "surprised", "curious" -> RobotFacePose(4f, .9f, 1.12f)
    "confused" -> RobotFacePose(8f, .52f, .94f)
    "thinking" -> RobotFacePose(5f, .28f, .82f)
    "explaining" -> RobotFacePose(-2f, .82f, 1f)
    "warning", "serious" -> RobotFacePose(0f, .76f, .72f)
    "sad", "caring" -> RobotFacePose(4f, .20f, .82f)
    "sleepy" -> RobotFacePose(5f, .08f, .35f)
    "proud", "warm" -> RobotFacePose(-2f, .62f, .92f)
    "attentive" -> RobotFacePose(-3f, .46f, 1.05f)
    "focused" -> RobotFacePose(0f, .64f, .74f)
    "skeptical" -> RobotFacePose(0f, .44f, .84f)
    "apologetic" -> RobotFacePose(0f, .18f, .76f)
    "delighted" -> RobotFacePose(0f, 1.08f, 1.12f)
    "calm" -> RobotFacePose(0f, .22f, .86f)
    else -> when (state) {
        "talk" -> RobotFacePose(-1f, .58f, 1f)
        "listen" -> RobotFacePose(-3f, .45f, 1.06f)
        else -> RobotFacePose()
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
        listOf("生气", "愤怒", "太过分", "不可以", "必须注意", "严肃").any(value::contains) -> "serious"
        listOf("难过", "伤心", "想哭", "失去", "遗憾", "去世").any(value::contains) -> "sad"
        listOf("对不起", "很抱歉", "是我没做好", "让您久等", "给您添麻烦").any(value::contains) -> "apologetic"
        listOf("担心", "害怕", "不舒服", "疼", "抱歉", "陪着您", "别着急").any(value::contains) -> "caring"
        listOf("爱你", "想你", "喜欢你", "么么", "亲爱的", "真暖心").any(value::contains) -> "love"
        listOf("害羞", "不好意思", "夸得", "脸红").any(value::contains) -> "shy"
        listOf("哈哈", "逗你", "开玩笑", "调皮", "嘿嘿").any(value::contains) -> "playful"
        listOf("好耶", "太棒啦", "太厉害", "真的棒", "惊喜").any(value::contains) -> "excited"
        listOf("太开心了", "特别高兴", "真让人高兴", "太赞了").any(value::contains) -> "delighted"
        listOf("太好了", "开心", "恭喜", "真棒", "成功了", "完成了").any(value::contains) -> "happy"
        listOf("终于", "安全了", "没事了", "放心下来", "辛苦了").any(value::contains) -> "relieved"
        listOf("放心", "没问题", "交给我", "当然可以", "已经办好").any(value::contains) -> "proud"
        listOf("谢谢", "感谢", "不客气", "很高兴帮到", "慢慢来").any(value::contains) -> "warm"
        listOf("原来", "竟然", "真的吗", "没想到", "哇", "居然").any(value::contains) -> "surprised"
        listOf("什么意思", "不太明白", "再说一遍", "没听清", "哪个", "怎么会").any(value::contains) -> "confused"
        listOf("确定吗", "靠谱吗", "好像不对", "再核实", "值得怀疑").any(value::contains) -> "skeptical"
        value.contains("?") || value.contains("？") || listOf("您是说", "您想", "要不要").any(value::contains) -> "curious"
        listOf("休息", "晚安", "困了", "睡觉", "做个好梦").any(value::contains) -> "sleepy"
        state == MascotState.Thinking -> "thinking"
        state == MascotState.Talking && listOf("重点是", "请注意", "关键是", "先确认").any(value::contains) -> "focused"
        state == MascotState.Caring && listOf("放心", "慢慢来", "我在").any(value::contains) -> "calm"
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
