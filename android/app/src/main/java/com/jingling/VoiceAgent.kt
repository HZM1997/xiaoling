package com.jingling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * 小灵 · 语音闭环核心
 * 唤醒(端侧离线) → 识别(系统ASR降级 / 生产用讯飞阿里流式方言SDK)
 *   → 请求云端大脑 → TTS播报 → 调起已有App(精灵=遥控器,不重造轮子)
 *
 * 瘦客户端原则:本类不含任何模型,APK 才能压到十几 MB、跑得动老年机。
 * 低端机/无网时全部降级到系统 ASR/TTS,保证不白屏。
 */
class VoiceAgent(private val ctx: Context) {

    // 生产环境替换为你的大脑地址;联调可先用 Node 版: node xiaoling.js --serve
    private val brainUrl = "https://api.your-domain.com/dialogue"

    private lateinit var tts: TextToSpeech
    private lateinit var asr: SpeechRecognizer
    private val http = OkHttpClient()

    fun init() {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.CHINA
        }
        asr = SpeechRecognizer.createSpeechRecognizer(ctx)
        startWakeWord()   // 端侧离线唤醒常驻(见下方说明)
    }

    /** 唤醒词"小灵小灵"命中后开启一轮对话 */
    fun onWake() {
        speak("我在,您说")
        listenOnce()
    }

    /** 一次语音识别。生产用讯飞/阿里流式SDK(支持方言+边说边出字);此处系统ASR做降级演示 */
    private fun listenOnce() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        }
        asr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) askBrain(text, context = null)
            }
            override fun onError(error: Int) { speak("我没听清,您再说一遍好吗?") }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(i: Int, b: Bundle?) {}
        })
        asr.startListening(intent)
    }

    /**
     * 来电监听触发防诈分析:由 CallScreeningService/BroadcastReceiver 调用,
     * 把对方号码与实时转写内容送云端。context 里带 scene=incoming_call。
     */
    fun onIncomingCallSpeech(caller: String, liveText: String) {
        askBrain(liveText, context = JSONObject()
            .put("scene", "incoming_call").put("caller", caller))
    }

    /** 请求云端大脑,拿到"话术 + 动作" */
    private fun askBrain(text: String, context: JSONObject?) {
        val payload = JSONObject()
            .put("user_id", "u001")
            .put("text", text)
            .apply { context?.let { put("context", it) } }
            .toString()
        val req = Request.Builder().url(brainUrl)
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onResponse(call: Call, resp: Response) {
                val j = JSONObject(resp.body?.string().orEmpty())
                speak(j.optString("speech", "我在呢。"))          // 先播报
                j.optJSONObject("action")?.let { execute(it) }    // 再执行
            }
            override fun onFailure(call: Call, e: IOException) {
                speak("网络好像不太好,我们待会儿再试。")            // 降级:不白屏
            }
        })
    }

    /** 执行动作:核心是"调起已有App",精灵只做遥控器 */
    private fun execute(a: JSONObject) {
        when (a.optString("type")) {
            "CALL"      -> dial(lookupContact(a.optString("target")))
            "SOS"       -> { dial(a.optString("call", "120")); notifyFamily() }
            "OPEN_URI"  -> openUri(a.optString("uri"))
            "FRAUD_WARN"-> flashRedWarning()      // 全屏红色警告,老人也看得懂
            "PLAY"      -> openUri("iting://open?msg=" + a.optString("keyword")) // 示例DeepLink
            "REMIND"    -> scheduleRemind(a.optString("raw"))
            else        -> {}
        }
    }

    private fun openUri(uri: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { speak("没找到对应的应用,要不要我帮您装一个?") }

    private fun dial(num: String) {
        if (num.isBlank()) { speak("没找到这个联系人,您能说全名字吗?"); return }
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))   // 需 CALL_PHONE 权限
    }

    private fun speak(s: String) = tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "utt")

    // —— 以下为占位,接真实实现 ——
    private fun startWakeWord() { /* Picovoice Porcupine / 自训KWS 常驻低功耗监听,命中回调 onWake() */ }
    private fun lookupContact(name: String): String = "" /* 查 ContactsContract 返回号码 */
    private fun notifyFamily() { /* 给紧急联系人发短信+定位 */ }
    private fun flashRedWarning() { /* 拉起全屏红色警告页 + 震动 + 大声播报 */ }
    private fun scheduleRemind(raw: String) { /* 解析时间 → AlarmManager 定时提醒 */ }
}
