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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            vm.warmUpMic()
            com.xiaoling.service.WakeService.start(ctx)
        }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // 进主页:申请权限并预热识别器(不常听,等老人按住说话)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (hasMic()) { vm.warmUpMic(); com.xiaoling.service.WakeService.start(ctx) }
        else launcher.launch(perms)
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgMid, BgBottom)))
    ) {
        // 极简主界面:角色形象为主角 + 语音状态 + 字幕
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (ui.live2d) {
                Avatar3DView(ui.mascot, ui.speaking, Modifier.fillMaxWidth().aspectRatio(0.86f))
            } else {
                Avatar(ui.mascot, Modifier.fillMaxWidth().aspectRatio(0.86f))
            }
            Spacer(Modifier.height(20.dp))
            VoiceWave(ui.listening, ui.busy)
            Spacer(Modifier.height(16.dp))
            // 字幕:毛玻璃卡片
            GlassSurface(radius = 24.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    ui.caption,
                    fontSize = 17.sp,
                    lineHeight = 25.sp,
                    color = InkColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
            // 智能澄清:多选项 → 大按钮,老人点或语音说"第一个"皆可
            if (ui.choices.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ui.choices.forEachIndexed { i, c ->
                    Surface(
                        onClick = { vm.chooseOption(c) },
                        shape = RoundedCornerShape(18.dp),
                        color = AccentBlue.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    ) {
                        Text(
                            "${i + 1}、${c.label}",
                            fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AccentBlue,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            // 唯一操作:按住说话(按下→开始识别;松手→收尾)。播报中按住即打断。
            PressToTalkButton(
                listening = ui.listening,
                onPress = { if (hasMic()) vm.pressToTalk() else launcher.launch(perms) },
                onRelease = { vm.releaseToTalk() }
            )
            Spacer(Modifier.height(20.dp))
        }

        // 唯一入口:右上角 毛玻璃 设置按钮
        GlassSurface(
            onClick = { vm.showScreen(Screen.Settings) },
            radius = 30.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).size(50.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("⚙", fontSize = 22.sp, color = AccentBlue)
            }
        }
    }
}

/** 毛玻璃表面:半透明白 + 细白描边 + 柔和投影(Compose 无原生模糊,用半透明分层近似) */
@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val glass = Modifier
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.55f))
            )
        )
        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)), shape)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = Color.Transparent, shadowElevation = 6.dp,
            modifier = modifier) { Box(glass) { content() } }
    } else {
        Surface(shape = shape, color = Color.Transparent, shadowElevation = 3.dp,
            modifier = modifier) { Box(glass) { content() } }
    }
}

/** 按住说话大按钮:按下开始识别、松手收尾。老人只需这一个操作,不用双击/长按。 */
@Composable
private fun PressToTalkButton(
    listening: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(shape)
            .background(
                if (listening)
                    Brush.verticalGradient(listOf(AccentGlow, AccentBlue))
                else
                    Brush.verticalGradient(listOf(AccentBlue, AccentBlue.copy(alpha = 0.85f)))
            )
            .pointerInput(Unit) {
                androidx.compose.foundation.gestures.detectTapGestures(
                    onPress = {
                        onPress()
                        try { awaitRelease() } finally { onRelease() }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (listening) "🎤 在听…松手结束" else "🎤 按住说话",
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White
        )
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
