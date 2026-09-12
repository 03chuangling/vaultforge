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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.model.VaultItem

@Composable
fun AddItemScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val store = VaultApp.store
    var type by remember { mutableStateOf("file") }
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("webdav") }
    var address by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var authMethod by remember { mutableStateOf("password") }
    var secret by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var demoCode by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }

    fun save() {
        val tags = parseTags(tagsText)
        val item = when (type) {
            "file" -> VaultItem(
                id = store.newId(),
                type = "file",
                name = name.ifBlank { "文件密钥" },
                tags = tags,
                protocol = protocol.trim(),
                address = address.trim(),
            )
            "ssh" -> VaultItem(
                id = store.newId(),
                type = "ssh",
                name = name.ifBlank { if (host.isBlank()) "SSH主机" else host.trim() },
                tags = tags,
                host = host.trim(),
                port = portText.toIntOrNull() ?: 22,
                username = username.trim(),
                authMethod = authMethod,
                secret = secret,
                privateKey = privateKey,
            )
            else -> VaultItem(
                id = store.newId(),
                type = "api",
                name = name.ifBlank { "API密钥" },
                tags = tags,
                endpoint = endpoint.trim(),
                demoCode = demoCode,
            )
        }
        store.upsert(item)
        onSaved()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Text1)
            }
            Text("添加密钥", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Text1)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill("文件协议", selected = type == "file") { type = "file" }
            TagPill("SSH", selected = type == "ssh") { type = "ssh" }
            TagPill("API", selected = type == "api") { type = "api" }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可留空）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        when (type) {
            "file" -> {
                OutlinedTextField(
                    value = protocol,
                    onValueChange = { protocol = it },
                    label = { Text("协议（webdav / sftp / ftp / s3）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址（https://dav.example.com/backup 或 host:22）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("密码 / 凭据（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            "ssh" -> {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机（IP 或域名）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("端口") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagPill("密码认证", selected = authMethod == "password") { authMethod = "password" }
                    TagPill("密钥认证", selected = authMethod == "key") { authMethod = "key" }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(if (authMethod == "key") "私钥口令（可留空）" else "密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (authMethod == "key") {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("私钥内容（粘贴 -----BEGIN ...）") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        maxLines = 8,
                    )
                }
            }
            else -> {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("API 调用地址（endpoint）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = demoCode,
                    onValueChange = { demoCode = it },
                    label = { Text("官方演示代码（检测时执行）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    maxLines = 8,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "支持 curl / Python / JS 示例片段：检测时会提取其中真实请求并实际调用，验证 API 是否可用",
                    fontSize = 11.sp,
                    color = Text3,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = tagsText,
            onValueChange = { tagsText = it },
            label = { Text("标签（逗号分隔）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Brand)
                .clickable { save() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("保存", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(30.dp))
    }
}