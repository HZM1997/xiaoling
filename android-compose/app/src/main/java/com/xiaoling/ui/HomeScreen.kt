package com.xiaoling.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoling.R
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.InkColor

@Composable
fun HomeScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

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

    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            if (hasMic()) {
                vm.warmUpMic()
                com.xiaoling.service.WakeService.start(ctx)
            } else {
                launcher.launch(permissions)
            }
        } catch (_: Throwable) {
            // 初始化失败不阻塞首页;按住麦克风时还能再次尝试。
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F8FC))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 68.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (ui.live2d) {
                Avatar3DView(
                    ui.mascot,
                    ui.speaking,
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)
                )
            } else {
                Avatar(
                    ui.mascot,
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp)
                )
            }

            Text(
                text = ui.caption,
                color = InkColor,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp).padding(horizontal = 4.dp, vertical = 8.dp)
            )

            Spacer(Modifier.height(12.dp))
            MicrophoneButton(
                listening = ui.listening,
                onPress = {
                    if (hasMic()) vm.pressToTalk() else launcher.launch(permissions)
                },
                onRelease = vm::releaseToTalk
            )
        }

        IconButton(
            onClick = { vm.showScreen(Screen.Settings) },
            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(52.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "设置",
                tint = InkColor,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun MicrophoneButton(
    listening: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val fill = if (listening) Color(0xFFE05252) else AccentBlue
    Box(
        modifier = Modifier
            .size(92.dp)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(fill)
            .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try { awaitRelease() } finally { onRelease() }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = if (listening) "正在听,松手结束" else "按住说话",
            tint = Color.White,
            modifier = Modifier.size(46.dp)
        )
    }
}
