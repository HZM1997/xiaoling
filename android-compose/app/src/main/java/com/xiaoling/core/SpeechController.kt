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
    private var recognizerAttempt = 0
    private val cloud = CloudAsrRecorder(ctx)
    private var systemMisses = 0
    private var preferCloud = false

    private val standardAvailable: Boolean
        get() = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    private val onDeviceAvailable: Boolean
        get() = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }

    private val systemAvailable: Boolean
        get() = recognitionServices().isNotEmpty() || standardAvailable || onDeviceAvailable

    private val cloudAvailable: Boolean
        get() = Settings.brainUrl(ctx).isNotBlank() && NetworkStatus.isOnline(ctx)

    val isAvailable: Boolean
        get() = hasPermission && (systemAvailable || cloudAvailable)

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 预热:提前建好识别器,首次/每轮开听更快 */
    fun warmUp() {
        if (!isAvailable) return
        try {
            if (systemAvailable && recognizer == null) {
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
        onReady: () -> Unit = {},
        onSpeechStart: () -> Unit = {},
        onText: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        if (!hasPermission) { onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        if (!isAvailable) { onError(SpeechRecognizer.ERROR_CLIENT); return }
        val partialCb = onPartial
        val readyCb = onReady
        val speechStartCb = onSpeechStart
        val textCb = onText
        val errCb = onError
        var lastPartial = ""
        if ((!systemAvailable || preferCloud) && cloudAvailable) {
            val started = cloud.start(
                onReady = readyCb,
                onSpeechStart = speechStartCb,
                onText = { text ->
                    preferCloud = false
                    systemMisses = 0
                    textCb(text)
                },
                onError = { error ->
                    preferCloud = false
                    errCb(error)
                }
            )
            if (started) return
        }
        try {
            if (recognizer == null) recognizer = createRecognizer()
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = bestCandidate(results)
                    if (text.isBlank()) {
                        systemMisses++
                        if (systemMisses >= 2 && cloudAvailable) preferCloud = true
                    } else {
                        systemMisses = 0
                    }
                    textCb(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.let(::bestCandidate).orEmpty()
                    if (p.isNotBlank()) {
                        lastPartial = p
                        partialCb(p)
                    }
                }
                override fun onError(error: Int) {
                    // 轻声或远距离收音时 MIUI 可能已有临时文本,却最终返回 NO_MATCH。
                    if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                        lastPartial.count { !it.isWhitespace() } >= 2) {
                        releaseRecognizer()
                        systemMisses = 0
                        textCb(lastPartial)
                        return
                    }
                    // MIUI 的识别服务在取消、超时或断网后可能留下失效实例;下次重新创建更可靠。
                    releaseRecognizer()
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        systemMisses++
                        if (systemMisses >= 2 && cloudAvailable) preferCloud = true
                    }
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_NETWORK ||
                        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                    ) {
                        advanceRecognitionService()
                        if (cloudAvailable) preferCloud = true
                    }
                    errCb(error)
                }
                override fun onReadyForSpeech(params: Bundle?) { readyCb() }
                override fun onBeginningOfSpeech() { speechStartCb() }
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // 不强制 EXTRA_PREFER_OFFLINE。部分红米会声称支持设备端识别,实际没有中文模型,
                // 强制离线后只返回 ERROR_CLIENT/NO_MATCH。标准服务可自行选择在线或本地引擎。
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_BIASING_STRINGS,
                        arrayListOf(
                            "小灵", "打电话", "提醒我", "导航", "播放", "天气",
                            "诈骗", "验证码", "转账", "地震", "台风", "暴雨", "沙尘暴"
                        )
                    )
                }
            }
            recognizer?.startListening(intent)
        } catch (_: Throwable) {
            releaseRecognizer()
            if (cloudAvailable) preferCloud = true
            errCb(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** 松手时调用:停止采音并尽快给出最终结果(触发 onResults) */
    fun stopListening() {
        if (cloud.isActive) {
            cloud.stopListening()
            return
        }
        try { recognizer?.stopListening() } catch (_: Throwable) { releaseRecognizer() }
    }

    /** 取消后销毁实例,避免 MIUI 把下一次识别继续绑定到已失效会话。 */
    fun cancel() {
        cloud.cancel()
        try { recognizer?.cancel() } catch (_: Throwable) {}
        releaseRecognizer()
    }

    fun destroy() {
        cloud.cancel()
        releaseRecognizer()
    }

    private fun bestCandidate(results: Bundle): String {
        val candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (candidates.isEmpty()) return ""
        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return candidates.indices.maxWithOrNull(
            compareBy<Int> { index -> confidence?.getOrNull(index)?.takeIf { it >= 0f } ?: -1f }
                .thenBy { index -> candidates[index].count { !it.isWhitespace() } }
        )?.let(candidates::get).orEmpty()
    }

    private fun createRecognizer(): SpeechRecognizer {
        // 部分 MIUI 有识别服务但没有写入默认服务设置。显式绑定已安装服务可避免
        // isRecognitionAvailable()==true、startListening() 却直接 ERROR_CLIENT 的情况。
        val services = recognitionServices()
        var option = recognizerAttempt
        if (option < services.size) {
            return SpeechRecognizer.createSpeechRecognizer(ctx, services[option])
        }
        option -= services.size
        if (standardAvailable) {
            if (option == 0) return SpeechRecognizer.createSpeechRecognizer(ctx)
            option--
        }
        if (onDeviceAvailable && option == 0) return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        recognizerAttempt = 0
        if (services.isNotEmpty()) return SpeechRecognizer.createSpeechRecognizer(ctx, services.first())
        if (standardAvailable) return SpeechRecognizer.createSpeechRecognizer(ctx)
        if (onDeviceAvailable) return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        throw IllegalStateException("No speech recognition service")
    }

    private fun recognitionServices(): List<ComponentName> {
        val configured = try {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
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
            if (configured != null && configured in discovered) add(configured)
            addAll(discovered)
        }.distinct()
    }

    private fun advanceRecognitionService() {
        val count = recognitionServices().size +
            (if (standardAvailable) 1 else 0) + (if (onDeviceAvailable) 1 else 0)
        if (count > 1) recognizerAttempt = (recognizerAttempt + 1) % count
    }

    private fun releaseRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
