package com.xiaoling.ui

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
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

/**
 * 无边框角色半身形象:PNG 使用 Fit 完整显示,仅保留与对话状态相关的轻微动作。
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
    val breathe by inf.animateFloat(0.99f, 1.02f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "b")
    val bob by inf.animateFloat(1.01f, 1.055f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "bo")
    val talkY by inf.animateFloat(-8f, 5f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "talk-y")
    val talkTilt by inf.animateFloat(-1.2f, 1.2f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "talk-tilt")
    val listenScale by inf.animateFloat(1.025f, 1.055f, infiniteRepeatable(tween(720), RepeatMode.Reverse), label = "listen")
    val thinkX by inf.animateFloat(-7f, 7f, infiniteRepeatable(tween(920), RepeatMode.Reverse), label = "think")
    val shake by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "sh")
    val sway by inf.animateFloat(-3.2f, 3.2f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "sw")
    val isAlarm = state == MascotState.Alarm

    val figureModifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0.72f)
            val s = when (state) {
                MascotState.Talking -> bob
                MascotState.Listening -> listenScale
                MascotState.Thinking -> 1.025f
                MascotState.Caring -> 1.02f
                MascotState.Alarm -> 1.01f
                else -> breathe
            }
            scaleX = s; scaleY = s
            translationX = when (state) {
                MascotState.Alarm -> shake * 14f
                MascotState.Thinking -> thinkX
                else -> 0f
            }
            translationY = when (state) {
                MascotState.Talking -> talkY
                MascotState.Listening -> -5f
                else -> 0f
            }
            rotationZ = when (state) {
                MascotState.Talking -> talkTilt
                MascotState.Caring -> sway
                MascotState.Thinking -> thinkX / 8f
                else -> 0f
            }
        }

    Box(modifier, contentAlignment = Alignment.Center) {
        // 形象:PNG / 动态图,Fit 不裁剪、无卡片和装饰元素。
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
                painter = painterResource(R.drawable.avatar_clean),
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
