package com.xiaoling.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.xiaoling.core.AlarmBus
import com.xiaoling.core.FraudStore
import com.xiaoling.core.FraudText
import com.xiaoling.core.Notifier

/**
 * 接收进来的短信,做端侧诈骗判定。高危 → 计数 + 高优先级通知 + 推 AlarmBus 让形象即时警惕。
 * 需 RECEIVE_SMS 权限;非默认短信应用也能读取 SMS_RECEIVED(仅读,不拦截)。
 */
class SmsFraudReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val text = msgs.joinToString("") { it.messageBody ?: "" }
        if (text.isBlank()) return
        val sender = msgs.firstOrNull()?.originatingAddress ?: "未知号码"

        val (high, reason) = FraudText.assess(text)
        if (!high) return

        val app = ctx.applicationContext
        FraudStore.inc(app)
        FraudStore.setPending(app, "这条短信疑似诈骗:$reason")
        AlarmBus.post("这条短信疑似诈骗:$reason")
        Notifier.warn(
            app,
            "⚠ 疑似诈骗短信",
            "$sender:$reason。千万不要点链接、不要转账、不要提供验证码。",
            ("sms" + sender).hashCode()
        )
    }
}
