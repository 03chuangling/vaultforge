package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.bitwarden.BwEngine
import com.vaultforge.app.bitwarden.BwEntry
import com.vaultforge.app.bitwarden.BwPhase
import kotlinx.coroutines.launch

@Composable
fun BitwardenScreen(onBack: () -> Unit) {
    val store = VaultApp.store
    val settings by store.settings.collectAsState()
    val state by BwEngine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var urlInput by remember { mutableStateOf(settings.bwUrl) }
    var emailInput by remember { mutableStateOf(settings.bwEmail) }
    var passInput by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var shownIds by remember { mutableStateOf(setOf<String>()) }
    var importMsg by remember { mutableStateOf("") }

    val busy = state.phase == BwPhase.RUNNING

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
            Text("拉取密码库", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Text1)
        }

        Text(
            "连接自建 Vaultwarden 或官方 Bitwarden，拉取并解密密码条目，可一键导入到秘钥库。主密码仅用于本次拉取，不会保存。",
            fontSize = 11.sp,
            color = Text3,
        )
        Spacer(Modifier.height(10.dp))

        // ===== 表单 =====
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(14.dp)
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://vault.bitwarden.com 或自建地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text("邮箱") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passInput,
                onValueChange = { passInput = it },
                label = { Text("主密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        text = if (showPass) "隐藏" else "显示",
                        color = Brand,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { showPass = !showPass }
                            .padding(8.dp),
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (busy) LineColor else Brand)
                    .clickable(enabled = !busy) {
                        importMsg = ""
                        scope.launch {
                            val ok = BwEngine.fetch(store, urlInput, emailInput, passInput)
                            if (ok) passInput = ""
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "拉取中…" else "拉取密码库",
                    color = if (busy) Text3 else Color.White,
                    fontSize = 14.sp,
                )
            }
        }

        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                state.message,
                fontSize = 12.sp,
                color = when (state.phase) {
                    BwPhase.ERROR -> StatusDown
                    BwPhase.SUCCESS -> StatusUp
                    else -> Text2
                },
            )
        }

        // ===== 结果 =====
        val vault = state.vault
        if (state.phase == BwPhase.SUCCESS && vault != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("共 " + vault.entries.size + " 条", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Text1)
                    Text(
                        vault.email + (if (vault.name.isNotBlank()) " · " + vault.name else "") + " · " + vault.folders.size + " 个文件夹",
                        fontSize = 11.sp,
                        color = Text3,
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brand)
                        .clickable {
                            val n = BwEngine.importToStore(store, vault)
                            importMsg = "已导入 " + n + " 条到秘钥库（首页可见，再次导入将自动更新）"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("全部导入", color = Color.White, fontSize = 12.sp)
                }
            }
            if (importMsg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(importMsg, fontSize = 12.sp, color = StatusUp)
            }

            Spacer(Modifier.height(6.dp))
            SectionTitle("条目预览（" + vault.entries.size + "）")
            vault.entries.forEach { e ->
                BwEntryCard(
                    entry = e,
                    showPassword = shownIds.contains(e.id),
                    onTogglePassword = {
                        shownIds = if (shownIds.contains(e.id)) shownIds - e.id else shownIds + e.id
                    },
                    onCopyUsername = {
                        runCatching { clipboard.setText(AnnotatedString(e.username)) }
                    },
                    onCopyPassword = {
                        runCatching { clipboard.setText(AnnotatedString(e.password)) }
                    },
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun BwEntryCard(
    entry: BwEntry,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, LineColor.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
            .padding(12.dp)
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
                MiniAction("复制用户名") { onCopyUsername() }
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
                MiniAction(if (showPassword) "隐藏" else "显示") { onTogglePassword() }
                Spacer(Modifier.width(4.dp))
                MiniAction("复制密码") { onCopyPassword() }
            }
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
private fun MiniAction(label: String, onClick: () -> Unit) {
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