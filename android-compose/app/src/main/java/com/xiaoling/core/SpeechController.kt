package com.xiaoling.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * 系统语音识别封装(中文),支持「按住说话」交互:
 *  - listen() 按住时开始识别,支持边说边出字(partial);
 *  - stopListening() 松手时收尾,尽快出最终结果;
 *  - 预热省冷启动。
 * 注意:必须在主线程创建与调用;RECORD_AUDIO 需已授权;实现全部 RecognitionListener 抽象方法。
 */
class SpeechController(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var serviceCursor = 0

    private val standardAvailable: Boolean
        get() = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    private val onDeviceAvailable: Boolean
        get() = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }

    val isAvailable: Boolean
        get() = hasPermission && (recognitionServices().isNotEmpty() || standardAvailable || onDeviceAvailable)

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 预热:提前建好识别器,首次/每轮开听更快 */
    fun warmUp() {
        if (!isAvailable) return
        try {
            if (recognizer == null) {
                recognizer = createRecognizer()
            }
        } catch (_: Throwable) {
            releaseRecognizer()
        }
    }

    /**
     * 开始识别(按住说话时调用)。
     * @param onPartial 边说边出的临时结果(用于实时字幕)
     * @param onText    最终定稿文本
     * @param onError   识别错误码
     */
    fun listen(
        onPartial: (String) -> Unit = {},
        onText: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        if (!hasPermission) { onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        if (!isAvailable) { onError(SpeechRecognizer.ERROR_CLIENT); return }
        val partialCb = onPartial
        val textCb = onText
        val errCb = onError
        try {
            if (recognizer == null) recognizer = createRecognizer()
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    textCb(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (p.isNotBlank()) partialCb(p)
                }
                override fun onError(error: Int) {
                    // MIUI 的识别服务在取消、超时或断网后可能留下失效实例;下次重新创建更可靠。
                    releaseRecognizer()
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                    ) {
                        advanceRecognitionService()
                    }
                    errCb(error)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 不强制 EXTRA_PREFER_OFFLINE。部分红米会声称支持设备端识别,实际没有中文模型,
                // 强制离线后只返回 ERROR_CLIENT/NO_MATCH。标准服务可自行选择在线或本地引擎。
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            }
            recognizer?.startListening(intent)
        } catch (_: Throwable) {
            releaseRecognizer()
            errCb(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** 松手时调用:停止采音并尽快给出最终结果(触发 onResults) */
    fun stopListening() {
        try { recognizer?.stopListening() } catch (_: Throwable) { releaseRecognizer() }
    }

    /** 取消后销毁实例,避免 MIUI 把下一次识别继续绑定到已失效会话。 */
    fun cancel() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        releaseRecognizer()
    }

    fun destroy() = releaseRecognizer()

    private fun createRecognizer(): SpeechRecognizer {
        // 部分 MIUI 有识别服务但没有写入默认服务设置。显式绑定已安装服务可避免
        // isRecognitionAvailable()==true、startListening() 却直接 ERROR_CLIENT 的情况。
        val services = recognitionServices()
        if (services.isNotEmpty()) {
            val service = services[serviceCursor.mod(services.size)]
            return SpeechRecognizer.createSpeechRecognizer(ctx, service)
        }
        return if (onDeviceAvailable) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else if (standardAvailable) {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } else {
            throw IllegalStateException("No speech recognition service")
        }
    }

    private fun recognitionServices(): List<ComponentName> {
        val configured = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.VOICE_RECOGNITION_SERVICE)
                ?.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
        } catch (_: Throwable) {
            null
        }
        val discovered = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { info ->
                    info.serviceInfo?.let { ComponentName(it.packageName, it.name) }
                }
        } catch (_: Throwable) {
            emptyList()
        }
        return buildList {
            // MIUI 12.5 may keep a stale voice_recognition_service component after splitting
            // Voice Assist and MiBrain into separate packages. Only trust it when it resolves.
            if (configured != null && (discovered.isEmpty() || configured in discovered)) add(configured)
            addAll(discovered)
        }.distinct()
    }

    private fun advanceRecognitionService() {
        val count = recognitionServices().size
        if (count > 1) serviceCursor = (serviceCursor + 1) % count
    }

    private fun releaseRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
