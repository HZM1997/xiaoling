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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
        fun onInputSpeechStarted(latencyMs: Long)
        fun onInputTranscript(text: String, final: Boolean)
        fun onOutputStarted()
        /** A real downstream audio packet arrived. Used to distinguish a
         * healthy long answer from a stalled upstream response. */
        fun onOutputAudioActivity()
        fun onOutputVisual(open: Float, wide: Float, round: Float, emphasis: Boolean)
        fun onOutputTranscript(text: String, final: Boolean)
        fun onOutputDone(text: String)
        fun onAction(action: JSONObject)
        fun onDelegationStarted(task: String)
        fun onDelegationCompleted(text: String)
        fun onBackchannel(text: String)
        fun onWeakNetwork()
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    // Preserve complete sentences during network bursts and short candidate
    // barge-ins. Confirmed speech still flushes this queue immediately.
    private val playbackQueue = ArrayBlockingQueue<ByteArray>(300)
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
    @Volatile private var playbackWriting = false
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
    @Volatile private var inputSpeechCandidate = false
    @Volatile private var inputInterruptConfirmed = false
    @Volatile private var localSpeechActive = false
    @Volatile private var interruptionCandidate = false
    @Volatile private var interruptionCandidateAtMs = 0L
    @Volatile private var discardResponseAudio = false
    @Volatile private var outputDonePending = false
    @Volatile private var pendingOutputText = ""
    @Volatile private var outputPlaybackStartedAtMs = 0L
    @Volatile private var outputLevelSmoothed = 0f
    @Volatile private var outputWideSmoothed = 0f
    @Volatile private var outputRoundSmoothed = 0f
    private var previousVisualLevel = 0f
    private var lastEmphasisAtMs = 0L
    private var lastOutputLevelAtMs = 0L
    private var lastOutputActivityAtMs = 0L
    private var weakNetworkReported = false
    private val candidateGeneration = AtomicLong(0)

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
        // Pause first. If no new speech is detected, release resumes the queued answer.
        pausePlaybackTrack()
    }

    fun endManualInterruption() {
        manualHold = false
        if (!interruptionCandidate) resumePlaybackTrack()
        if (playbackQueue.isNotEmpty()) {
            outputPlaying = true
            post { listener.onOutputStarted() }
        }
    }

    fun updateContext(extra: JSONObject? = null) {
        if (!isConnected) return
        val context = JSONObject().put("device", DeviceContext.toJson(app))
        extra?.keys()?.forEach { key -> context.put(key, extra.opt(key)) }
        socket?.send(
            JSONObject()
                .put("type", "session.context")
                .put("context", context)
                .toString()
        )
    }

    /** Send a local camera observation or other completed client-side result
     * into the existing realtime conversation without recreating the socket. */
    fun sendConversationText(text: String): Boolean {
        val value = text.trim().take(2_000)
        if (!isConnected || value.isBlank()) return false
        return socket?.send(
            JSONObject().put("type", "conversation.text").put("text", value).toString()
        ) == true
    }

    fun cancelResponse() {
        candidateGeneration.incrementAndGet()
        interruptionCandidate = false
        discardResponseAudio = true
        clearPlayback()
        socket?.send(JSONObject().put("type", "response.cancel").toString())
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
        inputSpeechCandidate = false
        inputInterruptConfirmed = false
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
            val audioRecord = createAudioRecord(maxOf(recordMin * 2, FRAME_BYTES * 4))
            val audioTrack = createAudioTrack(playMin)
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

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord {
        var last: AudioRecord? = null
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )) {
            val candidate = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (_: Throwable) {
                continue
            }
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                last?.release()
                return candidate
            }
            last?.release()
            last = candidate
        }
        return requireNotNull(last)
    }

    private fun recordLoop(turn: Long) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        val frame = ByteArray(FRAME_BYTES)
        var loudFrames = 0
        var quietFrames = 0
        var noiseFloor = 35.0
        while (running && turn == generation.get()) {
            val count = try { recorder?.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) ?: -1 } catch (_: Throwable) { -1 }
            if (count <= 0) break
            val rms = pcmRms(frame, count)
            // Keep a slow noise/echo baseline. The previous implementation
            // measured only silence, so a steady TTS echo looked like speech
            // and cancelled the answer after two frames.
            val echoWarmup = outputPlaying && !manualHold &&
                SystemClock.elapsedRealtime() - outputPlaybackStartedAtMs < ECHO_WARMUP_MS
            val baselineLimit = if (outputPlaying) 1.45 else 2.2
            if (rms < noiseFloor * baselineLimit || outputPlaying || responseInProgress) {
                // A slow baseline follows speaker echo without following a
                // real voice burst, which keeps the interrupt threshold stable.
                val alpha = if (echoWarmup) 0.24 else if (outputPlaying) 0.03 else if (responseInProgress) 0.015 else 0.01
                noiseFloor = noiseFloor * (1.0 - alpha) + rms.coerceAtLeast(20.0) * alpha
            }
            // A response that has not emitted audio yet must still be
            // interruptible, but it should use the normal speech threshold.
            // Only actual playback or a manual hold uses the stricter echo
            // rejection threshold.
            val interruptWindow = outputPlaying || manualHold
            val speechThreshold = if (interruptWindow) {
                maxOf(INTERRUPT_MIN_SPEECH_RMS, noiseFloor * 1.7, noiseFloor + INTERRUPT_MIN_RISE_RMS)
            } else {
                maxOf(LOCAL_MIN_SPEECH_RMS, noiseFloor * 2.15)
            }
            if (echoWarmup) {
                // Calibrate against the first loudspeaker frames before
                // allowing barge-in. This removes the common mid-sentence
                // self-cancel while adding at most a tiny one-off delay.
                loudFrames = 0
                quietFrames++
            } else if (rms >= speechThreshold) {
                loudFrames++
                quietFrames = 0
            } else {
                quietFrames++
                if (quietFrames >= 10) {
                    loudFrames = 0
                }
            }
            val strongSpeech = rms >= speechThreshold * 1.55
            // Require a short stable run even for a loud frame. This keeps a
            // speaker echo, notification click, or key tone from cancelling TTS.
            val requiredFrames = when {
                manualHold && strongSpeech -> 2
                interruptWindow && strongSpeech -> 2
                interruptWindow -> 4
                else -> REQUIRED_INTERRUPT_FRAMES
            }
            if (loudFrames >= requiredFrames && !localSpeechActive) {
                localSpeechActive = true
                val confirmsBargeIn = outputPlaying || responseInProgress || manualHold
                if (confirmsBargeIn) {
                    beginInterruptionCandidate(rms, speechThreshold)
                }
                if (!confirmsBargeIn) {
                    socket?.send(JSONObject().put("type", "input.speech_candidate").toString())
                    post { listener.onInputSpeechStarted(-1L) }
                }
            }
            if (localSpeechActive && quietFrames >= 12) {
                localSpeechActive = false
                socket?.send(JSONObject().put("type", "input.speech_stopped").put("source", "client_vad").toString())
                scheduleCandidateResume()
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
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        while (running && turn == generation.get()) {
            if (manualHold) {
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            val bytes = try { playbackQueue.poll(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            if (bytes == null) {
                if (!responseInProgress && !playbackWriting && playbackQueue.isEmpty()) outputPlaying = false
                maybeDeliverOutputDone()
                continue
            }
            outputPlaying = true
            playbackWriting = true
            val shape = pcmMouthShape(bytes, bytes.size)
            val targetLevel = shape.open
            outputLevelSmoothed = outputLevelSmoothed * 0.34f + targetLevel * 0.66f
            outputWideSmoothed = outputWideSmoothed * 0.42f + shape.wide * 0.58f
            outputRoundSmoothed = outputRoundSmoothed * 0.42f + shape.round * 0.58f
            val levelNow = SystemClock.elapsedRealtime()
            if (levelNow - lastOutputLevelAtMs >= OUTPUT_LEVEL_INTERVAL_MS) {
                lastOutputLevelAtMs = levelNow
                val emphasis = outputLevelSmoothed >= 0.44f &&
                    outputLevelSmoothed - previousVisualLevel >= 0.13f &&
                    levelNow - lastEmphasisAtMs >= EMPHASIS_COOLDOWN_MS
                if (emphasis) lastEmphasisAtMs = levelNow
                previousVisualLevel = outputLevelSmoothed
                val open = outputLevelSmoothed
                val wide = outputWideSmoothed
                val round = outputRoundSmoothed
                post { listener.onOutputVisual(open, wide, round, emphasis) }
            }
            var offset = 0
            var recoveries = 0
            try {
                while (offset < bytes.size && running && turn == generation.get()) {
                    // A candidate barge-in pauses AudioTrack before ASR confirms
                    // real speech. A blocking write can then return zero or an
                    // invalid-operation code on MIUI. That is a normal pause,
                    // not a fatal realtime-session error.
                    if (manualHold || interruptionCandidate) {
                        try { Thread.sleep(12) } catch (_: InterruptedException) {}
                        continue
                    }
                    val currentTrack = track
                    if (currentTrack == null) {
                        if (!recoverPlaybackTrack()) throw IllegalStateException("AudioTrack unavailable")
                        continue
                    }
                    val written = try {
                        currentTrack.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    } catch (error: Throwable) {
                        if (manualHold || interruptionCandidate) continue
                        if (recoveries++ < MAX_AUDIO_TRACK_RECOVERIES && recoverPlaybackTrack()) {
                            Log.w(TAG, "AudioTrack recovered after write exception", error)
                            continue
                        }
                        throw error
                    }
                    when {
                        written > 0 -> offset += written
                        manualHold || interruptionCandidate -> Unit
                        recoveries++ < MAX_AUDIO_TRACK_RECOVERIES && recoverPlaybackTrack() ->
                            Log.w(TAG, "AudioTrack recovered after write=$written")
                        else -> throw IllegalStateException("AudioTrack write=$written")
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "playback write failed at $offset/${bytes.size}", error)
                socket?.cancel()
                fail(turn, "语音播放器已自动重启", retryable = true)
                return
            }
            playbackWriting = false
            if (playbackQueue.isEmpty() && !responseInProgress) {
                outputPlaying = false
                maybeDeliverOutputDone()
            }
        }
    }

    private fun handleMessage(raw: String) {
        val event = try { JSONObject(raw) } catch (_: Exception) { return }
        when (event.optString("type")) {
            "session.ready" -> post { listener.onConnected(event.optString("model", "qwen-realtime")) }
            "input.speech_started" -> {
                val source = event.optString("source")
                // Local VAD already notified the UI and stopped playback. The
                // server echo of that event must not produce a second state
                // transition; server-VAD events remain informational only
                // while audio is playing.
                if (source == "client_vad") return
                inputText = StringBuilder()
                inputSpeechCandidate = true
                inputInterruptConfirmed = false
                // Server VAD can hear the phone speaker through a weak AEC.
                // Do not stop output on this event alone; wait for local VAD or
                // a real transcript before confirming an interruption.
                if (!outputPlaying && !responseInProgress && !manualHold) {
                    post { listener.onInputSpeechStarted(-1L) }
                }
            }
            "input.transcript.delta" -> {
                val delta = event.optString("text")
                if (delta.isNotBlank()) {
                    inputText.append(delta)
                    val text = inputText.toString()
                    // Upstream ASR can transcribe speaker echo on entry-level
                    // phones. A transcript alone is never enough to stop the
                    // answer; only the local microphone VAD (or a held mic)
                    // may confirm a barge-in.
                    val echo = isLikelyPlaybackEcho(text)
                    if (inputSpeechCandidate && (localSpeechActive || interruptionCandidate) &&
                        !inputInterruptConfirmed && text.count { !it.isWhitespace() } >= 2 && !echo) {
                        confirmInterruption()
                    }
                    if (!echo) post { listener.onInputTranscript(text, false) }
                }
            }
            "input.transcript.done" -> {
                val text = event.optString("text").ifBlank { inputText.toString() }
                inputText = StringBuilder()
                if (text.isNotBlank()) {
                    val echo = isLikelyPlaybackEcho(text)
                    if (inputSpeechCandidate && (localSpeechActive || interruptionCandidate) &&
                        !inputInterruptConfirmed && !echo) {
                        confirmInterruption()
                    }
                    inputSpeechCandidate = false
                    if (echo) {
                        Log.i(TAG, "suppressed playback echo transcript")
                        resumeCandidateOutput()
                    } else {
                        post { listener.onInputTranscript(text, true) }
                    }
                }
            }
            "output.started" -> {
                // The server has accepted a fresh turn. Audio belonging to a
                // cancelled turn is ignored until this point.
                discardResponseAudio = false
                candidateGeneration.incrementAndGet()
                interruptionCandidate = false
                interruptionCandidateAtMs = 0L
                responseInProgress = true
                outputDonePending = false
                pendingOutputText = ""
                outputPlaybackStartedAtMs = 0L
                outputText = StringBuilder()
                post { listener.onOutputStarted() }
            }
            "output.audio.delta" -> {
                val bytes = try { Base64.decode(event.optString("audio"), Base64.DEFAULT) } catch (_: Exception) { null }
                if (!discardResponseAudio && bytes != null && bytes.isNotEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    if (!outputPlaying) outputPlaybackStartedAtMs = now
                    outputPlaying = true
                    // A larger queue normally absorbs upstream bursts. If it
                    // ever fills, wait briefly instead of deleting the middle
                    // of a sentence.
                    if (!playbackQueue.offer(bytes)) {
                        val queued = try { playbackQueue.offer(bytes, 80, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
                        if (!queued) {
                            Log.w(TAG, "playback queue full; preserving queued sentence")
                            if (!weakNetworkReported) {
                                weakNetworkReported = true
                                post { listener.onWeakNetwork() }
                            }
                        }
                    }
                    if (now - lastOutputActivityAtMs >= OUTPUT_ACTIVITY_INTERVAL_MS) {
                        lastOutputActivityAtMs = now
                        post { listener.onOutputAudioActivity() }
                    }
                }
            }
            "output.transcript.delta" -> {
                val delta = event.optString("text")
                if (!discardResponseAudio && delta.isNotBlank()) {
                    outputText.append(delta)
                    val text = outputText.toString()
                    post { listener.onOutputTranscript(text, false) }
                }
            }
            "output.transcript.done" -> {
                val text = event.optString("text").ifBlank { outputText.toString() }
                outputText = StringBuilder(text)
                if (!discardResponseAudio && text.isNotBlank()) post { listener.onOutputTranscript(text, true) }
            }
            "output.done" -> {
                responseInProgress = false
                pendingOutputText = if (discardResponseAudio) "" else
                    event.optString("text").ifBlank { outputText.toString() }
                outputDonePending = true
                if (playbackQueue.isEmpty() && !playbackWriting) outputPlaying = false
                maybeDeliverOutputDone()
            }
            "tool.action" -> event.optJSONObject("action")?.let { action -> post { listener.onAction(action) } }
            "delegation.started" -> post { listener.onDelegationStarted(event.optString("task")) }
            "delegation.completed" -> post { listener.onDelegationCompleted(event.optString("text")) }
            "backchannel" -> {
                val text = event.optString("text")
                if (text.isNotBlank()) post { listener.onBackchannel(text) }
            }
            "error" -> {
                val message = event.optString("message").ifBlank { event.optString("code", "实时语音服务异常") }
                fail(generation.get(), message, retryable = true)
            }
        }
    }

    private fun clearPlayback() {
        candidateGeneration.incrementAndGet()
        interruptionCandidate = false
        interruptionCandidateAtMs = 0L
        playbackQueue.clear()
        outputPlaying = false
        playbackWriting = false
        outputPlaybackStartedAtMs = 0L
        outputLevelSmoothed = 0f
        outputWideSmoothed = 0f
        outputRoundSmoothed = 0f
        previousVisualLevel = 0f
        lastOutputActivityAtMs = 0L
        post { listener.onOutputVisual(0f, 0f, 0f, false) }
        responseInProgress = false
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Throwable) {}
    }

    private fun createAudioTrack(minBufferBytes: Int): AudioTrack {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(maxOf(minBufferBytes * 2, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    @Synchronized
    private fun recoverPlaybackTrack(): Boolean {
        if (!running) return false
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val replacement = try { createAudioTrack(minBuffer) } catch (_: Throwable) { return false }
        if (replacement.state != AudioTrack.STATE_INITIALIZED) {
            replacement.release()
            return false
        }
        val previous = track
        track = replacement
        try {
            if (!manualHold && !interruptionCandidate) replacement.play()
        } catch (_: Throwable) {
            track = previous
            replacement.release()
            return false
        }
        try { previous?.pause(); previous?.flush(); previous?.release() } catch (_: Throwable) {}
        return true
    }

    private fun maybeDeliverOutputDone() {
        if (!outputDonePending || outputPlaying || playbackQueue.isNotEmpty()) return
        outputDonePending = false
        val text = pendingOutputText
        pendingOutputText = ""
        outputLevelSmoothed = 0f
        outputWideSmoothed = 0f
        outputRoundSmoothed = 0f
        previousVisualLevel = 0f
        lastOutputActivityAtMs = 0L
        post { listener.onOutputVisual(0f, 0f, 0f, false) }
        post { listener.onOutputDone(text) }
    }

    private fun beginInterruptionCandidate(rms: Double, threshold: Double) {
        if (interruptionCandidate || inputInterruptConfirmed) return
        interruptionCandidate = true
        interruptionCandidateAtMs = SystemClock.elapsedRealtime()
        inputSpeechCandidate = true
        val token = candidateGeneration.incrementAndGet()
        pausePlaybackTrack()
        post { listener.onOutputVisual(0f, 0f, 0f, false) }
        socket?.send(JSONObject().put("type", "input.speech_candidate").toString())
        scheduleCandidateResume()
        Log.i(TAG, "barge-in candidate token=$token ratio=${"%.2f".format(rms / threshold)}")
    }

    private fun confirmInterruption() {
        if (inputInterruptConfirmed) return
        candidateGeneration.incrementAndGet()
        interruptionCandidate = false
        inputInterruptConfirmed = true
        discardResponseAudio = true
        val latencyMs = (SystemClock.elapsedRealtime() - interruptionCandidateAtMs).coerceAtLeast(0L)
        clearPlayback()
        socket?.send(JSONObject().put("type", "input.speech_started").put("source", "client_vad_confirmed").toString())
        socket?.send(JSONObject().put("type", "response.cancel").toString())
        if (!localSpeechActive) {
            socket?.send(JSONObject().put("type", "input.speech_stopped").put("source", "client_vad_confirmed").toString())
        }
        Log.i(TAG, "barge-in confirmed")
        post { listener.onInputSpeechStarted(latencyMs) }
    }

    private fun scheduleCandidateResume(delayMs: Long = CANDIDATE_RESUME_MS) {
        if (!interruptionCandidate || inputInterruptConfirmed) return
        val token = candidateGeneration.get()
        main.postDelayed({
            if (token == candidateGeneration.get() && interruptionCandidate && !inputInterruptConfirmed) {
                if (localSpeechActive) scheduleCandidateResume(CANDIDATE_ACTIVE_RECHECK_MS)
                else resumeCandidateOutput()
            }
        }, delayMs)
    }

    private fun resumeCandidateOutput() {
        if (!interruptionCandidate || inputInterruptConfirmed) return
        candidateGeneration.incrementAndGet()
        interruptionCandidate = false
        interruptionCandidateAtMs = 0L
        inputSpeechCandidate = false
        resumePlaybackTrack()
        Log.i(TAG, "barge-in candidate rejected; playback resumed")
    }

    private fun isLikelyPlaybackEcho(text: String): Boolean {
        if (!outputPlaying && !responseInProgress && !interruptionCandidate) return false
        val heard = normalizeForEcho(text)
        if (heard.length < 2) return false
        val spoken = normalizeForEcho(outputText.toString())
        if (spoken.length < heard.length) return false
        return spoken.contains(heard) || (heard.length >= 6 && spoken.takeLast(80).contains(heard.take(6)))
    }

    private fun normalizeForEcho(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    @Synchronized
    private fun pausePlaybackTrack() {
        try { track?.pause() } catch (_: Throwable) {}
    }

    @Synchronized
    private fun resumePlaybackTrack() {
        if (manualHold || interruptionCandidate) return
        try { track?.play() } catch (_: Throwable) {}
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
        inputSpeechCandidate = false
        inputInterruptConfirmed = false
        interruptionCandidate = false
        interruptionCandidateAtMs = 0L
        discardResponseAudio = false
        if (shouldNotify) post { listener.onDisconnected(message, retryable) }
    }

    @Synchronized
    private fun releaseAudio() {
        running = false
        playbackQueue.clear()
        outputPlaying = false
        playbackWriting = false
        outputPlaybackStartedAtMs = 0L
        outputLevelSmoothed = 0f
        outputWideSmoothed = 0f
        outputRoundSmoothed = 0f
        previousVisualLevel = 0f
        outputDonePending = false
        pendingOutputText = ""
        localSpeechActive = false
        interruptionCandidate = false
        interruptionCandidateAtMs = 0L
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

    private data class MouthShape(val open: Float, val wide: Float, val round: Float)

    private fun pcmMouthShape(bytes: ByteArray, count: Int): MouthShape {
        var sum = 0.0
        var crossings = 0
        var samples = 0
        var previous = 0
        var index = 0
        while (index + 1 < count) {
            val sample = ((bytes[index].toInt() and 0xff) or
                (bytes[index + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            if (samples > 0 && (sample >= 0) != (previous >= 0)) crossings++
            previous = sample
            samples++
            index += 2
        }
        if (samples < 2) return MouthShape(0f, 0f, 0f)
        val rms = sqrt(sum / samples)
        val open = (rms / 5200.0).toFloat().coerceIn(0f, 1f)
        val zeroCrossing = crossings.toFloat() / (samples - 1).toFloat()
        val brightness = ((zeroCrossing - 0.035f) / 0.24f).coerceIn(0f, 1f)
        val wide = (open * (0.12f + brightness * 0.72f)).coerceIn(0f, 0.82f)
        val round = (open * (0.1f + (1f - brightness) * 0.64f)).coerceIn(0f, 0.78f)
        return MouthShape(open, wide, round)
    }

    private fun post(block: () -> Unit) { main.post(block) }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val FRAME_BYTES = 960 // 20 ms, PCM16 mono;每秒 50 次本地打断判断
        const val LOCAL_MIN_SPEECH_RMS = 90.0
        const val INTERRUPT_MIN_SPEECH_RMS = 120.0
        const val INTERRUPT_MIN_RISE_RMS = 60.0
        // 20 ms PCM frames: three consecutive frames keeps clicks out while
        // bringing barge-in onset below 100 ms after the echo settling window.
        const val REQUIRED_INTERRUPT_FRAMES = 3
        const val ECHO_WARMUP_MS = 120L
        const val CANDIDATE_RESUME_MS = 280L
        const val CANDIDATE_ACTIVE_RECHECK_MS = 100L
        const val OUTPUT_LEVEL_INTERVAL_MS = 40L
        const val OUTPUT_ACTIVITY_INTERVAL_MS = 750L
        const val EMPHASIS_COOLDOWN_MS = 420L
        const val MAX_WEBSOCKET_QUEUE_BYTES = 512L * 1024L
        const val MAX_AUDIO_TRACK_RECOVERIES = 2
        const val TAG = "XiaolingRealtime"
    }
}
