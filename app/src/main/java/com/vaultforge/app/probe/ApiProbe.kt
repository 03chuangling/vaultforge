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
        // 先测 TCP 级握手延迟（更接近真实网络延迟，而非含 TLS/请求的完整耗时）
        val tcpMs = try {
            val u = URL(endpoint)
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            val tc = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(u.host, port), 8000)
            }
            System.currentTimeMillis() - tc
        } catch (e: Exception) {
            -1L
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
                ProbeResult(true, if (tcpMs >= 0L) tcpMs else ms, "HTTP $code · 可用")
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