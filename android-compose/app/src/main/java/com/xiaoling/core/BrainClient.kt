package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 云端大脑客户端:POST /dialogue,用平台 HttpURLConnection + org.json,零额外依赖 */
object BrainClient {

    /** 在 IO 线程发请求;失败抛异常,调用方兜底到 LocalSafetyNet */
    suspend fun ask(ctx: Context, text: String, context: JSONObject? = null): Reply =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("user_id", "u001")
                .put("text", text)
                .apply { if (context != null) put("context", context) }
                .toString()

            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/dialogue")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2500
                readTimeout = 3500
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) throw IOException("HTTP $code")
                val j = JSONObject(resp)
                Reply(
                    j.optString("speech", "我在呢。"),
                    j.optJSONObject("action"),
                    j.optString("skill", ""),
                    j.optDouble("risk", 0.0)
                )
            } finally {
                c.disconnect()
            }
        }
}
