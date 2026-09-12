package com.vaultforge.app.probe

import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ApiProbe {

    suspend fun probe(item: VaultItem): ProbeResult = withContext(Dispatchers.IO) {
        val endpoint = item.endpoint.trim()
        if (endpoint.isEmpty()) {
            return@withContext ProbeResult(false, -1L, "未填写调用地址")
        }
        val t0 = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            val code = conn.responseCode
            runCatching { conn.inputStream.use { it.read() } }
            val ms = System.currentTimeMillis() - t0
            if (code in 200..399) {
                ProbeResult(true, ms, "HTTP $code · 可用")
            } else {
                ProbeResult(false, ms, "HTTP $code · 不可用")
            }
        } catch (e: Exception) {
            ProbeResult(false, -1L, "请求失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}