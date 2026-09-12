package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.probe.DockerContainer
import com.vaultforge.app.probe.DockerResult
import com.vaultforge.app.probe.ProbeRunner
import com.vaultforge.app.probe.SshClient
import com.vaultforge.app.probe.SshMetrics
import kotlinx.coroutines.launch

@Composable
fun ItemDetailScreen(itemId: String, onBack: () -> Unit) {
    val store = VaultApp.store
    val items by store.items.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scope = rememberCoroutineScope()
    var name by remember(itemId) { mutableStateOf(item.name) }
    var tagsText by remember(itemId) { mutableStateOf(item.tags.joinToString(", ")) }
    var protocol by remember(itemId) { mutableStateOf(item.protocol) }
    var address by remember(itemId) { mutableStateOf(item.address) }
    var host by remember(itemId) { mutableStateOf(item.host) }
    var portText by remember(itemId) { mutableStateOf(item.port.toString()) }
    var username by remember(itemId) { mutableStateOf(item.username) }
    var secretText by remember(itemId) { mutableStateOf(item.secret) }
    var endpoint by remember(itemId) { mutableStateOf(item.endpoint) }
    var demoCode by remember(itemId) { mutableStateOf(item.demoCode) }

    var busy by remember(itemId) { mutableStateOf(false) }
    var showDelete by remember(itemId) { mutableStateOf(false) }
    var metrics by remember(itemId) { mutableStateOf<SshMetrics?>(null) }
    var docker by remember(itemId) { mutableStateOf<DockerResult?>(null) }
    var logText by remember(itemId) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Text1)
            }
            Text(
                text = item.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Text1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = StatusDown)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(item.status, size = 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(statusText(item), fontSize = 14.sp, color = statusColor(item.status), fontWeight = FontWeight.Medium)
                Text("最后检测：" + friendlyTime(item.lastCheckedAt), fontSize = 11.sp, color = Text3)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brand)
                    .clickable {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                runCatching { ProbeRunner.probe(store, item) }
                                busy = false
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (busy) "检测中…" else "立即检测", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("配置")

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(10.dp))
        when (item.type) {
            "file" -> {
                OutlinedTextField(value = protocol, onValueChange = { protocol = it }, label = { Text("协议") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = secretText, onValueChange = { secretText = it }, label = { Text("密码 / 凭据") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            "ssh" -> {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = portText, onValueChange = { portText = it }, label = { Text("端口") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = secretText, onValueChange = { secretText = it }, label = { Text("密码 / 口令") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            else -> {
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("API 调用地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = demoCode, onValueChange = { demoCode = it }, label = { Text("官方演示代码") }, modifier = Modifier.fillMaxWidth().height(130.dp), maxLines = 7)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = tagsText, onValueChange = { tagsText = it }, label = { Text("标签（逗号分隔）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Brand)
                .clickable {
                    val updated = item.copy(
                        name = name.trim().ifBlank { item.name },
                        tags = parseTags(tagsText),
                        protocol = protocol.trim(),
                        address = address.trim(),
                        host = host.trim(),
                        port = portText.toIntOrNull() ?: item.port,
                        username = username.trim(),
                        secret = secretText,
                        endpoint = endpoint.trim(),
                        demoCode = demoCode,
                    )
                    store.upsert(updated)
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("保存修改", color = Color.White, fontSize = 14.sp)
        }

        if (item.type == "ssh") {
            Spacer(Modifier.height(18.dp))
            SectionTitle("运行指标")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandSoft)
                        .clickable {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    metrics = runCatching { SshClient.fetchMetrics(item) }.getOrNull()
                                    busy = false
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (busy) "拉取中…" else "刷新指标", color = Brand, fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BrandSoft)
                        .clickable {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    docker = runCatching { SshClient.listContainers(item) }.getOrNull()
                                    busy = false
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("刷新容器", color = Brand, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            val m = metrics
            if (m == null) {
                Text("点「刷新指标」查看 CPU / 内存 / 网络 / 磁盘", color = Text3, fontSize = 12.sp)
            } else if (m.error.isNotBlank()) {
                Text("指标获取失败：" + m.error, color = StatusDown, fontSize = 12.sp)
            } else {
                MiniBar("CPU", (m.cpuPercent / 100.0).toFloat(), if (m.cpuPercent >= 0.0) String.format("%.1f%%", m.cpuPercent) else "—")
                MiniBar("内存", (m.memUsedPercent / 100.0).toFloat(), if (m.memUsedPercent >= 0.0) String.format("%.1f%%", m.memUsedPercent) else "—")
                MiniBar("网络 ↓", -1f, formatRate(m.netRxKbps))
                MiniBar("网络 ↑", -1f, formatRate(m.netTxKbps))
                MiniBar("磁盘", (m.diskUsedPercent / 100.0).toFloat(), if (m.diskUsedPercent >= 0.0) String.format("%.1f%%", m.diskUsedPercent) else "—")
                MiniBar("磁盘读", -1f, formatRate(m.diskReadKbps))
                MiniBar("磁盘写", -1f, formatRate(m.diskWriteKbps))
                if (m.load1.isNotBlank()) {
                    Text("负载 " + m.load1 + " · " + m.kernel, color = Text3, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Docker 容器")
            val d = docker
            if (d == null) {
                Text("点「刷新容器」查看服务器上的容器", color = Text3, fontSize = 12.sp)
            } else if (d.error.isNotBlank()) {
                Text("容器列表获取失败：" + d.error, color = StatusDown, fontSize = 12.sp)
            } else if (d.containers.isEmpty()) {
                Text("没有容器（或 Docker 未安装）", color = Text3, fontSize = 12.sp)
            } else {
                d.containers.forEach { c ->
                    ContainerRow(container = c, onAction = { action ->
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val res = runCatching { SshClient.containerAction(item, c.name, action) }.getOrNull()
                                if (action == "logs") {
                                    logText = res?.output ?: "无法获取日志"
                                } else {
                                    docker = runCatching { SshClient.listContainers(item) }.getOrNull()
                                }
                                busy = false
                            }
                        }
                    })
                }
            }
        }

        if (item.type == "api") {
            Spacer(Modifier.height(16.dp))
            SectionTitle("试跑")
            Text(
                "检测时会向该地址发起 GET 请求，结果显示「可用 / 不可用」，调用地址自动保存。",
                color = Text3,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(40.dp))
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条密钥？") },
            text = { Text("删除后不可恢复：" + item.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    store.delete(item.id)
                    onBack()
                }) { Text("删除", color = StatusDown) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }

    val log = logText
    if (log != null) {
        AlertDialog(
            onDismissRequest = { logText = null },
            title = { Text("容器日志（tail 100）") },
            text = {
                Text(
                    text = log.take(3000),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { logText = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ContainerRow(container: DockerContainer, onAction: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(container.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Text1, modifier = Modifier.weight(1f))
            Text(container.status, fontSize = 11.sp, color = Text3)
        }
        Spacer(Modifier.height(4.dp))
        Text(container.image, fontSize = 11.sp, color = Text3)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("重启") { onAction("restart") }
            SmallAction("停止") { onAction("stop") }
            SmallAction("启动") { onAction("start") }
            SmallAction("日志") { onAction("logs") }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandSoft)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = Brand, fontSize = 12.sp)
    }
}

private fun formatRate(kbps: Double): String = when {
    kbps < 0 -> "—"
    kbps < 1024 -> String.format("%.0f KB/s", kbps)
    else -> String.format("%.2f MB/s", kbps / 1024.0)
}