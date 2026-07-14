package com.jingling

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 常驻前台服务:保活端侧唤醒词监听,让"小灵小灵"随时可唤醒(即使锁屏)。
 * 适老关键:老人不会去后台把它拉起来,必须常驻不被系统杀。
 * 生产:在此初始化 Picovoice Porcupine / 自训 KWS,命中回调里启动一轮对话。
 */
class WakeService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        startWakeWordEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // 被杀后自动重启,保证唤醒常在
    }

    private fun startWakeWordEngine() {
        // TODO: Porcupine.create(keyword="小灵小灵") { onDetected() }
        // onDetected() -> 发广播/绑定,触发 VoiceAgent.onWake()
    }

    private fun buildNotification(): Notification {
        val ch = "xiaoling_wake"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(ch, "小灵语音助手",
                    NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("小灵正在守护您")
            .setContentText("随时对我说：小灵小灵")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
