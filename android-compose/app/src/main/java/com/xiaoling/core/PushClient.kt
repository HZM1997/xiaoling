package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 跨设备实时推送(家人看护)。
 *  - emit:老人机把事件上报服务器,服务器广播给家庭组;
 *  - subscribe:家人设备用 SSE 长连接实时接收(App 在线场景)。
 *  真正送达「关着的 App」需叠加厂商推送(极光/个推/华为/小米/APNs)—— 见 emit 处标注。
 */
object PushClient {

    private const val DEFAULT_FAMILY = "family-100086"

    fun familyId(ctx: Context): String = DEFAULT_FAMILY   // 真实场景:与账号/家庭组绑定

    /** 上报事件(老人机侧调用)。best-effort,失败不影响主流程。 */
    suspend fun emit(ctx: Context, type: String, text: String, atMs: Long) = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("family_id", familyId(ctx)).put("type", type)
                .put("text", text).put("at", atMs / 1000).toString()
            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/push/emit")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 2500; readTimeout = 2500
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode
            c.disconnect()
            // ==== 接厂商推送的位置:在这里也调 极光/个推 的服务端 API,以覆盖离线家人设备 ====
        } catch (e: Exception) { /* 忽略 */ }
    }

    /** 订阅家庭组事件(家人设备侧);每来一条事件回调 onEvent。阻塞式长连接,放在 IO 协程里跑。 */
    suspend fun subscribe(ctx: Context, onEvent: (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val fid = familyId(ctx)
            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/push/subscribe?family_id=$fid")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000; readTimeout = 0   // 长连接不超时
                setRequestProperty("Accept", "text/event-stream")
            }
            c.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (coroutineContext.isActive) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val json = line.removePrefix("data:").trim()
                        if (json.isNotEmpty() && json != "{}") {
                            try { onEvent(JSONObject(json)) } catch (e: Exception) {}
                        }
                    }
                }
            }
            c.disconnect()
        } catch (e: Exception) { /* 断线由上层重连 */ }
    }
}
