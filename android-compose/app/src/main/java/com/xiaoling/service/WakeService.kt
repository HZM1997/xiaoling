package com.xiaoling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.xiaoling.MainActivity

/** App 是否在前台(前台时由 App 自己听,后台时由本服务听唤醒词,避免抢麦) */
object AppForeground { @Volatile var active = false }

/**
 * 常驻前台服务:退出 App 回到主屏后,后台持续听唤醒词「小灵」,听到就把 App 拉到前台并开始对话。
 * 唤醒依赖系统语音识别(需较新的 Google 语音服务;真正离线唤醒可在此换 Picovoice/Vosk 引擎)。
 * App 在前台时本服务让位(不占麦)。
 */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private var porcupine: com.xiaoling.core.PorcupineWakeEngine? = null
    @Volatile private var offlineOn = false

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
            running = true
            // 优先离线唤醒(Picovoice):省电、不联网、误唤醒低;不可用则回退系统识别循环
            porcupine = com.xiaoling.core.PorcupineWakeEngine(this)
            offlineOn = porcupine?.start { if (!AppForeground.active) wakeUp() } == true
            if (!offlineOn) loop()
        } catch (_: Throwable) {
            shutdownAndStop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (running) START_STICKY else START_NOT_STICKY

    /** 后台监听循环:App 在前台/无识别服务时让位重试;否则听一轮,命中「小灵」就唤起 */
    private fun loop() {
        if (!running) return
        val available = try { SpeechRecognizer.isRecognitionAvailable(this) } catch (_: Throwable) { false }
        if (AppForeground.active || !available) {
            main.postDelayed({ loop() }, 1500); return
        }
        try {
            releaseRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val hit = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.any { it.replace(" ", "").contains("小灵") } == true
                        if (hit) wakeUp() else again(500)
                    }
                    override fun onError(error: Int) = again(700)
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(intent)
        } catch (_: Throwable) {
            releaseRecognizer()
            again(1500)
        }
    }

    private fun again(delay: Long) { main.postDelayed({ loop() }, delay) }

    /** 唤醒:把 App 拉到前台并让其立即开听 */
    private fun wakeUp() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_WAKE, true)
        }
        try { startActivity(i) } catch (e: Exception) {}
        again(2500)   // 唤起后停一会儿,交给 App 前台自己听
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH, "小灵守护", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("小灵正在守护您")
            .setContentText("随时说「小灵」把我唤起,或点此打开")
            .setContentIntent(pi).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        releaseRecognizer()
        try { porcupine?.stop() } catch (_: Throwable) {}
        porcupine = null
        super.onDestroy()
    }

    private fun releaseRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
    }

    private fun shutdownAndStop() {
        running = false
        main.removeCallbacksAndMessages(null)
        releaseRecognizer()
        try { porcupine?.stop() } catch (_: Throwable) {}
        porcupine = null
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "xiaoling_wake"
        private const val NOTIF_ID = 1001
        const val EXTRA_WAKE = "wake_and_listen"

        fun start(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }
    }
}
