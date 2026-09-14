package com.vaultforge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.bitwarden.BwCache
import com.vaultforge.app.bitwarden.BwEntry
import com.vaultforge.app.bitwarden.Totp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「Bitwarden 密码库」Tab：浏览最近一次拉取的条目（本机缓存），
 * 支持搜索、显示/复制密码与用户名，以及本地生成动态验证码（TOTP）。
 * 数据来源：拉取流程（设置 → 拉取密码库）写入的 BwCache。
 */
@Composable
fun BwVaultScreen(onOpenSettings: () -> Unit) {
    val cache by BwCache.state.collectAsState()
    var search by remember { mutableStateOf("") }
    var shownIds by remember { mutableStateOf(setOf<String>()) }
    var openTotpId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // 秒级 ticker：仅在展开验证码时运行
    LaunchedEffect(openTotpId) {
        while (openTotpId != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(VaultBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Bitwarden 密码库",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Text1,
                modifier = Modifier.weight(1f),
            )
            Text("拉取入口在「设置」", fontSize = 11.sp, color = Text3)
        }
        val data = cache
        if (data == null || data.vault.entries.isEmpty()) {
            BwVaultEmpty(onOpenSettings)
        } else {
            BwVaultStatus(data)
            BwVaultSearch(value = search, onValueChange = { search = it })
            val filtered = data.vault.entries.filter { e ->
                search.isBlank() ||
                    e.name.contains(search, true) ||
                    e.username.contains(search, true) ||
                    e.uris.any { u -> u.contains(search, true) } ||
                    e.folderName.contains(search, true)
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的条目", fontSize = 13.sp, color = Text3)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 24.dp),
                ) {
                    items(filtered, key = { it.id }) { e ->
                        BwVaultCard(
                            entry = e,
                            now = now,
                            showPassword = shownIds.contains(e.id),
                            totpOpen = openTotpId == e.id,
                            onTogglePassword = {
                                shownIds = if (shownIds.contains(e.id)) shownIds - e.id else shownIds + e.id
                            },
                            onToggleTotp = {
                                openTotpId = if (openTotpId == e.id) null else e.id
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BwVaultEmpty(onOpenSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, LineColor, RoundedCornerShape(14.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔐", fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text("还没有拉取过密码库", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Text1)
            Spacer(Modifier.height(6.dp))
            Text(
                "在「设置」里输入服务器地址、邮箱与主密码拉取后，这里可以浏览全部条目，并查看动态验证码。",
                fontSize = 12.sp,
                color = Text3,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brand)
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("去设置里拉取", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun BwVaultStatus(data: BwCache.Data) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(StatusUp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                data.email.ifBlank { "已连接" },
                fontSize = 12.sp,
                color = Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "共 " + data.vault.entries.size + " 条 · " + data.vault.folders.size + " 个文件夹 · 拉取于 " + fmt.format(Date(data.pulledAt)),
            fontSize = 11.sp,
            color = Text3,
        )
    }
}

@Composable
private fun BwVaultSearch(value: String, onValueChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(1.dp, LineColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 13.sp, color = Text1),
            cursorBrush = SolidColor(Brand),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 9.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("搜索名称 / 用户名 / 网址…", fontSize = 13.sp, color = Text3)
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "清除",
                tint = Text3,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun BwVaultCard(
    entry: BwEntry,
    now: Long,
    showPassword: Boolean,
    totpOpen: Boolean,
    onTogglePassword: () -> Unit,
    onToggleTotp: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, LineColor.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (entry.favorite) {
                Text("★ ", color = StatusWarn, fontSize = 13.sp)
            }
            Text(
                entry.name.ifBlank { "(未命名)" },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.typeName,
                fontSize = 11.sp,
                color = Brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brand.copy(alpha = 0.10f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (entry.username.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.username,
                    fontSize = 12.sp,
                    color = Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BwAction("复制用户名") { runCatching { clipboard.setText(AnnotatedString(entry.username)) } }
            }
        }
        if (entry.password.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showPassword) entry.password else "••••••••",
                    fontSize = 12.sp,
                    color = Text2,
                    fontFamily = if (showPassword) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BwAction(if (showPassword) "隐藏" else "显示") { onTogglePassword() }
                Spacer(Modifier.width(4.dp))
                BwAction("复制密码") { runCatching { clipboard.setText(AnnotatedString(entry.password)) } }
            }
        }
        if (entry.totp.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            BwTotpRow(rawTotp = entry.totp, now = now, open = totpOpen, onToggle = onToggleTotp)
        }
        if (entry.uris.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.uris.first(),
                fontSize = 11.sp,
                color = Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.folderName.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text("📁 " + entry.folderName, fontSize = 11.sp, color = Text3)
        }
    }
}

@Composable
private fun BwTotpRow(rawTotp: String, now: Long, open: Boolean, onToggle: () -> Unit) {
    val cfg = remember(rawTotp) { Totp.parse(rawTotp) }
    val clipboard = LocalClipboardManager.current
    val code = cfg?.let { Totp.code(it, now) }
    val left = cfg?.let { Totp.secondsLeft(it, now) }
    if (!open) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            BwAction("验证码") { onToggle() }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("动态验证码", fontSize = 10.sp, color = Text3)
            Spacer(Modifier.height(2.dp))
            when {
                cfg == null -> Text("密钥格式无法识别", fontSize = 12.sp, color = Text3)
                code == null -> Text("暂不支持该算法（如 Steam）", fontSize = 12.sp, color = Text3)
                else -> Text(
                    formatTotp(code),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Brand,
                    letterSpacing = 2.sp,
                )
            }
        }
        if (code != null && left != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CountdownRing(left = left, period = cfg?.period ?: 30, size = 24.dp)
                Spacer(Modifier.height(2.dp))
                Text(left.toString() + "s", fontSize = 9.sp, color = Text3)
            }
            Spacer(Modifier.width(8.dp))
            BwAction("复制") { runCatching { clipboard.setText(AnnotatedString(code)) } }
            Spacer(Modifier.width(4.dp))
        }
        BwAction("收起") { onToggle() }
    }
}

@Composable
private fun CountdownRing(left: Int, period: Int, size: Dp) {
    val frac = left.coerceIn(0, period).toFloat() / period.coerceAtLeast(1)
    val ringColor = if (left <= 5) StatusDown else Brand
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        val inset = w / 2f
        val s = Size(this.size.width - w, this.size.height - w)
        drawArc(
            color = LineColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = s,
            style = Stroke(width = w),
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * frac,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = s,
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun BwAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BrandSoft)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = Brand, fontSize = 11.sp)
    }
}

private fun formatTotp(code: String): String =
    if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
