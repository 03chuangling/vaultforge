package com.vaultforge.app.probe

import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64

object FileProbe {

    suspend fun probe(item: VaultItem): ProbeResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            if (item.address.startsWith("http://") || item.address.startsWith("https://")) {
                probeHttp(item, t0)
            } else {
                probeTcp(item, t0)
            }
        } catch (e: Exception) {
            ProbeResult(false, -1L, "失败：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun probeHttp(item: VaultItem, t0: Long): ProbeResult {
        var code = httpCode(item, "HEAD", t0)
        if (code in 400..599) {
            code = httpCode(item, "GET", t0)
        }
        val ok = code in 200..399
        val note = when {
            code == 401 || code == 403 -> "HTTP $code（认证失败）"
            code == -1 -> "无法连接"
            ok -> "HTTP $code"
            else -> "HTTP $code（不可用）"
        }
        return ProbeResult(ok, if (ok) System.currentTimeMillis() - t0 else -1L, note)
    }

    private fun httpCode(item: VaultItem, method: String, t0: Long): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(item.address).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.requestMethod = method
            if (item.username.isNotBlank()) {
                val raw = item.username + ":" + item.secret
                val auth = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
                conn.setRequestProperty("Authorization", "Basic $auth")
            }
            val c = conn.responseCode
            if (method == "GET") {
                runCatching { conn.inputStream.use { it.read() } }
            }
            c
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun probeTcp(item: VaultItem, t0: Long): ProbeResult {
        val raw = item.address
            .removePrefix("sftp://")
            .removePrefix("ftp://")
            .removePrefix("s3://")
        val hostPart = raw.substringBefore('/')
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "").toIntOrNull()
            ?: when (item.protocol.lowercase()) {
                "sftp" -> 22
                "ftp" -> 21
                else -> -1
            }
        if (host.isBlank() || port <= 0) {
            return ProbeResult(false, -1L, "地址格式无法解析（示例：host:22）")
        }
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 8000)
        }
        return ProbeResult(true, System.currentTimeMillis() - t0, "TCP $host:$port 可达")
    }
}