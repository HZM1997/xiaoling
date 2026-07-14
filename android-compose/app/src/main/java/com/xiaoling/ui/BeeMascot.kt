package com.xiaoling.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.xiaoling.core.MascotState

/** 原生绘制的小蜜蜂,按状态变表情/颜色(危险=红光环+抖动) */
@Composable
fun BeeMascot(state: MascotState, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "bee")
    val breathe by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "b"
    )
    val wing by inf.animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "w"
    )
    val shake by inf.animateFloat(
        -1f, 1f, infiniteRepeatable(tween(90), RepeatMode.Reverse), label = "s"
    )

    val yellow = Color(0xFFF2B705)
    val dark = Color(0xFF3A2412)
    val skin = Color(0xFFFFD9B8)
    val aura = when (state) {
        MascotState.Alarm -> Color(0xFFE5534B)
        MascotState.Caring -> Color(0xFFFF8FA3)
        MascotState.Listening, MascotState.Thinking -> Color(0xFF3FB6A8)
        else -> yellow
    }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val shakeX = if (state == MascotState.Alarm) shake * w * 0.02f else 0f
        val cy = h * 0.52f - breathe * h * 0.02f
        val cx = w / 2f + shakeX
        val r = size.minDimension * 0.28f

        // 光环
        drawCircle(aura.copy(alpha = 0.28f), r * 1.7f, Offset(cx, cy))

        // 触角
        val antColor = dark
        drawLine(antColor, Offset(cx - r * 0.4f, cy - r * 0.9f), Offset(cx - r * 0.55f, cy - r * 1.5f), 6f)
        drawLine(antColor, Offset(cx + r * 0.4f, cy - r * 0.9f), Offset(cx + r * 0.55f, cy - r * 1.5f), 6f)
        drawCircle(yellow, r * 0.14f, Offset(cx - r * 0.55f, cy - r * 1.55f))
        drawCircle(yellow, r * 0.14f, Offset(cx + r * 0.55f, cy - r * 1.55f))

        // 翅膀(扇动:用高度随 wing 变化)
        drawOval(
            Color(0xFFEAF6FF).copy(alpha = 0.55f),
            Offset(cx - r * 1.5f, cy - r * 0.6f),
            Size(r * 0.7f, r * (1.1f * wing))
        )
        drawOval(
            Color(0xFFEAF6FF).copy(alpha = 0.55f),
            Offset(cx + r * 0.8f, cy - r * 0.6f),
            Size(r * 0.7f, r * (1.1f * wing))
        )

        // 身体
        drawCircle(yellow, r, Offset(cx, cy))
        // 条纹
        drawRect(dark, Offset(cx - r, cy - r * 0.15f), Size(2 * r, r * 0.16f))
        drawRect(dark, Offset(cx - r, cy + r * 0.35f), Size(2 * r, r * 0.16f))
        // 脸(浅色圆)
        drawCircle(skin, r * 0.62f, Offset(cx, cy - r * 0.15f))

        // 眼睛
        val eyeY = cy - r * 0.2f
        val eyeR = if (state == MascotState.Alarm || state == MascotState.Listening) r * 0.13f else r * 0.1f
        drawCircle(dark, eyeR, Offset(cx - r * 0.25f, eyeY))
        drawCircle(dark, eyeR, Offset(cx + r * 0.25f, eyeY))

        // 腮红(开心/陪伴)
        if (state == MascotState.Caring || state == MascotState.Idle) {
            drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.6f), r * 0.1f, Offset(cx - r * 0.42f, cy + r * 0.02f))
            drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.6f), r * 0.1f, Offset(cx + r * 0.42f, cy + r * 0.02f))
        }

        // 嘴
        val mouthTop = cy + r * 0.08f
        when (state) {
            MascotState.Talking, MascotState.Alarm ->
                drawOval(Color(0xFF8C2A38), Offset(cx - r * 0.14f, mouthTop), Size(r * 0.28f, r * 0.24f))
            else ->
                drawArc(
                    Color(0xFF7A2230), 20f, 140f, false,
                    Offset(cx - r * 0.2f, mouthTop - r * 0.05f), Size(r * 0.4f, r * 0.25f),
                    style = Stroke(6f)
                )
        }

        // 危险:红色警示环
        if (state == MascotState.Alarm) {
            drawCircle(Color(0xFFE5534B), r * 1.25f, Offset(cx, cy), style = Stroke(8f))
        }
    }
}
