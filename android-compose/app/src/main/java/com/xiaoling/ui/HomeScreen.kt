package com.xiaoling.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.MascotState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.AccentGlow
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgMid
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor
import com.xiaoling.ui.theme.InkColor

@Composable
fun HomeScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECEIVE_SMS
    )
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            vm.startAuto()
            com.xiaoling.service.WakeService.start(ctx)
        }
    }

    // 生命周期驱动:App 到前台开听、退到后台停听(把麦交给 WakeService 听唤醒词,避免抢麦)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        vm.startAuto()
                        com.xiaoling.service.WakeService.start(ctx)
                    } else launcher.launch(perms)
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.stopAuto()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); vm.stopAuto() }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
    ) {
        // 顶栏:品牌名 + 智能状态 + 设置
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("小灵", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkColor)
                Text("你的 AI 智能管家", fontSize = 12.sp, color = DimColor)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = { vm.showScreen(Screen.Settings) },
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.7f),
                shadowElevation = 2.dp,
                modifier = Modifier.size(44.dp)
            ) { Box(contentAlignment = Alignment.Center) { Text("⚙", fontSize = 20.sp) } }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (ui.live2d) {
                Avatar3DView(ui.mascot, ui.speaking, Modifier.fillMaxWidth().aspectRatio(0.8f))
            } else {
                Avatar(ui.mascot, Modifier.fillMaxWidth().aspectRatio(0.8f))
            }
            Spacer(Modifier.height(18.dp))
            VoiceWave(ui.listening, ui.busy)
            Spacer(Modifier.height(16.dp))
            // 字幕:毛玻璃卡片
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.66f),
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    ui.caption,
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    color = InkColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }
    }
}

/** 声纹波形:聆听时跳动,忙碌时流动,静止时低平 —— 传达"在听/在想" */
@Composable
private fun VoiceWave(listening: Boolean, busy: Boolean) {
    val inf = rememberInfiniteTransition(label = "wave")
    val bars = 7
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (i in 0 until bars) {
            val phase = i * 90
            val h by inf.animateFloat(
                if (listening) 8f else 5f,
                if (listening) 26f else if (busy) 16f else 6f,
                infiniteRepeatable(tween(500 + phase, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
                label = "h$i"
            )
            Box(
                Modifier.size(width = 5.dp, height = h.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (listening || busy) AccentGlow else DimColor.copy(alpha = 0.5f),
                                if (listening || busy) AccentBlue else DimColor.copy(alpha = 0.3f)
                            )
                        )
                    )
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            if (listening) "在听…" else if (busy) "思考中…" else "小灵待命中",
            fontSize = 14.sp,
            color = if (listening || busy) AccentBlue else DimColor,
            fontWeight = FontWeight.Medium
        )
    }
}
