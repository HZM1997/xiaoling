package com.xiaoling.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper

/**
 * A small haptic vocabulary shared by phones and watches. It is best-effort:
 * devices without a vibrator, and user-disabled feedback, remain fully usable.
 */
object TactileFeedback {
    enum class Signal {
        Touch,
        VoiceListening,
        Released,
        Cancelled,
        VoiceRecognized,
        ActionConfirmed,
        BeautyAdjusted,
        StyleApplied,
        ReferenceAnalyzing,
        ReferenceApplied,
        CameraFocused,
        PhotoCountdown,
        PhotoCaptured,
        Warning,
        Emergency,
    }

    @Volatile private var lastAt = 0L
    @Volatile private var lastPriority = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingWatchTouch: Runnable? = null

    fun onWatchTouch(context: Context, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isWatch(context) && Settings.hapticsEnabled(context)) {
            // A semantic event (listen/filter/confirm) normally follows a UI
            // touch. Delay the generic tick so it does not mask that richer cue.
            pendingWatchTouch?.let(mainHandler::removeCallbacks)
            val task = Runnable {
                pendingWatchTouch = null
                emitInternal(context.applicationContext, Signal.Touch, cancelPendingTouch = false)
            }
            pendingWatchTouch = task
            mainHandler.postDelayed(task, WATCH_TOUCH_DELAY_MS)
        }
    }

    fun emit(context: Context, signal: Signal) = emitInternal(context.applicationContext, signal, cancelPendingTouch = true)

    private fun emitInternal(context: Context, signal: Signal, cancelPendingTouch: Boolean) {
        if (!Settings.hapticsEnabled(context)) return
        if (cancelPendingTouch) {
            pendingWatchTouch?.let(mainHandler::removeCallbacks)
            pendingWatchTouch = null
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val priority = priority(signal)
        val minimumGap = minimumGap(signal)
        // A danger cue is never suppressed by an earlier touch. Higher-value
        // feedback also supersedes a pending low-value tick.
        if (signal != Signal.Warning && signal != Signal.Emergency &&
            now - lastAt < minimumGap && priority <= lastPriority) return
        lastAt = now
        lastPriority = priority

        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = when (signal) {
                    Signal.Touch -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    Signal.VoiceListening, Signal.ActionConfirmed ->
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    Signal.Released, Signal.Cancelled, Signal.CameraFocused,
                    Signal.ReferenceAnalyzing, Signal.PhotoCountdown ->
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    Signal.VoiceRecognized, Signal.BeautyAdjusted, Signal.StyleApplied,
                    Signal.ReferenceApplied ->
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    Signal.PhotoCaptured, Signal.Warning, Signal.Emergency ->
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                }
                vibrator.vibrate(effect)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val (timings, baseAmplitudes) = waveform(signal)
                val amplitudes = if (isWatch(context)) {
                    baseAmplitudes.map { if (it == 0) 0 else (it * 0.82f).toInt().coerceAtLeast(45) }.toIntArray()
                } else baseAmplitudes
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(legacyDuration(signal))
            }
        } catch (_: Throwable) {
            // Haptics are feedback only and must never interrupt voice control.
        }
    }

    private fun isWatch(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private fun priority(signal: Signal): Int = when (signal) {
        Signal.Emergency -> 5
        Signal.Warning, Signal.PhotoCaptured -> 4
        Signal.VoiceRecognized, Signal.ActionConfirmed, Signal.BeautyAdjusted,
        Signal.StyleApplied, Signal.ReferenceApplied -> 3
        Signal.VoiceListening, Signal.Cancelled, Signal.ReferenceAnalyzing,
        Signal.CameraFocused, Signal.PhotoCountdown -> 2
        Signal.Touch, Signal.Released -> 1
    }

    private fun minimumGap(signal: Signal): Long = when (signal) {
        Signal.Emergency, Signal.Warning -> 0L
        Signal.VoiceRecognized -> 240L // Partial ASR results must not vibrate repeatedly.
        Signal.ActionConfirmed, Signal.BeautyAdjusted, Signal.StyleApplied,
        Signal.ReferenceApplied, Signal.PhotoCaptured -> 100L
        Signal.VoiceListening, Signal.Cancelled, Signal.ReferenceAnalyzing,
        Signal.CameraFocused, Signal.PhotoCountdown -> 70L
        Signal.Touch, Signal.Released -> 55L
    }

    private fun waveform(signal: Signal): Pair<LongArray, IntArray> = when (signal) {
        Signal.Touch -> longArrayOf(0, 12) to intArrayOf(0, 70)
        Signal.VoiceListening -> longArrayOf(0, 20) to intArrayOf(0, 115)
        Signal.Released -> longArrayOf(0, 14) to intArrayOf(0, 75)
        Signal.Cancelled -> longArrayOf(0, 12, 30, 12) to intArrayOf(0, 65, 0, 48)
        Signal.VoiceRecognized -> longArrayOf(0, 14, 28, 20) to intArrayOf(0, 95, 0, 135)
        Signal.ActionConfirmed -> longArrayOf(0, 24) to intArrayOf(0, 145)
        Signal.BeautyAdjusted -> longArrayOf(0, 12, 26, 22) to intArrayOf(0, 78, 0, 138)
        Signal.StyleApplied -> longArrayOf(0, 22, 34, 18) to intArrayOf(0, 142, 0, 92)
        Signal.ReferenceAnalyzing -> longArrayOf(0, 10, 34, 12, 34, 16) to intArrayOf(0, 55, 0, 72, 0, 92)
        Signal.ReferenceApplied -> longArrayOf(0, 16, 28, 28) to intArrayOf(0, 105, 0, 155)
        Signal.CameraFocused -> longArrayOf(0, 10, 22, 10) to intArrayOf(0, 75, 0, 75)
        Signal.PhotoCountdown -> longArrayOf(0, 16) to intArrayOf(0, 105)
        Signal.PhotoCaptured -> longArrayOf(0, 46) to intArrayOf(0, 205)
        Signal.Warning -> longArrayOf(0, 75, 50, 135) to intArrayOf(0, 170, 0, 220)
        Signal.Emergency -> longArrayOf(0, 120, 45, 170, 45, 220) to intArrayOf(0, 220, 0, 245, 0, 255)
    }

    private fun legacyDuration(signal: Signal): Long = when (signal) {
        Signal.Emergency -> 420L
        Signal.Warning -> 220L
        Signal.PhotoCaptured -> 55L
        Signal.VoiceRecognized, Signal.ActionConfirmed, Signal.BeautyAdjusted,
        Signal.StyleApplied, Signal.ReferenceApplied -> 36L
        else -> 20L
    }

    private fun vibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private const val WATCH_TOUCH_DELAY_MS = 85L
}
