package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手机号登录(验证码)。演示骨架:
 *  - sendCode:调后端 /auth/send_code(真实场景后端发短信;demo 直接返回验证码可省略)。
 *  - login:调后端 /auth/login(phone+code)→ {token,uid,family_id,membership}。
 *  真实场景:后端接短信服务(阿里云/腾讯云)发 OTP + 校验;token 用 JWT/OAuth。demo 验证码固定 1234。
 */
object AuthClient {

    suspend fun sendCode(ctx: Context, phone: String): Boolean = withContext(Dispatchers.IO) {
        post(ctx, "/auth/send_code", JSONObject().put("phone", phone)) != null
    }

    /** @return 登录成功返回账号 JSON(token/uid/family_id/membership),失败 null */
    suspend fun login(ctx: Context, phone: String, code: String): JSONObject? = withContext(Dispatchers.IO) {
        post(ctx, "/auth/login", JSONObject().put("phone", phone).put("code", code))
    }

    /** 微信一键登录:真实场景传微信授权 code;demo 传固定 code。 */
    suspend fun wxLogin(ctx: Context): JSONObject? = withContext(Dispatchers.IO) {
        post(ctx, "/auth/wx_login", JSONObject().put("code", "demo-wx-code"))
    }

    private fun post(ctx: Context, path: String, body: JSONObject): JSONObject? {
        return try {
            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + path)
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 3000; readTimeout = 3500
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode in 200..299
            val resp = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (ok) JSONObject(resp).takeIf { it.optBoolean("ok", true) } else null
        } catch (e: Exception) { null }
    }
}
