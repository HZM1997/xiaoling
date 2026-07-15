package com.xiaoling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xiaoling.MainActivity

/**
 * 常驻前台服务:让「退出 App 回到主屏后也能瞬间唤起」「离线/弱网下语音唤起」成为可能。
 * 唤醒引擎做成可插拔:
 *   - 默认:占位(仅常驻,不真正离线监听),点通知即唤起 App。
 *   - 生产:在 startWakeEngine() 接 Picovoice Porcupine(AccessKey)或 Vosk(离线模型),
 *           命中唤醒词「小灵小灵」→ wakeUp() 拉起 App 并直接开听。
 * 离线唤醒不依赖网络;唤醒后的复杂对话才需要网络(离线时走本地快通道/兜底)。
 */
class WakeService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 前台服务启动包一层:未授麦克风权限时在 API 34 上 startForeground(microphone) 会抛异常,避免崩溃
        try {
            startForeground(NOTIF_ID, buildNotification())
            startWakeEngine()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startWakeEngine() {
        // TODO 接离线唤醒引擎:
        // Picovoice: PorcupineManager.Builder().setKeyword("小灵小灵").setAccessKey(KEY)
        //   .setCallback { wakeUp() }.build(this).start()
        // Vosk: 加载离线模型,识别到唤醒词 → wakeUp()
        // 命中后调用 wakeUp()
    }

    /** 唤醒:拉起 App 到前台并请其立即开听 */
    private fun wakeUp() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_WAKE, true)
        }
        startActivity(i)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "小灵守护", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小灵正在守护您")
            .setContentText("随时对我说话,或点此打开")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "xiaoling_wake"
        private const val NOTIF_ID = 1001
        const val EXTRA_WAKE = "wake_and_listen"

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
