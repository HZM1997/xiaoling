package com.xiaoling.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.xiaoling.R
import com.xiaoling.core.MascotState
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.AlarmRed

/**
 * 无边框透明形象:透明通道 PNG(Fit,不裁剪、不描边),身后叠加柔和"智能光晕"传达 AI 感。
 * 支持 assets/ 动态图(webp/gif)与分表情;缺失回退静态透明 PNG。
 */
@Composable
fun Avatar(state: MascotState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
            else add(GifDecoder.Factory())
        }.build()
    }
    val animModel = remember(state) { pickAnimAsset(ctx, baseName(state)) ?: pickAnimAsset(ctx, "avatar") }

    val inf = rememberInfiniteTransition(label = "av")
    val breathe by inf.animateFloat(0.985f, 1.015f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "b")
    val bob by inf.animateFloat(1f, 1.03f, infiniteRepeatable(tween(320), RepeatMode.Reverse), label = "bo")
    val shake by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "sh")
    val sway by inf.animateFloat(-2.4f, 2.4f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "sw")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == MascotState.Alarm) 420 else 2200), RepeatMode.Reverse), label = "pu")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "sp")
    val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "rp")

    val glow by animateColorAsState(
        when (state) {
            MascotState.Alarm -> AlarmRed
            MascotState.Listening -> AccentBlue
            MascotState.Thinking -> Color(0xFF7C5CFF)
            MascotState.Talking -> AccentBlue
            MascotState.Caring -> Color(0xFFFF8FB0)
            else -> Color(0xFF7FA8FF)
        }, label = "glow"
    )
    val isAlarm = state == MascotState.Alarm

    val figureModifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            val s = when (state) {
                MascotState.Talking -> bob
                MascotState.Alarm -> 1.01f
                else -> breathe
            }
            scaleX = s; scaleY = s
            translationX = if (isAlarm) shake * 14f else 0f
            rotationZ = if (state == MascotState.Caring) sway else 0f
        }

    Box(modifier, contentAlignment = Alignment.Center) {
        // 智能光晕:柔和径向光 + 旋转光弧,危险时红色暴闪
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.52f)
            val base = size.minDimension * 0.46f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.30f + 0.16f * pulse), Color.Transparent),
                    center = c, radius = base * (1.35f + 0.12f * pulse)
                ),
                radius = base * (1.35f + 0.12f * pulse), center = c
            )
            // 环绕光点(智能感),非危险态
            if (state != MascotState.Alarm) {
                for (i in 0 until 3) {
                    val ang = Math.toRadians((spin + i * 120.0))
                    val rr = base * 1.15f
                    val p = Offset(c.x + (rr * kotlin.math.cos(ang)).toFloat(), c.y + (rr * kotlin.math.sin(ang)).toFloat())
                    drawCircle(glow.copy(alpha = 0.5f), size.minDimension * 0.012f, p)
                }
            }
            if (state == MascotState.Listening) {
                drawCircle(glow.copy(alpha = 0.22f * (1f - ripple)), base * (0.9f + 0.6f * ripple), c, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            }
            if (isAlarm) {
                drawCircle(AlarmRed.copy(alpha = 0.12f + 0.20f * pulse), base * 1.5f, c)
            }
        }
        // 形象:透明 PNG / 动态图,Fit 不裁剪、无边框
        if (animModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(animModel).build(),
                imageLoader = loader,
                contentDescription = "小灵",
                contentScale = ContentScale.Fit,
                modifier = figureModifier
            )
        } else {
            Image(
                painter = painterResource(R.drawable.avatar),
                contentDescription = "小灵",
                contentScale = ContentScale.Fit,
                modifier = figureModifier
            )
        }
    }
}

private fun baseName(state: MascotState): String = when (state) {
    MascotState.Alarm -> "avatar_alarm"
    MascotState.Caring -> "avatar_happy"
    MascotState.Listening -> "avatar_listening"
    MascotState.Thinking -> "avatar_thinking"
    MascotState.Talking -> "avatar_talking"
    else -> "avatar"
}

private fun pickAnimAsset(ctx: android.content.Context, name: String): String? {
    for (ext in listOf("webp", "gif")) {
        val f = "$name.$ext"
        try { ctx.assets.open(f).close(); return "file:///android_asset/$f" } catch (e: Exception) { }
    }
    return null
}
