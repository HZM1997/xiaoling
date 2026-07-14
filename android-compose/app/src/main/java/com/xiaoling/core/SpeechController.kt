package com.xiaoling.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统语音识别封装(中文)。注意:必须在主线程创建与调用;RECORD_AUDIO 需已授权。
 * 所有 RecognitionListener 抽象方法都要实现。
 */
class SpeechController(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(onText: (String) -> Unit, onError: (Int) -> Unit) {
        if (!isAvailable) { onError(-1); return }
        val textCb = onText          // 捕获,避免与 override 同名方法冲突(否则递归)
        val errCb = onError
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { r ->
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    textCb(text)
                }
                override fun onError(error: Int) { errCb(error) }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 极致反应:用户停顿 ~0.8s 即定稿,尽快出结果
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800)
        }
        recognizer?.startListening(intent)
    }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}
