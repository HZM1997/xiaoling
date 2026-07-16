package com.xiaoling.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统语音识别封装(中文)。极致反应优化:
 *  - 预热:提前创建 recognizer,省每轮冷启动;
 *  - partial results:边说边出字,上层可对明确指令提前预备;
 *  - 停顿 0.7s 即定稿。
 * 注意:必须在主线程创建与调用;RECORD_AUDIO 需已授权;实现全部 RecognitionListener 抽象方法。
 */
class SpeechController(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** 预热:提前建好识别器,首次/每轮开听更快 */
    fun warmUp() {
        if (!isAvailable) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        }
    }

    /**
     * @param onPartial 边说边出的临时结果(可为空);上层用于提前预备,不代表定稿
     * @param onText    最终定稿文本
     * @param onError   识别错误码
     */
    fun listen(
        onPartial: (String) -> Unit = {},
        onText: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        if (!isAvailable) { onError(-1); return }
        val partialCb = onPartial
        val textCb = onText
        val errCb = onError
        // 复用预热好的识别器;没有才新建
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)   // 边说边出字
            // 极致反应:停顿 ~0.7s 即定稿,最短时长下调
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
        }
        recognizer?.startListening(intent)
    }

    /** 停止当前识别但保留识别器(供复用,避免下一轮冷启动) */
    fun cancel() { recognizer?.cancel() }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}
