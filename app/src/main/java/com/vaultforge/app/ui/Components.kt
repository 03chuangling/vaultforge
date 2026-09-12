package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.model.ItemStatus
import com.vaultforge.app.model.VaultItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun statusColor(status: ItemStatus): Color = when (status) {
    ItemStatus.UP -> StatusUp
    ItemStatus.WARN -> StatusWarn
    ItemStatus.DOWN -> StatusDown
    ItemStatus.UNKNOWN -> StatusUnknown
}

fun statusText(item: VaultItem): String {
    if (item.lastCheckedAt <= 0L) return "未检测"
    return if (item.lastOk == true) {
        val base = if (item.type == "ssh") "在线" else "可用"
        if (item.lastLatencyMs >= 0L) "$base · ${item.lastLatencyMs}ms" else base
    } else {
        if (item.lastMessage.isNotBlank()) "不可用 · ${item.lastMessage}" else "不可用"
    }
}

fun addressLine(item: VaultItem): String = when (item.type) {
    "file" -> listOf(item.protocol, item.address).filter { it.isNotBlank() }.joinToString(" · ")
    "ssh" -> item.host + ":" + item.port + (if (item.username.isNotBlank()) " · " + item.username else "")
    "api" -> item.endpoint
    else -> item.address
}

fun parseTags(text: String): List<String> =
    text.split(',', '，', ';', '；', ' ', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

fun friendlyTime(ts: Long): String {
    if (ts <= 0L) return "—"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ts))
}

@Composable
fun StatusDot(status: ItemStatus, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(statusColor(status))
    )
}

@Composable
fun TagPill(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (selected) Brand else BrandSoft
    val fg = if (selected) Color.White else Brand
    val base = Modifier
        .clip(RoundedCornerShape(999.dp))
        .background(bg)
        .border(1.dp, Brand.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
        .padding(horizontal = 9.dp, vertical = 3.dp)
    val mod = if (onClick != null) base.clickable { onClick() } else base
    Text(text = text, color = fg, fontSize = 12.sp, modifier = mod)
}

@Composable
fun MiniBar(label: String, fraction: Float, valueText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Text3, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFFECEEF0))
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brand)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(valueText, color = Text1, fontSize = 12.sp)
    }
}

@Composable
fun DividerLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LineColor)
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Text1,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}