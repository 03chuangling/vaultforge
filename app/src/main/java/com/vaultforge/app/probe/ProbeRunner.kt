package com.vaultforge.app.probe

import com.vaultforge.app.data.VaultStore
import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem

object ProbeRunner {

    suspend fun probe(store: VaultStore, item: VaultItem): ProbeResult {
        if (item.type == "login") return ProbeResult(true, -1L, "Bitwarden 条目（无需检测）")
        val result = when (item.type) {
            "file" -> FileProbe.probe(item)
            "ssh" -> SshClient.probe(item)
            "api" -> ApiProbe.probe(item)
            else -> ProbeResult(false, -1L, "未知类型：" + item.type)
        }
        store.updateStatus(item.id, result)
        return result
    }

    /** 轻量模式：只测 TCP 级延迟（列表页每 5 秒自动刷新用），并更新状态 */
    suspend fun probeApiLatency(store: VaultStore, item: VaultItem): ProbeResult {
        val result = ApiProbe.latency(item)
        store.updateStatus(item.id, result)
        return result
    }

    suspend fun probeAll(store: VaultStore) {
        store.items.value.forEach { item ->
            if (item.type == "login") return@forEach
            runCatching { probe(store, item) }
        }
    }
}