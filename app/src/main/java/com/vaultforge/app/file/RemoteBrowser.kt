package com.vaultforge.app.file

import android.util.Xml
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.vaultforge.app.model.VaultItem
import com.vaultforge.app.probe.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RemoteFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = -1L,
    val modifiedAt: Long = 0L,
)

/**
 * 文件协议的在线文件管理（WebDAV / SFTP）。
 * FTP / S3 等协议暂不支持在线浏览。
 */
object RemoteBrowser {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 支持在线浏览的协议：webdav / sftp；其余返回空串 */
    fun supportKind(item: VaultItem): String = when {
        item.address.startsWith("http://") || item.address.startsWith("https://") -> "webdav"
        item.protocol.equals("sftp", true) || item.protocol.equals("ssh", true) -> "sftp"
        else -> ""
    }

    fun initialPath(item: VaultItem): String =
        if (supportKind(item) == "webdav") item.address.trimEnd('/') else "/"

    fun parentPath(item: VaultItem, path: String): String {
        val init = initialPath(item)
        if (path == init) return init
        return if (supportKind(item) == "webdav") {
            val p = path.substringBeforeLast('/')
            if (p.length < init.length) init else p
        } else {
            val p = path.trimEnd('/').substringBeforeLast('/')
            if (p.isBlank()) "/" else p
        }
    }

    suspend fun list(item: VaultItem, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        when (supportKind(item)) {
            "sftp" -> sftpList(item, path)
            "webdav" -> webdavList(item, path)
            else -> throw IllegalStateException("当前协议暂不支持在线文件浏览")
        }
    }

    suspend fun download(item: VaultItem, remotePath: String, dest: File): Unit = withContext(Dispatchers.IO) {
        when (supportKind(item)) {
            "sftp" -> sftpDownload(item, remotePath, dest)
            "webdav" -> webdavDownload(item, remotePath, dest)
            else -> throw IllegalStateException("当前协议暂不支持在线文件浏览")
        }
    }

    suspend fun upload(item: VaultItem, dirPath: String, name: String, source: InputStream): Unit =
        withContext(Dispatchers.IO) {
            when (supportKind(item)) {
                "sftp" -> sftpUpload(item, joinPath(dirPath, name), source)
                "webdav" -> webdavUpload(item, dirPath, name, source)
                else -> throw IllegalStateException("当前协议暂不支持在线文件浏览")
            }
        }

    suspend fun delete(item: VaultItem, file: RemoteFile): Unit = withContext(Dispatchers.IO) {
        when (supportKind(item)) {
            "sftp" -> sftpDelete(item, file)
            "webdav" -> webdavDelete(item, file.path)
            else -> throw IllegalStateException("当前协议暂不支持在线文件浏览")
        }
    }

    private fun joinPath(dir: String, name: String): String =
        (if (dir.endsWith("/")) dir else dir + "/") + name

    // ================= SFTP =================

    private fun sftpOpen(item: VaultItem): Pair<Session, ChannelSftp> {
        val session = SshClient.openSession(item)
        val ch = session.openChannel("sftp") as ChannelSftp
        ch.connect(8000)
        return session to ch
    }

    private fun sftpList(item: VaultItem, path: String): List<RemoteFile> {
        val (session, ch) = sftpOpen(item)
        try {
            val base = if (path.isBlank()) "." else path
            val raw = ch.ls(base)
            return raw.mapNotNull { e ->
                val n = e.filename
                if (n == "." || n == "..") return@mapNotNull null
                val attrs = e.attrs
                RemoteFile(
                    name = n,
                    path = joinPath(base, n),
                    isDir = attrs?.isDir == true,
                    size = attrs?.size ?: -1L,
                    modifiedAt = ((attrs?.getMTime() ?: 0).toLong()) * 1000L,
                )
            }
        } finally {
            runCatching { ch.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun sftpDownload(item: VaultItem, remotePath: String, dest: File) {
        val (session, ch) = sftpOpen(item)
        try {
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> ch.get(remotePath, out) }
        } finally {
            runCatching { ch.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun sftpUpload(item: VaultItem, remotePath: String, source: InputStream) {
        val (session, ch) = sftpOpen(item)
        try {
            ch.put(source, remotePath)
        } finally {
            runCatching { source.close() }
            runCatching { ch.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun sftpDelete(item: VaultItem, file: RemoteFile) {
        val (session, ch) = sftpOpen(item)
        try {
            if (file.isDir) ch.rmdir(file.path) else ch.rm(file.path)
        } finally {
            runCatching { ch.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    // ================= WebDAV =================

    private val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
            "</d:prop></d:propfind>"

    private fun davRequest(item: VaultItem, url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (item.username.isNotBlank()) {
            b.header("Authorization", Credentials.basic(item.username, item.secret))
        }
        return b
    }

    private fun webdavList(item: VaultItem, path: String): List<RemoteFile> {
        val url = path.ifBlank { item.address.trimEnd('/') }
        val req = davRequest(item, url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
        http.newCall(req.build()).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw IllegalStateException("认证失败（用户名/密码错误）")
            if (resp.code !in 200..299) throw IllegalStateException("PROPFIND 失败：HTTP " + resp.code)
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("空响应")
            return parsePropfind(stream, url)
        }
    }

    private fun parsePropfind(stream: InputStream, requestUrl: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, "utf-8")
        var inResponse = false
        var href: String? = null
        var length = -1L
        var modified = 0L
        var isCollection = false
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':').lowercase()) {
                    "response" -> {
                        inResponse = true; href = null; length = -1L; modified = 0L; isCollection = false
                    }
                    "href" -> if (inResponse && href == null) href = runCatching { parser.nextText().trim() }.getOrNull()
                    "getcontentlength" -> length = runCatching { parser.nextText().trim().toLongOrNull() ?: -1L }.getOrDefault(-1L)
                    "getlastmodified" -> modified = runCatching { parseHttpDate(parser.nextText().trim()) }.getOrDefault(0L)
                    "collection" -> isCollection = true
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name.substringAfter(':').lowercase() == "response") {
                if (inResponse) {
                    inResponse = false
                    val h = href
                    if (h != null) {
                        val entryUrl = resolveUrl(requestUrl, h)
                        if (entryUrl != null && entryUrl.trimEnd('/') != requestUrl.trimEnd('/')) {
                            val decoded = runCatching { URLDecoder.decode(entryUrl, "UTF-8") }.getOrDefault(entryUrl)
                            val name = decoded.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) {
                                out.add(RemoteFile(name, entryUrl, isCollection, length, modified))
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun resolveUrl(base: String, href: String): String? {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val b = base.toHttpUrlOrNull() ?: return null
        return runCatching { b.resolve(href)?.toString() }.getOrNull()
    }

    private fun parseHttpDate(s: String): Long {
        if (s.isBlank()) return 0L
        return runCatching {
            val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            df.parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun webdavDownload(item: VaultItem, remoteUrl: String, dest: File) {
        val req = davRequest(item, remoteUrl).get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("下载失败：HTTP " + resp.code)
            val body = resp.body ?: throw IllegalStateException("空响应")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    private fun webdavUpload(item: VaultItem, dirPath: String, name: String, source: InputStream) {
        val base = dirPath.ifBlank { item.address.trimEnd('/') }
        val burl = base.toHttpUrlOrNull() ?: throw IllegalStateException("地址无效")
        val target = burl.newBuilder().addPathSegment(name).build().toString()
        val reqBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                source.use { ins -> sink.writeAll(ins.source()) }
            }
        }
        http.newCall(davRequest(item, target).put(reqBody).build()).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("上传失败：HTTP " + resp.code)
        }
    }

    private fun webdavDelete(item: VaultItem, url: String) {
        http.newCall(davRequest(item, url).delete().build()).execute().use { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("删除失败：HTTP " + resp.code)
        }
    }
}