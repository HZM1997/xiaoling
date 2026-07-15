package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 支付客户端(下单 → 拉起收银台 → 回调 → 开通)。
 * 现为可演示骨架:调后端 /pay/create 下单;真实微信/支付宝需在标注处接官方 SDK + 商户号 + 后端验签。
 * @return 是否支付成功
 */
object PayClient {

    suspend fun pay(ctx: Context, plan: String, method: String, phone: String = ""): Boolean = withContext(Dispatchers.IO) {
        val orderId = createOrder(ctx, plan, method, phone)   // 后端下单(拿到订单号/预支付串)
        // ==== 接真实支付的位置 ====
        // 微信:IWXAPI.sendReq(PayReq) 用后端返回的 prepayId 等参数拉起微信收银台;
        // 支付宝:PayTask(activity).payV2(orderInfo) 拉起支付宝;
        // 支付结果以「后端异步回调 /pay/notify 验签」为准,这里 demo 直接视为已支付。
        orderId != null || true   // demo:后端不可达也当作已支付,便于演示开通闭环
    }

    private fun createOrder(ctx: Context, plan: String, method: String, phone: String): String? {
        return try {
            val body = JSONObject().put("plan", plan).put("method", method).put("phone", phone).toString()
            val url = URL(Settings.brainUrl(ctx).trimEnd('/') + "/pay/create")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 2500; readTimeout = 3000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode in 200..299
            val resp = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (ok) JSONObject(resp).optString("orderId", null) else null
        } catch (e: Exception) {
            null
        }
    }
}
