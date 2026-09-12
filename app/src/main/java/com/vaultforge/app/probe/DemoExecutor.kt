package com.vaultforge.app.probe

import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 「官方演示代码」解析与执行器。
 *
 * 语义：检测 API 条目时，优先执行官方演示代码 —— 从中提取真实 HTTP 请求
 * （URL / 方法 / 请求头 / Body），实际发出调用，用真实响应判断 API 能否正常使用。
 *
 * 支持解析的常见演示代码格式：
 * - curl（-X / -H / -d / --data* / --json / -u / --url 等）
 * - Python requests（requests.get/post(...)、headers={...}、json={...}）
 * - JavaScript fetch / axios（url / method / headers / body）
 * - 裸 URL / 裸 HTTP 行（按 GET 处理）
 */
object DemoExecutor {

    data class DemoRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
        val contentType: String?,
        val source: String, // "curl" | "python" | "js" | "url"
    )

    data class DemoResult(
        val extracted: Boolean, // 是否成功从演示代码提取到请求
        val executed: Boolean,  // 是否实际发出请求
        val ok: Boolean,        // API 是否可用（2xx）
        val httpCode: Int,
        val tcpMs: Long,
        val totalMs: Long,
        val snippet: String,    // 响应片段
        val message: String,    // 人性化结论
    )

    private val DATA_FLAGS = setOf("-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode")
    private val VALUE_FLAGS = setOf(
        "-A", "--user-agent", "-e", "--referer", "-o", "--output", "-b", "--cookie",
        "-w", "--write-out", "-x", "--proxy", "--connect-timeout", "-m", "--max-time",
        "-F", "--form", "--form-string",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 从演示代码中提取可执行请求；无法识别时返回 null */
    fun extract(demoCode: String): DemoRequest? {
        val code = demoCode.trim().removePrefix("\$").trim()
        if (code.isEmpty()) return null
        return runCatching {
            val lower = code.lowercase()
            when {
                lower.startsWith("curl") -> parseCurl(code)
                Regex("""requests\.(get|post|put|delete|patch|head)\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(code) -> parsePython(code)
                Regex("""(fetch\s*\(|axios\.)""", RegexOption.IGNORE_CASE).containsMatchIn(code) -> parseJs(code)
                code.startsWith("http://") || code.startsWith("https://") -> parseRawUrl(code)
                lower.contains("curl ") -> parseCurl(code)
                else -> null
            }
        }.getOrNull()
    }

    /** 执行演示代码提取出的请求，返回真实调用结果 */
    suspend fun execute(item: VaultItem): DemoResult = withContext(Dispatchers.IO) {
        val req = extract(item.demoCode)
            ?: return@withContext DemoResult(false, false, false, -1, -1L, -1L, "", "未能从演示代码中提取可执行请求")
        val tcpMs = tcpLatency(req.url)
        val method = req.method.uppercase()
        val rawBody = req.body
        val bodyObj: RequestBody? = when {
            rawBody != null -> rawBody.toRequestBody((req.contentType ?: "application/json").toMediaType())
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(null)
            else -> null
        }
        val request = try {
            Request.Builder().url(req.url).apply {
                req.headers.forEach { (k, v) -> if (!k.equals("Content-Length", true)) header(k, v) }
                method(method, bodyObj)
            }.build()
        } catch (e: Exception) {
            return@withContext DemoResult(true, false, false, -1, tcpMs, -1L, "", "演示代码中的请求无法执行：" + friendly(e))
        }
        val t0 = System.currentTimeMillis()
        try {
            http.newCall(request).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                val text = runCatching { resp.body?.string() ?: "" }.getOrDefault("")
                val snippet = text.replace(Regex("""\s+"""), " ").trim().take(200)
                val ok = resp.code in 200..299
                val verdict = when {
                    ok -> "API 可用"
                    resp.code == 401 || resp.code == 403 -> "认证失败（密钥/权限问题）"
                    resp.code == 404 -> "路径或地址有误"
                    resp.code == 429 -> "触发限流"
                    resp.code >= 500 -> "服务端错误"
                    else -> "异常响应"
                }
                val msg = "演示代码已执行（" + req.source + "）· HTTP " + resp.code + " · " + verdict +
                    if (snippet.isNotBlank()) " · " + snippet.take(80) else ""
                DemoResult(true, true, ok, resp.code, tcpMs, ms, snippet, msg)
            }
        } catch (e: Exception) {
            DemoResult(true, false, false, -1, tcpMs, System.currentTimeMillis() - t0, "", "演示代码执行失败：" + friendly(e))
        }
    }

    // ==================== 解析：curl ====================

    private fun parseCurl(code: String): DemoRequest? {
        val tokens = tokenizeCurl(code)
        if (tokens.isEmpty()) return null
        var url: String? = null
        var method: String? = null
        var body: String? = null
        var contentType: String? = null
        var basic: String? = null
        val headers = LinkedHashMap<String, String>()
        var i = 1 // 跳过 "curl"
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            when {
                t == "-X" || t == "--request" -> { method = next?.uppercase(); i++ }
                t == "-H" || t == "--header" -> {
                    val h = next ?: ""
                    val idx = h.indexOf(':')
                    if (idx > 0) headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
                    i++
                }
                t == "-u" || t == "--user" -> { basic = next; i++ }
                t == "--json" -> { body = next; contentType = "application/json"; i++ }
                t in DATA_FLAGS -> { body = next; i++ }
                t == "--url" -> { url = next; i++ }
                t == "-I" || t == "--head" -> method = "HEAD"
                t.startsWith("http://") || t.startsWith("https://") -> if (url == null) url = t
                t in VALUE_FLAGS -> i++ // 跳过该开关的值
                else -> { /* 忽略其他参数 */ }
            }
            i++
        }
        val u = url ?: return null
        if (basic != null) {
            runCatching {
                headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(basic.toByteArray(Charsets.UTF_8))
            }
        }
        var ct = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: contentType
        if (body != null && ct == null) {
            val b = body.trimStart()
            ct = if (b.startsWith("{") || b.startsWith("[")) "application/json" else "application/x-www-form-urlencoded"
        }
        val m = method ?: if (body != null) "POST" else "GET"
        return DemoRequest(u, m, headers, body, ct, "curl")
    }

    /** curl 命令行分词：支持单/双引号、反斜杠续行 */
    private fun tokenizeCurl(input: String): List<String> {
        val s = input.replace("\\\r\n", " ").replace("\\\n", " ")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                when {
                    c == quote -> quote = null
                    c == '\\' && quote == '"' && i + 1 < s.length -> { sb.append(s[i + 1]); i++ }
                    else -> sb.append(c)
                }
            } else {
                when {
                    c == '\'' || c == '"' -> quote = c
                    c.isWhitespace() -> {
                        if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // ==================== 解析：Python requests ====================

    private fun parsePython(code: String): DemoRequest? {
        val m = Regex("""requests\.(get|post|put|delete|patch|head)\s*\(\s*(['"])([^'"]+)\2""", RegexOption.IGNORE_CASE).find(code)
            ?: return null
        val method = m.groupValues[1].uppercase()
        val url = m.groupValues[3]
        val headers = LinkedHashMap<String, String>()
        Regex("""headers\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(code)?.let { hm ->
            Regex("""['"]([^'"]+)['"]\s*:\s*['"]([^'"]*)['"]""").findAll(hm.groupValues[1]).forEach { kv ->
                headers[kv.groupValues[1]] = kv.groupValues[2]
            }
        }
        var body: String? = null
        var contentType: String? = null
        Regex("""json\s*=\s*\{""").find(code)?.let { jm ->
            val raw = balancedFrom(code, jm.range.last)
            if (raw != null) {
                body = raw.replace('\'', '"')
                contentType = "application/json"
            }
        }
        return DemoRequest(url, method, headers, body, contentType, "python")
    }

    // ==================== 解析：JS fetch / axios ====================

    private fun parseJs(code: String): DemoRequest? {
        val fetch = Regex("""fetch\s*\(\s*(['"])([^'"]+)\1""").find(code)
        val axios = Regex("""axios\.(get|post|put|delete|patch)\s*\(\s*(['"])([^'"]+)\2""", RegexOption.IGNORE_CASE).find(code)
        val url = fetch?.groupValues?.get(2) ?: axios?.groupValues?.get(3) ?: return null
        var method = if (fetch != null) "GET" else axios?.groupValues?.get(1)?.uppercase() ?: "GET"
        Regex("""method\s*:\s*['"]([A-Za-z]+)['"]""").find(code)?.let { method = it.groupValues[1].uppercase() }
        val headers = LinkedHashMap<String, String>()
        Regex("""headers\s*:\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(code)?.let { hm ->
            Regex("""['"]?([\w-]+)['"]?\s*:\s*['"]([^'"]*)['"]""").findAll(hm.groupValues[1]).forEach { kv ->
                headers[kv.groupValues[1]] = kv.groupValues[2]
            }
        }
        var body: String? = null
        var contentType: String? = null
        Regex("""body\s*:\s*(?:JSON\.stringify\s*\(\s*)?\{""").find(code)?.let { bm ->
            val raw = balancedFrom(code, bm.range.last)
            if (raw != null) {
                body = raw
                contentType = "application/json"
            }
        }
        if (body == null) {
            Regex("""body\s*:\s*['"]([^'"]+)['"]""").find(code)?.let { body = it.groupValues[1] }
        }
        return DemoRequest(url, method, headers, body, contentType, "js")
    }

    // ==================== 解析：裸 URL ====================

    private fun parseRawUrl(code: String): DemoRequest? {
        val line = code.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        return DemoRequest(line.substringBefore(' '), "GET", emptyMap(), null, null, "url")
    }

    // ==================== 工具 ====================

    /** 从 startIdx（'{' 位置）起，按括号平衡截取 { ... } 片段 */
    private fun balancedFrom(s: String, startIdx: Int): String? {
        var depth = 0
        var j = startIdx
        while (j < s.length) {
            val c = s[j]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return s.substring(startIdx, j + 1)
            }
            j++
        }
        return null
    }

    private fun tcpLatency(url: String): Long {
        return try {
            val u = URI(url)
            val host = u.host
            if (host.isNullOrBlank()) -1L else {
                val port = if (u.port > 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
                val tc = System.currentTimeMillis()
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 8000) }
                System.currentTimeMillis() - tc
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun friendly(e: Exception): String = when {
        e is java.net.UnknownHostException -> "域名无法解析"
        e is java.net.SocketTimeoutException -> "连接超时"
        e is javax.net.ssl.SSLException -> "TLS/证书错误"
        else -> e.message ?: e.javaClass.simpleName
    }
}
