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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.BgBottom
import com.xiaoling.ui.theme.BgTop
import com.xiaoling.ui.theme.DimColor

@Composable
fun HomeScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val perms = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startAuto() }

    // 进入即免手动开始「常听」;离开页面停止
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startAuto() else launcher.launch(perms)
    }
    DisposableEffect(Unit) { onDispose { vm.stopAuto() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        // 右上角:唯一的次要入口——设置
        Surface(
            onClick = { vm.showScreen(Screen.Settings) },
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(18.dp)
                .size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Text("⚙", fontSize = 22.sp) }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Avatar(
                state = ui.mascot,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.82f)
            )
            Spacer(Modifier.height(28.dp))
            ListeningLine(listening = ui.listening)
            Spacer(Modifier.height(12.dp))
            Text(
                text = ui.caption,
                fontSize = 18.sp,
                color = DimColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ListeningLine(listening: Boolean) {
    val inf = rememberInfiniteTransition(label = "mic")
    val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (listening) AccentBlue else Color(0xFFB9C3D0))
                .alpha(if (listening) a else 1f)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (listening) "在听…有事就跟我说" else "小灵在这儿",
            fontSize = 15.sp,
            color = if (listening) AccentBlue else DimColor
        )
    }
}
