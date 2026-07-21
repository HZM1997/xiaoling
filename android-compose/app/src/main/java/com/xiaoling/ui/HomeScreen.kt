package com.xiaoling.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.xiaoling.service.WakeService
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.InkColor

@Composable
fun HomeScreen(vm: AppState) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            vm.warmUpMic()
            WakeService.start(ctx)
            vm.startVoiceConversation()
        } else {
            vm.onMicrophonePermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        if (micGranted) {
            vm.warmUpMic()
            WakeService.start(ctx)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val showCaption = ui.listening || ui.speaking || ui.busy || ui.micFeedback.isNotBlank()
    val visibleCaption = ui.micFeedback.ifBlank { ui.caption }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.White)
    ) {
        val compact = maxHeight < 700.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = if (compact) 16.dp else 22.dp,
                end = if (compact) 16.dp else 22.dp,
                top = if (compact) 50.dp else 62.dp,
                bottom = if (compact) 18.dp else 24.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Crossfade(
                targetState = ui.live2d,
                modifier = Modifier.fillMaxWidth().weight(1f),
                animationSpec = tween(360),
                label = "avatar-mode"
            ) { live3d ->
                if (live3d) {
                    Avatar3DView(ui.mascot, ui.speaking, Modifier.fillMaxSize())
                } else {
                    Avatar(ui.mascot, Modifier.fillMaxSize())
                }
            }

            AnimatedVisibility(
                visible = showCaption,
                enter = fadeIn(tween(150)) + expandVertically(expandFrom = Alignment.CenterVertically),
                exit = fadeOut(tween(120)) + shrinkVertically(shrinkTowards = Alignment.CenterVertically)
            ) {
                AnimatedContent(
                    targetState = visibleCaption,
                    transitionSpec = {
                        (slideInVertically { it / 5 } + fadeIn(tween(150))) togetherWith
                            (slideOutVertically { -it / 6 } + fadeOut(tween(110)))
                    },
                    label = "dialogue-caption"
                ) { caption ->
                    Text(
                        text = caption,
                        color = InkColor,
                        fontSize = if (compact) 23.sp else 27.sp,
                        lineHeight = if (compact) 30.sp else 35.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = if (compact) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(if (showCaption) 8.dp else 14.dp))
            MicrophoneButton(
                listening = ui.listening,
                onPress = {
                    if (micGranted) {
                        WakeService.pause(ctx)
                        vm.pressToTalk()
                    }
                    else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onRelease = vm::releaseToTalk
            )
        }

        IconButton(
            onClick = { vm.showScreen(Screen.Settings) },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "设置",
                tint = InkColor,
                modifier = Modifier.size(28.dp)
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
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (listening) Color(0xFFE05252) else AccentBlue)
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
            modifier = Modifier.size(32.dp)
        )
    }
}
