package com.vaultforge.app.sync

import com.vaultforge.app.data.VaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class SyncPhase { IDLE, RUNNING, SUCCESS, ERROR }

data class SyncUiState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val message: String = "",
)

/** 云端同步编排：注册 / 登录 / 退出 + 双向同步（删除队列 → push → pull → 合并）。 */
object SyncEngine {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private fun set(phase: SyncPhase, message: String) {
        _state.value = SyncUiState(phase, message)
    }

    // ---- 账号 ----

    suspend fun register(store: VaultStore, base: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            set(SyncPhase.ERROR, "请填写账号和密码")
            return
        }
        set(SyncPhase.RUNNING, "正在注册…")
        try {
            val auth = CloudClient(base).register(username, password)
            applyAuth(store, base, auth)
            set(SyncPhase.RUNNING, "注册成功，正在首次同步…")
            doSync(store)
        } catch (e: Exception) {
            set(SyncPhase.ERROR, friendly(e))
        }
    }

    suspend fun login(store: VaultStore, base: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            set(SyncPhase.ERROR, "请填写账号和密码")
            return
        }
        set(SyncPhase.RUNNING, "正在登录…")
        try {
            val auth = CloudClient(base).login(username, password)
            applyAuth(store, base, auth)
            set(SyncPhase.RUNNING, "登录成功，正在首次同步…")
            doSync(store)
        } catch (e: Exception) {
            set(SyncPhase.ERROR, friendly(e))
        }
    }

    suspend fun logout(store: VaultStore) {
        val s = store.settings.value
        runCatching { CloudClient(s.cloudUrl, s.cloudToken).logout() }
        store.updateSettings(s.copy(cloudToken = "", lastSyncAt = 0L))
        set(SyncPhase.IDLE, "已退出云端账号")
    }

    // ---- 同步 ----

    suspend fun syncNow(store: VaultStore) {
        set(SyncPhase.RUNNING, "同步中…")
        try {
            doSync(store)
        } catch (e: Exception) {
            set(SyncPhase.ERROR, friendly(e))
        }
    }

    private suspend fun doSync(store: VaultStore) {
        val settings = store.settings.value
        val base = normalizeBaseUrl(settings.cloudUrl)
        if (settings.cloudToken.isBlank()) throw CloudException("请先登录云端账号，再同步")
        val client = CloudClient(base, settings.cloudToken)
        val deviceId = ensureDeviceId(store)
        val since = settings.lastSyncAt

        // 1) 本地删除队列先同步到云端（分批，单次上限 200）
        var removedCloud = 0
        val pend = store.pendingDeletes()
        if (pend.isNotEmpty()) {
            for (chunk in pend.chunked(200)) {
                removedCloud += client.batchDelete(chunk)
            }
            store.clearPendingDeletes(pend)
        }

        // 2) 推送本地变更（updatedAt > 游标）
        val localChanged = store.items.value.filter { it.updatedAt > since }
        var pushed = 0
        var conflicts = 0
        if (localChanged.isNotEmpty()) {
            val res = client.push(localChanged, deviceId)
            pushed = res.accepted.size
            for (c in res.conflicts) {
                val sv = c.serverItem ?: continue
                conflicts++
                if (sv.deleted) {
                    store.removeSilent(c.id)
                } else {
                    val local = store.get(c.id)
                    if (local == null || sv.updatedAt > local.updatedAt) store.putRemote(sv)
                }
            }
        }

        // 3) 拉取云端变更并合并（LWW：updatedAt 新者胜）
        val pull = client.pull(since, deviceId)
        var pulled = 0
        for (sv in pull.items) {
            val local = store.get(sv.id)
            if (local == null || sv.updatedAt > local.updatedAt) {
                store.putRemote(sv)
                pulled++
            }
        }
        var removedLocal = 0
        for (id in pull.deletedIds) {
            if (store.get(id) != null) {
                store.removeSilent(id)
                removedLocal++
            }
        }

        // 4) 推进同步游标
        store.setLastSyncAt(pull.serverTime)

        val parts = mutableListOf<String>()
        if (removedCloud > 0) parts.add("云端删除 $removedCloud")
        parts.add("上传 $pushed")
        parts.add("下载 $pulled")
        if (conflicts > 0) parts.add("冲突合并 $conflicts")
        if (removedLocal > 0) parts.add("本地移除 $removedLocal")
        set(SyncPhase.SUCCESS, "同步完成 · " + parts.joinToString(" · "))
    }

    // ---- 内部 ----

    private fun applyAuth(store: VaultStore, base: String, auth: AuthData) {
        val s = store.settings.value
        store.updateSettings(
            s.copy(
                cloudUrl = normalizeBaseUrl(base),
                cloudUser = auth.user?.username ?: s.cloudUser,
                cloudToken = auth.token,
                lastSyncAt = 0L, // 重新登录后按全量游标做一次安全合并
            )
        )
    }

    private fun ensureDeviceId(store: VaultStore): String {
        val s = store.settings.value
        if (s.cloudDeviceId.isNotBlank()) return s.cloudDeviceId
        val id = "app-" + UUID.randomUUID().toString().take(8)
        store.updateSettings(s.copy(cloudDeviceId = id))
        return id
    }

    private fun friendly(e: Throwable): String =
        if (e is CloudException) e.message ?: "同步失败"
        else "同步失败：${e.message ?: e.javaClass.simpleName}"
}