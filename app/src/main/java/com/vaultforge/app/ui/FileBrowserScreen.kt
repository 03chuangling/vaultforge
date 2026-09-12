package com.vaultforge.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultforge.app.VaultApp
import com.vaultforge.app.file.RemoteBrowser
import com.vaultforge.app.file.RemoteFile
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(itemId: String, onBack: () -> Unit) {
    val store = VaultApp.store
    val items by store.items.collectAsState()
    val item = items.firstOrNull { it.id == itemId }
    if (item == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val kind = RemoteBrowser.supportKind(item)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(itemId) { RemoteBrowser.initialPath(item) }
    var path by remember(itemId) { mutableStateOf(initial) }
    var files by remember(itemId) { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember(itemId) { mutableStateOf("") }
    var loading by remember(itemId) { mutableStateOf(false) }
    var pendingDelete by remember(itemId) { mutableStateOf<RemoteFile?>(null) }

    fun load(p: String) {
        scope.launch {
            loading = true
            error = ""
            runCatching { RemoteBrowser.list(item, p) }
                .onSuccess {
                    files = it
                    path = p
                }
                .onFailure { error = it.message ?: "加载失败" }
            loading = false
        }
    }

    fun download(f: RemoteFile) {
        scope.launch {
            loading = true
            val destDir = context.getExternalFilesDir("downloads") ?: context.filesDir
            val dest = File(destDir, f.name)
            val res = runCatching { RemoteBrowser.download(item, f.path, dest) }
            loading = false
            res.onSuccess {
                Toast.makeText(context, "已保存：" + dest.absolutePath, Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "下载失败：" + (it.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun doDelete(f: RemoteFile) {
        scope.launch {
            loading = true
            val res = runCatching { RemoteBrowser.delete(item, f) }
            loading = false
            res.onSuccess {
                Toast.makeText(context, "已删除：" + f.name, Toast.LENGTH_SHORT).show()
                load(path)
            }.onFailure {
                error = it.message ?: "删除失败"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "upload.bin"
            scope.launch {
                loading = true
                val res = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        RemoteBrowser.upload(item, path, name, ins)
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                loading = false
                res.onSuccess {
                    Toast.makeText(context, "已上传：" + name, Toast.LENGTH_LONG).show()
                    load(path)
                }.onFailure {
                    Toast.makeText(context, "上传失败：" + (it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(itemId) {
        if (kind.isNotEmpty()) load(initial)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VaultBg)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Text1)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (kind) {
                        "webdav" -> "WebDAV 文件管理"
                        "sftp" -> "SFTP 文件管理 · " + item.username.ifBlank { "root" } + "@" + item.host
                        else -> "该协议暂不支持"
                    },
                    fontSize = 11.sp,
                    color = Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (kind.isNotEmpty()) {
                SmallChip("上传") { picker.launch("*/*") }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (kind.isNotEmpty()) {
                SmallChip("⬆ 上级") { load(RemoteBrowser.parentPath(item, path)) }
                Spacer(Modifier.width(6.dp))
                SmallChip("刷新") { load(path) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (kind.isNotEmpty()) path else "",
                fontSize = 11.sp,
                color = Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                Text("同步中…", fontSize = 11.sp, color = Brand)
            }
        }

        when {
            kind.isEmpty() -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text("🗂", fontSize = 38.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("当前协议暂不支持在线文件浏览", fontSize = 14.sp, color = Text1, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("已支持：WebDAV、SFTP（FTP / S3 后续版本加入）", fontSize = 12.sp, color = Text3)
                }
            }
            loading && files == null -> {
                CenterHint("正在读取目录…")
            }
            files == null && error.isNotBlank() -> {
                CenterHint(error)
            }
            else -> {
                val list = files ?: emptyList()
                if (list.isEmpty() && error.isBlank()) {
                    CenterHint("空目录（点右上角可上传文件）")
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 30.dp),
                    ) {
                        if (error.isNotBlank()) {
                            item {
                                Text(
                                    "错误：" + error,
                                    color = StatusDown,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(list, key = { it.path }) { f ->
                            FileRow(
                                f = f,
                                onOpen = { if (f.isDir) load(f.path) },
                                onDownload = { download(f) },
                                onDelete = { pendingDelete = f },
                            )
                        }
                    }
                }
            }
        }
    }

    val pd = pendingDelete
    if (pd != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「" + pd.name + "」？") },
            text = { Text(if (pd.isDir) "将删除该文件夹（通常需为空文件夹）" else "删除后不可恢复") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    doDelete(pd)
                }) { Text("删除", color = StatusDown) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CenterHint(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(text, fontSize = 13.sp, color = Text3)
    }
}

@Composable
private fun FileRow(
    f: RemoteFile,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(enabled = f.isDir) { onOpen() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (f.isDir) "📁" else "📄", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                f.name,
                fontSize = 13.sp,
                color = Text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fileSub(f), fontSize = 11.sp, color = Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!f.isDir) {
            SmallChip("下载") { onDownload() }
            Spacer(Modifier.width(6.dp))
        }
        SmallChip("删除") { onDelete() }
    }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
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

private fun fileSub(f: RemoteFile): String {
    if (f.isDir) return "文件夹"
    val sz = when {
        f.size < 0L -> ""
        f.size < 1024 -> f.size.toString() + " B"
        f.size < 1024 * 1024 -> String.format("%.1f KB", f.size / 1024.0)
        else -> String.format("%.1f MB", f.size / 1048576.0)
    }
    val tm = if (f.modifiedAt > 0L) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.modifiedAt))
    } else ""
    return listOf(sz, tm).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        } else null
    }
}.getOrNull()