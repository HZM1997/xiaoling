package com.xiaoling.core

/**
 * 号码信誉评估(端侧)。返回 level ∈ {safe, warn, block} + 原因。
 * 说明:第三方 App 拿不到通话「内容」音频,来电防诈只能基于「号码」判定;
 * 号码库可后续接三方信誉服务 / 自建黑名单。
 */
object NumberReputation {
    // 已知诈骗号(可热更新 / 接三方库)
    private val blacklist = setOf<String>()

    fun assess(raw: String): Pair<String, String> {
        val c = raw.replace(Regex("[^0-9+]"), "")
        if (c.isBlank()) return "safe" to ""
        if (c in blacklist) return "block" to "已知诈骗号码"
        if (suspicious(c)) return "warn" to reason(c)
        return "safe" to ""
    }

    private fun suspicious(c: String): Boolean {
        if (c.startsWith("+") && !c.startsWith("+86")) return true
        if (c.startsWith("00") || c.startsWith("95") || c.startsWith("96") ||
            c.startsWith("170") || c.startsWith("171")
        ) return true
        val d = c.removePrefix("+")
        return d.length < 7 || d.length > 12
    }

    private fun reason(c: String): String = when {
        c.startsWith("+") && !c.startsWith("+86") -> "境外来电"
        c.startsWith("170") || c.startsWith("171") -> "虚拟运营商号段"
        c.startsWith("00") -> "疑似改号/境外"
        c.startsWith("95") || c.startsWith("96") -> "特殊号段(常被诈骗冒用)"
        else -> "可疑号码"
    }
}
