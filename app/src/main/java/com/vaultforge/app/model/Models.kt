package com.vaultforge.app.model

import kotlinx.serialization.Serializable

@Serializable
data class VaultItem(
    val id: String = "",
    val type: String, // "file" | "ssh" | "api"
    val name: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,

    // 文件协议（webdav / sftp / ftp / s3 ...）
    val protocol: String = "",
    val address: String = "",

    // SSH
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: String = "password", // password | key
    val secret: String = "",
    val privateKey: String = "",

    // 第三方 API
    val endpoint: String = "",
    val apiKey: String = "",
    val demoCode: String = "",

    // 最近一次探测结果
    val lastOk: Boolean? = null,
    val lastLatencyMs: Long = -1L,
    val lastCheckedAt: Long = 0L,
    val lastMessage: String = "",

    // 删除墓碑（仅云端同步使用；本地正常条目恒为 false）
    val deleted: Boolean = false,
) {
    val status: ItemStatus
        get() = when {
            lastCheckedAt <= 0L -> ItemStatus.UNKNOWN
            lastOk == false -> ItemStatus.DOWN
            lastOk == true && lastLatencyMs > 300L -> ItemStatus.WARN
            lastOk == true -> ItemStatus.UP
            else -> ItemStatus.UNKNOWN
        }
}

enum class ItemStatus { UP, WARN, DOWN, UNKNOWN }

@Serializable
data class ProbeResult(
    val ok: Boolean,
    val latencyMs: Long = -1L,
    val message: String = "",
)