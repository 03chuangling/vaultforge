package com.vaultforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.probe.SshClient
import kotlinx.coroutines.launch

private val TermBg = Color(0xFF0F1518)
private val TermText = Color(0xFFD5E2E8)
private val TermMuted = Color(0xFF6E818B)
private val TermAccent = Color(0xFF7FD8A0)
private val TermInputBg = Color(0xFF1A2429)
private val TermCmd = Color(0xFF8FC7E8)

private data class TermLine(val text: String, val kind: Int) // 0=输出 1=命令 2=提示

@Composable
fun TerminalScreen(itemId: String, container: String?, onBack: () -> Unit) {
    val store = VaultApp.store
    val items by store.items.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val quickCmds = if (container == null) {
        listOf("docker ps", "free -m", "df -h", "uptime")
    } else {
        listOf("ls -la", "env", "cat /etc/os-release")
    }

    LaunchedEffect(itemId, container) {
        lines.clear()
        if (container == null) {
            lines.add(TermLine("已就绪 · 服务器终端", 2))
            lines.add(TermLine((item.username.ifBlank { "root" }) + "@" + item.host + ":" + item.port, 2))
            lines.add(TermLine("输入命令执行，或使用下方快捷指令。", 2))
        } else {
            lines.add(TermLine("已就绪 · 容器终端", 2))
            lines.add(TermLine("docker exec · " + container + " @ " + item.host, 2))
            lines.add(TermLine("命令将在容器内执行。", 2))
        }
    }

    fun sendCmd(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || running) return
        lines.add(TermLine((if (container == null) "$ " else "[" + container + "] $ ") + cmd, 1))
        input = ""
        running = true
        scope.launch {
            val out = runCatching {
                if (container == null) SshClient.runCommand(item, cmd)
                else SshClient.runInContainer(item, container, cmd)
            }.getOrElse { e -> "[错误] " + (e.message ?: "执行失败") }
            val text = out.trimEnd()
            lines.add(TermLine(text.ifBlank { "(无输出)" }, 0))
            while (lines.size > 600) lines.removeAt(0)
            running = false
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TermBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (container == null) "终端 · " + item.name else "容器终端 · " + container,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    if (container == null) "SSH 命令模式" else "docker exec 模式",
                    color = TermMuted,
                    fontSize = 11.sp,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(TermInputBg)
                    .clickable {
                        lines.clear()
                        lines.add(TermLine("已清空。", 2))
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("清空", color = TermAccent, fontSize = 12.sp)
            }
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            state = listState,
        ) {
            items(lines.size) { i ->
                val l = lines[i]
                Text(
                    text = l.text,
                    color = when (l.kind) {
                        1 -> TermCmd
                        2 -> TermMuted
                        else -> TermText
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quickCmds.size) { i ->
                val c = quickCmds[i]
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(TermInputBg)
                        .clickable { sendCmd(c) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(c, color = TermAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TermInputBg)
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                if (input.isEmpty()) {
                    Text("输入命令…", color = TermMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TermText, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(TermAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { sendCmd(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (running) TermMuted else TermAccent)
                    .clickable(enabled = !running) { sendCmd(input) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(
                    if (running) "…" else "运行",
                    color = TermBg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}