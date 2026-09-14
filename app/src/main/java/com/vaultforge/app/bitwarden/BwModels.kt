package com.vaultforge.app.bitwarden

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bitwarden 对接异常（网络 / 协议 / 解密错误），message 已转成可展示文案。 */
class BwException(message: String) : Exception(message)

// ---- 业务模型 ----

@Serializable
data class BwEntry(
    val id: String,
    val type: Int,
    val typeName: String,
    val name: String,
    val username: String = "",
    val password: String = "",
    val totp: String = "",
    val uris: List<String> = emptyList(),
    val notes: String = "",
    val folderId: String = "",
    val folderName: String = "",
    val favorite: Boolean = false,
)

@Serializable
data class BwFolder(val id: String, val name: String)

@Serializable
data class BwVault(
    val email: String = "",
    val name: String = "",
    val folders: List<BwFolder> = emptyList(),
    val entries: List<BwEntry> = emptyList(),
)

// ---- 网络模型（与 Vaultwarden / Bitwarden 响应对齐） ----

@Serializable
data class BwPreloginResp(
    val kdf: Int = 0,
    @SerialName("kdfIterations") val kdfIterations: Int? = null,
    @SerialName("kdfMemory") val kdfMemory: Int? = null,
    @SerialName("kdfParallelism") val kdfParallelism: Int? = null,
)

@Serializable
data class BwTokenResp(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("Key") val key: String = "",
    @SerialName("TwoFactorRequired") val twoFactorRequired: Boolean = false,
)

@Serializable
data class BwSyncProfile(val email: String? = null, val name: String? = null)

@Serializable
data class BwSyncFolder(val id: String? = null, val name: String? = null)

@Serializable
data class BwSyncUri(val uri: String? = null)

@Serializable
data class BwSyncLogin(
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null,
    val uris: List<BwSyncUri>? = null,
)

@Serializable
data class BwSyncCipher(
    val id: String? = null,
    val type: Int? = null,
    val name: String? = null,
    val notes: String? = null,
    @SerialName("folderId") val folderId: String? = null,
    val favorite: Boolean? = null,
    @SerialName("deletedDate") val deletedDate: String? = null,
    val login: BwSyncLogin? = null,
)

@Serializable
data class BwSyncResp(
    val profile: BwSyncProfile? = null,
    val folders: List<BwSyncFolder>? = null,
    val ciphers: List<BwSyncCipher>? = null,
)

// ---- UI 状态 ----

enum class BwPhase { IDLE, RUNNING, SUCCESS, ERROR }

data class BwUiState(
    val phase: BwPhase = BwPhase.IDLE,
    val message: String = "",
    val vault: BwVault? = null,
)