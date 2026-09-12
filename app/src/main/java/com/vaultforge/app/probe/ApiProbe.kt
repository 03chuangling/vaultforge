package com.vaultforge.app.probe

import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object ApiProbe {

    suspend fun probe(item: VaultItem): ProbeResult = withContext(Dispatchers.IO) {
        // 优先执行「官方演示代码」：提取里面的真实请求实际调用，用真实响应判断 API 能否正常使用
        if (item.demoCode.isNotBlank()) {
            val demo = DemoExecutor.execute(item)
            if (demo.extracted) {
                return@withContext ProbeResult(
                    ok = demo.ok,
                    latencyMs = if (demo.tcpMs >= 0L) demo.tcpMs else demo.totalMs,
                    message = demo.message,
                )
            }
            // 演示代码无法解析：回退为地址连通性探测，并在结果中说明
            val fallback = probeEndpoint(item)
            return@withContext fallback.copy(message = "（演示代码未能解析，已按地址连通性探测）" + fallback.message)
        }
        probeEndpoint(item)
    }

    /** 轻量模式：只测 TCP 级握手延迟（列表页自动刷新用） */
    suspend fun latency(item: VaultItem): ProbeResult = withContext(Dispatchers.IO) {
        val endpoint = item.endpoint.trim()
        if (endpoint.isEmpty()) return@withContext ProbeResult(false, -1L, "未填写调用地址")
        val tcpMs = try {
            val u = URL(endpoint)
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            val tc = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(u.host, port), 3000)
            }
            System.currentTimeMillis() - tc
        } catch (e: Exception) {
            -1L
        }
        if (tcpMs >= 0L) ProbeResult(true, tcpMs, "延迟 $tcpMs ms")
        else ProbeResult(false, -1L, "连接失败")
    }

    private fun probeEndpoint(item: VaultItem): ProbeResult {
        val endpoint = item.endpoint.trim()
        if (endpoint.isEmpty()) {
            return ProbeResult(false, -1L, "未填写调用地址")
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
        return try {
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