package com.vaultforge.app.data

import android.content.Context
import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.random.Random

@Serializable
data class VaultSettings(
    val port: Int = 8737,
    val token: String = "",
)

@Serializable
private data class VaultSnapshot(
    val items: List<VaultItem> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
)

class VaultStore(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file = File(context.filesDir, "vault_data.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(VaultSettings())
    val settings: StateFlow<VaultSettings> = _settings.asStateFlow()

    fun load() {
        scope.launch {
            val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
            var snapshot = VaultSnapshot()
            if (text.isNotBlank()) {
                snapshot = runCatching { json.decodeFromString(VaultSnapshot.serializer(), text) }
                    .getOrDefault(VaultSnapshot())
            }
            val settings = if (snapshot.settings.token.isBlank()) {
                snapshot.settings.copy(token = generateToken())
            } else {
                snapshot.settings
            }
            _items.value = snapshot.items
            _settings.value = settings
            snapshotNow()
        }
    }

    fun get(id: String): VaultItem? = _items.value.firstOrNull { it.id == id }

    fun upsert(item: VaultItem) {
        val now = System.currentTimeMillis()
        val stamped = item.copy(
            updatedAt = now,
            createdAt = if (item.createdAt == 0L) now else item.createdAt,
        )
        val list = _items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx >= 0) list[idx] = stamped else list.add(0, stamped)
        _items.value = list
        snapshotNow()
    }

    fun delete(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        snapshotNow()
    }

    fun setTags(id: String, tags: List<String>) {
        get(id)?.let { upsert(it.copy(tags = tags)) }
    }

    fun updateStatus(id: String, result: ProbeResult) {
        get(id)?.let {
            upsert(
                it.copy(
                    lastOk = result.ok,
                    lastLatencyMs = result.latencyMs,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastMessage = result.message,
                )
            )
        }
    }

    fun updateSettings(settings: VaultSettings) {
        _settings.value = settings
        snapshotNow()
    }

    fun allTags(): List<String> = _items.value.flatMap { it.tags }.distinct().sorted()

    fun newId(): String = UUID.randomUUID().toString()

    private fun snapshotNow() {
        scope.launch {
            mutex.withLock {
                runCatching {
                    val snap = VaultSnapshot(_items.value, _settings.value)
                    file.writeText(json.encodeToString(VaultSnapshot.serializer(), snap))
                }
            }
        }
    }

    private fun generateToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = Random.Default
        val sb = StringBuilder("vf_")
        repeat(24) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }
}