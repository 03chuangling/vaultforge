package com.vaultforge.app.probe

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.vaultforge.app.model.ProbeResult
import com.vaultforge.app.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties

@Serializable
data class SshMetrics(
    val ok: Boolean = false,
    val error: String = "",
    val cpuPercent: Double = -1.0,
    val memUsedPercent: Double = -1.0,
    val memUsedMb: Long = -1L,
    val memTotalMb: Long = -1L,
    val netRxKbps: Double = -1.0,
    val netTxKbps: Double = -1.0,
    val diskUsedPercent: Double = -1.0,
    val diskReadKbps: Double = -1.0,
    val diskWriteKbps: Double = -1.0,
    val load1: String = "",
    val kernel: String = "",
    val fetchedAt: Long = 0L,
)

@Serializable
data class DockerContainer(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    val image: String = "",
)

@Serializable
data class DockerResult(
    val ok: Boolean = false,
    val error: String = "",
    val containers: List<DockerContainer> = emptyList(),
    val output: String = "",
)

object SshClient {

    private const val SNAPSHOT_CMD =
        "cat /proc/stat; echo '@@@'; cat /proc/net/dev; echo '@@@'; cat /proc/diskstats; " +
            "echo '@@@'; sleep 0.12; cat /proc/stat; echo '@@@'; cat /proc/net/dev; echo '@@@'; " +
            "cat /proc/diskstats; echo '@@@'; cat /proc/meminfo; echo '@@@'; df -k / 2>/dev/null; " +
            "echo '@@@'; cat /proc/loadavg 2>/dev/null; echo '@@@'; uname -srm 2>/dev/null"

    fun openSession(item: VaultItem, timeoutMs: Int = 12000): Session {
        val jsch = JSch()
        val useKey = item.authMethod == "key" && item.privateKey.isNotBlank()
        if (useKey) {
            val passphrase = item.secret.ifBlank { null }
            jsch.addIdentity(
                "vaultforge_key",
                item.privateKey.toByteArray(Charsets.UTF_8),
                null,
                passphrase?.toByteArray(Charsets.UTF_8),
            )
        }
        val session = jsch.getSession(item.username.ifBlank { "root" }, item.host, item.port)
        if (!useKey) {
            session.setPassword(item.secret)
        }
        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = "password,keyboard-interactive,publickey"
        session.setConfig(config)
        session.timeout = timeoutMs
        session.setServerAliveInterval(15000)
        session.setServerAliveCountMax(4)
        session.connect(timeoutMs)
        return session
    }

    // ===== 会话复用（高频指标采样用）：登录一次，后续命令复用同一连接 =====
    @Volatile
    private var cachedSession: Session? = null
    @Volatile
    private var cachedKey: String = ""
    private val sessionLock = Any()
    private fun keyOf(item: VaultItem): String =
        item.host + "|" + item.port + "|" + item.username + "|" + item.authMethod + "|" + item.secret.hashCode()
    private fun acquireSession(item: VaultItem): Session {
        val key = keyOf(item)
        val cur = cachedSession
        if (cur != null && cur.isConnected && cachedKey == key) return cur
        synchronized(sessionLock) {
            val again = cachedSession
            if (again != null && again.isConnected && cachedKey == key) return again
            runCatching { cachedSession?.disconnect() }
            val fresh = openSession(item)
            cachedSession = fresh
            cachedKey = key
            return fresh
        }
    }
    private fun dropSession() {
        synchronized(sessionLock) {
            runCatching { cachedSession?.disconnect() }
            cachedSession = null
            cachedKey = ""
        }
    }

    suspend fun probe(item: VaultItem): ProbeResult = withContext(Dispatchers.IO) {
        // 1) 先测 TCP 级握手延迟（更接近真实网络延迟，不含密钥交换/认证的额外开销）
        val tcp0 = System.currentTimeMillis()
        val tcpMs = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(item.host, item.port), 6000)
            }
            System.currentTimeMillis() - tcp0
        } catch (e: Exception) {
            return@withContext ProbeResult(false, -1L, friendly(e))
        }
        // 2) 完整 SSH 握手（确认端口背后的服务与凭据可用），界面展示的延迟取 TCP 值
        var session: Session? = null
        try {
            session = openSession(item)
            ProbeResult(true, tcpMs, "SSH 连接成功")
        } catch (e: Exception) {
            ProbeResult(false, -1L, friendly(e))
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    suspend fun fetchMetrics(item: VaultItem): SshMetrics = withContext(Dispatchers.IO) {
        // 复用会话采样：失败时清理缓存并重建重试一次
        try {
            val session = acquireSession(item)
            val raw = exec(session, SNAPSHOT_CMD, 20000)
            parseMetrics(raw)
        } catch (e: Exception) {
            dropSession()
            try {
                val session = acquireSession(item)
                val raw = exec(session, SNAPSHOT_CMD, 20000)
                parseMetrics(raw)
            } catch (e2: Exception) {
                SshMetrics(error = friendly(e2))
            }
        }
    }

    suspend fun listContainers(item: VaultItem): DockerResult = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            session = openSession(item)
            val out = exec(
                session,
                "docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}' 2>&1",
                20000,
            )
            parseContainers(out)
        } catch (e: Exception) {
            DockerResult(error = friendly(e))
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    suspend fun containerAction(item: VaultItem, containerId: String, action: String): DockerResult =
        withContext(Dispatchers.IO) {
            if (!containerId.matches(Regex("[A-Za-z0-9_.\\-]{1,80}"))) {
                return@withContext DockerResult(error = "容器 ID 不合法")
            }
            val cmd = when (action) {
                "start" -> "docker start '$containerId' 2>&1"
                "stop" -> "docker stop '$containerId' 2>&1"
                "restart" -> "docker restart '$containerId' 2>&1"
                "logs" -> "docker logs --tail 100 '$containerId' 2>&1"
                else -> return@withContext DockerResult(error = "不支持的操作：" + action)
            }
            var session: Session? = null
            try {
                session = openSession(item)
                val out = exec(session, cmd, 30000)
                DockerResult(ok = true, output = out.trim().take(8000))
            } catch (e: Exception) {
                DockerResult(error = friendly(e))
            } finally {
                runCatching { session?.disconnect() }
            }
        }

    suspend fun runCommand(item: VaultItem, command: String): String = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            session = openSession(item)
            exec(session, command, 30000)
        } catch (e: Exception) {
            "[错误] " + friendly(e)
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    suspend fun runInContainer(item: VaultItem, container: String, command: String): String =
        withContext(Dispatchers.IO) {
            if (!container.matches(Regex("[A-Za-z0-9_.\\-]{1,80}"))) {
                return@withContext "[错误] 容器名不合法"
            }
            val escaped = command.replace("'", "'\\''")
            val cmd = "docker exec '" + container + "' sh -c '" + escaped + "' 2>&1"
            var session: Session? = null
            try {
                session = openSession(item)
                exec(session, cmd, 30000)
            } catch (e: Exception) {
                "[错误] " + friendly(e)
            } finally {
                runCatching { session?.disconnect() }
            }
        }

    private fun exec(session: Session, command: String, timeoutMs: Int = 15000): String {
        var channel: ChannelExec? = null
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val errBuf = ByteArrayOutputStream()
            channel.setErrStream(errBuf)
            val input: InputStream = channel.inputStream
            channel.connect(timeoutMs)
            val buf = StringBuilder()
            val bytes = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                var readAny = false
                while (input.available() > 0) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    buf.append(String(bytes, 0, n, Charsets.UTF_8))
                    readAny = true
                }
                if (channel.isClosed) {
                    if (input.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() > deadline) break
                if (!readAny) Thread.sleep(60)
            }
            val err = errBuf.toString("UTF-8")
            return if (err.isBlank()) buf.toString() else buf.toString() + "\n[stderr] " + err
        } finally {
            runCatching { channel?.disconnect() }
        }
    }

    private fun parseContainers(out: String): DockerResult {
        val trimmed = out.trim()
        if (trimmed.isEmpty()) return DockerResult(ok = true, containers = emptyList())
        val lower = trimmed.lowercase()
        if (lower.contains("command not found") || lower.contains("no such file")) {
            return DockerResult(error = "服务器上未找到 docker 命令")
        }
        if (lower.contains("permission denied")) {
            return DockerResult(error = "权限不足（请使用有 docker 权限的用户）")
        }
        if (lower.contains("cannot connect to the docker daemon")) {
            return DockerResult(error = "Docker 守护进程未运行")
        }
        val hasPipe = trimmed.contains("|")
        if (lower.contains("[stderr]") && !hasPipe) {
            return DockerResult(error = trimmed.take(300))
        }
        val containers = trimmed.lineSequence()
            .filter { it.contains("|") }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 4) null
                else DockerContainer(
                    id = parts[0].trim(),
                    name = parts[1].trim(),
                    status = parts[2].trim(),
                    image = parts[3].trim(),
                )
            }
            .toList()
        return DockerResult(ok = true, containers = containers)
    }

    private fun parseMetrics(raw: String): SshMetrics {
        val sec = raw.split("@@@")
        if (sec.size < 10) return SshMetrics(error = "指标输出解析失败（数据不完整）")
        val cpu = cpuPercent(sec[0], sec[3])
        val net = netRateBytes(sec[1], sec[4])
        val disk = diskRateBytes(sec[2], sec[5])
        val mem = memInfo(sec[6])
        val diskUsed = dfUsed(sec[7])
        val load1 = sec[8].trim().split(Regex("\\s+")).firstOrNull() ?: ""
        val kernel = sec[9].trim().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        return SshMetrics(
            ok = true,
            cpuPercent = cpu,
            memUsedPercent = mem.first,
            memUsedMb = mem.second,
            memTotalMb = mem.third,
            netRxKbps = net.first / 1024.0,
            netTxKbps = net.second / 1024.0,
            diskUsedPercent = diskUsed,
            diskReadKbps = disk.first / 1024.0,
            diskWriteKbps = disk.second / 1024.0,
            load1 = load1,
            kernel = kernel,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun cpuPercent(s1: String, s2: String): Double {
        val l1 = s1.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return -1.0
        val l2 = s2.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return -1.0
        val v1 = l1.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val v2 = l2.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v1.size < 4 || v2.size < 4) return -1.0
        val total1 = v1.sum()
        val total2 = v2.sum()
        val idle1 = v1[3] + (v1.getOrNull(4) ?: 0L)
        val idle2 = v2[3] + (v2.getOrNull(4) ?: 0L)
        val dt = total2 - total1
        val di = idle2 - idle1
        if (dt <= 0L) return -1.0
        return ((1.0 - di.toDouble() / dt.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun netRateBytes(s1: String, s2: String): Pair<Double, Double> {
        fun sum(section: String): Pair<Long, Long> {
            var rx = 0L
            var tx = 0L
            section.lineSequence().forEach { line ->
                val t = line.trim()
                val idx = t.indexOf(':')
                if (idx <= 0) return@forEach
                val name = t.substring(0, idx)
                if (name == "lo" || name.contains(" ")) return@forEach
                val fields = t.substring(idx + 1).trim().split(Regex("\\s+"))
                if (fields.size >= 9) {
                    rx += fields[0].toLongOrNull() ?: 0L
                    tx += fields[8].toLongOrNull() ?: 0L
                }
            }
            return rx to tx
        }
        val (r1, t1) = sum(s1)
        val (r2, t2) = sum(s2)
        return (r2 - r1).coerceAtLeast(0L).toDouble() to (t2 - t1).coerceAtLeast(0L).toDouble()
    }

    private fun diskRateBytes(s1: String, s2: String): Pair<Double, Double> {
        fun sum(section: String): Pair<Long, Long> {
            var read = 0L
            var written = 0L
            section.lineSequence().forEach { line ->
                val f = line.trim().split(Regex("\\s+"))
                if (f.size >= 10 && isWholeDisk(f[2])) {
                    read += (f[5].toLongOrNull() ?: 0L) * 512L
                    written += (f[9].toLongOrNull() ?: 0L) * 512L
                }
            }
            return read to written
        }
        val (r1, w1) = sum(s1)
        val (r2, w2) = sum(s2)
        return (r2 - r1).coerceAtLeast(0L).toDouble() to (w2 - w1).coerceAtLeast(0L).toDouble()
    }

    private fun isWholeDisk(name: String): Boolean {
        if (name.startsWith("loop") || name.startsWith("ram") || name.startsWith("sr") || name.startsWith("dm-")) return false
        if (Regex("^nvme\\d+n\\d+$").matches(name)) return true
        if (Regex("^mmcblk\\d+$").matches(name)) return true
        if (Regex("^[sv]d[a-z]+$").matches(name)) return true
        if (Regex("^xvd[a-z]+$").matches(name)) return true
        return false
    }

    private fun memInfo(meminfo: String): Triple<Double, Long, Long> {
        var totalKb = -1L
        var availKb = -1L
        meminfo.lineSequence().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> totalKb = firstKb(line)
                line.startsWith("MemAvailable:") -> availKb = firstKb(line)
            }
        }
        if (totalKb <= 0L) return Triple(-1.0, -1L, -1L)
        val avail = if (availKb < 0L) 0L else availKb
        val used = totalKb - avail
        val pct = used.toDouble() / totalKb.toDouble() * 100.0
        return Triple(pct, used / 1024L, totalKb / 1024L)
    }

    private fun firstKb(line: String): Long =
        line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: -1L

    private fun dfUsed(df: String): Double {
        val lines = df.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return -1.0
        val f = lines[1].trim().split(Regex("\\s+"))
        if (f.size >= 5) {
            val p = f[4].removeSuffix("%").toDoubleOrNull()
            if (p != null) return p
        }
        if (f.size >= 3) {
            val total = f[1].toDoubleOrNull() ?: 0.0
            val used = f[2].toDoubleOrNull() ?: 0.0
            if (total > 0.0) return used / total * 100.0
        }
        return -1.0
    }

    private fun friendly(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        val lower = msg.lowercase()
        return when {
            lower.contains("auth fail") -> "认证失败（用户名/密码/密钥错误）"
            lower.contains("connection refused") -> "连接被拒绝（端口不通或服务未开）"
            lower.contains("unknownhost") -> "域名解析失败"
            lower.contains("timeout") || lower.contains("timed out") -> "连接超时"
            lower.contains("connection reset") -> "连接被重置"
            else -> msg
        }
    }
}