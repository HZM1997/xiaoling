package com.xiaoling.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xiaoling.R
import com.xiaoling.core.MascotState

/**
 * 无边框角色半身形象:PNG 使用 Fit 完整显示,通过 Compose 状态动画表现呼吸、倾听与说话。
 */
@Composable
fun Avatar(state: MascotState, modifier: Modifier = Modifier) {
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
        Image(
            painter = painterResource(R.drawable.avatar_clean),
            contentDescription = "小灵",
            contentScale = ContentScale.Fit,
            modifier = figureModifier
        )
    }
}
