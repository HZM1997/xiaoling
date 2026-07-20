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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class OfficialAlert(
    val id: String,
    val category: String,
    val speech: String,
    val source: String,
    val url: String
)

/**
 * 公共预警聚合。地震直接使用 USGS 官方免费 GeoJSON;气象类由后端 /alerts
 * 适配已获授权的官方数据源,未配置时返回空列表,不使用模拟预警误导用户。
 */
object OfficialAlerts {
    suspend fun latest(ctx: Context): OfficialAlert? = withContext(Dispatchers.IO) {
        val location = lastLocation(ctx)
        fetchConfiguredFeed(ctx, location) ?: fetchUsgsEarthquake(location)
    }

    fun markIfNew(ctx: Context, alert: OfficialAlert): Boolean {
        val sp = ctx.getSharedPreferences("official_alerts", Context.MODE_PRIVATE)
        if (sp.getString("last_id", "") == alert.id) return false
        return sp.edit().putString("last_id", alert.id).commit()
    }

    private fun fetchConfiguredFeed(ctx: Context, location: Location?): OfficialAlert? {
        val base = Settings.brainUrl(ctx).trimEnd('/')
        val query = if (location == null) "" else "?lat=${location.latitude}&lon=${location.longitude}"
        val json = getJson("$base/alerts$query", 1200, 1600) ?: return null
        val item = json.optJSONArray("alerts")?.optJSONObject(0) ?: return null
        val id = item.optString("id")
        val speech = item.optString("speech")
        if (id.isBlank() || speech.isBlank()) return null
        return OfficialAlert(
            id = id,
            category = item.optString("category", "weather"),
            speech = speech,
            source = item.optString("source", "官方预警源"),
            url = item.optString("url")
        )
    }

    private fun fetchUsgsEarthquake(location: Location?): OfficialAlert? {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val start = URLEncoder.encode(formatter.format(Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)), "UTF-8")
        val endpoint = "https://earthquake.usgs.gov/fdsnws/event/1/query" +
            "?format=geojson&orderby=time&limit=20&minmagnitude=4.5" +
            "&minlatitude=3&maxlatitude=54&minlongitude=73&maxlongitude=136&starttime=$start"
        val json = getJson(endpoint, 1800, 2200) ?: return null
        val features = json.optJSONArray("features") ?: return null
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val magnitude = props.optDouble("mag", 0.0)
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lon.isNaN() || lat.isNaN()) continue
            val distance = location?.let { distanceKm(it.latitude, it.longitude, lat, lon) }
            val relevant = when {
                distance == null -> magnitude >= 6.0
                magnitude >= 6.5 -> distance <= 1200
                magnitude >= 5.5 -> distance <= 600
                else -> distance <= 250
            }
            if (!relevant) continue
            val place = props.optString("place", "附近区域")
            val distanceText = distance?.let { ",距您约${it.toInt()}公里" }.orEmpty()
            return OfficialAlert(
                id = "usgs:${feature.optString("id")}",
                category = "earthquake",
                speech = "地震预警:监测到${magnitude}级地震,位置$place$distanceText。请远离玻璃和高处物品,注意避险。",
                source = "USGS",
                url = props.optString("url")
            )
        }
        return null
    }

    private fun getJson(endpoint: String, connectMs: Int, readMs: Int): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectMs
                readTimeout = readMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Xiaoling-Android/1.0")
            }
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
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

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
