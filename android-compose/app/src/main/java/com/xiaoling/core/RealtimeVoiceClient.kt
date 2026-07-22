package com.xiaoling.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import com.xiaoling.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Realtime 全双工音频通道。模型密钥不在手机端；本类只连接小灵服务端 /realtime。
 * 24 kHz PCM16 单声道持续上行，回答音频流式下行；本地先行打断，服务端 VAD 再校准。
 */
class RealtimeVoiceClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onConnected(model: String)
        fun onDisconnected(message: String, retryable: Boolean)
        fun onInputSpeechStarted()
        fun onInputTranscript(text: String, final: Boolean)
        fun onOutputStarted()
        fun onOutputTranscript(text: String, final: Boolean)
        fun onOutputDone(text: String)
        fun onAction(action: JSONObject)
        fun onDelegationStarted(task: String)
        fun onDelegationCompleted(text: String)
        fun onWeakNetwork()
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(90)
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var connecting = false
    @Volatile private var connected = false
    @Volatile private var outputPlaying = false
    @Volatile private var responseInProgress = false
    @Volatile private var manualHold = false
    @Volatile private var stopping = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var inputText = StringBuilder()
    private var outputText = StringBuilder()
    private var weakNetworkReported = false

    val isConnected: Boolean get() = connected && running
    val canConnect: Boolean
        get() = Settings.brainUrl(app).isNotBlank() && NetworkStatus.isOnline(app) && hasPermission()

    fun start() {
        if (running || connecting || !canConnect) return
        stop(notify = false)
        val turn = generation.incrementAndGet()
        stopping = false
        connecting = true
        val request = try {
            Request.Builder().url(realtimeUrl()).apply {
                if (BuildConfig.REALTIME_CLIENT_TOKEN.isNotBlank()) {
                    header("X-Xiaoling-Token", BuildConfig.REALTIME_CLIENT_TOKEN)
                }
            }.build()
        } catch (_: IllegalArgumentException) {
            connecting = false
            post { listener.onDisconnected("AI 服务地址格式不正确", false) }
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (turn != generation.get() || stopping) {
                    connecting = false
                    webSocket.close(1000, "stale")
                    return
                }
                socket = webSocket
                connecting = false
                running = true
                connected = true
                weakNetworkReported = false
                val context = JSONObject()
                    .put("scene", "realtime_voice")
                    .put("device", DeviceContext.toJson(app))
                Profile.toJson(app)?.let { context.put("profile", it) }
                webSocket.send(
                    JSONObject()
                        .put("type", "session.start")
                        .put("user_id", Settings.userId(app))
                        .put("context", context)
                        .toString()
                )
                if (!startAudio(turn)) {
                    webSocket.close(1011, "audio unavailable")
                    fail(turn, "麦克风音频通道没有启动", retryable = true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (turn != generation.get() || !running) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(turn, reason.ifBlank { "实时会话已断开" }, retryable = code != 1000)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(turn, t.message ?: "实时语音连接失败", retryable = true)
            }
        })
    }

    fun beginManualInterruption() {
        manualHold = true
        clearPlayback()
    }

    fun endManualInterruption() {
        manualHold = false
        if (playbackQueue.isNotEmpty()) {
            outputPlaying = true
            post { listener.onOutputStarted() }
        }
    }

    fun updateContext() {
        if (!isConnected) return
        socket?.send(
            JSONObject()
                .put("type", "session.context")
                .put("context", JSONObject().put("device", DeviceContext.toJson(app)))
                .toString()
        )
    }

    fun stop(notify: Boolean = false) {
        generation.incrementAndGet()
        stopping = true
        val wasConnected = connected
        connected = false
        connecting = false
        running = false
        socket?.close(1000, "client stop")
        socket = null
        releaseAudio()
        if (notify && wasConnected) post { listener.onDisconnected("实时会话已停止", false) }
    }

    fun destroy() {
        stop(notify = false)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun realtimeUrl(): String {
        val base = Settings.brainUrl(app).trim().trimEnd('/')
        return when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/realtime"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/realtime"
            else -> "wss://$base/realtime"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio(turn: Long): Boolean {
        if (!hasPermission()) return false
        val recordMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val playMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (recordMin <= 0 || playMin <= 0) return false
        return try {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(recordMin * 2, FRAME_BYTES * 4),
            )
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(playMin * 2, SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                audioRecord.release()
                audioTrack.release()
                return false
            }
            recorder = audioRecord
            track = audioTrack
            val session = audioRecord.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(session)?.apply { enabled = true }
            app.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioRecord.startRecording()
            audioTrack.play()
            recordThread = Thread({ recordLoop(turn) }, "xiaoling-realtime-record").apply { start() }
            playThread = Thread({ playbackLoop(turn) }, "xiaoling-realtime-play").apply { start() }
            true
        } catch (_: Throwable) {
            releaseAudio()
            false
        }
    }

    private fun recordLoop(turn: Long) {
        val frame = ByteArray(FRAME_BYTES)
        var loudFrames = 0
        var quietFrames = 0
        var interruptedThisUtterance = false
        while (running && turn == generation.get()) {
            val count = try { recorder?.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) ?: -1 } catch (_: Throwable) { -1 }
            if (count <= 0) break
            val rms = pcmRms(frame, count)
            if (rms >= LOCAL_SPEECH_RMS) {
                loudFrames++
                quietFrames = 0
            } else {
                quietFrames++
                if (quietFrames >= 10) {
                    loudFrames = 0
                    interruptedThisUtterance = false
                }
            }
            if ((outputPlaying || (manualHold && responseInProgress)) &&
                loudFrames >= 3 && !interruptedThisUtterance) {
                interruptedThisUtterance = true
                responseInProgress = false
                clearPlayback()
                socket?.send(JSONObject().put("type", "response.cancel").toString())
                post { listener.onInputSpeechStarted() }
            }
            val ws = socket ?: continue
            if (ws.queueSize() > MAX_WEBSOCKET_QUEUE_BYTES) {
                if (!weakNetworkReported) {
                    weakNetworkReported = true
                    post { listener.onWeakNetwork() }
                }
                continue
            }
            if (weakNetworkReported && ws.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES / 4) weakNetworkReported = false
            val audio = Base64.encodeToString(if (count == frame.size) frame else frame.copyOf(count), Base64.NO_WRAP)
            ws.send(JSONObject().put("type", "audio.append").put("audio", audio).toString())
        }
    }

    private fun playbackLoop(turn: Long) {
        while (running && turn == generation.get()) {
            if (manualHold) {
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            val bytes = try { playbackQueue.poll(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                ?: continue
            outputPlaying = true
            try { track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING) } catch (_: Throwable) {}
            if (playbackQueue.isEmpty()) outputPlaying = false
        }
    }

    private fun handleMessage(raw: String) {
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        when (event.optString("type")) {
            "session.ready" -> post { listener.onConnected(event.optString("model", "gpt-realtime")) }
            "input.speech_started" -> {
                inputText = StringBuilder()
                responseInProgress = false
                clearPlayback()
                post { listener.onInputSpeechStarted() }
            }
            "input.transcript.delta" -> {
                val delta = event.optString("text")
                if (delta.isNotBlank()) {
                    inputText.append(delta)
                    val text = inputText.toString()
                    post { listener.onInputTranscript(text, false) }
                }
            }
            "input.transcript.done" -> {
                val text = event.optString("text").ifBlank { inputText.toString() }
                inputText = StringBuilder()
                if (text.isNotBlank()) post { listener.onInputTranscript(text, true) }
            }
            "output.started" -> {
                responseInProgress = true
                outputText = StringBuilder()
                post { listener.onOutputStarted() }
            }
            "output.audio.delta" -> {
                val bytes = try { Base64.decode(event.optString("audio"), Base64.DEFAULT) } catch (_: Exception) { null }
                if (bytes != null && bytes.isNotEmpty()) {
                    outputPlaying = true
                    if (!playbackQueue.offer(bytes)) {
                        playbackQueue.poll()
                        playbackQueue.offer(bytes)
                    }
                }
            }
            "output.transcript.delta" -> {
                val delta = event.optString("text")
                if (delta.isNotBlank()) {
                    outputText.append(delta)
                    val text = outputText.toString()
                    post { listener.onOutputTranscript(text, false) }
                }
            }
            "output.transcript.done" -> {
                val text = event.optString("text").ifBlank { outputText.toString() }
                outputText = StringBuilder(text)
                if (text.isNotBlank()) post { listener.onOutputTranscript(text, true) }
            }
            "output.done" -> {
                responseInProgress = false
                outputPlaying = playbackQueue.isNotEmpty()
                val text = event.optString("text").ifBlank { outputText.toString() }
                post { listener.onOutputDone(text) }
            }
            "tool.action" -> event.optJSONObject("action")?.let { action -> post { listener.onAction(action) } }
            "delegation.started" -> post { listener.onDelegationStarted(event.optString("task")) }
            "delegation.completed" -> post { listener.onDelegationCompleted(event.optString("text")) }
            "error" -> {
                val message = event.optString("message").ifBlank { event.optString("code", "实时语音服务异常") }
                fail(generation.get(), message, retryable = true)
            }
        }
    }

    private fun clearPlayback() {
        playbackQueue.clear()
        outputPlaying = false
        responseInProgress = false
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Throwable) {}
    }

    private fun fail(turn: Long, message: String, retryable: Boolean) {
        if (turn != generation.get()) return
        generation.incrementAndGet()
        val shouldNotify = !stopping
        connected = false
        connecting = false
        running = false
        releaseAudio()
        socket = null
        if (shouldNotify) post { listener.onDisconnected(message, retryable) }
    }

    @Synchronized
    private fun releaseAudio() {
        running = false
        playbackQueue.clear()
        outputPlaying = false
        manualHold = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Throwable) {}
        try { echoCanceler?.release() } catch (_: Throwable) {}
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { gainControl?.release() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        recorder = null
        track = null
        app.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_NORMAL
    }

    private fun pcmRms(bytes: ByteArray, count: Int): Double {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < count) {
            val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            samples++
            index += 2
        }
        return if (samples == 0) 0.0 else sqrt(sum / samples)
    }

    private fun post(block: () -> Unit) { main.post(block) }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val FRAME_BYTES = 1_920 // 40 ms, PCM16 mono
        const val LOCAL_SPEECH_RMS = 420.0
        const val MAX_WEBSOCKET_QUEUE_BYTES = 512L * 1024L
    }
}
