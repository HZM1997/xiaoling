package com.xiaoling.core

import android.content.Context

/**
 * 离线唤醒配置。默认空 → 用系统识别唤醒(在线);填入 Picovoice AccessKey + 中文模型/关键词
 * 即启用真·离线唤醒(不联网、更省电、误唤醒更低)。
 *
 * 获取方式(免费):
 *  1. 到 console.picovoice.ai 注册,拿到 AccessKey;
 *  2. 在控制台用中文训练唤醒词「小灵」,下载 .ppn(放 assets/wake/xiaoling_zh.ppn);
 *  3. 下载中文模型 porcupine_params_zh.pv(放 assets/wake/porcupine_params_zh.pv);
 *  4. 把 AccessKey 填到下面 ACCESS_KEY(或运行时 SharedPreferences 覆盖)。
 */
object WakeConfig {
    // ← 填你的 Picovoice AccessKey;留空则不启用离线唤醒
    const val ACCESS_KEY = ""

    const val KEYWORD_ASSET = "wake/xiaoling_zh.ppn"
    const val MODEL_ASSET = "wake/porcupine_params_zh.pv"

    fun accessKey(ctx: Context): String {
        val ov = ctx.getSharedPreferences("xiaoling", Context.MODE_PRIVATE)
            .getString("pv_access_key", null)
        return if (!ov.isNullOrBlank()) ov else ACCESS_KEY
    }

    /** 三要素齐全(key + 两个 asset 存在)才认为可用 */
    fun available(ctx: Context): Boolean {
        if (accessKey(ctx).isBlank()) return false
        return assetExists(ctx, KEYWORD_ASSET) && assetExists(ctx, MODEL_ASSET)
    }

    private fun assetExists(ctx: Context, path: String): Boolean =
        try { ctx.assets.open(path).close(); true } catch (e: Exception) { false }
}
