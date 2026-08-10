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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoling.core.AppState
import com.xiaoling.core.Screen

@Composable
fun CameraScreen(vm: AppState) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("\u9700\u8981\u76f8\u673a\u6743\u9650\u624d\u80fd\u5e2e\u60a8\u770b\u6e05\u7269\u54c1", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.showScreen(Screen.Home) }, modifier = Modifier.padding(top = 16.dp)) { Text("\u8fd4\u56de") }
        }
        return
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(ui.cameraLens) {
        onDispose { cameraProvider?.unbindAll() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(ui.cameraLens) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        previewView = view
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                            val provider = providerFuture.get()
                            cameraProvider = provider
                                provider.unbindAll()
                                val preview = Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }
                                val selector = if (ui.cameraLens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                                provider.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner, selector, preview)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (ui.cameraAnalyzing) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp))
            if (ui.caption.isNotBlank()) Text(ui.caption, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.setCameraLens(if (ui.cameraLens == "front") "back" else "front") }) {
                    Text(if (ui.cameraLens == "front") "\u5207\u6362\u540e\u7f6e" else "\u5207\u6362\u524d\u7f6e")
                }
                Button(enabled = !ui.cameraAnalyzing, onClick = { previewView?.bitmap?.let(vm::analyzeCameraFrame) }) {
                    Text("\u518d\u770b\u4e00\u6b21")
                }
                Button(onClick = { vm.showScreen(Screen.Home) }) { Text("\u8fd4\u56de") }
            }
        }
    }
    LaunchedEffect(previewView, ui.cameraLens, ui.cameraRequestId) {
        if (previewView != null && !ui.cameraAnalyzing) {
            var frame: android.graphics.Bitmap? = null
            for (attempt in 0 until 6) {
                kotlinx.coroutines.delay(if (attempt == 0) 700 else 250)
                frame = previewView?.bitmap
                if (frame != null) break
            }
            frame?.let(vm::analyzeCameraFrame)
        }
    }
}
