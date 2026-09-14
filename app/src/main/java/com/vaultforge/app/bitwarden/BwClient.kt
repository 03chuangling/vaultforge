package com.vaultforge.app.bitwarden

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

/** 规范化 Bitwarden 服务器地址：补 https://、去尾斜杠与 /api、/identity 后缀。 */
fun normalizeBwUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) throw BwException("请填写服务器地址")
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return try {
        val u = URI(s)
        var p = (u.path ?: "").trimEnd('/')
        if (p.endsWith("/api")) p = p.dropLast(4)
        if (p.endsWith("/identity")) p = p.dropLast(9)
        URI(u.scheme, u.userInfo, u.host, u.port, p, null, null).toString().trimEnd('/')
    } catch (e: Exception) {
        s.trimEnd('/')
    }
}

/**
 * Bitwarden / Vaultwarden 最小客户端（协议流程与 VaultForge Server v0.4.0 完全一致）：
 *   POST /identity/accounts/prelogin   → KDF 参数
 *   POST /identity/connect/token       → 会话令牌 + 加密账户密钥
 *   GET  /api/sync                     → 全量密码库
 */
class BwClient(rawUrl: String) {

    private val base = normalizeBwUrl(rawUrl)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val jsonMt = "application/json; charset=utf-8".toMediaType()
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
        const val DEVICE_ID = "8f5a2c91-3d7e-4b6f-a1c2-9e8d7f6a5b40"
        const val DEVICE_NAME = "VaultForge-App"
    }

    suspend fun prelogin(email: String): BwPreloginResp {
        val body = buildJsonObject { put("email", email) }.toString()
        val (code, text) = call(
            Request.Builder()
                .url(base + "/identity/accounts/prelogin")
                .post(body.toRequestBody(jsonMt))
                .build()
        )
        if (code >= 400) throw BwException("prelogin 失败（HTTP $code）")
        return try {
            json.decodeFromString(BwPreloginResp.serializer(), text)
        } catch (e: Exception) {
            throw BwException("服务器响应格式异常")
        }
    }

    suspend fun login(email: String, mpHashB64: String): BwTokenResp {
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", email)
            .add("password", mpHashB64)
            .add("scope", "api offline_access")
            .add("client_id", "cli")
            .add("deviceType", "0")
            .add("deviceIdentifier", DEVICE_ID)
            .add("deviceName", DEVICE_NAME)
            .build()
        val (code, text) = call(
            Request.Builder()
                .url(base + "/identity/connect/token")
                .post(form)
                .build()
        )
        if (code == 400 || code == 401) throw BwException("邮箱或主密码不正确")
        if (code != 200) throw BwException("登录失败（HTTP $code）")
        val tr = try {
            json.decodeFromString(BwTokenResp.serializer(), text)
        } catch (e: Exception) {
            throw BwException("服务器响应格式异常")
        }
        if (tr.twoFactorRequired) throw BwException("该账号启用了两步验证（2FA），暂不支持自动登录")
        if (tr.accessToken.isBlank() || tr.key.isBlank()) throw BwException("服务器未返回会话密钥")
        return tr
    }

    suspend fun sync(token: String): BwSyncResp {
        val (code, text) = call(
            Request.Builder()
                .url(base + "/api/sync")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        )
        if (code == 401) throw BwException("会话已失效，请重试")
        if (code != 200) throw BwException("拉取失败（HTTP $code）")
        return try {
            json.decodeFromString(BwSyncResp.serializer(), text)
        } catch (e: Exception) {
            throw BwException("服务器响应格式异常")
        }
    }

    private suspend fun call(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BwException("无法连接服务器：" + (e.message ?: "网络错误"))
        }
    }
}