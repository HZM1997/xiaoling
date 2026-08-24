package com.xiaoling.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.YuvImage
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoling.R
import com.xiaoling.core.AppState
import com.xiaoling.core.CameraFilter
import com.xiaoling.core.Screen
import com.xiaoling.core.TactileFeedback
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun CameraScreen(vm: AppState) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    val filter = CameraFilter.fromId(ui.cameraFilter)
    val filterStrength = ui.cameraFilterStrength
    val exposure = ui.cameraExposure
    val saturation = ui.cameraSaturation
    val whitening = ui.cameraWhitening
    val smoothing = ui.cameraSmoothing
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    val referenceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                }.getOrNull()
            }
            if (bitmap != null) vm.applyReferenceStyle(bitmap) else vm.onReferenceImageLoadFailed()
        }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(ui.cameraReferenceRequestId) {
        if (ui.cameraReferenceRequestId > 0L) referenceLauncher.launch("image/*")
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val latestAnalysisFrame = remember { AtomicReference<Bitmap?>(null) }
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "xiaoling-camera-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
        }
    }
    DisposableEffect(ui.cameraLens) { onDispose { cameraProvider?.unbindAll() } }
    DisposableEffect(Unit) {
        onDispose {
            synchronized(latestAnalysisFrame) { latestAnalysisFrame.getAndSet(null)?.recycle() }
            analysisExecutor.shutdownNow()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            key(ui.cameraLens) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            // Texture-backed preview makes bitmap snapshots
                            // reliable on MIUI; SurfaceView often returns a
                            // blank bitmap, which made voice-requested visual
                            // recognition appear to do nothing.
                            view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
                                    val capture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .setTargetResolution(android.util.Size(1440, 1920))
                                        .build()
                                    val analysis = ImageAnalysis.Builder()
                                        .setTargetResolution(android.util.Size(640, 480))
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(analysisExecutor, CameraFrameAnalyzer { bitmap ->
                                        synchronized(latestAnalysisFrame) {
                                            latestAnalysisFrame.getAndSet(bitmap)?.recycle()
                                        }
                                        val observation = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        vm.observeCameraFrame(observation)
                                    })
                                    imageCapture = capture
                                    val selector = if (ui.cameraLens == "front") {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    }
                                    provider.bindToLifecycle(
                                        context as androidx.lifecycle.LifecycleOwner,
                                        selector,
                                        preview,
                                        capture,
                                        analysis,
                                    )
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )
            }
        }

        // Android 12+ applies a real color matrix to the camera surface. Older
        // phones retain the lightweight overlay below instead of loading a
        // large GPU/image-processing dependency.
        LaunchedEffect(previewView, filter, filterStrength, exposure, saturation, whitening, smoothing) {
            previewView?.applyXiaolingFilter(filter, filterStrength, exposure, saturation, whitening, smoothing)
        }
        CameraFilterOverlay(
            filter,
            filterStrength,
            exposure,
            saturation,
            whitening,
            smoothing,
            isFrontCamera = ui.cameraLens == "front",
        )
        if (filter != CameraFilter.Natural || whitening > 0.01f || smoothing > 0.01f) {
            CameraLookReference(
                filter = filter,
                description = ui.cameraStyleDescription,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
            )
        }

        FlatCameraIconButton(
            icon = R.drawable.ic_close_camera,
            description = "退出相机",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 22.dp),
                onClick = {
                TactileFeedback.emit(context, TactileFeedback.Signal.Cancelled)
                vm.exitCamera()
            },
        )
        if (granted) {
            FlatCameraIconButton(
                icon = R.drawable.ic_camera_flip,
                description = "切换前后摄像机",
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp),
                onClick = {
                    TactileFeedback.emit(context, TactileFeedback.Signal.CameraFocused)
                    vm.setCameraLens(if (ui.cameraLens == "front") "back" else "front")
                },
            )
        }
    }

    LaunchedEffect(previewView, ui.cameraLens, ui.cameraRequestId) {
        if (previewView != null) {
            var frame: android.graphics.Bitmap? = null
            for (attempt in 0 until 8) {
                kotlinx.coroutines.delay(if (attempt == 0) 450 else 180)
                frame = synchronized(latestAnalysisFrame) {
                    latestAnalysisFrame.get()?.copy(Bitmap.Config.ARGB_8888, false)
                } ?: previewView?.bitmap
                if (frame != null) break
            }
            frame?.let { vm.analyzeCameraFrame(it, ui.cameraRequestId) }
        }
    }

    LaunchedEffect(previewView, ui.cameraCaptureRequestId) {
        if (ui.cameraCaptureRequestId > 0L) {
            kotlinx.coroutines.delay(420L)
            val frame = imageCapture?.let { captureCameraFrame(it, context) }
                ?: synchronized(latestAnalysisFrame) {
                    latestAnalysisFrame.get()?.copy(Bitmap.Config.ARGB_8888, false)
                }
                ?: previewView?.bitmap
            val saved = if (frame == null) false else withContext(Dispatchers.IO) {
                val rendered = renderCameraPhoto(frame, filter, filterStrength, exposure, saturation, whitening, smoothing)
                try { saveCameraPhoto(context, rendered) } finally {
                    if (rendered !== frame) rendered.recycle()
                    frame.recycle()
                }
            }
            vm.onCameraPhotoSaved(saved)
        }
    }

}

@Composable
private fun CameraLookReference(filter: CameraFilter, description: String, modifier: Modifier = Modifier) {
    val colors = when (filter) {
        CameraFilter.Natural -> listOf(Color(0xFF4C5662), Color(0xFFAEB6BF), Color(0xFFF0F2F4))
        CameraFilter.Warm -> listOf(Color(0xFF5A3529), Color(0xFFE09A62), Color(0xFFFFE0B0))
        CameraFilter.Cream -> listOf(Color(0xFF76585A), Color(0xFFE6BBAF), Color(0xFFFFE9D7))
        CameraFilter.Mist -> listOf(Color(0xFF738399), Color(0xFFC7D5E4), Color(0xFFF4F7FA))
        CameraFilter.Cool -> listOf(Color(0xFF244B67), Color(0xFF7AB7C9), Color(0xFFDDF4F6))
        CameraFilter.Vivid -> listOf(Color(0xFF294C47), Color(0xFFE55E70), Color(0xFFFFD668))
        CameraFilter.Sunset -> listOf(Color(0xFF5C2939), Color(0xFFE76F51), Color(0xFFFFC06A))
        CameraFilter.Forest -> listOf(Color(0xFF173F35), Color(0xFF5E9272), Color(0xFFD6D7A3))
        CameraFilter.TealOrange -> listOf(Color(0xFF164F5B), Color(0xFFC97352), Color(0xFFF2C38F))
        CameraFilter.Vintage -> listOf(Color(0xFF3C3730), Color(0xFF997A58), Color(0xFFD7C09B))
        CameraFilter.Film -> listOf(Color(0xFF353E43), Color(0xFF9A8974), Color(0xFFD8CAB3))
        CameraFilter.HongKong -> listOf(Color(0xFF352B43), Color(0xFFB34F55), Color(0xFFF1B26B))
        CameraFilter.Mono -> listOf(Color(0xFF202124), Color(0xFF898989), Color(0xFFE0E0E0))
        CameraFilter.Noir -> listOf(Color(0xFF050608), Color(0xFF55585D), Color(0xFFF2F2F2))
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(description.ifBlank { filter.label }, color = Color.White, fontSize = 13.sp)
        Canvas(Modifier.padding(top = 5.dp).width(112.dp).height(7.dp)) {
            drawRoundRect(
                brush = Brush.horizontalGradient(colors),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
        }
    }
}

private fun PreviewView.applyXiaolingFilter(
    filter: CameraFilter,
    strength: Float,
    exposure: Float,
    saturation: Float,
    whitening: Float,
    smoothing: Float,
) {
    val matrix = filter.colorMatrix(strength, exposure, saturation, whitening)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // COMPATIBLE PreviewView is texture-backed. A hardware layer paint
        // gives Android 7-11 the same real color matrix instead of a flat tint.
        val paint = matrix?.let { value ->
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(value)
            }
        }
        setLayerType(if (paint == null) android.view.View.LAYER_TYPE_NONE else android.view.View.LAYER_TYPE_HARDWARE, paint)
        return
    }
    setLayerType(android.view.View.LAYER_TYPE_NONE, null)
    val colorEffect = matrix?.let { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(it)) }
    // Keep the live preview soft without turning the entire background into a
    // blur. Saved photos use a skin mask for stronger, face-local smoothing.
    val blurRadius = smoothing.coerceIn(0f, 1f) * 0.92f
    val effect = when {
        blurRadius >= 0.05f && colorEffect != null ->
            RenderEffect.createBlurEffect(blurRadius, blurRadius, colorEffect, Shader.TileMode.CLAMP)
        blurRadius >= 0.05f -> RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        else -> colorEffect
    }
    setRenderEffect(effect)
}

private class CameraFrameAnalyzer(
    private val onChangedFrame: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastSample: IntArray? = null
    private var lastCheckAt = 0L
    private var lastEmitAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCheckAt < 280L) return
            lastCheckAt = now
            val sample = lumaSample(image)
            val previous = lastSample
            val change = if (previous == null) Float.MAX_VALUE else
                sample.indices.sumOf { kotlin.math.abs(sample[it] - previous[it]).toDouble() }.toFloat() / sample.size
            val periodicRefresh = now - lastEmitAt >= 3_500L
            val changedFrame = change >= 8.5f && now - lastEmitAt >= 900L
            if (previous == null || changedFrame || periodicRefresh) {
                lastSample = sample
                imageProxyToBitmap(image)?.let { bitmap ->
                    lastEmitAt = now
                    onChangedFrame(bitmap)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun lumaSample(image: ImageProxy): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val columns = 12
        val rows = 9
        return IntArray(columns * rows) { index ->
            val x = ((index % columns) + 0.5f) * image.width / columns
            val y = ((index / columns) + 0.5f) * image.height / rows
            val position = y.toInt().coerceAtMost(image.height - 1) * plane.rowStride +
                x.toInt().coerceAtMost(image.width - 1) * plane.pixelStride
            if (position < buffer.limit()) buffer.get(position).toInt() and 0xff else 0
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? = runCatching {
    val width = image.width
    val height = image.height
    val nv21 = ByteArray(width * height * 3 / 2)
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    var output = 0
    for (y in 0 until height) {
        val row = y * yPlane.rowStride
        for (x in 0 until width) nv21[output++] = yPlane.buffer.get(row + x * yPlane.pixelStride)
    }
    for (y in 0 until height / 2) {
        val uRow = y * uPlane.rowStride
        val vRow = y * vPlane.rowStride
        for (x in 0 until width / 2) {
            nv21[output++] = vPlane.buffer.get(vRow + x * vPlane.pixelStride)
            nv21[output++] = uPlane.buffer.get(uRow + x * uPlane.pixelStride)
        }
    }
    val jpeg = ByteArrayOutputStream().use { stream ->
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 82, stream)
        stream.toByteArray()
    }
    val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) source else Bitmap.createBitmap(
        source, 0, 0, source.width, source.height,
        Matrix().apply { postRotate(rotation.toFloat()) }, true,
    ).also { if (it !== source) source.recycle() }
}.getOrNull()

@Composable
private fun CameraFilterOverlay(
    filter: CameraFilter,
    strength: Float,
    exposure: Float,
    saturation: Float,
    whitening: Float,
    smoothing: Float,
    isFrontCamera: Boolean,
) {
    if (filter == CameraFilter.Natural && kotlin.math.abs(exposure) < 0.001f &&
        kotlin.math.abs(saturation - 1f) < 0.001f && whitening < 0.001f) return
    Canvas(Modifier.fillMaxSize()) {
        // API 30 MIUI devices can ignore a Paint color filter on the
        // TextureView-backed PreviewView. These restrained blend-mode layers
        // keep the live preview visibly consistent with the saved photo even
        // when that hardware path is unavailable. Saved photos still receive
        // the full matrix and skin-local beauty pass.
        val level = strength.coerceIn(0.25f, 1f)
        when (filter) {
            CameraFilter.Warm, CameraFilter.Sunset -> {
                drawRect(Color(0xFFFF9E5C).copy(alpha = 0.13f * level), blendMode = BlendMode.Softlight)
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFFFFD4A3).copy(alpha = 0.05f * level), Color.Transparent),
                    ),
                    blendMode = BlendMode.Screen,
                )
            }
            CameraFilter.Cream -> drawRect(
                Color(0xFFFFD2C0).copy(alpha = 0.13f * level), blendMode = BlendMode.Softlight)
            CameraFilter.Mist -> drawRect(
                Color(0xFFE8F3FF).copy(alpha = 0.16f * level), blendMode = BlendMode.Screen)
            CameraFilter.Cool -> drawRect(
                Color(0xFF83CFFF).copy(alpha = 0.14f * level), blendMode = BlendMode.Color)
            CameraFilter.Forest -> drawRect(
                Color(0xFF70B98A).copy(alpha = 0.12f * level), blendMode = BlendMode.Color)
            CameraFilter.TealOrange -> {
                drawRect(Color(0xFF2AB8B7).copy(alpha = 0.10f * level), blendMode = BlendMode.Color)
                drawRect(Color(0xFFFF955D).copy(alpha = 0.08f * level), blendMode = BlendMode.Softlight)
            }
            CameraFilter.Vivid -> drawRect(
                Color(0xFFFF6570).copy(alpha = 0.10f * level), blendMode = BlendMode.Saturation)
            CameraFilter.Vintage, CameraFilter.Film -> drawRect(
                Color(0xFFB48A64).copy(alpha = 0.13f * level), blendMode = BlendMode.Color)
            CameraFilter.HongKong -> {
                drawRect(Color(0xFFB94F55).copy(alpha = 0.10f * level), blendMode = BlendMode.Color)
                drawRect(Color(0xFFFFB15F).copy(alpha = 0.07f * level), blendMode = BlendMode.Softlight)
            }
            CameraFilter.Mono, CameraFilter.Noir -> drawRect(
                Color.Black.copy(alpha = if (filter == CameraFilter.Noir) 0.08f else 0.03f),
                blendMode = BlendMode.Saturation)
            CameraFilter.Natural -> Unit
        }
        if (exposure > 0.02f || (!isFrontCamera && whitening > 0.04f)) {
            drawRect(
                Color.White.copy(alpha = (
                    exposure.coerceAtLeast(0f) * 0.10f +
                        (if (isFrontCamera) 0f else whitening * 0.035f)
                    ).coerceAtMost(0.065f)),
                blendMode = BlendMode.Screen,
            )
        } else if (exposure < -0.02f) {
            drawRect(Color.Black.copy(alpha = (-exposure * 0.08f).coerceAtMost(0.035f)), blendMode = BlendMode.Multiply)
        }
        // Entry-level MIUI devices may ignore the PreviewView layer paint.
        // Keep beauty preview centered on the likely front-camera face instead
        // of washing the whole frame white. Captured photos still use the
        // pixel-level YCbCr skin mask below.
        if (isFrontCamera && (whitening > 0.02f || smoothing > 0.02f)) {
            val beauty = (whitening * 0.12f + smoothing * 0.055f).coerceAtMost(0.12f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFF8F5).copy(alpha = beauty),
                        Color(0xFFFFEDE9).copy(alpha = beauty * 0.46f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.39f),
                    radius = minOf(size.width * 0.47f, size.height * 0.35f),
                ),
                blendMode = BlendMode.Screen,
            )
        }
        if (filter == CameraFilter.Vintage) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.30f * strength.coerceIn(0.25f, 1f)),
                ),
                blendMode = BlendMode.Multiply,
            )
        }
        // Cream/Mist bloom is intentionally subtle; a full-screen white wash
        // makes the camera look like it only changed brightness.
        if (filter == CameraFilter.Noir || filter == CameraFilter.Film || filter == CameraFilter.HongKong) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to Color.Black.copy(alpha =
                        (if (filter == CameraFilter.Noir) 0.40f else 0.18f) * strength.coerceIn(0.25f, 1f)),
                ),
                blendMode = BlendMode.Multiply,
            )
        }
    }
}

private fun renderCameraPhoto(
    source: Bitmap,
    filter: CameraFilter,
    strength: Float,
    exposure: Float,
    saturation: Float,
    whitening: Float,
    smoothing: Float,
): Bitmap {
    val smooth = smoothing.coerceIn(0f, 1f)
    val white = whitening.coerceIn(0f, 1f)
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Whitening is applied through the skin mask below, so scenery and
        // white objects do not become washed out.
        filter.colorMatrix(strength, exposure, saturation, 0f)?.let {
            colorFilter = ColorMatrixColorFilter(it)
        }
    }
    AndroidCanvas(output).drawBitmap(source, 0f, 0f, paint)
    val opticalFilter = filter in setOf(
        CameraFilter.Mist, CameraFilter.Cream, CameraFilter.Vintage,
        CameraFilter.Film, CameraFilter.HongKong, CameraFilter.Noir,
    )
    if (smooth <= 0.02f && white <= 0.01f) {
        if (opticalFilter) applyOpticalFinish(output, filter, filterStrength = strength)
        return output
    }

    // Work in short row strips. A 1440x1920 capture then needs well under 1 MB
    // of temporary pixel arrays instead of allocating three full-frame arrays.
    val softened = if (smooth > 0.02f) {
        val scale = (0.78f - smooth * 0.18f).coerceAtLeast(0.58f)
        val small = Bitmap.createScaledBitmap(output, (output.width * scale).toInt().coerceAtLeast(1),
            (output.height * scale).toInt().coerceAtLeast(1), true)
        Bitmap.createScaledBitmap(small, output.width, output.height, true).also { small.recycle() }
    } else output
    val rows = 48
    val originalPixels = IntArray(output.width * rows)
    val gradedPixels = IntArray(output.width * rows)
    val softPixels = IntArray(output.width * rows)
    var top = 0
    while (top < output.height) {
        val height = minOf(rows, output.height - top)
        val count = output.width * height
        source.getPixels(originalPixels, 0, output.width, 0, top, output.width, height)
        output.getPixels(gradedPixels, 0, output.width, 0, top, output.width, height)
        if (softened !== output) softened.getPixels(softPixels, 0, output.width, 0, top, output.width, height)
        for (index in 0 until count) {
            val raw = originalPixels[index]
            val rr = raw ushr 16 and 0xff
            val rg = raw ushr 8 and 0xff
            val rb = raw and 0xff
            val mask = skinMask(rr, rg, rb)
            if (mask <= 0f) continue
            val graded = gradedPixels[index]
            var r = graded ushr 16 and 0xff
            var g = graded ushr 8 and 0xff
            var b = graded and 0xff
            if (softened !== output) {
                val softPixel = softPixels[index]
                val sr = softPixel ushr 16 and 0xff
                val sg = softPixel ushr 8 and 0xff
                val sb = softPixel and 0xff
                val edge = (kotlin.math.abs(r - sr) + kotlin.math.abs(g - sg) + kotlin.math.abs(b - sb)) / 255f
                val blend = (smooth * mask * (0.72f - edge * 0.34f)).coerceIn(0f, 0.68f)
                r = mixChannel(r, sr, blend)
                g = mixChannel(g, sg, blend)
                b = mixChannel(b, sb, blend)
            }
            if (white > 0.01f) {
                val lift = white * mask * 0.42f
                // A slightly warm lift removes dull yellow without turning
                // natural skin into flat paper white.
                r = mixChannel(r, 255, lift)
                g = mixChannel(g, 249, lift * 0.92f)
                b = mixChannel(b, 244, lift * 0.78f)
            }
            gradedPixels[index] = (graded ushr 24 shl 24) or (r shl 16) or (g shl 8) or b
        }
        output.setPixels(gradedPixels, 0, output.width, 0, top, output.width, height)
        top += height
    }
    if (softened !== output) softened.recycle()
    if (opticalFilter) applyOpticalFinish(output, filter, filterStrength = strength)
    return output
}

private fun applyOpticalFinish(bitmap: Bitmap, filter: CameraFilter, filterStrength: Float) {
    val amount = filterStrength.coerceIn(0.25f, 1f)
    val canvas = AndroidCanvas(bitmap)
    if (filter == CameraFilter.Mist || filter == CameraFilter.Cream) {
        val alpha = ((if (filter == CameraFilter.Mist) 18 else 9) * amount).toInt()
        canvas.drawColor(android.graphics.Color.argb(alpha, 255, 255, 255))
    }
    val vignetteAlpha = when (filter) {
        CameraFilter.Noir -> 105
        CameraFilter.Vintage -> 66
        CameraFilter.Film -> 48
        CameraFilter.HongKong -> 54
        else -> 0
    }
    if (vignetteAlpha > 0) {
        val radius = kotlin.math.hypot(bitmap.width.toDouble(), bitmap.height.toDouble()).toFloat() * 0.62f
        val shader = RadialGradient(
            bitmap.width / 2f,
            bitmap.height * 0.48f,
            radius,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                android.graphics.Color.argb((vignetteAlpha * amount).toInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply { this.shader = shader })
    }
}

private fun skinMask(r: Int, g: Int, b: Int): Float {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (max < 42 || max - min <= 7) return 0f
    val y = 0.299f * r + 0.587f * g + 0.114f * b
    val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
    val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
    // Elliptical YCbCr likelihood has soft edges and does not reject olive or
    // cool indoor skin just because red is not the largest RGB channel.
    val cbDistance = (cb - 104f) / 34f
    val crDistance = (cr - 151f) / 33f
    val chroma = (1.16f - cbDistance * cbDistance - crDistance * crDistance).coerceIn(0f, 1f)
    val light = ((y - 28f) / 52f).coerceIn(0f, 1f) * ((253f - y) / 38f).coerceIn(0f, 1f)
    val rgbPlausibility = ((r - b + 28f) / 45f).coerceIn(0f, 1f) *
        ((r - g + 24f) / 38f).coerceIn(0f, 1f)
    return (chroma * light * (0.70f + rgbPlausibility * 0.30f)).coerceIn(0f, 1f)
}

private fun mixChannel(from: Int, to: Int, amount: Float): Int =
    (from + (to - from) * amount).toInt().coerceIn(0, 255)

private fun saveCameraPhoto(context: android.content.Context, bitmap: Bitmap): Boolean = runCatching {
    val name = "Xiaoling_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Xiaoling")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            ?: return@runCatching false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Xiaoling").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }
    true
}.getOrDefault(false)

private suspend fun captureCameraFrame(capture: ImageCapture, context: android.content.Context): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    val buffer = image.planes.firstOrNull()?.buffer ?: return continuation.resume(null)
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return continuation.resume(null)
                    val rotation = image.imageInfo.rotationDegrees
                    if (rotation == 0) decoded else Bitmap.createBitmap(
                        decoded, 0, 0, decoded.width, decoded.height,
                        Matrix().apply { postRotate(rotation.toFloat()) }, true,
                    ).also { if (it !== decoded) decoded.recycle() }
                } finally {
                    image.close()
                }
                continuation.resume(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resume(null)
            }
        })
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
