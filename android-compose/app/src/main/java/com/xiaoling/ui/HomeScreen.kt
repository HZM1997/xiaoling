package com.xiaoling.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xiaoling.R
import com.xiaoling.MainActivity
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen
import com.xiaoling.service.AppForeground
import com.xiaoling.service.WakeService
import com.xiaoling.ui.theme.AccentBlue
import com.xiaoling.ui.theme.InkColor
import kotlin.math.sin

@Composable
fun HomeScreen(vm: AppState) {
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx as? ComponentActivity
    val inPip = (activity as? MainActivity)?.voicePictureInPicture == true
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
        if (!granted) {
            vm.onMicrophonePermissionDenied()
        }
    }

    val actionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> vm.onActionPermissionsResult(result) }

    LaunchedEffect(ui.permissionRequestId) {
        if (ui.permissionRequestId > 0L && ui.requestedPermissions.isNotEmpty()) {
            actionPermissionLauncher.launch(ui.requestedPermissions.toTypedArray())
        }
    }

    LaunchedEffect(micGranted) {
        if (micGranted) {
            // 首页由 App 自己持续收音,后台唤醒服务让出麦克风。
            WakeService.pause(ctx)
            vm.warmUpMic()
            vm.startVoiceConversation()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(activity, micGranted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (micGranted) {
                    WakeService.pause(ctx)
                    vm.startVoiceConversation()
                }
                Lifecycle.Event.ON_STOP -> if (activity?.isInPictureInPictureMode != true && !AppForeground.companionMode) {
                    vm.pauseVoiceConversation()
                }
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.White)
    ) {
        val compact = maxHeight < 700.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = if (inPip) 4.dp else if (compact) 16.dp else 22.dp,
                end = if (inPip) 4.dp else if (compact) 16.dp else 22.dp,
                top = if (inPip) 4.dp else if (compact) 50.dp else 62.dp,
                bottom = if (inPip) 4.dp else if (compact) 18.dp else 24.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (inPip) {
                // Android controls the PiP window position. Inside that window,
                // keep only the voice surface: no avatar, captions, settings,
                // exit affordance, or microphone controls.
                VoiceActivityIndicator(
                    listening = ui.listening,
                    speaking = ui.speaking,
                    thinking = ui.busy,
                    pip = true,
                )
            } else {
                Avatar3DView(
                    state = ui.mascot,
                    talking = ui.speaking,
                    voiceLevel = ui.voiceLevel,
                    mouthWide = ui.voiceMouthWide,
                    mouthRound = ui.voiceMouthRound,
                    emphasisTick = ui.voiceEmphasisTick,
                    caption = ui.caption,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(8.dp))
                VoiceActivityIndicator(
                    listening = ui.listening,
                    speaking = ui.speaking,
                    thinking = ui.busy,
                )
                Spacer(Modifier.height(12.dp))
                MicrophoneButton(
                    listening = ui.micPressed,
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
        }

        if (!inPip) {
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
}

@Composable
private fun VoiceActivityIndicator(
    listening: Boolean,
    speaking: Boolean,
    thinking: Boolean,
    pip: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "voice-activity")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    listening -> 520
                    speaking -> 690
                    thinking -> 1120
                    else -> 1800
                }
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice-phase",
    )
    val energy = when {
        listening -> 0.94f
        speaking -> 0.82f
        thinking -> 0.48f
        else -> 0.16f
    }
    // Reference treatment: a black voice surface with a blue/cyan electrical
    // spectrum. State is conveyed by cadence and amplitude, not labels.
    val pillWidth = if (pip) 218.dp else 164.dp
    val pillHeight = if (pip) 46.dp else 38.dp
    val waveWidth = if (pip) 174.dp else 132.dp
    val waveHeight = if (pip) 32.dp else 27.dp

    Box(
        modifier = Modifier
            .width(pillWidth)
            .height(pillHeight)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(pillHeight / 2))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(waveWidth).height(waveHeight)) {
            val count = 17
            val barWidth = size.width / 36f
            val gap = barWidth * 1.18f
            repeat(count) { index ->
                val distance = kotlin.math.abs(index - (count - 1) / 2f) / ((count - 1) / 2f)
                val envelope = (1f - distance * 0.82f).coerceAtLeast(0.18f)
                val waveA = ((sin((phase * (1.04f + index % 3 * 0.08f) + index * 0.78f).toDouble()) + 1.0) * 0.5).toFloat()
                val waveB = ((sin((phase * 0.63f - index * 0.47f).toDouble()) + 1.0) * 0.5).toFloat()
                val activity = waveA * 0.72f + waveB * 0.28f
                val minHeight = 0.10f + envelope * 0.08f
                val barHeight = size.height * (minHeight + energy * envelope * (0.30f + activity * 0.62f))
                val left = (size.width - (barWidth * count + gap * (count - 1))) / 2f + index * (barWidth + gap)
                val color = when {
                    distance < 0.28f -> Color(0xFFBDF9FF)
                    distance < 0.58f -> Color(0xFF49CBFF)
                    else -> Color(0xFF2359FF)
                }.copy(alpha = (1f - distance * 0.28f).coerceIn(0.62f, 1f))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, (size.height - barHeight) / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
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
