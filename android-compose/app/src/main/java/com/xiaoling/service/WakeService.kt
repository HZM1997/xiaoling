package com.xiaoling.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.xiaoling.MainActivity

/** App 是否在前台(前台时由 App 自己听,后台时由本服务听唤醒词,避免抢麦) */
object AppForeground { @Volatile var active = false }

/**
 * 常驻前台服务:退出 App 回到主屏后,后台持续听唤醒词「小灵」,听到就把 App 拉到前台并开始对话。
 * Release 优先使用包内 Porcupine 中文关键词模型离线检测“小灵”;资源不可用时才回退系统语音识别。
 * App 在前台时本服务让位(不占麦)。
 */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizerAttempt = 0
    @Volatile private var running = false
    @Volatile private var wakeTriggered = false
    private var porcupine: com.xiaoling.core.PorcupineWakeEngine? = null
    @Volatile private var offlineOn = false

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
            running = true
        } catch (_: Throwable) {
            shutdownAndStop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
        if (intent?.action == ACTION_PAUSE || AppForeground.active) {
            pauseWakeEngine()
        } else {
            restartWakeEngine()
        }
        return START_STICKY
    }

    /** 前后台切换时先释放上一轮识别,避免守护服务和主页 ASR 同时占用麦克风。 */
    private fun pauseWakeEngine() {
        main.removeCallbacksAndMessages(null)
        releaseRecognizer()
        if (offlineOn) {
            try { porcupine?.stop() } catch (_: Throwable) {}
        }
        porcupine = null
        offlineOn = false
    }

    private fun restartWakeEngine() {
        pauseWakeEngine()
        if (!running || AppForeground.active) return
        // 优先离线唤醒(Picovoice):省电、不联网、误唤醒低;不可用则回退系统识别循环
        porcupine = com.xiaoling.core.PorcupineWakeEngine(this)
        offlineOn = porcupine?.start { if (!AppForeground.active) wakeUp() } == true
        if (!offlineOn) loop()
    }

    /** 后台监听循环:App 在前台/无识别服务时让位重试;否则听一轮,命中「小灵」就唤起 */
    private fun loop() {
        if (!running) return
        val available = try {
            recognitionServices().isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(this) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
        } catch (_: Throwable) { false }
        if (AppForeground.active || !available) {
            main.postDelayed({ loop() }, 1500); return
        }
        try {
            releaseRecognizer()
            recognizer = createRecognizer().also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val hit = containsWakeWord(results)
                        if (hit) wakeUp() else again(500)
                    }
                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_NETWORK ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) {
                            advanceRecognizer()
                        }
                        again(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500)
                    }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (partialResults != null && containsWakeWord(partialResults)) wakeUp()
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_BIASING_STRINGS,
                        arrayListOf("小灵", "小玲", "晓玲")
                    )
                }
            }
            recognizer?.startListening(intent)
        } catch (_: Throwable) {
            releaseRecognizer()
            again(1500)
        }
    }

    private fun again(delay: Long) {
        releaseRecognizer()
        main.postDelayed({ loop() }, delay)
    }

    /** 唤醒:把 App 拉到前台并让其立即开听 */
    private fun wakeUp() {
        if (wakeTriggered || AppForeground.active) return
        wakeTriggered = true
        // 先释放服务占用的麦克风,再让前台 ASR 接管,避免 ERROR_RECOGNIZER_BUSY。
        releaseRecognizer()
        if (offlineOn) {
            try { porcupine?.stop() } catch (_: Throwable) {}
            offlineOn = false
        }
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_WAKE, true)
        }
        try { startActivity(i) } catch (e: Exception) {}
        main.postDelayed({
            wakeTriggered = false
            if (!AppForeground.active) loop()
        }, 2500)
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
        val current = recognizer
        recognizer = null
        try { current?.destroy() } catch (_: Throwable) {}
    }

    private fun createRecognizer(): SpeechRecognizer {
        val services = recognitionServices()
        val standard = try { SpeechRecognizer.isRecognitionAvailable(this) } catch (_: Throwable) { false }
        val onDevice = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        } catch (_: Throwable) { false }
        val preferOnDevice = onDevice && !com.xiaoling.core.NetworkStatus.isOnline(this)
        val creators = buildList<() -> SpeechRecognizer> {
            if (preferOnDevice) add { SpeechRecognizer.createOnDeviceSpeechRecognizer(this@WakeService) }
            services.forEach { service ->
                add { SpeechRecognizer.createSpeechRecognizer(this@WakeService, service) }
            }
            if (standard) add { SpeechRecognizer.createSpeechRecognizer(this@WakeService) }
            if (onDevice && !preferOnDevice) {
                add { SpeechRecognizer.createOnDeviceSpeechRecognizer(this@WakeService) }
            }
        }
        if (creators.isEmpty()) throw IllegalStateException("No speech recognition service")
        recognizerAttempt = recognizerAttempt.mod(creators.size)
        return creators[recognizerAttempt].invoke()
    }

    private fun recognitionServices(): List<ComponentName> {
        val configured = try {
            Settings.Secure.getString(contentResolver, "voice_recognition_service")
                ?.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
        } catch (_: Throwable) {
            null
        }
        val discovered = try {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { info ->
                    info.serviceInfo?.let { ComponentName(it.packageName, it.name) }
                }
        } catch (_: Throwable) {
            emptyList()
        }
        val services = buildList {
            if (configured != null && configured in discovered) add(configured)
            addAll(discovered)
        }.distinct()
        return services
    }

    private fun advanceRecognizer() {
        val count = recognitionServices().size +
            (if (try { SpeechRecognizer.isRecognitionAvailable(this) } catch (_: Throwable) { false }) 1 else 0) +
            (if (try {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            } catch (_: Throwable) { false }) 1 else 0)
        if (count > 1) recognizerAttempt = (recognizerAttempt + 1) % count
    }

    private fun containsWakeWord(results: Bundle): Boolean {
        return results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.any { raw ->
                val text = raw.replace(Regex("[\\s,，。.!！?？]"), "")
                text.contains("小灵") || text.contains("小玲") || text.contains("晓玲")
            } == true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉任务后保留守护服务;系统回收后由 START_STICKY 恢复。
        if (running) again(300)
        super.onTaskRemoved(rootIntent)
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
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_RESUME)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        fun pause(ctx: Context) {
            val i = Intent(ctx, WakeService::class.java).setAction(ACTION_PAUSE)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }

        private const val ACTION_RESUME = "com.xiaoling.action.RESUME_WAKE"
        private const val ACTION_PAUSE = "com.xiaoling.action.PAUSE_WAKE"
    }
}
