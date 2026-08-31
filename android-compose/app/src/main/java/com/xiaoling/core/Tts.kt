package com.xiaoling.core

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统 TTS 封装:异步 init、中文可用性判断、带 id 的说完回调。
 * 记住"上次说的话"以支持"被打断→无新指令→恢复播报"。
 */
class Tts(
    ctx: Context,
    private val onDone: (String?) -> Unit,
    private val onStarted: (String?) -> Unit = {},
    private val onPreparing: (String?) -> Unit = {},
) {

    private var ready = false
    private var tts: TextToSpeech? = null
    private var seq = 0
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Long>()
    private val started = ConcurrentHashMap.newKeySet<String>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    /** 最近一次正常播报的内容(供打断后恢复) */
    @Volatile var lastSpoken: String = ""
        private set
    @Volatile private var lastLanguage: String = "mandarin"

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.CHINA) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                // Prefer a locally installed Mandarin female voice. Engines
                // expose different names, so use conservative name/locale
                // hints and fall back to the engine default when unavailable.
                val female = tts?.voices.orEmpty()
                    .filter { it.locale.language == Locale.CHINA.language && !it.isNetworkConnectionRequired }
                    .sortedWith(compareBy<Voice> {
                        val n = it.name.lowercase(Locale.ROOT)
                        if (listOf("female", "woman", "xiaoxiao", "xiaoyi", "女", "晓晓", "小艺").any(n::contains)) 0 else 1
                    }.thenByDescending { it.quality })
                    .firstOrNull()
                if (female != null) tts?.voice = female
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Slightly slower and softer than the engine default. A small
                // pitch lift reads as warm without producing a synthetic or
                // child-like voice on common Xiaomi system engines.
                tts?.setSpeechRate(0.98f)
                tts?.setPitch(1.08f)
                val cb = onDone   // 捕获,避免与 UtteranceProgressListener.onDone 同名方法递归
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        id?.takeIf { pending.containsKey(it) }?.let {
                            started.add(it)
                            startedAt.putIfAbsent(it, SystemClock.elapsedRealtime())
                        }
                        main.post { onStarted(id) }
                    }
                    override fun onDone(id: String?) { finishPending(id, cb) }
                    @Deprecated("deprecated") override fun onError(id: String?) { finishPending(id, cb) }
                    override fun onError(id: String?, errorCode: Int) { finishPending(id, cb) }
                    override fun onStop(id: String?, interrupted: Boolean) {
                        if (interrupted) {
                            id?.let {
                                pending.remove(it)
                                started.remove(it)
                                startedAt.remove(it)
                            }
                        } else finishPending(id, cb)
                    }
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
    fun speak(s: String, language: String = "mandarin"): String {
        val id = (++seq).toString()
        if (s.isNotBlank()) {
            lastSpoken = s
            lastLanguage = language
        }
        if (ready && s.isNotBlank()) {
            onPreparing(id)
            tts?.setLanguage(localeFor(language))
            val timeoutMs = (2_500L + s.length * 420L).coerceIn(4_500L, 30_000L)
            pending[id] = SystemClock.elapsedRealtime() + timeoutMs
            val r = tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
            if (r == TextToSpeech.ERROR) finishPending(id, onDone) else monitorCompletion(id, onDone)
        } else {
            // 保持异步语义,避免调用方尚未记录 utteranceId 时完成回调已经到达。
            main.post { onDone(id) }
        }
        return id
    }

    /** 全双工倾听反馈。低音量插入队列,不覆盖正常回答,也不修改可恢复的上一句话。 */
    fun speakBackchannel(s: String) {
        if (!ready || s.isBlank()) return
        val id = "backchannel-${++seq}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.58f)
        }
        tts?.speak(s, TextToSpeech.QUEUE_ADD, params, id)
    }

    /** 重播上一句(打断后未识别到新指令时恢复);无历史则不动作,返回空 id */
    fun speakLast(): String = if (lastSpoken.isNotBlank()) speak(lastSpoken, lastLanguage) else ""

    /** 部分 MIUI TTS 会播完却漏掉 onDone；轮询 isSpeaking，保证连续对话不会永久卡住。 */
    private fun monitorCompletion(id: String, callback: (String?) -> Unit) {
        main.postDelayed(object : Runnable {
            private var silentPolls = 0

            override fun run() {
                val deadline = pending[id] ?: return
                val engineSpeaking = try { tts?.isSpeaking == true } catch (_: Throwable) { false }
                if (engineSpeaking) {
                    started.add(id)
                    startedAt.putIfAbsent(id, SystemClock.elapsedRealtime())
                    silentPolls = 0
                } else if (started.contains(id)) {
                    silentPolls++
                }
                val elapsed = SystemClock.elapsedRealtime() - (startedAt[id] ?: Long.MAX_VALUE)
                val finishedNormally = started.contains(id) &&
                    silentPolls >= SILENT_POLLS_REQUIRED && elapsed >= MIN_PLAYBACK_GUARD_MS
                if (finishedNormally || SystemClock.elapsedRealtime() >= deadline) {
                    finishPending(id, callback)
                } else {
                    main.postDelayed(this, 220L)
                }
            }
        }, 320L)
    }

    private fun finishPending(id: String?, callback: (String?) -> Unit) {
        if (id == null || pending.remove(id) == null) return
        started.remove(id)
        startedAt.remove(id)
        main.post { callback(id) }
    }

    private fun localeFor(language: String): Locale = when (language) {
        "english" -> Locale.US
        "cantonese" -> Locale.forLanguageTag("yue-Hans-CN")
        "japanese" -> Locale.JAPAN
        "korean" -> Locale.KOREA
        "spanish" -> Locale.forLanguageTag("es-ES")
        "french" -> Locale.FRANCE
        "german" -> Locale.GERMANY
        "russian" -> Locale.forLanguageTag("ru-RU")
        "portuguese" -> Locale.forLanguageTag("pt-BR")
        "arabic" -> Locale.forLanguageTag("ar-SA")
        "thai" -> Locale.forLanguageTag("th-TH")
        "vietnamese" -> Locale.forLanguageTag("vi-VN")
        else -> Locale.CHINA
    }

    /** 立即停止当前播报(打断用) */
    fun stop() { tts?.stop() }

    fun shutdown() { pending.clear(); started.clear(); startedAt.clear(); tts?.stop(); tts?.shutdown(); tts = null }

    private companion object {
        // 220 ms polling * 4 lets MIUI recover from a transient false isSpeaking
        // value without delaying the normal onDone callback path.
        const val SILENT_POLLS_REQUIRED = 4
        const val MIN_PLAYBACK_GUARD_MS = 900L
    }
}
