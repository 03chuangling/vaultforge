package com.vaultforge.app.sync

import com.vaultforge.app.model.VaultItem
import kotlinx.serialization.Serializable

// ---- 账号 ----

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
)

@Serializable
data class CloudUser(
    val id: String = "",
    val username: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class AuthData(
    val user: CloudUser? = null,
    val token: String = "",
    val expiresAt: Long = 0L,
)

// ---- 同步 ----

@Serializable
data class SyncPullRequest(
    val deviceId: String = "",
    val since: Long = 0L,
)

@Serializable
data class SyncPullData(
    val serverTime: Long = 0L,
    val items: List<VaultItem> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
data class SyncConflict(
    val id: String = "",
    val serverItem: VaultItem? = null,
)

@Serializable
data class SyncPushRequest(
    val deviceId: String = "",
    val items: List<VaultItem> = emptyList(),
)

@Serializable
data class SyncPushData(
    val accepted: List<String> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
)

// ---- 批量操作 ----

@Serializable
data class BatchRequest(
    val action: String = "delete",
    val ids: List<String> = emptyList(),
)

@Serializable
data class BatchData(
    val affected: Int = 0,
)
