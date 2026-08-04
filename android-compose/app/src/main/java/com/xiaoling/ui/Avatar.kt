package com.xiaoling.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
    val blink by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 3200
            0f at 0; 0f at 2700; 1f at 2780; 1f at 2870; 0f at 2960; 0f at 3200
        }),
        label = "blink"
    )
    val mouth by inf.animateFloat(0.15f, 1f, infiniteRepeatable(tween(145), RepeatMode.Reverse), label = "mouth")
    val glance by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(1150), RepeatMode.Reverse), label = "glance")
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
        Canvas(figureModifier) {
            val side = minOf(size.width, size.height)
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            fun x(v: Float) = left + side * v
            fun y(v: Float) = top + side * v

            // 角色图为正方形透明半身图,以下坐标只覆盖瞳孔与嘴部,不遮挡眼镜框。
            if (blink > 0.55f) {
                val skin = Color(0xFFF2C1AA)
                listOf(0.405f, 0.592f).forEach { eyeX ->
                    drawOval(
                        color = skin,
                        topLeft = androidx.compose.ui.geometry.Offset(x(eyeX - 0.034f), y(0.421f)),
                        size = androidx.compose.ui.geometry.Size(side * 0.068f, side * 0.034f)
                    )
                    drawLine(
                        color = Color(0xFF49352F),
                        start = androidx.compose.ui.geometry.Offset(x(eyeX - 0.025f), y(0.441f)),
                        end = androidx.compose.ui.geometry.Offset(x(eyeX + 0.025f), y(0.441f)),
                        strokeWidth = side * 0.006f,
                        cap = StrokeCap.Round
                    )
                }
            } else if (state == MascotState.Thinking) {
                listOf(0.405f, 0.592f).forEach { eyeX ->
                    drawCircle(Color(0xFF382C29), side * 0.008f,
                        androidx.compose.ui.geometry.Offset(x(eyeX + glance * 0.008f), y(0.437f)))
                }
            }

            if (state == MascotState.Talking) {
                drawOval(
                    color = Color(0xFF8B3E47),
                    topLeft = androidx.compose.ui.geometry.Offset(x(0.475f), y(0.594f - mouth * 0.007f)),
                    size = androidx.compose.ui.geometry.Size(side * 0.052f, side * (0.012f + mouth * 0.026f))
                )
            }
            if (state == MascotState.Caring) {
                val cheek = Color(0x45E97883)
                drawCircle(cheek, side * 0.027f, androidx.compose.ui.geometry.Offset(x(0.36f), y(0.535f)))
                drawCircle(cheek, side * 0.027f, androidx.compose.ui.geometry.Offset(x(0.64f), y(0.535f)))
            }
            if (isAlarm) {
                val brow = Color(0xFF49352F)
                drawLine(brow, androidx.compose.ui.geometry.Offset(x(0.37f), y(0.386f)),
                    androidx.compose.ui.geometry.Offset(x(0.43f), y(0.403f)), side * 0.009f, StrokeCap.Round)
                drawLine(brow, androidx.compose.ui.geometry.Offset(x(0.57f), y(0.403f)),
                    androidx.compose.ui.geometry.Offset(x(0.63f), y(0.386f)), side * 0.009f, StrokeCap.Round)
            }
        }
    }
}
