package com.xiaoling.core

import android.content.Context

/** 来电防诈拦截计数(SharedPreferences,供子女端看护页展示) */
object FraudStore {
    private const val PREF = "xiaoling"
    private const val KEY = "call_fraud_blocked"

    fun inc(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val n = sp.getInt(KEY, 0) + 1
        sp.edit().putInt(KEY, n).apply()
        return n
    }

    fun count(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY, 0)
}
