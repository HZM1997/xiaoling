package com.xiaoling.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 系统 TTS 封装:异步 init、中文可用性判断、说完回调 */
class Tts(ctx: Context, private val onDone: () -> Unit) {

    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.CHINA) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { onDone() }
                    @Deprecated("deprecated") override fun onError(id: String?) { onDone() }
                })
            }
        }
    }

    val isReady: Boolean get() = ready

    fun speak(s: String) {
        if (ready && s.isNotBlank()) {
            tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "xl")
        } else {
            onDone()
        }
    }

    fun stop() { tts?.stop() }

    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
}
