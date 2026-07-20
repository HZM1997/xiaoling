package com.xiaoling.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 免密钥实时天气查询;灾害预警不使用本接口。 */
object WeatherClient {
    fun isWeatherQuery(text: String): Boolean =
        Regex("天气|气温|温度|下雨|带伞|冷不冷|热不热|风大").containsMatchIn(text)

    suspend fun ask(ctx: Context): Reply = withContext(Dispatchers.IO) {
        val location = lastLocation(ctx)
            ?: return@withContext Reply("我还不知道您在哪儿,请在设置里允许定位后再问我天气。", null, "生活问答·天气", 0.0)
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1400
                readTimeout = 1800
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("weather http")
            val current = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .getJSONObject("current")
            val temperature = current.optDouble("temperature_2m").toInt()
            val apparent = current.optDouble("apparent_temperature").toInt()
            val rain = current.optDouble("precipitation", 0.0)
            val wind = current.optDouble("wind_speed_10m", 0.0).toInt()
            val condition = weatherText(current.optInt("weather_code", -1))
            val rainTip = if (rain > 0.1) "正在下雨,出门记得带伞。" else "目前没有明显降水。"
            Reply("您这里现在$condition,${temperature}度,体感${apparent}度,风速每小时${wind}公里。$rainTip",
                null, "生活问答·天气", 0.0)
        } catch (_: Exception) {
            Reply("天气服务暂时没有回应,过一会儿我再帮您查。", null, "生活问答·天气", 0.0)
        } finally {
            connection?.disconnect()
        }
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "晴朗"
        1, 2 -> "晴间多云"
        3 -> "阴天"
        45, 48 -> "有雾"
        in 51..57 -> "有毛毛雨"
        in 61..67, in 80..82 -> "有雨"
        in 71..77, 85, 86 -> "有雪"
        in 95..99 -> "有雷雨"
        else -> "天气平稳"
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(ctx: Context): Location? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }
}
