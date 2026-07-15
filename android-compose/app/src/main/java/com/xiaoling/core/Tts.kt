package com.xiaoling.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 系统 TTS 封装:异步 init、中文可用性判断、带 id 的说完回调(避免被 flush 的旧句误清状态) */
class Tts(ctx: Context, private val onDone: (String?) -> Unit) {

    private var ready = false
    private var tts: TextToSpeech? = null
    private var seq = 0

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.CHINA) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                val cb = onDone   // 捕获,避免与 UtteranceProgressListener.onDone 同名方法递归
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { cb(id) }
                    @Deprecated("deprecated") override fun onError(id: String?) { cb(id) }
                })
            }
        }
    }

    val isReady: Boolean get() = ready

    /** 播报;返回本次 utteranceId。引擎报错/未就绪时立即回调 onDone,避免状态卡死。 */
    fun speak(s: String): String {
        val id = (++seq).toString()
        if (ready && s.isNotBlank()) {
            val r = tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.ERROR) onDone(id)
        } else {
            onDone(id)
        }
        return id
    }

    fun stop() { tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
