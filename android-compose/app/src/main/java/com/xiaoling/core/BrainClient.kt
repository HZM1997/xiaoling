package com.xiaoling.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 云端大脑客户端:POST /dialogue,用平台 HttpURLConnection + org.json,零额外依赖 */
object BrainClient {
    @Volatile private var consecutiveFailures = 0
    @Volatile private var retryAfterElapsed = 0L

    data class Health(
        val reachable: Boolean,
        val modelAvailable: Boolean,
        val asrAvailable: Boolean,
        val realtimeAvailable: Boolean = false,
        val delegationAvailable: Boolean = false,
        val providers: String = ""
    )

    suspend fun health(ctx: Context): Health = withContext(Dispatchers.IO) {
        val base = Settings.brainUrl(ctx).trim().trimEnd('/')
        if (base.isBlank()) return@withContext Health(false, false, false)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$base/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 2500
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return@withContext Health(false, false, false)
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val models = json.optJSONObject("runtime")?.optJSONObject("models")
            val providers = models?.optJSONArray("providers")
            val names = buildList {
                if (providers != null) for (index in 0 until providers.length()) {
                    providers.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.joinToString(" / ")
            val realtime = json.optJSONObject("realtime")
            Health(
                true,
                json.optBoolean("llm", false),
                json.optBoolean("asr", false),
                realtime?.optBoolean("available", false) == true,
                realtime?.optBoolean("delegation", false) == true,
                names,
            )
        } catch (_: Exception) {
            Health(false, false, false)
        } finally {
            connection?.disconnect()
        }
    }

    fun transcribeWav(ctx: Context, wav: ByteArray): String? {
        val base = Settings.brainUrl(ctx).trim().trimEnd('/')
        if (base.isBlank() || wav.isEmpty()) return null
        val boundary = "----xiaoling-${System.currentTimeMillis()}"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/asr").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 1800
                readTimeout = 20000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { output ->
                output.write(("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"audio\"; filename=\"speech.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8))
                output.write(wav)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
            }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            json.optString("text").trim().ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 在 IO 线程发请求;失败抛异常,调用方兜底到 LocalSafetyNet。自动附带用户画像让大脑更懂用户。 */
    suspend fun ask(ctx: Context, text: String, context: JSONObject? = null): Reply =
        withContext(Dispatchers.IO) {
            if (SystemClock.elapsedRealtime() < retryAfterElapsed) {
                throw IOException("AI service cooling down")
            }
            // 合并用户画像到 context.profile,让智能大脑理解称呼/偏好/常联系人
            val mergedCtx = (context ?: JSONObject()).apply {
                Profile.toJson(ctx)?.let { put("profile", it) }
                put("device", DeviceContext.toJson(ctx))
                if (!has("scene")) put("scene", "voice_chat")
            }
            val hasCtx = mergedCtx.length() > 0
            val body = JSONObject()
                .put("user_id", Settings.userId(ctx))
                .put("text", text)
                .apply { if (hasCtx) put("context", mergedCtx) }
                .toString()

            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/dialogue")
            try {
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 1500
                    readTimeout = 4200
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) throw IOException("HTTP $code")
                    val j = JSONObject(resp)
                    consecutiveFailures = 0
                    retryAfterElapsed = 0L
                    Reply(
                        j.optString("speech", "我在呢。"),
                        j.optJSONObject("action"),
                        j.optString("skill", ""),
                        j.optDouble("risk", 0.0)
                    )
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(3)
                retryAfterElapsed = SystemClock.elapsedRealtime() + when (consecutiveFailures) {
                    1 -> 8000L
                    2 -> 20000L
                    else -> 45000L
                }
                throw e
            }
        }

    /** 举报/信任号码 → POST /report_number。best-effort,返回是否成功 */
    suspend fun reportNumber(ctx: Context, number: String, action: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("number", number).put("action", action).toString()
                val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/report_number")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 1500; readTimeout = 2000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val ok = c.responseCode in 200..299
                c.disconnect()
                ok
            } catch (e: Exception) { false }
        }
}
