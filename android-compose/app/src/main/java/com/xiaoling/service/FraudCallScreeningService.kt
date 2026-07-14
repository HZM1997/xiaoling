package com.xiaoling.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import com.xiaoling.core.FraudStore
import com.xiaoling.core.NumberReputation

/**
 * 来电防诈第一道闸(Android 10+ 需被设为「来电识别/防骚扰」默认应用)。
 * 系统在响铃前回调:按号码信誉决定 拦截(block)/ 弹警告(warn)/ 放行(safe)。
 * 限制:第三方 App 无法获取通话音频内容,故只能基于号码判定。
 */
class FraudCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart ?: ""
        val (level, reason) = NumberReputation.assess(number)
        val builder = CallResponse.Builder()

        when (level) {
            "block" -> {
                builder.setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                FraudStore.inc(applicationContext)
                warn(number, "已为您拦截:$reason")
            }
            "warn" -> {
                FraudStore.inc(applicationContext)
                warn(number, "$reason,请勿转账、勿提供验证码")
            }
        }
        respondToCall(callDetails, builder.build())
    }

    private fun warn(number: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = "fraud_call"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "来电防诈", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(this, ch)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠ 疑似诈骗来电")
            .setContentText("$number · $text")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$number\n$text"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(number.hashCode(), n)
        } catch (e: SecurityException) {
            // Android 13+ 未授 POST_NOTIFICATIONS:忽略
        }
    }
}
