package com.xiaoling.core

import android.content.Context

/** 本地账号会话(登录态、家庭组、会员随账号走) */
object Account {
    private const val PREF = "xiaoling_acct"

    fun isLoggedIn(ctx: Context) = token(ctx).isNotEmpty()
    fun token(ctx: Context) = sp(ctx).getString("token", "") ?: ""
    fun phone(ctx: Context) = sp(ctx).getString("phone", "") ?: ""
    fun userId(ctx: Context) = sp(ctx).getString("uid", "") ?: ""
    fun familyId(ctx: Context) = sp(ctx).getString("family_id", "") ?: ""
    fun membership(ctx: Context) = sp(ctx).getString("membership", "") ?: ""
    fun role(ctx: Context) = sp(ctx).getString("role", "elder") ?: "elder"   // elder(老人端) / family(家人端)

    fun setRole(ctx: Context, role: String) { sp(ctx).edit().putString("role", role).apply() }

    fun save(ctx: Context, token: String, phone: String, uid: String, familyId: String, membership: String) {
        sp(ctx).edit()
            .putString("token", token).putString("phone", phone).putString("uid", uid)
            .putString("family_id", familyId).putString("membership", membership).apply()
    }

    fun setMembership(ctx: Context, tier: String) {
        sp(ctx).edit().putString("membership", tier).apply()
    }

    fun logout(ctx: Context) { sp(ctx).edit().clear().apply() }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
