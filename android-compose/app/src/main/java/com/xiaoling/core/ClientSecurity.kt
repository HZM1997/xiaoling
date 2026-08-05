package com.xiaoling.core

import com.xiaoling.BuildConfig
import java.net.HttpURLConnection

/** Applies the server access credential without exposing model-provider keys. */
object ClientSecurity {
    fun apply(connection: HttpURLConnection) {
        connection.setRequestProperty("User-Agent", "Xiaoling-Android/${BuildConfig.VERSION_NAME}")
        if (BuildConfig.REALTIME_CLIENT_TOKEN.isNotBlank()) {
            connection.setRequestProperty("X-Xiaoling-Token", BuildConfig.REALTIME_CLIENT_TOKEN)
        }
    }
}
