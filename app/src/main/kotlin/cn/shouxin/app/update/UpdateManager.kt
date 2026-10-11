package cn.shouxin.app.update

import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import cn.shouxin.app.BuildConfig
import cn.shouxin.app.data.*
import cn.shouxin.app.graph
import cn.shouxin.core.Versions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val sha256: String?)
data class UpdateState(val busy: Boolean = false, val message: String = "点击检查更新，从 GitHub 获取公开版本。", val release: ReleaseInfo? = null, val readyFile: String? = null)
class UpdateManager(private val context: Context, private val db: AppDatabase) {
    val state = MutableStateFlow(UpdateState())
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val downloadMutex = Mutex()
    suspend fun check(repository: String) = withContext(Dispatchers.IO) {
        require(Versions.validRepository(repository))
        if (BuildConfig.DEBUG) { state.value = UpdateState(message = "当前是独立开发版。正式版更新使用同一包名和签名，开发版不能覆盖正式版。"); return@withContext }
        state.value = UpdateState(busy = true, message = "正在检查新版…")
        var connection: HttpURLConnection? = null
        try {
            connection = URL("https://api.github.com/repos/$repository/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Shouxin/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode == 404) { state.value = UpdateState(message = "仓库还没有公开的正式版本。当前版本仍可继续使用。"); return@withContext }
            check(connection.responseCode == 200)
            val body = connection.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
            val json = JSONObject(body); val tag = json.getString("tag_name")
            if (json.optBoolean("prerelease") || !Versions.newer(tag, BuildConfig.VERSION_NAME)) {
                state.value = UpdateState(message = "当前已是最新正式版本 ${BuildConfig.VERSION_NAME}"); return@withContext
            }
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") && !it.getString("name").contains("debug", ignoreCase = true) }
                ?: error("NO_APK")
            val url = asset.getString("browser_download_url")
            val uri = Uri.parse(url)
            check(uri.scheme == "https" && uri.host == "github.com" && uri.path?.startsWith("/$repository/releases/download/") == true)
            val page = json.getString("html_url")
            check(Uri.parse(page).host == "github.com" && Uri.parse(page).path?.startsWith("/$repository/releases/") == true)
            val digest = asset.optString("digest").removePrefix("sha256:").takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }
            state.value = UpdateState(message = "发现新版本 $tag，安装时会保留设置和记录。", release = ReleaseInfo(tag, json.optString("body").take(4000), url, page, digest))
        } catch (e: Exception) { if (e is CancellationException) throw e; state.value = UpdateState(message = "暂时无法检查更新，请检查网络后重试。监测与专注不受影响。") }
        finally { connection?.disconnect() }
    }
    suspend fun download(release: ReleaseInfo) = downloadMutex.withLock { withContext(Dispatchers.IO) {
        require(Uri.parse(release.apkUrl).scheme == "https" && Uri.parse(release.apkUrl).host == "github.com")
        val existingId = db.dao().meta("update_download_id")?.toLongOrNull()
        if (existingId != null) { recoverDownload(); if (state.value.busy || state.value.readyFile != null) return@withContext }
        val fileName = "shouxin-${release.version.removePrefix("v")}.apk"
        require(Regex("shouxin-[0-9]+\\.[0-9]+\\.[0-9]+\\.apk").matches(fileName))
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) check(file.delete())
        val request = DownloadManager.Request(Uri.parse(release.apkUrl)).setTitle("收心 ${release.version}")
            .setDescription("用户主动下载的新版安装包").setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = downloads.enqueue(request)
        db.dao().saveMeta(MetaRow("update_download_id", id.toString())); db.dao().saveMeta(MetaRow("update_file", file.absolutePath))
        db.dao().saveMeta(MetaRow("update_sha256", release.sha256 ?: ""))
        state.value = UpdateState(busy = true, message = "安装包下载中，可从系统通知查看进度。", release = release)
    } }
    suspend fun recoverDownload() = withContext(Dispatchers.IO) {
        val id = db.dao().meta("update_download_id")?.toLongOrNull() ?: return@withContext
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) { state.value = UpdateState(message = "下载记录已不可用，请重新检查更新。"); db.dao().saveMeta(MetaRow("update_download_id", "")); return@withContext }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = File(db.dao().meta("update_file") ?: return@withContext)
                    val expected = db.dao().meta("update_sha256").orEmpty()
                    if (verify(file, expected)) state.value = UpdateState(message = "安装包已校验。点击安装，通过 Android 系统确认覆盖更新。", readyFile = file.absolutePath)
                    else { state.value = UpdateState(message = "安装包的包名、版本或签名校验失败，已阻止安装。请重新检查更新。"); db.dao().saveMeta(MetaRow("update_download_id", "")) }
                }
                DownloadManager.STATUS_FAILED -> { state.value = UpdateState(message = "下载失败，请重新检查更新。当前数据不受影响。"); db.dao().saveMeta(MetaRow("update_download_id", "")) }
                else -> state.value = UpdateState(busy = true, message = "安装包下载中，可从系统通知查看进度。")
            }
        }
    }
    private fun verify(file: File, expectedHash: String): Boolean = runCatching {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile ?: return false
        check(file.canonicalFile.parentFile == root && file.isFile)
        if (expectedHash.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, ignoreCase = true))
        }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(archive.packageName == context.packageName && archive.longVersionCode > current.longVersionCode)
        val currentCertificates = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val newCertificates = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        check(!currentCertificates.isNullOrEmpty() && newCertificates == currentCertificates)
        true
    }.getOrDefault(false)
    fun install(): Intent? {
        val path = state.value.readyFile ?: return null
        val file = File(path)
        if (!verify(file, "")) { state.value = UpdateState(message = "安装文件已失效或无法验证，请重新检查更新。"); return null }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        context.graph.launch { try { context.graph.updates.recoverDownload() } finally { pending.finish() } }
    }
}
