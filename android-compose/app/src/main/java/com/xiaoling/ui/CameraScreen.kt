package com.xiaoling.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoling.R
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen

@Composable
fun CameraScreen(vm: AppState) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(ui.cameraLens) { onDispose { cameraProvider?.unbindAll() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            key(ui.cameraLens) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            previewView = view
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                runCatching {
                                    val provider = future.get()
                                    cameraProvider = provider
                                    provider.unbindAll()
                                    val preview = Preview.Builder().build().apply {
                                        setSurfaceProvider(view.surfaceProvider)
                                    }
                                    val selector = if (ui.cameraLens == "front") {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    }
                                    provider.bindToLifecycle(
                                        context as androidx.lifecycle.LifecycleOwner,
                                        selector,
                                        preview,
                                    )
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )
            }
        }

        FlatCameraIconButton(
            icon = R.drawable.ic_close_camera,
            description = "退出相机",
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 22.dp),
            onClick = { vm.showScreen(Screen.Home) },
        )
        if (granted) {
            FlatCameraIconButton(
                icon = R.drawable.ic_camera_flip,
                description = "切换前后摄像机",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp),
                onClick = { vm.setCameraLens(if (ui.cameraLens == "front") "back" else "front") },
            )
        }
    }

    LaunchedEffect(previewView, ui.cameraLens, ui.cameraRequestId) {
        if (previewView != null && !ui.cameraAnalyzing) {
            var frame: android.graphics.Bitmap? = null
            for (attempt in 0 until 8) {
                kotlinx.coroutines.delay(if (attempt == 0) 700 else 250)
                frame = previewView?.bitmap
                if (frame != null) break
            }
            frame?.let(vm::analyzeCameraFrame)
        }
    }
}

@Composable
private fun FlatCameraIconButton(
    icon: Int,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(54.dp).background(Color.Black.copy(alpha = 0.28f), CircleShape),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}
