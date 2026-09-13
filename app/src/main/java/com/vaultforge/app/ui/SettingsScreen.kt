package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.sync.SyncEngine
import com.vaultforge.app.sync.SyncPhase
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = VaultApp.store
    val settings by store.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    var sampleInput by remember { mutableStateOf(fmtSec(settings.sampleSec)) }
    Column(
        Modifier
            .fillMaxSize()
            .background(VaultBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Text1)
            }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Text1)
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("显示")
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(14.dp)
        ) {
            Text("指标图表样式", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Text1)
            Spacer(Modifier.height(2.dp))
            Text("SSH 详情页中 CPU / 内存等指标的呈现方式", fontSize = 11.sp, color = Text3)
            Spacer(Modifier.height(10.dp))
            StyleOption(
                title = "环形样式",
                desc = "环形图 + 进度条（默认）",
                selected = settings.chartStyle != "plot",
            ) { store.updateSettings(settings.copy(chartStyle = "ring")) }
            Spacer(Modifier.height(8.dp))
            StyleOption(
                title = "坐标图样式",
                desc = "折线图展示占用趋势（当前每 " + fmtSec(settings.sampleSec) + " 秒采样）",
                selected = settings.chartStyle == "plot",
            ) { store.updateSettings(settings.copy(chartStyle = "plot")) }
            Spacer(Modifier.height(12.dp))
            Text("坐标图采样间隔", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Text1)
            Spacer(Modifier.height(2.dp))
            Text("折线图每隔多久采集一次数据（可低至 0.1 秒，上限 600 秒）", fontSize = 11.sp, color = Text3)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.1f, 1f, 5f, 10f, 30f).forEach { sec ->
                    TagPill(
                        text = sec.toString() + "秒",
                        selected = settings.sampleSec == sec,
                        onClick = {
                            sampleInput = fmtSec(sec)
                            store.updateSettings(settings.copy(sampleSec = sec))
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sampleInput,
                    onValueChange = { v -> sampleInput = v.filter { it.isDigit() || it == '.' }.take(6) },
                    label = { Text("自定义秒数") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brand)
                        .clickable {
                            val v = (sampleInput.toFloatOrNull() ?: settings.sampleSec).coerceIn(0.1f, 600f)
                            sampleInput = fmtSec(v)
                            store.updateSettings(settings.copy(sampleSec = v))
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("应用", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("云端同步")
        Spacer(Modifier.height(6.dp))
        CloudSyncSection()

        Spacer(Modifier.height(18.dp))
        SectionTitle("本地 API")
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(14.dp)
        ) {
            Text("监听 127.0.0.1:" + settings.port, fontSize = 13.sp, color = Text2)
            Spacer(Modifier.height(8.dp))
            Text(
                text = settings.token,
                fontSize = 12.sp,
                color = Brand,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandSoft)
                    .clickable { runCatching { clipboard.setText(AnnotatedString(settings.token)) } }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("复制 Token", color = Brand, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("关于")
        Spacer(Modifier.height(6.dp))
        Text("秘钥仓 VaultForge v0.2.0", fontSize = 13.sp, color = Text2)
        Text("本地密钥管理 · SSH / Docker 运维小工具", fontSize = 11.sp, color = Text3)

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StyleOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrandSoft else Color.Transparent)
            .border(1.dp, if (selected) Brand else LineColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) Brand else Color.Transparent)
                .border(2.dp, if (selected) Brand else LineColor, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Text1)
            Text(desc, fontSize = 11.sp, color = Text3)
        }
    }
}

@Composable
private fun CloudSyncSection() {
    val store = VaultApp.store
    val settings by store.settings.collectAsState()
    val syncState by SyncEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

    var urlInput by remember(settings.cloudUrl) { mutableStateOf(settings.cloudUrl) }
    var userInput by remember(settings.cloudUser) { mutableStateOf(settings.cloudUser) }
    var passInput by remember { mutableStateOf("") }

    val busy = syncState.phase == SyncPhase.RUNNING

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(14.dp)
    ) {
        Text("把本地条目同步到云端（VaultForge Server），多设备共享同一份数据。", fontSize = 11.sp, color = Text3)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("服务器地址") },
            placeholder = { Text("https://vf.bdshjgg.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
        )

        if (settings.cloudToken.isBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                label = { Text("账号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passInput,
                onValueChange = { passInput = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudAction(
                    text = "登录",
                    enabled = !busy && urlInput.isNotBlank() && userInput.isNotBlank() && passInput.isNotBlank(),
                ) { scope.launch { SyncEngine.login(store, urlInput, userInput, passInput) } }
                CloudAction(
                    text = "注册",
                    subtle = true,
                    enabled = !busy && urlInput.isNotBlank() && userInput.isNotBlank() && passInput.isNotBlank(),
                ) { scope.launch { SyncEngine.register(store, urlInput, userInput, passInput) } }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text("已登录：" + settings.cloudUser, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Text1)
            Spacer(Modifier.height(2.dp))
            Text(
                "上次同步：" + if (settings.lastSyncAt > 0) friendlyTime(settings.lastSyncAt) else "尚未同步",
                fontSize = 11.sp,
                color = Text3,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudAction(text = "立即同步", enabled = !busy) {
                    scope.launch { SyncEngine.syncNow(store) }
                }
                CloudAction(text = "退出账号", subtle = true, enabled = !busy) {
                    scope.launch { SyncEngine.logout(store) }
                }
            }
        }

        if (syncState.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                syncState.message,
                fontSize = 11.sp,
                color = when (syncState.phase) {
                    SyncPhase.ERROR -> StatusDown
                    SyncPhase.SUCCESS -> StatusUp
                    else -> Text2
                },
            )
        }
    }
}

@Composable
private fun CloudAction(
    text: String,
    enabled: Boolean = true,
    subtle: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> LineColor
        subtle -> BrandSoft
        else -> Brand
    }
    val fg = when {
        !enabled -> Text3
        subtle -> Brand
        else -> Color.White
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = fg, fontSize = 13.sp)
    }
}
