package com.vaultforge.app.sync

import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 云端请求异常（网络 / 服务端错误），message 已转成可展示文案。 */
class CloudException(message: String) : Exception(message)

/** 规范化服务器地址：补 https:// 前缀、去尾部斜杠。 */
fun normalizeBaseUrl(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.isEmpty()) throw CloudException("请先填写服务器地址")
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return s
}

/** VaultForge Server 轻量客户端（OkHttp + kotlinx-serialization）。 */
class CloudClient(baseUrl: String, private val token: String? = null) {

    private val base = normalizeBaseUrl(baseUrl)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val mediaType = "application/json; charset=utf-8".toMediaType()
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---- 账号 ----

    suspend fun register(username: String, password: String): AuthData {
        val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(username.trim(), password))
        return decodeData(exec("/api/v1/auth/register", "POST", body), AuthData.serializer())
    }

    suspend fun login(username: String, password: String): AuthData {
        val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(username.trim(), password))
        return decodeData(exec("/api/v1/auth/login", "POST", body), AuthData.serializer())
    }

    suspend fun logout() {
        exec("/api/v1/auth/logout", "POST")
    }

    // ---- 同步 ----

    suspend fun pull(since: Long, deviceId: String): SyncPullData {
        val body = json.encodeToString(SyncPullRequest.serializer(), SyncPullRequest(deviceId, since))
        return decodeData(exec("/api/v1/sync/pull", "POST", body), SyncPullData.serializer())
    }

    suspend fun push(items: List<VaultItem>, deviceId: String): SyncPushData {
        val body = json.encodeToString(SyncPushRequest.serializer(), SyncPushRequest(deviceId, items))
        return decodeData(exec("/api/v1/sync/push", "POST", body), SyncPushData.serializer())
    }

    suspend fun batchDelete(ids: List<String>): Int {
        val body = json.encodeToString(BatchRequest.serializer(), BatchRequest("delete", ids))
        return decodeData(exec("/api/v1/items/batch", "POST", body), BatchData.serializer()).affected
    }

    // ---- 低层 ----

    private suspend fun exec(path: String, method: String, body: String? = null): JsonObject {
        val raw = withContext(Dispatchers.IO) {
            try {
                val rb = Request.Builder().url(base + path)
                if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer $token")
                if (method == "GET") {
                    rb.get()
                } else {
                    rb.post((body ?: "{}").toRequestBody(mediaType))
                }
                http.newCall(rb.build()).execute().use { resp -> resp.body?.string().orEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CloudException("无法连接服务器：${e.message ?: "网络错误"}")
            }
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw CloudException("服务器响应格式异常") }
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw CloudException(
                when (code) {
                    401 -> "登录已失效或账号密码错误"
                    403 -> msg.ifBlank { "没有权限（403）" }
                    else -> msg.ifBlank { "请求失败（$code）" }
                }
            )
        }
        return obj
    }

    private fun <T> decodeData(obj: JsonObject, ser: KSerializer<T>): T {
        val data = obj["data"] ?: throw CloudException("响应缺少数据")
        return json.decodeFromJsonElement(ser, data)
    }
}
