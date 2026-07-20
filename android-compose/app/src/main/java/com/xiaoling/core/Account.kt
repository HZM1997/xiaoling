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
    fun realNameVerified(ctx: Context) = sp(ctx).getBoolean("real_name_verified", false)
    fun displayName(ctx: Context) = sp(ctx).getString("display_name", "") ?: ""
    fun chatEntitlement(ctx: Context) = sp(ctx).getString("chat_entitlement", "") ?: ""

    fun save(
        ctx: Context,
        token: String,
        phone: String,
        uid: String,
        familyId: String,
        membership: String,
        realNameVerified: Boolean = false,
        displayName: String = "",
        chatEntitlement: String = ""
    ) {
        sp(ctx).edit()
            .putString("token", token).putString("phone", phone).putString("uid", uid)
            .putString("family_id", familyId).putString("membership", membership)
            .putBoolean("real_name_verified", realNameVerified)
            .putString("display_name", displayName)
            .putString("chat_entitlement", chatEntitlement)
            .apply()
    }

    fun setMembership(ctx: Context, tier: String) {
        sp(ctx).edit().putString("membership", tier).apply()
    }

    fun setRealNameVerified(ctx: Context, displayName: String, entitlement: String) {
        sp(ctx).edit()
            .putBoolean("real_name_verified", true)
            .putString("display_name", displayName)
            .putString("chat_entitlement", entitlement)
            .apply()
    }

    fun logout(ctx: Context) { sp(ctx).edit().clear().apply() }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
