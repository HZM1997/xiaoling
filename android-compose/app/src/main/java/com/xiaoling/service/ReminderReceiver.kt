package com.xiaoling.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * 到点提醒:AlarmManager 触发本接收器 → 语音播报 + 高优先级通知(超大字)。
 * 纯语音闭环:老人不用看屏幕也知道该做什么。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val content = intent.getStringExtra("content") ?: "该做的事"
        val say = "到时间了,该${content}啦。"
        notify(ctx.applicationContext, content)
        speak(ctx.applicationContext, say)
    }

    private fun notify(ctx: Context, content: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val ch = "xiaoling_remind"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "小灵提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ 小灵提醒您")
            .setContentText("该$content 啦")
            .setStyle(NotificationCompat.BigTextStyle().bigText("该$content 啦"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { nm.notify(content.hashCode(), n) } catch (e: SecurityException) {}
    }

    /** 用一次性 TTS 播报(接收器里没有常驻 TTS,临时建一个用完释放) */
    private fun speak(ctx: Context, text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "remind")
            }
        }
        // 8 秒后释放(足够念完)
        android.os.Handler(ctx.mainLooper).postDelayed({ tts?.shutdown() }, 8000)
    }
}
