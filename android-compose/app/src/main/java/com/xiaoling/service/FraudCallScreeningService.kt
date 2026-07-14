package com.xiaoling.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.xiaoling.core.AlarmBus
import com.xiaoling.core.FraudStore
import com.xiaoling.core.NumberReputation
import com.xiaoling.core.Notifier

/**
 * 来电防诈闸(Android 10+ 需设为「来电识别/防骚扰」默认应用)。
 * 按号码信誉:block=拦截,warn=预警。高危 → 计数 + 通知 + 推 AlarmBus 让形象即时警惕。
 * 限制:第三方 App 无法获取通话音频内容,只能基于号码判定。
 */
class FraudCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val (level, reason) = NumberReputation.assess(number)
        val builder = CallResponse.Builder()

        if (level == "block" || level == "warn") {
            if (level == "block") {
                builder.setDisallowCall(true).setRejectCall(true)
                    .setSkipCallLog(false).setSkipNotification(false)
            }
            val app = applicationContext
            val why = if (level == "block") "已为您拦截:$reason" else "$reason,请勿转账、勿提供验证码"
            FraudStore.inc(app)
            FraudStore.setPending(app, "这通电话疑似诈骗:$reason")
            AlarmBus.post("这通电话疑似诈骗:$reason")
            Notifier.warn(app, "⚠ 疑似诈骗来电", "$number · $why", ("call" + number).hashCode())
        }
        respondToCall(callDetails, builder.build())
    }
}
