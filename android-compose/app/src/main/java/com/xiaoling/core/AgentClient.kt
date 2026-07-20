package com.xiaoling.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AgentCatalogStatus(val count: Int, val revision: String)

object AgentClient {
    suspend fun status(ctx: Context): AgentCatalogStatus? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(Settings.brainUrl(ctx).trimEnd('/') + "/agent/catalog").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1400
                readTimeout = 1800
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            AgentCatalogStatus(json.optInt("count", 0), json.optString("revision"))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
