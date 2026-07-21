package com.xiaoling.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.sqrt

/** 系统识别连续失败时使用的远场录音 + 云端 ASR 兜底。 */
class CloudAsrRecorder(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var recorder: AudioRecord? = null

    val isActive: Boolean get() = active

    fun start(
        onReady: () -> Unit,
        onSpeechStart: () -> Unit,
        onText: (String) -> Unit,
        onError: (Int) -> Unit
    ): Boolean {
        if (active || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return false
        active = true
        stopRequested = false
        cancelRequested = false
        Thread({ capture(onReady, onSpeechStart, onText, onError) }, "xiaoling-cloud-asr").start()
        return true
    }

    fun stopListening() { stopRequested = true }

    fun cancel() {
        cancelRequested = true
        stopRequested = true
        val current = recorder
        recorder = null
        try { current?.stop() } catch (_: Throwable) {}
        try { current?.release() } catch (_: Throwable) {}
    }

    private fun capture(
        onReady: () -> Unit,
        onSpeechStart: () -> Unit,
        onText: (String) -> Unit,
        onError: (Int) -> Unit
    ) {
        val sampleRate = 16000
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return fail(onError, SpeechRecognizer.ERROR_AUDIO)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBytes, sampleRate / 2)
            )
        } catch (_: Throwable) {
            return fail(onError, SpeechRecognizer.ERROR_AUDIO)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return fail(onError, SpeechRecognizer.ERROR_AUDIO)
        }
        recorder = record
        val agc = runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null
        }.getOrNull()
        val suppressor = runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
        }.getOrNull()
        try {
            record.startRecording()
            main.post { onReady() }
            val samples = ShortArray(1600)
            val audio = ByteArrayOutputStream(sampleRate * 2 * 12)
            val preRoll = ArrayDeque<ByteArray>()
            var preRollBytes = 0
            var noiseRms = 160.0
            var speechStarted = false
            var voiceChunks = 0
            var silenceMs = 0
            var elapsedMs = 0

            while (!cancelRequested && elapsedMs < 12000) {
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (stopRequested) break
                    return fail(onError, SpeechRecognizer.ERROR_AUDIO)
                }
                elapsedMs += count * 1000 / sampleRate
                var energy = 0.0
                for (index in 0 until count) {
                    val value = samples[index].toDouble()
                    energy += value * value
                }
                val rms = sqrt(energy / count)
                val threshold = max(220.0, noiseRms * 2.1)
                val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                    for (index in 0 until count) putShort(samples[index])
                }.array()

                if (!speechStarted) {
                    preRoll.addLast(bytes)
                    preRollBytes += bytes.size
                    while (preRollBytes > sampleRate && preRoll.isNotEmpty()) {
                        preRollBytes -= preRoll.removeFirst().size
                    }
                    if (rms > threshold) voiceChunks++ else {
                        voiceChunks = 0
                        noiseRms = noiseRms * 0.92 + rms * 0.08
                    }
                    if (voiceChunks >= 2) {
                        speechStarted = true
                        preRoll.forEach { audio.write(it) }
                        preRoll.clear()
                        main.post { onSpeechStart() }
                    }
                } else {
                    audio.write(bytes)
                    silenceMs = if (rms < threshold * 0.72) silenceMs + count * 1000 / sampleRate else 0
                    if (silenceMs >= 1000 && audio.size() >= sampleRate) break
                }
                if (stopRequested) break
            }

            if (cancelRequested) return
            if (!speechStarted || audio.size() < sampleRate / 2) {
                return fail(onError, SpeechRecognizer.ERROR_NO_MATCH)
            }
            val text = BrainClient.transcribeWav(ctx, wav(audio.toByteArray(), sampleRate))
            if (cancelRequested) return
            if (text.isNullOrBlank()) fail(onError, SpeechRecognizer.ERROR_SERVER)
            else main.post { onText(text) }
        } catch (_: Throwable) {
            if (!cancelRequested) fail(onError, SpeechRecognizer.ERROR_AUDIO)
        } finally {
            recorder = null
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
            try { agc?.release() } catch (_: Throwable) {}
            try { suppressor?.release() } catch (_: Throwable) {}
            active = false
        }
    }

    private fun fail(onError: (Int) -> Unit, error: Int) {
        active = false
        if (!cancelRequested) main.post { onError(error) }
    }

    private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val output = ByteArrayOutputStream(pcm.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size + 36)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        output.write(header.array())
        output.write(pcm)
        return output.toByteArray()
    }
}
