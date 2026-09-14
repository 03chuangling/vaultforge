package com.vaultforge.app.bitwarden

import com.vaultforge.app.data.VaultStore
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Bitwarden / Vaultwarden 拉取编排（仅内存态；主密码不保存、不落盘）。 */
object BwEngine {

    private val _state = MutableStateFlow(BwUiState())
    val state: StateFlow<BwUiState> = _state.asStateFlow()

    private fun set(phase: BwPhase, message: String, vault: BwVault? = null) {
        _state.value = BwUiState(phase, message, vault)
    }

    fun reset() {
        _state.value = BwUiState()
    }

    suspend fun fetch(store: VaultStore, serverUrl: String, email: String, password: String): Boolean {
        if (serverUrl.isBlank() || email.isBlank() || password.isEmpty()) {
            set(BwPhase.ERROR, "请填写服务器地址、邮箱与主密码")
            return false
        }
        set(BwPhase.RUNNING, "正在连接服务器…")
        return try {
            val vault = withContext(Dispatchers.Default) { pull(serverUrl, email, password) }
            // 记住服务器与邮箱（不保存密码）
            val s = store.settings.value
            store.updateSettings(s.copy(bwUrl = normalizeBwUrl(serverUrl), bwEmail = email.trim()))
            // 写入本机密码库缓存（供「Bitwarden 密码库」页浏览与动态验证码）
            BwCache.save(normalizeBwUrl(serverUrl), email.trim(), vault)
            set(
                BwPhase.SUCCESS,
                "已拉取 " + vault.entries.size + " 条 · " + vault.folders.size + " 个文件夹",
                vault,
            )
            true
        } catch (e: Exception) {
            set(BwPhase.ERROR, friendly(e))
            false
        }
    }

    private suspend fun pull(serverUrl: String, email: String, password: String): BwVault {
        val client = BwClient(serverUrl)
        val em = email.trim()

        // 1. KDF 参数
        set(BwPhase.RUNNING, "正在获取账号参数…")
        val pl = client.prelogin(em)

        // 2. 派生密钥
        val kdfDesc = if (pl.kdf == 1) "Argon2id" else "PBKDF2 × " + (pl.kdfIterations ?: 600000)
        set(BwPhase.RUNNING, "正在派生密钥（$kdfDesc）…")
        val mk = BwCrypto.deriveMasterKey(password, em, pl.kdf, pl.kdfIterations ?: 0, pl.kdfMemory, pl.kdfParallelism)
        val mpHash = BwCrypto.masterPasswordHash(mk, password)
        val (stEnc, stMac) = BwCrypto.stretchKey(mk)

        // 3. 登录
        set(BwPhase.RUNNING, "正在登录…")
        val token = client.login(em, BwCrypto.b64e(mpHash))

        // 4. 解密账户密钥（64 字节：enc32 + mac32）
        val userKey = BwCrypto.decSeg(stEnc, stMac, token.key)
        if (userKey == null || userKey.size != 64) throw BwException("无法解密账户密钥（主密码可能不正确）")
        val encKey = userKey.copyOfRange(0, 32)
        val macKey = userKey.copyOfRange(32, 64)

        // 5. 拉取密码库
        set(BwPhase.RUNNING, "正在拉取密码库…")
        val sync = client.sync(token.accessToken)

        // 6. 解密条目
        val folderNames = HashMap<String, String>()
        val folders = ArrayList<BwFolder>()
        sync.folders.orEmpty().forEach { f ->
            val id = f.id.orEmpty()
            if (id.isBlank()) return@forEach
            val nm = safeDec(encKey, macKey, f.name)
            folderNames[id] = nm
            folders.add(BwFolder(id, nm))
        }

        val entries = ArrayList<BwEntry>()
        sync.ciphers.orEmpty().forEach { c ->
            if (!c.deletedDate.isNullOrEmpty()) return@forEach
            val uris = c.login?.uris.orEmpty()
                .map { safeDec(encKey, macKey, it.uri) }
                .filter { it.isNotBlank() }
            entries.add(
                BwEntry(
                    id = c.id.orEmpty(),
                    type = c.type ?: 1,
                    typeName = bwTypeName(c.type ?: 1),
                    name = safeDec(encKey, macKey, c.name),
                    username = safeDec(encKey, macKey, c.login?.username),
                    password = safeDec(encKey, macKey, c.login?.password),
                    totp = safeDec(encKey, macKey, c.login?.totp),
                    uris = uris,
                    notes = safeDec(encKey, macKey, c.notes),
                    folderId = c.folderId.orEmpty(),
                    folderName = folderNames[c.folderId.orEmpty()] ?: "",
                    favorite = c.favorite == true,
                )
            )
        }

        return BwVault(
            email = sync.profile?.email.orEmpty().ifBlank { em },
            name = sync.profile?.name.orEmpty(),
            folders = folders,
            entries = entries.sortedWith(compareBy { it.name.lowercase() }),
        )
    }

    /** 把拉取的条目导入本地秘钥库（幂等：id 为 "bw-" + Bitwarden 条目 id，再次导入自动更新）。返回导入数量。 */
    fun importToStore(store: VaultStore, vault: BwVault): Int {
        var n = 0
        for (e in vault.entries) {
            if (e.id.isBlank()) continue
            val id = "bw-" + e.id
            val tags = ArrayList<String>()
            tags.add("bitwarden")
            if (e.folderName.isNotBlank()) tags.add(e.folderName)
            val existing = store.get(id)
            store.upsert(
                VaultItem(
                    id = id,
                    type = "login",
                    name = e.name.ifBlank { "(未命名条目)" },
                    username = e.username,
                    secret = e.password,
                    endpoint = e.uris.firstOrNull().orEmpty(),
                    tags = tags,
                    createdAt = existing?.createdAt ?: 0L,
                )
            )
            n++
        }
        return n
    }

    private fun safeDec(encKey: ByteArray, macKey: ByteArray, s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return try {
            BwCrypto.decString(encKey, macKey, s)
        } catch (e: Exception) {
            ""
        }
    }

    private fun bwTypeName(t: Int): String = when (t) {
        1 -> "登录"
        2 -> "安全笔记"
        3 -> "银行卡"
        4 -> "身份"
        5 -> "SSH 密钥"
        else -> "其他"
    }

    private fun friendly(e: Throwable): String =
        if (e is BwException) e.message ?: "拉取失败"
        else "拉取失败：" + (e.message ?: e.javaClass.simpleName)
}