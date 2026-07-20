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
    val breathe by inf.animateFloat(0.985f, 1.015f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "b")
    val bob by inf.animateFloat(1f, 1.03f, infiniteRepeatable(tween(320), RepeatMode.Reverse), label = "bo")
    val shake by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "sh")
    val sway by inf.animateFloat(-2.4f, 2.4f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "sw")
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
