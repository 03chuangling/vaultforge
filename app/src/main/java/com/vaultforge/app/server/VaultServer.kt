package com.vaultforge.app.server

import android.util.Log
import com.vaultforge.app.data.VaultStore
import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import com.vaultforge.app.probe.DockerResult
import com.vaultforge.app.probe.ProbeRunner
import com.vaultforge.app.probe.SshClient
import com.vaultforge.app.probe.SshMetrics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * VaultForge 本地接口服务
 * 监听 127.0.0.1:8737，供 AI / 脚本调用。
 * 统一响应体 {"code":0,"message":"ok","data":...}
 */
object VaultServer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    var port: Int = 0
        private set

    fun start(store: VaultStore) {
        if (running) return
        running = true
        thread = Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", store.settings.value.port.coerceIn(1024, 65000)))
                serverSocket = ss
                port = ss.localPort
                Log.i("VaultServer", "listening on 127.0.0.1:$port")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!running) break
                        continue
                    }
                    Thread {
                        try {
                            handleClient(client, store)
                        } catch (e: Exception) {
                            Log.w("VaultServer", "client error", e)
                        } finally {
                            runCatching { client.close() }
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e("VaultServer", "server error", e)
                running = false
            }
        }.also {
            it.isDaemon = true
            it.name = "vault-server"
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: Socket, store: VaultStore) {
        client.soTimeout = 30000
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            respondJson(output, 400, 400, "bad request", null)
            return
        }
        val method = parts[0].uppercase()
        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) readBody(input, len) else ""

        route(method, path, query, headers, body, store, output)
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String,
        store: VaultStore,
        output: OutputStream,
    ) {
        val seg = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (seg.size < 2 || seg[0] != "api" || seg[1] != "v1") {
            respondJson(output, 404, 404, "not found", null)
            return
        }

        // GET /api/v1 → 服务信息（无需鉴权，用于连通性检查）
        if (seg.size == 2 && method == "GET") {
            respondJson(
                output, 200, 0, "ok",
                buildJsonObject {
                    put("name", "vaultforge")
                    put("version", "0.1.0")
                    put("port", port)
                }.toString(),
            )
            return
        }

        val token = store.settings.value.token
        val auth = headers["authorization"] ?: ""
        if (auth != "Bearer $token") {
            respondJson(output, 401, 401, "unauthorized：请携带 Authorization: Bearer <token>", null)
            return
        }

        try {
            when {
                seg.size == 3 && seg[2] == "items" && method == "GET" ->
                    listItems(store, query, output)
                seg.size == 3 && seg[2] == "items" && method == "POST" ->
                    createItem(store, body, output)
                seg.size == 4 && seg[2] == "items" && method == "GET" ->
                    getItem(store, seg[3], output)
                seg.size == 4 && seg[2] == "items" && method == "PATCH" ->
                    patchItem(store, seg[3], body, output)
                seg.size == 4 && seg[2] == "items" && method == "DELETE" ->
                    deleteItem(store, seg[3], output)
                seg.size == 5 && seg[2] == "items" && seg[4] == "test" && method == "POST" ->
                    testItem(store, seg[3], output)
                seg.size == 5 && seg[2] == "items" && seg[4] == "tags" && method == "PUT" ->
                    setTags(store, seg[3], body, output)
                seg.size == 5 && seg[2] == "ssh" && seg[4] == "metrics" && method == "GET" ->
                    sshMetrics(store, seg[3], output)
                seg.size == 5 && seg[2] == "ssh" && seg[4] == "containers" && method == "GET" ->
                    dockerList(store, seg[3], output)
                seg.size == 7 && seg[2] == "ssh" && seg[4] == "containers" && method == "POST" ->
                    dockerAction(store, seg[3], seg[5], seg[6], output)
                seg.size == 5 && seg[2] == "api" && seg[4] == "probe" && method == "POST" ->
                    apiProbe(store, seg[3], output)
                else -> respondJson(output, 404, 404, "no such route: $method $path", null)
            }
        } catch (e: Exception) {
            respondJson(output, 500, 500, "server error: " + (e.message ?: e.javaClass.simpleName), null)
        }
    }

    private fun listItems(store: VaultStore, query: Map<String, String>, output: OutputStream) {
        var list = store.items.value
        query["type"]?.takeIf { it.isNotBlank() }?.let { t ->
            list = list.filter { it.type == t }
        }
        query["tag"]?.takeIf { it.isNotBlank() }?.let { tg ->
            list = list.filter { it.tags.contains(tg) }
        }
        respondJson(output, 200, 0, "ok", json.encodeToString(ListSerializer(VaultItem.serializer()), list))
    }

    private fun createItem(store: VaultStore, body: String, output: OutputStream) {
        val item = try {
            json.decodeFromString(VaultItem.serializer(), body)
        } catch (e: Exception) {
            respondJson(output, 400, 400, "invalid json: " + (e.message ?: ""), null)
            return
        }
        val fixed = if (item.id.isBlank()) item.copy(id = store.newId()) else item
        store.upsert(fixed)
        val saved = store.get(fixed.id) ?: fixed
        respondJson(output, 200, 0, "created", json.encodeToString(VaultItem.serializer(), saved))
    }

    private fun getItem(store: VaultStore, id: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null) {
            respondJson(output, 404, 404, "item not found", null)
        } else {
            respondJson(output, 200, 0, "ok", json.encodeToString(VaultItem.serializer(), item))
        }
    }

    private fun patchItem(store: VaultStore, id: String, body: String, output: OutputStream) {
        val existing = store.get(id)
        if (existing == null) {
            respondJson(output, 404, 404, "item not found", null)
            return
        }
        val patch = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (patch == null) {
            respondJson(output, 400, 400, "invalid json", null)
            return
        }
        fun str(field: String, old: String): String =
            (patch[field] as? JsonPrimitive)?.contentOrNull ?: old

        val tags = (patch["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: existing.tags

        val updated = existing.copy(
            name = str("name", existing.name),
            tags = tags,
            protocol = str("protocol", existing.protocol),
            address = str("address", existing.address),
            host = str("host", existing.host),
            port = ((patch["port"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()) ?: existing.port,
            username = str("username", existing.username),
            authMethod = str("authMethod", existing.authMethod),
            secret = str("secret", existing.secret),
            privateKey = str("privateKey", existing.privateKey),
            endpoint = str("endpoint", existing.endpoint),
            apiKey = str("apiKey", existing.apiKey),
            demoCode = str("demoCode", existing.demoCode),
        )
        store.upsert(updated)
        val saved = store.get(updated.id) ?: updated
        respondJson(output, 200, 0, "updated", json.encodeToString(VaultItem.serializer(), saved))
    }

    private fun deleteItem(store: VaultStore, id: String, output: OutputStream) {
        if (store.get(id) == null) {
            respondJson(output, 404, 404, "item not found", null)
        } else {
            store.delete(id)
            respondJson(output, 200, 0, "deleted", null)
        }
    }

    private fun testItem(store: VaultStore, id: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null) {
            respondJson(output, 404, 404, "item not found", null)
            return
        }
        val result = runBlocking { ProbeRunner.probe(store, item) }
        respondJson(output, 200, 0, "ok", json.encodeToString(ProbeResult.serializer(), result))
    }

    private fun setTags(store: VaultStore, id: String, body: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null) {
            respondJson(output, 404, 404, "item not found", null)
            return
        }
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (obj == null) {
            respondJson(output, 400, 400, "invalid json", null)
            return
        }
        val tags = (obj["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        store.setTags(id, tags)
        respondJson(output, 200, 0, "ok", json.encodeToString(VaultItem.serializer(), store.get(id) ?: item))
    }

    private fun sshMetrics(store: VaultStore, id: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null || item.type != "ssh") {
            respondJson(output, 404, 404, "ssh item not found", null)
            return
        }
        val metrics = runBlocking { SshClient.fetchMetrics(item) }
        respondJson(output, 200, 0, "ok", json.encodeToString(SshMetrics.serializer(), metrics))
    }

    private fun dockerList(store: VaultStore, id: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null || item.type != "ssh") {
            respondJson(output, 404, 404, "ssh item not found", null)
            return
        }
        val result = runBlocking { SshClient.listContainers(item) }
        respondJson(output, 200, 0, "ok", json.encodeToString(DockerResult.serializer(), result))
    }

    private fun dockerAction(
        store: VaultStore,
        id: String,
        containerId: String,
        action: String,
        output: OutputStream,
    ) {
        val item = store.get(id)
        if (item == null || item.type != "ssh") {
            respondJson(output, 404, 404, "ssh item not found", null)
            return
        }
        val result = runBlocking { SshClient.containerAction(item, containerId, action) }
        respondJson(output, 200, 0, "ok", json.encodeToString(DockerResult.serializer(), result))
    }

    private fun apiProbe(store: VaultStore, id: String, output: OutputStream) {
        val item = store.get(id)
        if (item == null || item.type != "api") {
            respondJson(output, 404, 404, "api item not found", null)
            return
        }
        val result = runBlocking { ProbeRunner.probe(store, item) }
        val data = buildJsonObject {
            put("ok", result.ok)
            put("latencyMs", result.latencyMs)
            put("message", result.message)
            put("endpoint", item.endpoint)
        }
        respondJson(output, 200, 0, "ok", data.toString())
    }

    private fun respondJson(
        output: OutputStream,
        httpCode: Int,
        code: Int,
        message: String,
        dataJson: String?,
    ) {
        val obj = buildJsonObject {
            put("code", code)
            put("message", message)
            if (dataJson != null) {
                put("data", Json.parseToJsonElement(dataJson))
            }
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $httpCode ${statusText(httpCode)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                runCatching {
                    val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    map[k] = v
                }
            }
        }
        return map
    }
}