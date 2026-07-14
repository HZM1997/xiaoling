package com.xiaoling.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoling.R
import com.xiaoling.core.MascotState
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.AlarmRed

/**
 * 富动效角色:单张静态图也能有强反馈。
 * 支持可选分表情图(avatar_alarm/avatar_happy/avatar_listening/avatar_thinking/avatar_talking),
 * 缺失自动回退到基础 avatar。危险态:红光暴闪 + 抖动 + 红框 + 头顶⚠。
 */
@Composable
fun Avatar(state: MascotState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val resId = remember(state) {
        val name = when (state) {
            MascotState.Alarm -> "avatar_alarm"
            MascotState.Caring -> "avatar_happy"
            MascotState.Listening -> "avatar_listening"
            MascotState.Thinking -> "avatar_thinking"
            MascotState.Talking -> "avatar_talking"
            else -> "avatar"
        }
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id != 0) id else R.drawable.avatar
    }

    val inf = rememberInfiniteTransition(label = "av")
    val breathe by inf.animateFloat(0.99f, 1.02f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "b")
    val bob by inf.animateFloat(1f, 1.03f, infiniteRepeatable(tween(320), RepeatMode.Reverse), label = "bo")
    val shake by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(80), RepeatMode.Reverse), label = "sh")
    val sway by inf.animateFloat(-3f, 3f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "sw")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == MascotState.Alarm) 420 else 1600), RepeatMode.Reverse), label = "pu")
    val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "rp")

    val aura by animateColorAsState(
        when (state) {
            MascotState.Alarm -> AlarmRed
            MascotState.Listening, MascotState.Thinking -> AccentBlue
            MascotState.Caring -> Color(0xFFFF8FA3)
            MascotState.Talking -> AccentBlue
            else -> Color(0xFF9BB6E8)
        }, label = "aura"
    )
    val shape = RoundedCornerShape(40.dp)
    val isAlarm = state == MascotState.Alarm

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        // 1) 背后光环 / 涟漪
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.5f)
            val base = size.minDimension * 0.5f
            drawCircle(aura.copy(alpha = 0.10f + 0.14f * pulse), base * (1.25f + 0.1f * pulse), c)
            if (state == MascotState.Listening) {
                drawCircle(aura.copy(alpha = 0.25f * (1f - ripple)), base * (1f + 0.5f * ripple), c)
            }
        }
        // 2) 角色图(呼吸/抖动/摆动/口型抖)
        Image(
            painter = painterResource(resId),
            contentDescription = "小灵",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = when (state) {
                        MascotState.Talking -> bob
                        MascotState.Alarm -> 1.01f
                        else -> breathe
                    }
                    scaleX = s; scaleY = s
                    translationX = if (isAlarm) shake * 16f else 0f
                    rotationZ = if (state == MascotState.Caring) sway else 0f
                }
                .clip(shape)
                .border(
                    if (isAlarm) 6.dp else if (state == MascotState.Idle) 2.dp else 3.dp,
                    aura, shape
                )
        )
        // 3) 危险:红光暴闪盖层
        if (isAlarm) {
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(AlarmRed.copy(alpha = 0.10f + 0.22f * pulse))
            )
            Text(
                "⚠", fontSize = 66.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
            )
        }
    }
}
