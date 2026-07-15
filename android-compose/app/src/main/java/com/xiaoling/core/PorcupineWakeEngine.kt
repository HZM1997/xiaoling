package com.xiaoling.core

import android.content.Context
import java.io.File

/**
 * Picovoice Porcupine 离线唤醒引擎(可插拔)。
 * 用反射调用 SDK,避免未接依赖时编译/运行报错;不可用时静默回退,由 WakeService 走系统识别唤醒。
 * 命中唤醒词 → 回调 onWake()。
 */
class PorcupineWakeEngine(private val ctx: Context) {

    private var manager: Any? = null

    val isAvailable: Boolean get() = WakeConfig.available(ctx)

    /** 启动离线唤醒;成功返回 true。失败(缺 key/模型/SDK)返回 false 由上层回退。 */
    fun start(onWake: () -> Unit): Boolean {
        if (!isAvailable) return false
        return try {
            val keywordPath = copyAsset(WakeConfig.KEYWORD_ASSET)
            val modelPath = copyAsset(WakeConfig.MODEL_ASSET)

            // 反射构建 PorcupineManager.Builder,避免硬编译依赖
            val builderCls = Class.forName("ai.picovoice.porcupine.PorcupineManager\$Builder")
            val callbackCls = Class.forName("ai.picovoice.porcupine.PorcupineManagerCallback")
            val builder = builderCls.getConstructor().newInstance()

            builderCls.getMethod("setAccessKey", String::class.java).invoke(builder, WakeConfig.accessKey(ctx))
            builderCls.getMethod("setKeywordPath", String::class.java).invoke(builder, keywordPath)
            builderCls.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)

            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackCls.classLoader, arrayOf(callbackCls)
            ) { _, method, _ ->
                if (method.name == "invoke") onWake()
                null
            }
            manager = builderCls.getMethod("build", Context::class.java, callbackCls)
                .invoke(builder, ctx, callback)
            manager?.javaClass?.getMethod("start")?.invoke(manager)
            true
        } catch (e: Throwable) {
            stop()
            false
        }
    }

    fun stop() {
        try {
            manager?.let {
                it.javaClass.getMethod("stop").invoke(it)
                it.javaClass.getMethod("delete").invoke(it)
            }
        } catch (e: Throwable) { /* 忽略 */ }
        manager = null
    }

    /** Porcupine 需要文件路径,把 assets 拷到私有目录 */
    private fun copyAsset(name: String): String {
        val out = File(ctx.filesDir, name.substringAfterLast('/'))
        if (!out.exists() || out.length() == 0L) {
            ctx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out.absolutePath
    }
}
