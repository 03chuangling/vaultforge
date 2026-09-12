package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.model.VaultItem
import com.vaultforge.app.probe.ProbeRunner
import com.vaultforge.app.server.VaultServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private var autoProbedOnce = false

/** API 条目自动延迟刷新的间隔（毫秒） */
private const val API_REFRESH_MS = 5_000L

@Composable
fun VaultListScreen(
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val store = VaultApp.store
    val items by store.items.collectAsState()
    val settings by store.settings.collectAsState()
    var typeFilter by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(items.size) {
        if (!autoProbedOnce && items.isNotEmpty()) {
            autoProbedOnce = true
            probing = true
            runCatching { ProbeRunner.probeAll(store) }
            probing = false
        }
    }

    // API 条目自动延迟刷新：进入后先轮流跑一遍，此后每 5 秒自动刷新一轮
    LaunchedEffect(Unit) {
        while (true) {
            val apiItems = store.items.value.filter { it.type == "api" && it.endpoint.isNotBlank() }
            for (api in apiItems) {
                runCatching { ProbeRunner.probeApiLatency(store, api) }
            }
            delay(API_REFRESH_MS)
        }
    }

    val filtered = items
        .filter { typeFilter.isEmpty() || it.type == typeFilter }
        .filter { tagFilter == null || it.tags.contains(tagFilter) }
        .filter { s ->
            search.isBlank() ||
                s.name.contains(search, ignoreCase = true) ||
                s.address.contains(search, ignoreCase = true) ||
                s.host.contains(search, ignoreCase = true) ||
                s.endpoint.contains(search, ignoreCase = true) ||
                s.tags.any { t -> t.contains(search, ignoreCase = true) }
        }
    val allTags = items.flatMap { it.tags }.distinct().sorted()

    Scaffold(
        containerColor = VaultBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = Brand,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ListHeader(
                port = if (VaultServer.port > 0) VaultServer.port else settings.port,
                token = settings.token,
                probing = probing,
                onRefresh = {
                    scope.launch {
                        probing = true
                        runCatching { ProbeRunner.probeAll(store) }
                        probing = false
                    }
                },
                onCopyToken = { runCatching { clipboard.setText(AnnotatedString(settings.token)) } },
                onOpenSettings = onOpenSettings,
            )
            SearchField(value = search, onValueChange = { search = it })
            TypeFilterRow(typeFilter) { typeFilter = it }
            if (allTags.isNotEmpty()) {
                TagFilterRow(allTags, tagFilter) { tag ->
                    tagFilter = if (tagFilter == tag) null else tag
                }
            }
            if (filtered.isEmpty()) {
                if (search.isNotBlank()) {
                    NoResultState(search)
                } else {
                    EmptyState(onAdd)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 90.dp),
                ) {
                    items(filtered, key = { it.id }) { item ->
                        VaultCard(item = item, onClick = { onOpen(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(1.dp, LineColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = Text3,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Text1, fontSize = 14.sp),
            cursorBrush = SolidColor(Brand),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text("搜索名称 / 地址 / 标签…", color = Text3, fontSize = 14.sp)
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清空",
                tint = Text3,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun ListHeader(
    port: Int,
    token: String,
    probing: Boolean,
    onRefresh: () -> Unit,
    onCopyToken: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("秘钥仓", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Text1)
            Spacer(Modifier.width(8.dp))
            Text("VaultForge", fontSize = 12.sp, color = Text3)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = Brand,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "巡检",
                    tint = if (probing) Text3 else Brand,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("接口 127.0.0.1:$port", fontSize = 12.sp, color = Text2)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (token.isBlank()) "（未生成）" else token.take(12) + "…",
                fontSize = 12.sp,
                color = Brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopyToken() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text("点按复制", fontSize = 11.sp, color = Text3)
        }
    }
}

@Composable
private fun TypeFilterRow(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("" to "全部", "file" to "文件", "ssh" to "SSH", "api" to "API")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { idx ->
            val pair = options[idx]
            TagPill(text = pair.second, selected = selected == pair.first, onClick = { onSelect(pair.first) })
        }
    }
}

@Composable
private fun TagFilterRow(tags: List<String>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tags, key = { "tag_" + it }) { tag ->
            TagPill(text = "#" + tag, selected = selected == tag, onClick = { onSelect(tag) })
        }
    }
}

@Composable
private fun VaultCard(item: VaultItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, LineColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 12.dp)
                .background(Brand.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(item.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                TypeBadge(item.type)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = addressLine(item),
                fontSize = 12.sp,
                color = Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.take(4).forEach { t ->
                        TagPill(text = "#" + t)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText(item), fontSize = 12.sp, color = statusColor(item.status))
                Spacer(Modifier.weight(1f))
                Text("管理 ›", fontSize = 12.sp, color = Brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val label = when (type) {
        "file" -> "文件"
        "ssh" -> "SSH"
        "api" -> "API"
        else -> type
    }
    Text(
        text = label,
        fontSize = 11.sp,
        color = Brand,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Brand.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun NoResultState(keyword: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔍", fontSize = 36.sp)
        Spacer(Modifier.height(10.dp))
        Text("没有找到「$keyword」", fontSize = 15.sp, color = Text1, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("试试别的关键词，或检查筛选条件", fontSize = 12.sp, color = Text3)
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔐", fontSize = 42.sp)
        Spacer(Modifier.height(10.dp))
        Text("还没有密钥", fontSize = 16.sp, color = Text1, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text("点右下角 + 添加第一条", fontSize = 13.sp, color = Text3)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Brand)
                .clickable { onAdd() }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text("添加第一条密钥", color = Color.White, fontSize = 14.sp)
        }
    }
}