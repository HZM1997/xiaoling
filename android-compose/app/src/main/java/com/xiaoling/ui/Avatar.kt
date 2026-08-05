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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.xiaoling.R
import com.xiaoling.core.MascotState

/** Reliable native avatar shown while the VRM renderer loads or when WebGL is unavailable. */
@Composable
fun Avatar(state: MascotState, modifier: Modifier = Modifier) {
    val animation = rememberInfiniteTransition(label = "avatar-fallback")
    val breathe by animation.animateFloat(0.99f, 1.02f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    val bob by animation.animateFloat(1.01f, 1.05f, infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "bob")
    val floatY by animation.animateFloat(-5f, 4f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "float-y")
    val idleSway by animation.animateFloat(-1.6f, 1.6f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "idle-sway")
    val talkY by animation.animateFloat(-14f, 8f, infiniteRepeatable(tween(330), RepeatMode.Reverse), label = "talk-y")
    val tilt by animation.animateFloat(-2.8f, 2.8f, infiniteRepeatable(tween(390), RepeatMode.Reverse), label = "tilt")
    val listenScale by animation.animateFloat(1.04f, 1.085f, infiniteRepeatable(tween(620), RepeatMode.Reverse), label = "listen")
    val thinkX by animation.animateFloat(-7f, 7f, infiniteRepeatable(tween(920), RepeatMode.Reverse), label = "think")
    val shake by animation.animateFloat(-1f, 1f, infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "alarm")
    val sway by animation.animateFloat(-4.8f, 4.8f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "sway")
    val blink by animation.animateFloat(0f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 3200
        0f at 0; 0f at 2700; 1f at 2780; 1f at 2870; 0f at 2960; 0f at 3200
    }), label = "blink")
    val mouth by animation.animateFloat(0.15f, 1f, infiniteRepeatable(tween(145), RepeatMode.Reverse), label = "mouth")
    val glance by animation.animateFloat(-1f, 1f, infiniteRepeatable(tween(1150), RepeatMode.Reverse), label = "glance")

    val figure = Modifier.fillMaxSize().graphicsLayer {
        transformOrigin = TransformOrigin(0.5f, 0.72f)
        val scale = when (state) {
            MascotState.Talking -> bob
            MascotState.Listening -> listenScale
            MascotState.Thinking -> 1.025f
            MascotState.Caring -> 1.02f
            MascotState.Alarm -> 1.01f
            else -> breathe
        }
        scaleX = scale
        scaleY = scale
        translationX = when (state) {
            MascotState.Alarm -> shake * 14f
            MascotState.Thinking -> thinkX
            else -> 0f
        }
            translationY = when (state) {
                MascotState.Talking -> talkY
                MascotState.Listening -> floatY - 8f
                MascotState.Thinking -> floatY - 3f
                else -> floatY
            }
            rotationZ = when (state) {
                MascotState.Talking -> tilt
                MascotState.Caring -> sway
                MascotState.Thinking -> thinkX / 8f
                MascotState.Listening -> idleSway * 0.55f
                MascotState.Idle -> idleSway
                else -> 0f
            }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.avatar_clean),
            contentDescription = "小灵",
            contentScale = ContentScale.Fit,
            modifier = figure
        )
        Canvas(figure) {
            val side = minOf(size.width, size.height)
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            fun x(value: Float) = left + side * value
            fun y(value: Float) = top + side * value

            if (blink > 0.55f) {
                listOf(0.405f, 0.592f).forEach { eyeX ->
                    drawOval(
                        Color(0xFFF2C1AA),
                        androidx.compose.ui.geometry.Offset(x(eyeX - 0.034f), y(0.421f)),
                        androidx.compose.ui.geometry.Size(side * 0.068f, side * 0.034f)
                    )
                    drawLine(
                        Color(0xFF49352F),
                        androidx.compose.ui.geometry.Offset(x(eyeX - 0.025f), y(0.441f)),
                        androidx.compose.ui.geometry.Offset(x(eyeX + 0.025f), y(0.441f)),
                        side * 0.006f,
                        StrokeCap.Round
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
                    Color(0xFF8B3E47),
                    androidx.compose.ui.geometry.Offset(x(0.475f), y(0.594f - mouth * 0.007f)),
                    androidx.compose.ui.geometry.Size(side * 0.052f, side * (0.012f + mouth * 0.026f))
                )
            }
            if (state == MascotState.Caring) {
                drawCircle(Color(0x45E97883), side * 0.027f, androidx.compose.ui.geometry.Offset(x(0.36f), y(0.535f)))
                drawCircle(Color(0x45E97883), side * 0.027f, androidx.compose.ui.geometry.Offset(x(0.64f), y(0.535f)))
            }
            if (state == MascotState.Alarm) {
                drawLine(Color(0xFF49352F), androidx.compose.ui.geometry.Offset(x(0.37f), y(0.386f)),
                    androidx.compose.ui.geometry.Offset(x(0.43f), y(0.403f)), side * 0.009f, StrokeCap.Round)
                drawLine(Color(0xFF49352F), androidx.compose.ui.geometry.Offset(x(0.57f), y(0.403f)),
                    androidx.compose.ui.geometry.Offset(x(0.63f), y(0.386f)), side * 0.009f, StrokeCap.Round)
            }
        }
    }
}
