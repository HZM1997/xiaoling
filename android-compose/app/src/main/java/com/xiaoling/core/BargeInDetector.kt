package com.xiaoling.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Detects real speech over TTS so the legacy voice path can support hands-free barge-in. */
class BargeInDetector(context: Context, private val onSpeech: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running || ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min * 2, FRAME_BYTES * 6),
            )
        } catch (_: Throwable) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        recorder = record
        val session = record.audioSessionId
        try { if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(session)?.apply { enabled = true } } catch (_: Throwable) {}
        try { if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(session)?.apply { enabled = true } } catch (_: Throwable) {}
        try { if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(session)?.apply { enabled = true } } catch (_: Throwable) {}
        val turn = generation.incrementAndGet()
        running = true
        try {
            record.startRecording()
            worker = Thread({ detectLoop(turn) }, "xiaoling-barge-in").apply { start() }
        } catch (_: Throwable) {
            release()
        }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        running = false
        release()
    }

    fun destroy() = stop()

    private fun detectLoop(turn: Long) {
        val frame = ByteArray(FRAME_BYTES)
        var frames = 0
        var loudFrames = 0
        var baseline = 70.0
        while (running && turn == generation.get()) {
            val count = try { recorder?.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) ?: -1 } catch (_: Throwable) { -1 }
            if (count <= 0) break
            val rms = rms(frame, count)
            frames++
            // Let acoustic echo cancellation settle, then track the residual speaker level.
            if (frames <= CALIBRATION_FRAMES) {
                baseline = baseline * 0.82 + rms.coerceAtLeast(25.0) * 0.18
                continue
            }
            if (rms < baseline * 1.35) baseline = baseline * 0.97 + rms.coerceAtLeast(25.0) * 0.03
            val threshold = maxOf(MIN_SPEECH_RMS, baseline * 1.62)
            loudFrames = if (rms >= threshold) loudFrames + 1 else (loudFrames - 1).coerceAtLeast(0)
            if (loudFrames >= REQUIRED_LOUD_FRAMES) {
                running = false
                main.post(onSpeech)
                break
            }
        }
        if (turn == generation.get()) release()
    }

    @Synchronized
    private fun release() {
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { echoCanceler?.release() } catch (_: Throwable) {}
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { gainControl?.release() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        worker = null
    }

    private fun rms(bytes: ByteArray, count: Int): Double {
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

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 640
        const val CALIBRATION_FRAMES = 24
        const val REQUIRED_LOUD_FRAMES = 3
        const val MIN_SPEECH_RMS = 105.0
    }
}
