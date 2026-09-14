package com.vaultforge.app.bitwarden

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 最近一次成功拉取的 Bitwarden 密码库缓存（仅本机，用于「密码库」Tab 浏览与动态验证码）。
 * 与本地秘钥库相同安全姿态：均以明文 JSON 存放于应用私有目录，不随云端同步。
 */
object BwCache {

    @Serializable
    data class Data(
        val server: String = "",
        val email: String = "",
        val pulledAt: Long = 0L,
        val vault: BwVault = BwVault(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var ctx: Context? = null
    val state = MutableStateFlow<Data?>(null)

    fun init(c: Context) {
        ctx = c.applicationContext
        load()
    }

    private fun file(): File? = ctx?.let { File(it.filesDir, "bw_cache.json") }

    private fun load() {
        val f = file() ?: return
        runCatching {
            if (f.exists()) state.value = json.decodeFromString(Data.serializer(), f.readText())
        }
    }

    fun save(server: String, email: String, vault: BwVault) {
        val d = Data(server, email, System.currentTimeMillis(), vault)
        state.value = d
        val f = file() ?: return
        runCatching { f.writeText(json.encodeToString(Data.serializer(), d)) }
    }

    fun clear() {
        state.value = null
        runCatching { file()?.delete() }
    }
}