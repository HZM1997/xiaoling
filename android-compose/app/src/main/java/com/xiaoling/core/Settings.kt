package com.xiaoling.core

import android.content.Context
import com.xiaoling.BuildConfig

/** 服务器地址:优先用户在 App 里填的,其次 BuildConfig 默认值 */
object Settings {
    private const val PREF = "xiaoling"
    private const val KEY_URL = "brain_url"

    fun brainUrl(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = sp.getString(KEY_URL, null)
        return if (v.isNullOrBlank()) BuildConfig.BRAIN_URL else v
    }

    fun setBrainUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url.trim()).apply()
    }

    private const val KEY_LIVE2D = "live2d_enabled"
    fun live2dEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_LIVE2D, false)

    fun setLive2d(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_LIVE2D, on).apply()
    }
}
