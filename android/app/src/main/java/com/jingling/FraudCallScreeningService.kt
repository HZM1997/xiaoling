package com.jingling

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * 来电防诈第一道闸(Android 10+ CallScreeningService)。
 * 系统在响铃前回调这里:先按号码信誉决定是否静音/拦截,
 * 接通后再把实时转写文本送云端 /dialogue 做语义研判(见 VoiceAgent.onIncomingCallSpeech)。
 *
 * 要生效需引导用户把小灵设为"默认来电识别应用"(系统设置里授权)。
 */
class FraudCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart ?: ""

        val resp = CallResponse.Builder()

        if (isBlacklisted(number)) {
            // 已知诈骗号:直接拒接 + 不进通话记录
            resp.setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
        } else if (isSuspicious(number)) {
            // 可疑(境外/改号/虚商):不拦截,但打标记,接通后开启实时研判
            armLiveTranscription(number)
        }
        respondToCall(details, resp.build())
    }

    private fun isBlacklisted(n: String): Boolean = false      // TODO 接黑名单库
    private fun isSuspicious(n: String): Boolean =
        n.startsWith("00") || (n.startsWith("+") && !n.startsWith("+86")) ||
        n.startsWith("170") || n.startsWith("171")

    private fun armLiveTranscription(number: String) {
        // TODO: 接通后用讯飞流式ASR转写通话 → VoiceAgent.onIncomingCallSpeech(number, text)
    }
}
