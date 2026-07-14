package com.jingling

import android.content.Context

/**
 * 方言流式 ASR 封装(以科大讯飞为例)。
 * 为什么用它:讯飞/阿里/百度已支持 20+ 方言(粤语/四川/河南/东北/闽南…),
 * 我们做"编排"不自研识别。此封装把厂商 SDK 收敛成一个统一接口,
 * 方便日后换供应商(阿里 NUI / 百度)而不动上层。
 *
 * 接入步骤见 BUILD.md:把讯飞 SDK 的 aar 放 app/libs/,填 APPID。
 * 未接 SDK 时,VoiceAgent 用系统 SpeechRecognizer 降级(仅普通话)。
 */
class IflytekAsr(private val ctx: Context) {

    interface Listener {
        fun onPartial(text: String)                 // 边说边出字,用于急速反馈
        fun onFinal(text: String)                   // 最终结果
        fun onError(code: Int, msg: String)
    }

    /** 选择方言:讯飞用 accent 参数,如 mandarin / cantonese / sichuanese / henanese ... */
    fun start(accent: String = "mandarin", listener: Listener) {
        // 伪代码(填入讯飞真实 API):
        // val recognizer = SpeechRecognizer.createRecognizer(ctx) { }
        // recognizer.setParameter(SpeechConstant.LANGUAGE, "zh_cn")
        // recognizer.setParameter(SpeechConstant.ACCENT, accent)
        // recognizer.setParameter(SpeechConstant.VAD_EOS, "700")     // 静音断句
        // recognizer.startListening(object: RecognizerListener { ... 回调映射到 listener ... })
    }

    fun stop() { /* recognizer.stopListening() */ }

    companion object {
        /** 常见方言映射,可从云端配置下发(不同地区默认不同) */
        val ACCENTS = mapOf(
            "普通话" to "mandarin", "粤语" to "cantonese", "四川话" to "sichuanese",
            "河南话" to "henanese", "东北话" to "dongbeihua", "闽南语" to "minnanese",
        )
    }
}
