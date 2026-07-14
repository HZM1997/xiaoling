package com.xiaoling.core

import android.content.Context

/** 会员状态(本地持久化)。tier: "" 未开通 / "basic" 基础 / "premium" 高级 */
object Membership {
    private const val PREF = "xiaoling"
    private const val KEY = "member_tier"

    fun tier(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(ctx: Context, tier: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, tier).apply()
    }

    fun label(tier: String): String = when (tier) {
        "basic" -> "基础会员"
        "premium" -> "高级会员"
        else -> "未开通"
    }
}
