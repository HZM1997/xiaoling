package com.xiaoling.core

import android.content.Context

/** 来电防诈拦截计数 + 待处理诈骗事件(供 App 冷启动读取后即时警惕) */
object FraudStore {
    private const val PREF = "xiaoling"
    private const val KEY = "call_fraud_blocked"
    private const val KEY_PENDING = "pending_fraud"
    private const val KEY_PENDING_AT = "pending_fraud_at"
    private const val KEY_LAST_NUM = "last_fraud_number"

    fun setLastFraudNumber(ctx: Context, number: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_NUM, number).apply()
    }

    fun lastFraudNumber(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST_NUM, "") ?: ""

    fun inc(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = sp.getInt(KEY, 0) + 1
        sp.edit().putInt(KEY, n).apply()
        return n
    }

    fun count(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun setPending(ctx: Context, reason: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, reason)
            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    /** 返回 2 分钟内的待处理事件,否则 null */
    fun takePendingIfRecent(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val reason = sp.getString(KEY_PENDING, null) ?: return null
        val at = sp.getLong(KEY_PENDING_AT, 0)
        clearPending(ctx)
        return if (System.currentTimeMillis() - at <= 120_000) reason else null
    }

    fun clearPending(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING).remove(KEY_PENDING_AT).apply()
    }
}
