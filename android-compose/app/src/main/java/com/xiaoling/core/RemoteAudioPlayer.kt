package com.xiaoling.core

import android.media.AudioAttributes
import android.media.MediaPlayer

/** 在 App 内直接播放家人推送的音频,避免弹出播放器或联系人选择界面。 */
object RemoteAudioPlayer {
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        stop()
        return try {
            val media = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { finished ->
                    finished.release()
                    synchronized(this@RemoteAudioPlayer) {
                        if (player === finished) player = null
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    failed.release()
                    synchronized(this@RemoteAudioPlayer) {
                        if (player === failed) player = null
                    }
                    true
                }
                prepareAsync()
            }
            player = media
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
