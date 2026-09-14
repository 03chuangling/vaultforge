package com.vaultforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.probe.DockerContainer
import com.vaultforge.app.probe.DockerResult
import com.vaultforge.app.probe.ProbeRunner
import com.vaultforge.app.probe.SshClient
import com.vaultforge.app.probe.SshMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 图表配色（与主题协调）
private val ChartTrack = Color(0xFFE6EAEC)
private val ChartMem = Color(0xFF3D7EA6)
private val ChartRx = Color(0xFF2E7D5B)
private val ChartTx = Color(0xFFC77E3F)
private val ChartDisk = Color(0xFF5C7A8C)

@Composable
fun ItemDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onOpenTerminal: (String?) -> Unit,
    onOpenFiles: () -> Unit,
) {
    val store = VaultApp.store
    val settings by store.settings.collectAsState()
    val items by store.items.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var editMode by remember(itemId) { mutableStateOf(false) }

    // 编辑字段
    var name by remember(itemId) { mutableStateOf(item.name) }
    var tagsText by remember(itemId) { mutableStateOf(item.tags.joinToString(", ")) }
    var protocol by remember(itemId) { mutableStateOf(item.protocol) }
    var address by remember(itemId) { mutableStateOf(item.address) }
    var host by remember(itemId) { mutableStateOf(item.host) }
    var portText by remember(itemId) { mutableStateOf(item.port.toString()) }
    var username by remember(itemId) { mutableStateOf(item.username) }
    var secretText by remember(itemId) { mutableStateOf(item.secret) }
    var endpoint by remember(itemId) { mutableStateOf(item.endpoint) }
    var apiKeyText by remember(itemId) { mutableStateOf(item.apiKey) }
    var demoCode by remember(itemId) { mutableStateOf(item.demoCode) }

    var busy by remember(itemId) { mutableStateOf(false) }
    var showDelete by remember(itemId) { mutableStateOf(false) }
    var metrics by remember(itemId) { mutableStateOf<SshMetrics?>(null) }
    var metricsLoading by remember(itemId) { mutableStateOf(false) }
    var docker by remember(itemId) { mutableStateOf<DockerResult?>(null) }
    var logText by remember(itemId) { mutableStateOf<String?>(null) }
    // 指标历史采样（坐标图模式）
    val cpuHist = remember(itemId) { mutableStateListOf<Float>() }
    val memHist = remember(itemId) { mutableStateListOf<Float>() }
    val diskHist = remember(itemId) { mutableStateListOf<Float>() }
    fun appendHist(m: SshMetrics) {
        if (m.cpuPercent >= 0.0) cpuHist.add(m.cpuPercent.toFloat())
        if (m.memUsedPercent >= 0.0) memHist.add(m.memUsedPercent.toFloat())
        if (m.diskUsedPercent >= 0.0) diskHist.add(m.diskUsedPercent.toFloat())
        while (cpuHist.size > 40) cpuHist.removeAt(0)
        while (memHist.size > 40) memHist.removeAt(0)
        while (diskHist.size > 40) diskHist.removeAt(0)
    }
    fun saveEdits() {
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
            apiKey = apiKeyText.trim(),
            demoCode = demoCode,
        )
        store.upsert(updated)
        editMode = false
    }

    // 进入 SSH 详情页时自动读取指标；坐标图模式下持续采样
    LaunchedEffect(itemId, settings.chartStyle) {
        if (item.type != "ssh") return@LaunchedEffect
        if (settings.chartStyle != "plot") {
            if (metrics == null && !metricsLoading) {
                metricsLoading = true
                val first = runCatching { SshClient.fetchMetrics(item) }.getOrNull()
                metrics = first
                if (first != null) appendHist(first)
                metricsLoading = false
            }
        } else {
            while (true) {
                metricsLoading = metrics == null
                val m2 = runCatching { SshClient.fetchMetrics(item) }.getOrNull()
                if (m2 != null && m2.ok) {
                    metrics = m2
                    appendHist(m2)
                } else if (metrics == null && m2 != null) {
                    metrics = m2
                }
                metricsLoading = false
                delay(((settings.sampleSec.coerceIn(0.1f, 600f)) * 1000f).toLong())
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        // ===== 顶栏 =====
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (editMode) editMode = false else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Text1)
            }
            Text(
                text = item.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Text1,
                modifier = Modifier.weight(1f),
            )
            if (editMode) {
                IconButton(onClick = { saveEdits() }) {
                    Icon(Icons.Filled.Check, contentDescription = "保存并返回", tint = Brand)
                }
            } else {
                IconButton(onClick = { editMode = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "密钥设置", tint = Brand)
                }
            }
            IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = StatusDown)
            }
        }

        if (editMode) {
            // ===== 编辑模式：密钥设置 =====
            Spacer(Modifier.height(4.dp))
            SectionTitle("密钥设置")

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
                "login" -> {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = secretText, onValueChange = { secretText = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("网址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                else -> {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("API 调用地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = apiKeyText, onValueChange = { apiKeyText = it }, label = { Text("API密钥（Token）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                    .clickable { saveEdits() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存修改", color = Color.White, fontSize = 14.sp)
            }
        } else {
            // ===== 状态视图：连接状态等 =====
            Spacer(Modifier.height(4.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp), ambientColor = Color(0x262F5D62), spotColor = Color(0x262F5D62))
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(item.status, size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusText(item), fontSize = 14.sp, color = statusColor(item.status), fontWeight = FontWeight.Medium, style = TextStyle(fontFeatureSettings = "tnum"))
                    Text("最后检测：" + friendlyTime(item.lastCheckedAt), fontSize = 11.sp, color = Text3)
                    if (item.lastMessage.isNotBlank()) {
                        Text(item.lastMessage, fontSize = 11.sp, color = Text3, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (item.type == "login") {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brand)
                            .clickable { runCatching { clipboard.setText(AnnotatedString(item.secret)) } }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("复制密码", color = Color.White, fontSize = 12.sp)
                    }
                } else {
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
            }

            if (item.type == "ssh") {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("运行指标")
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandSoft)
                            .clickable { onOpenTerminal(null) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("终端", color = Brand, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandSoft)
                            .clickable {
                                if (!metricsLoading) {
                                    metricsLoading = true
                                    scope.launch {
                                        metrics = runCatching { SshClient.fetchMetrics(item) }.getOrNull()
                                        metricsLoading = false
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (metricsLoading) "读取中…" else "刷新", color = Brand, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))

                val m = metrics
                when {
                    metricsLoading && m == null -> {
                        Text("正在连接服务器并读取指标…", color = Text3, fontSize = 12.sp)
                    }
                    m == null -> {
                        Text("未获取到指标，点「刷新」重试", color = Text3, fontSize = 12.sp)
                    }
                    m.error.isNotBlank() -> {
                        Text("指标获取失败：" + m.error, color = StatusDown, fontSize = 12.sp)
                    }
                    else -> {
                        if (settings.chartStyle == "plot") {
                            PlotCharts(m, cpuHist, memHist, diskHist, settings.sampleSec)
                        } else {
                            MetricsCharts(m)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Docker 容器")
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
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
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("刷新", color = Brand, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val d = docker
                if (d == null) {
                    Text("点「刷新」查看服务器上的容器", color = Text3, fontSize = 12.sp)
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
                        }, onTerminal = { onOpenTerminal(c.name) })
                    }
                }
            }
            if (item.type == "file") {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("文件管理")
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brand)
                            .clickable { onOpenFiles() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("打开文件浏览器", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "支持 WebDAV / SFTP：在线浏览、下载、上传、删除（FTP / S3 后续版本加入）",
                    color = Text3,
                    fontSize = 12.sp,
                )
            }
            if (item.type == "api") {
                Spacer(Modifier.height(16.dp))
                SectionTitle("说明")
                Text(
                    "检测时会优先执行「官方演示代码」：自动提取代码中的真实请求（支持 curl / Python / JS 片段）并实际调用，用真实响应（200 可用 / 401 密钥问题等）判断；未填或无法解析时回退为地址连通性探测。地址与演示代码在右上角设置中查看修改。",
                    color = Text3,
                    fontSize = 12.sp,
                )
            }
            if (item.type == "login") {
                Spacer(Modifier.height(16.dp))
                SectionTitle("说明")
                Text(
                    "该条目由 Bitwarden 密码库导入。点右上角设置可修改名称 / 用户名 / 密码 / 网址；再次导入同一账号时将按条目自动更新。",
                    color = Text3,
                    fontSize = 12.sp,
                )
            }
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

// ===== 图表区 =====
@Composable
private fun MetricsCharts(m: SshMetrics) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        DonutChart(
            percent = m.cpuPercent.toFloat(),
            centerText = if (m.cpuPercent >= 0.0) String.format("%.0f%%", m.cpuPercent) else "—",
            label = "CPU",
            color = Brand,
        )
        DonutChart(
            percent = m.memUsedPercent.toFloat(),
            centerText = if (m.memUsedPercent >= 0.0) String.format("%.0f%%", m.memUsedPercent) else "—",
            label = "内存",
            color = ChartMem,
        )
    }
    Spacer(Modifier.height(4.dp))
    if (m.memUsedMb >= 0 && m.memTotalMb > 0) {
        Text(
            "内存 " + formatMb(m.memUsedMb) + " / " + formatMb(m.memTotalMb),
            fontSize = 11.sp,
            color = Text3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    RateBars(m)
}

@Composable
private fun DonutChart(
    percent: Float,
    centerText: String,
    label: String,
    color: Color,
    diameter: Dp = 104.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter)) {
                val stroke = 11.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(
                    color = ChartTrack,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (percent >= 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * (percent / 100f).coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerText, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Text1)
                Text(label, fontSize = 11.sp, color = Text3)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MetricBar(title: String, valueText: String, fraction: Float, color: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 13.sp, color = Text2)
            Text(valueText, fontSize = 13.sp, color = Text1, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ChartTrack)
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
@Composable
private fun LegendDot(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Text2)
        Spacer(Modifier.width(3.dp))
        Text(value, fontSize = 11.sp, color = Text1, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun RateBars(m: SshMetrics) {
    MetricBar("网络下行", formatRate(m.netRxKbps), rateFraction(m.netRxKbps), ChartRx)
    MetricBar("网络上行", formatRate(m.netTxKbps), rateFraction(m.netTxKbps), ChartTx)
    MetricBar(
        "磁盘使用率",
        if (m.diskUsedPercent >= 0.0) String.format("%.0f%%", m.diskUsedPercent) else "—",
        if (m.diskUsedPercent >= 0.0) (m.diskUsedPercent / 100.0).toFloat() else -1f,
        ChartDisk,
    )
    MetricBar("磁盘读取", formatRate(m.diskReadKbps), rateFraction(m.diskReadKbps), ChartDisk)
    MetricBar("磁盘写入", formatRate(m.diskWriteKbps), rateFraction(m.diskWriteKbps), ChartDisk)
    if (m.load1.isNotBlank() || m.kernel.isNotBlank()) {
        Text(
            (if (m.load1.isNotBlank()) "负载 " + m.load1 else "") +
                (if (m.kernel.isNotBlank()) "  ·  " + m.kernel else ""),
            color = Text3,
            fontSize = 11.sp,
        )
    }
}
@Composable
private fun PlotCharts(m: SshMetrics, cpu: List<Float>, mem: List<Float>, disk: List<Float>, sampleSec: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(Brand, "CPU", if (m.cpuPercent >= 0.0) String.format("%.0f%%", m.cpuPercent) else "—")
            Spacer(Modifier.width(12.dp))
            LegendDot(ChartMem, "内存", if (m.memUsedPercent >= 0.0) String.format("%.0f%%", m.memUsedPercent) else "—")
            Spacer(Modifier.width(12.dp))
            LegendDot(ChartDisk, "磁盘", if (m.diskUsedPercent >= 0.0) String.format("%.0f%%", m.diskUsedPercent) else "—")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(170.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg)
                    .padding(8.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val grid = Color(0xFFEDF0F2)
                    for (i in 0..4) {
                        val y = h * (i / 4f)
                        drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }
                    fun line(vals: List<Float>, color: Color) {
                        if (vals.isEmpty()) return
                        val n = vals.size
                        var px = 0f
                        var py = 0f
                        vals.forEachIndexed { i, v ->
                            val x = w * ((i + (40 - n)) / 39f)
                            val y = h * (1f - (v.coerceIn(0f, 100f) / 100f))
                            if (i > 0) {
                                drawLine(color, Offset(px, py), Offset(x, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                            }
                            px = x
                            py = y
                        }
                        drawCircle(color, radius = 3.dp.toPx(), center = Offset(px, py))
                    }
                    line(cpu, Brand)
                    line(mem, ChartMem)
                    line(disk, ChartDisk)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(
                Modifier
                    .width(34.dp)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("100%", fontSize = 9.sp, color = Text3)
                Spacer(Modifier.weight(1f))
                Text("50%", fontSize = 9.sp, color = Text3)
                Spacer(Modifier.weight(1f))
                Text("0%", fontSize = 9.sp, color = Text3)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (cpu.size < 2 && mem.size < 2) "正在采样…（每 " + fmtSec(sampleSec) + " 秒一次，趋势将随采样逐步展开）"
            else "横轴：最近 40 次采样（约每 " + fmtSec(sampleSec) + " 秒一次）",
            fontSize = 10.sp,
            color = Text3,
        )
        Spacer(Modifier.height(10.dp))
        if (m.memUsedMb >= 0 && m.memTotalMb > 0) {
            Text(
                "内存 " + formatMb(m.memUsedMb) + " / " + formatMb(m.memTotalMb),
                fontSize = 11.sp,
                color = Text3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }
        RateBars(m)
    }
}
@Composable
private fun ContainerRow(container: DockerContainer, onAction: (String) -> Unit, onTerminal: () -> Unit) {
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
            SmallAction("终端") { onTerminal() }
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

private fun rateFraction(kbps: Double): Float = if (kbps < 0) -1f else (kbps / 1024.0).toFloat()

private fun formatRate(kbps: Double): String = when {
    kbps < 0 -> "—"
    kbps < 1024 -> String.format("%.0f KB/s", kbps)
    else -> String.format("%.2f MB/s", kbps / 1024.0)
}

private fun formatMb(mb: Long): String = when {
    mb < 1024 -> mb.toString() + " MB"
    else -> String.format("%.1f GB", mb / 1024.0)
}
