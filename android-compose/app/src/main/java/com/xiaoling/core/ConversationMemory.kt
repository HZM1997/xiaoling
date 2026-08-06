package com.xiaoling.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small on-device continuity layer used while the persistent server brain is unavailable. */
object ConversationMemory {
    private const val PREF = "xiaoling_conversation_memory"
    private const val KEY_TURNS = "recent_turns"
    private const val KEY_FACTS = "safe_facts"
    private const val MAX_TURNS = 32
    private const val MAX_FACTS = 24

    private val sensitive = Regex(
        "身份证|银行卡|验证码|支付密码|登录密码|安全码|CVV|\\b\\d{16,19}\\b|\\b\\d{17}[\\dXx]\\b"
    )

    @Synchronized
    fun recordUser(ctx: Context, text: String) {
        recordTurn(ctx, "user", text)
        extractFacts(ctx, text)
    }

    @Synchronized
    fun recordAssistant(ctx: Context, text: String) = recordTurn(ctx, "assistant", text)

    @Synchronized
    fun recentTurns(ctx: Context, limit: Int = 8): JSONArray {
        val all = readArray(ctx, KEY_TURNS)
        val output = JSONArray()
        val start = (all.length() - limit.coerceIn(1, 12)).coerceAtLeast(0)
        for (index in start until all.length()) output.put(all.optJSONObject(index))
        return output
    }

    @Synchronized
    fun relevantFacts(ctx: Context, query: String = "", limit: Int = 6): JSONArray {
        val facts = readArray(ctx, KEY_FACTS)
        val queryChars = meaningfulChars(query)
        val ranked = buildList {
            for (index in 0 until facts.length()) {
                val fact = facts.optJSONObject(index) ?: continue
                val value = fact.optString("value")
                val key = fact.optString("key")
                val overlap = meaningfulChars(key + value).count { it in queryChars }
                add((overlap * 100 + fact.optLong("updated", 0L) / 86_400_000L) to fact)
            }
        }.sortedByDescending { it.first }
        return JSONArray().apply {
            ranked.take(limit.coerceIn(1, 10)).forEach { put(it.second) }
        }
    }

    @Synchronized
    fun fact(ctx: Context, key: String): String {
        val facts = readArray(ctx, KEY_FACTS)
        for (index in facts.length() - 1 downTo 0) {
            val fact = facts.optJSONObject(index) ?: continue
            if (fact.optString("key") == key) return fact.optString("value")
        }
        return ""
    }

    private fun recordTurn(ctx: Context, role: String, raw: String) {
        val text = clean(raw, 240, redact = true)
        if (text.isBlank()) return
        val turns = readArray(ctx, KEY_TURNS)
        turns.put(JSONObject().put("role", role).put("content", text).put("at", System.currentTimeMillis()))
        writeArray(ctx, KEY_TURNS, tail(turns, MAX_TURNS))
    }

    private fun extractFacts(ctx: Context, raw: String) {
        val text = clean(raw, 160, redact = false)
        if (text.isBlank()) return
        val patterns = listOf(
            Triple("name", Regex("(?:我叫|叫我)([\\u4e00-\\u9fff]{2,8})"), 1),
            Triple("likes", Regex("我(?:喜欢|爱听|爱看|爱吃)([^,，。！？!?]{1,28})"), 1),
            Triple("dislikes", Regex("我(?:不喜欢|不爱听|不爱看|不爱吃)([^,，。！？!?]{1,28})"), 1),
            Triple("home", Regex("我(?:住在|家在)([^,，。！？!?]{2,24})"), 1),
        )
        patterns.forEach { (key, pattern, group) ->
            pattern.find(text)?.groupValues?.getOrNull(group)?.let { rememberFact(ctx, key, it) }
        }
        Regex("我(女儿|儿子|老伴|丈夫|妻子|孙子|孙女)叫([\\u4e00-\\u9fff]{2,8})")
            .find(text)?.let { rememberFact(ctx, "family_${it.groupValues[1]}", it.groupValues[2]) }
    }

    private fun rememberFact(ctx: Context, key: String, raw: String) {
        val value = clean(raw, 48, redact = false)
        if (value.isBlank()) return
        val old = readArray(ctx, KEY_FACTS)
        val next = JSONArray()
        for (index in 0 until old.length()) {
            val fact = old.optJSONObject(index) ?: continue
            if (fact.optString("key") != key) next.put(fact)
        }
        next.put(JSONObject().put("key", key).put("value", value).put("updated", System.currentTimeMillis()))
        writeArray(ctx, KEY_FACTS, tail(next, MAX_FACTS))
    }

    private fun readArray(ctx: Context, key: String): JSONArray = try {
        JSONArray(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeArray(ctx: Context, key: String, value: JSONArray) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(key, value.toString()).apply()
    }

    private fun tail(source: JSONArray, count: Int): JSONArray = JSONArray().apply {
        val start = (source.length() - count).coerceAtLeast(0)
        for (index in start until source.length()) put(source.opt(index))
    }

    private fun clean(raw: String, limit: Int, redact: Boolean): String {
        val value = raw.replace(Regex("\\s+"), " ").trim().take(limit)
        if (!sensitive.containsMatchIn(value)) return value
        return if (redact) "[敏感内容已省略]" else ""
    }

    private fun meaningfulChars(text: String): Set<Char> =
        text.lowercase().filter { it.isLetterOrDigit() }.toSet()
}
