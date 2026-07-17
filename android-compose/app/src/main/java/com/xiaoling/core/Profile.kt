package com.xiaoling.core

import android.content.Context
import org.json.JSONObject

/**
 * 用户画像(本地持久化):称呼、偏好、常联系人。
 * 随每次请求传给云端智能大脑,让大模型更懂这位老人(理解"给女儿""放我爱听的戏"等)。
 */
object Profile {
    private const val PREF = "xiaoling_profile"

    fun name(ctx: Context) = sp(ctx).getString("name", "") ?: ""
    fun prefs(ctx: Context) = sp(ctx).getString("prefs", "") ?: ""
    fun contacts(ctx: Context) = sp(ctx).getString("contacts", "") ?: ""

    fun save(ctx: Context, name: String, prefs: String, contacts: String) {
        sp(ctx).edit()
            .putString("name", name.trim())
            .putString("prefs", prefs.trim())
            .putString("contacts", contacts.trim())
            .apply()
    }

    /** 组装成传给大脑的 profile JSON;都为空则返回 null(不占用请求体) */
    fun toJson(ctx: Context): JSONObject? {
        val n = name(ctx); val p = prefs(ctx); val c = contacts(ctx)
        if (n.isBlank() && p.isBlank() && c.isBlank()) return null
        return JSONObject().apply {
            if (n.isNotBlank()) put("name", n)
            if (p.isNotBlank()) put("prefs", p)
            if (c.isNotBlank()) put("contacts", c)
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
