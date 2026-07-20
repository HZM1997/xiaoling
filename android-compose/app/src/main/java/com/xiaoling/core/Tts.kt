package com.xiaoling.core

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统 TTS 封装:异步 init、中文可用性判断、带 id 的说完回调。
 * 记住"上次说的话"以支持"被打断→无新指令→恢复播报"。
 */
class Tts(ctx: Context, private val onDone: (String?) -> Unit) {

    private var ready = false
    private var tts: TextToSpeech? = null
    private var seq = 0
    private val main = Handler(Looper.getMainLooper())
    /** 最近一次正常播报的内容(供打断后恢复) */
    @Volatile var lastSpoken: String = ""
        private set

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.CHINA) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.08f)   // 略快,老人也能听清,同时缩短播报时长
                val cb = onDone   // 捕获,避免与 UtteranceProgressListener.onDone 同名方法递归
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { cb(id) }
                    @Deprecated("deprecated") override fun onError(id: String?) { cb(id) }
                })
                // 预热:静音串跑一遍合成管线,消除首句冷启动延迟
                if (ready) tts?.speak(" ", TextToSpeech.QUEUE_FLUSH, Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
                }, "warmup")
            }
        }
    }

    val isReady: Boolean get() = ready

    /** 播报;返回本次 utteranceId。引擎报错/未就绪时立即回调 onDone,避免状态卡死。 */
    fun speak(s: String): String {
        val id = (++seq).toString()
        if (s.isNotBlank()) lastSpoken = s
        if (ready && s.isNotBlank()) {
            val r = tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.ERROR) main.post { onDone(id) }
        } else {
            // 保持异步语义,避免调用方尚未记录 utteranceId 时完成回调已经到达。
            main.post { onDone(id) }
        }
        return id
    }

    /** 重播上一句(打断后未识别到新指令时恢复);无历史则不动作,返回空 id */
    fun speakLast(): String = if (lastSpoken.isNotBlank()) speak(lastSpoken) else ""

    /** 立即停止当前播报(打断用) */
    fun stop() { tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
