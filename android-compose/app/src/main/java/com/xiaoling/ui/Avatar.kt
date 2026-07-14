package com.xiaoling.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoling.R
import com.xiaoling.core.MascotState
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.AlarmRed

/** 3D 角色半身像:静态图 + 状态化描边/呼吸;危险态红框 + ⚠ */
@Composable
fun Avatar(state: MascotState, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "av")
    val breathe by inf.animateFloat(
        0.99f, 1.02f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "br"
    )
    val ringColor by animateColorAsState(
        targetValue = when (state) {
            MascotState.Alarm -> AlarmRed
            MascotState.Listening -> AccentBlue
            MascotState.Caring -> Color(0xFFFF8FA3)
            else -> Color(0x22000000)
        }, label = "ring"
    )
    val shape = RoundedCornerShape(40.dp)

    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Image(
            painter = painterResource(R.drawable.avatar),
            contentDescription = "小灵",
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .scale(breathe)
                .shadow(18.dp, shape, clip = false)
                .clip(shape)
                .border(3.dp, ringColor, shape)
        )
        if (state == MascotState.Alarm) {
            androidx.compose.material3.Text(
                "⚠", fontSize = 56.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
