package com.xiaoling.core

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 亲情语音留言:张嘴录一段 → 说"发给我儿子" → 自动匹配联系人发送。
 * 录音存到 App 私有目录,发送时用系统分享(不用老人手动选联系人由上层匹配)。
 */
object VoiceMemo {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var current: File? = null

    val lastFile: File? get() = current

    fun startRecord(ctx: Context): Boolean {
        stopRecord()
        return try {
            val f = File(ctx.filesDir, "memo_" + (System.currentTimeMillis() % 1000000) + ".m4a")
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
                    else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; current = f
            true
        } catch (e: Exception) { false }
    }

    fun stopRecord(): File? {
        return try {
            recorder?.stop(); recorder?.release(); recorder = null; current
        } catch (e: Exception) { recorder?.release(); recorder = null; null }
    }

    fun playback(ctx: Context, onDone: () -> Unit = {}) {
        val f = current ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { onDone() }
                prepare(); start()
            }
        } catch (e: Exception) { onDone() }
    }

    fun release() {
        try { recorder?.release() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        recorder = null; player = null
    }
}
