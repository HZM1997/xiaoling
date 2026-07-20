package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 上传亲情语音到家庭组并由服务端直推目标设备,全程不打开联系人选择器。 */
object FamilyAudioClient {
    suspend fun send(ctx: Context, file: File, target: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0) return@withContext false
        val boundary = "xiaoling-${UUID.randomUUID()}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(Settings.brainUrl(ctx).trimEnd('/') + "/family/audio/upload").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2500
                readTimeout = 4500
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            DataOutputStream(connection.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("family_id", PushClient.familyId(ctx))
                field("sender", PushClient.deviceId(ctx))
                field("target", target)
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"memo.m4a\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = connection.responseCode
            if (code !in 200..299) return@withContext false
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(response).optBoolean("ok", false)
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
