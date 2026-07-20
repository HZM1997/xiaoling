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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF7F8FC))
    ) {
        val compact = maxHeight < 700.dp
        val horizontalPadding = if (compact) 18.dp else 24.dp
        val topPadding = if (compact) 54.dp else 68.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = topPadding,
                bottom = if (compact) 18.dp else 26.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Crossfade(
                targetState = ui.live2d,
                modifier = Modifier.fillMaxWidth().weight(1f),
                animationSpec = tween(420),
                label = "avatar-mode"
            ) { live3d ->
                if (live3d) {
                    Avatar3DView(
                        ui.mascot,
                        ui.speaking,
                        Modifier.fillMaxSize().padding(horizontal = 4.dp)
                    )
                } else {
                    Avatar(
                        ui.mascot,
                        Modifier.fillMaxSize().padding(horizontal = 4.dp)
                    )
                }
            }

            AnimatedContent(
                targetState = ui.caption,
                transitionSpec = {
                    (slideInVertically { it / 4 } + fadeIn(tween(180))) togetherWith
                        (slideOutVertically { -it / 5 } + fadeOut(tween(140)))
                },
                label = "dialogue-caption"
            ) { caption ->
                Text(
                    text = caption,
                    color = InkColor,
                    fontSize = if (compact) 24.sp else 28.sp,
                    lineHeight = if (compact) 32.sp else 36.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = if (compact) 76.dp else 108.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            MicrophoneButton(
                listening = ui.listening,
                compact = compact,
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
    compact: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val fill by animateColorAsState(
        targetValue = if (listening) Color(0xFFE05252) else AccentBlue,
        animationSpec = tween(180),
        label = "mic-color"
    )
    val size by animateDpAsState(
        targetValue = if (listening) (if (compact) 88.dp else 98.dp) else (if (compact) 80.dp else 92.dp),
        animationSpec = tween(220),
        label = "mic-size"
    )
    val pulse by rememberInfiniteTransition(label = "mic-pulse").animateFloat(
        initialValue = 0.98f,
        targetValue = if (listening) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "mic-scale"
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
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
