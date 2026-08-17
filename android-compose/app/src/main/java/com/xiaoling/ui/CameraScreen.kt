package com.xiaoling.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.xiaoling.R
import com.xiaoling.core.AppState
import com.xiaoling.core.CameraFilter
import com.xiaoling.core.Screen
import com.xiaoling.core.TactileFeedback
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
    DisposableEffect(ui.cameraLens) { onDispose { cameraProvider?.unbindAll() } }

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
        CameraFilterOverlay(filter, filterStrength, exposure, saturation, whitening)

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
                kotlinx.coroutines.delay(if (attempt == 0) 700 else 250)
                frame = previewView?.bitmap
                if (frame != null) break
            }
            frame?.let { vm.analyzeCameraFrame(it, ui.cameraRequestId) }
        }
    }

    LaunchedEffect(previewView, ui.cameraCaptureRequestId) {
        if (ui.cameraCaptureRequestId > 0L) {
            kotlinx.coroutines.delay(420L)
            val frame = imageCapture?.let { captureCameraFrame(it, context) } ?: previewView?.bitmap
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

    LaunchedEffect(previewView, ui.cameraLens) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // This is only context enrichment; user-requested recognition is
            // handled above immediately. Sampling less often protects voice
            // capture and rendering on entry-level phones.
            kotlinx.coroutines.delay(4_000L)
            previewView?.bitmap?.let(vm::observeCameraFrame)
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val matrix = filter.colorMatrix(strength, exposure, saturation, whitening)
    val colorEffect = matrix?.let { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(it)) }
    val blurRadius = smoothing.coerceIn(0f, 1f) * 1.35f
    val effect = when {
        blurRadius >= 0.05f && colorEffect != null ->
            RenderEffect.createBlurEffect(blurRadius, blurRadius, colorEffect, Shader.TileMode.CLAMP)
        blurRadius >= 0.05f -> RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        else -> colorEffect
    }
    setRenderEffect(effect)
}

@Composable
private fun CameraFilterOverlay(filter: CameraFilter, strength: Float, exposure: Float, saturation: Float, whitening: Float) {
    if ((filter == CameraFilter.Natural && kotlin.math.abs(exposure) < 0.001f && kotlin.math.abs(saturation - 1f) < 0.001f && whitening < 0.001f) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
    Canvas(Modifier.fillMaxSize()) {
        val (tint, alpha) = when (filter) {
            CameraFilter.Natural -> Color.Transparent to 0f
            CameraFilter.Warm -> Color(0xFFFFB15C) to 0.17f
            CameraFilter.Cream -> Color(0xFFFFE2B5) to 0.13f
            CameraFilter.Mist -> Color(0xFFE8F0FF) to 0.16f
            CameraFilter.Cool -> Color(0xFF76BEFF) to 0.16f
            CameraFilter.Sunset -> Color(0xFFFF7043) to 0.18f
            CameraFilter.Forest -> Color(0xFF63A86B) to 0.14f
            CameraFilter.TealOrange -> Color(0xFF3D9A9A) to 0.14f
            CameraFilter.Vintage -> Color(0xFFD59756) to 0.20f
            CameraFilter.Film -> Color(0xFFB99168) to 0.14f
            CameraFilter.HongKong -> Color(0xFFB45A45) to 0.14f
            CameraFilter.Mono -> Color(0xFF20242A) to 0.18f
            CameraFilter.Noir -> Color(0xFF101217) to 0.25f
            CameraFilter.Vivid -> Color(0xFFFF5577) to 0.08f
        }
        if (filter != CameraFilter.Natural) {
            drawRect(tint.copy(alpha = alpha * strength.coerceIn(0.25f, 1f)), blendMode = BlendMode.Softlight)
        }
        if (kotlin.math.abs(exposure) >= 0.01f) {
            val light = exposure.coerceIn(-0.35f, 0.35f)
            drawRect(
                if (light > 0f) Color.White.copy(alpha = light * 0.24f) else Color.Black.copy(alpha = -light * 0.28f),
                blendMode = if (light > 0f) BlendMode.Screen else BlendMode.Multiply,
            )
        }
        if (saturation < 0.95f) {
            drawRect(Color(0xFF5F6770).copy(alpha = (0.95f - saturation) * 0.20f), blendMode = BlendMode.Saturation)
        } else if (saturation > 1.05f) {
            drawRect(Color(0xFFFF5B7B).copy(alpha = (saturation - 1f) * 0.08f), blendMode = BlendMode.Color)
        }
        if (whitening > 0.01f) {
            drawRect(Color.White.copy(alpha = whitening.coerceIn(0f, 1f) * 0.13f), blendMode = BlendMode.Screen)
        }
        if (filter == CameraFilter.Vintage) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.30f),
                ),
                blendMode = BlendMode.Multiply,
            )
        }
        if (filter == CameraFilter.Mist || filter == CameraFilter.Cream) {
            drawRect(Color.White.copy(alpha = if (filter == CameraFilter.Mist) 0.11f else 0.06f),
                blendMode = BlendMode.Screen)
        }
        if (filter == CameraFilter.Noir || filter == CameraFilter.Film || filter == CameraFilter.HongKong) {
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (filter == CameraFilter.Noir) 0.40f else 0.18f),
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
    if (smooth <= 0.02f && white <= 0.01f) return output

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
    return output
}

private fun skinMask(r: Int, g: Int, b: Int): Float {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (r <= 70 || g <= 30 || b <= 15 || r <= g || r <= b || max - min <= 10) return 0f
    val y = 0.299f * r + 0.587f * g + 0.114f * b
    val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
    val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
    val chroma = minOf(
        ((cb - 72f) / 18f).coerceIn(0f, 1f),
        ((132f - cb) / 18f).coerceIn(0f, 1f),
        ((cr - 126f) / 16f).coerceIn(0f, 1f),
        ((178f - cr) / 18f).coerceIn(0f, 1f),
    )
    val light = ((y - 45f) / 45f).coerceIn(0f, 1f) * ((245f - y) / 32f).coerceIn(0f, 1f)
    val rgbConfidence = ((r - g - 4f) / 22f).coerceIn(0f, 1f)
    return (chroma * light * (0.55f + rgbConfidence * 0.45f)).coerceIn(0f, 1f)
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
