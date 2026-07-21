package com.xiaoling.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.xiaoling.service.AppForeground
import org.json.JSONObject
import java.util.TimeZone

/** 只上传理解当前对话所需的设备摘要,不读取联系人、文件或精确位置。 */
object DeviceContext {
    fun toJson(ctx: Context): JSONObject {
        val result = JSONObject()
            .put("local_time_ms", System.currentTimeMillis())
            .put("timezone", TimeZone.getDefault().id)
            .put("foreground", AppForeground.active)
            .put("android", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("microphone", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
            .put(
                "microphone_permission",
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            )

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
        result.put("network", when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        })
        result.put("metered", cm?.isActiveNetworkMetered ?: false)

        val battery = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) result.put("battery_percent", level * 100 / scale)
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        result.put(
            "charging",
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        )
        return result
    }
}
