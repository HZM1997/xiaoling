package com.xiaoling.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import android.Manifest

/**
 * 系统语音识别封装(中文),支持「按住说话」交互:
 *  - listen() 按住时开始识别,支持边说边出字(partial);
 *  - stopListening() 松手时收尾,尽快出最终结果;
 *  - 预热省冷启动。
 * 注意:必须在主线程创建与调用;RECORD_AUDIO 需已授权;实现全部 RecognitionListener 抽象方法。
 */
class SpeechController(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = try {
            hasPermission && (
                SpeechRecognizer.isRecognitionAvailable(ctx) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
                )
        } catch (_: Throwable) {
            false
        }

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
                override fun onError(error: Int) { errCb(error) }
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
                // 按住说话:靠松手 stopListening() 收尾,静音阈值放宽以免老人说话间断被半途截断
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300)
            }
            recognizer?.startListening(intent)
        } catch (_: Throwable) {
            releaseRecognizer()
            errCb(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** 松手时调用:停止采音并尽快给出最终结果(触发 onResults) */
    fun stopListening() { try { recognizer?.stopListening() } catch (e: Exception) {} }

    /** 取消本次识别但保留识别器复用 */
    fun cancel() { try { recognizer?.cancel() } catch (e: Exception) {} }

    fun destroy() = releaseRecognizer()

    private fun createRecognizer(): SpeechRecognizer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
    }

    private fun releaseRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }
}
