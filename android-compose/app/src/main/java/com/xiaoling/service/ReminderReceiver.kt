package com.xiaoling.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 到点提醒:AlarmManager 触发本接收器 → 语音播报 + 高优先级通知(超大字)。
 * 纯语音闭环:老人不用看屏幕也知道该做什么。
 * 用 goAsync() 撑住进程直到 TTS 念完,否则 App 若已被杀,onReceive 一返回进程就没了,语音会丢。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val content = intent.getStringExtra("content") ?: "该做的事"
        val say = "到时间了,该${content}啦。"
        notify(ctx.applicationContext, content)   // 通知同步弹出,最可靠的兜底
        val pending = goAsync()                    // 让系统在念完前别回收进程
        speak(ctx.applicationContext, say) { pending.finish() }
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

    /** 用一次性 TTS 播报,念完(或出错/超时)后回调 onDone 释放 goAsync */
    private fun speak(ctx: Context, text: String, onDone: () -> Unit) {
        val done = AtomicBoolean(false)
        var tts: TextToSpeech? = null
        val finish = {
            if (done.compareAndSet(false, true)) {
                try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
                onDone()
            }
        }
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { finish() }
                    @Deprecated("deprecated") override fun onError(id: String?) { finish() }
                    override fun onError(id: String?, code: Int) { finish() }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "remind")
            } else finish()   // 没有可用 TTS 引擎,直接结束(通知已兜底)
        }
        // 兜底:最多撑 9 秒,防 TTS 卡住不回调导致 ANR / goAsync 超时
        android.os.Handler(ctx.mainLooper).postDelayed({ finish() }, 9000)
    }
}
