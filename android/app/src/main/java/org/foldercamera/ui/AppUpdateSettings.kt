package org.foldercamera.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.foldercamera.BuildConfig
import org.foldercamera.R
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val REPO = "https://github.com/Aleks1496/folder-camera/releases/download"
private val updateClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
private val stableTag = Regex("folder-camera-v([0-9]+)\\.([0-9]+)\\.([0-9]+)")
private val previewTag = Regex("folder-camera-build-([0-9]+)")
private data class ApkUpdate(val tag: String, val url: String, val sha256: String, val order: Long)

private fun version(parts: List<String>): Long = parts.fold(0L) { total, part ->
    total * 10_000 + (part.toIntOrNull()?.takeIf { it in 0..9999 } ?: error("Invalid version"))
}

private fun checkUpdate(): ApkUpdate? {
    val request = Request.Builder()
        .url("https://api.github.com/repos/Aleks1496/folder-camera/releases?per_page=100")
        .header("Accept", "application/vnd.github+json").build()
    return updateClient.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "GitHub returned ${response.code}" }
        val releases = JSONArray(response.body!!.string())
        val current = if (BuildConfig.DEBUG) BuildConfig.UPDATE_BUILD.toLong()
            else version(BuildConfig.VERSION_NAME.split('.'))
        val name = if (BuildConfig.DEBUG) "folder-camera-test.apk" else "folder-camera-android.apk"
        (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease") != BuildConfig.DEBUG) return@mapNotNull null
            val tag = release.getString("tag_name")
            val match = (if (BuildConfig.DEBUG) previewTag else stableTag).matchEntire(tag) ?: return@mapNotNull null
            val order = if (BuildConfig.DEBUG) match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                else runCatching { version(match.groupValues.drop(1)) }.getOrNull() ?: return@mapNotNull null
            if (order <= current) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == name } ?: return@mapNotNull null
            val hash = asset.optString("digest").removePrefix("sha256:")
            val url = "$REPO/$tag/$name"
            if (!hash.matches(Regex("[0-9a-f]{64}")) || asset.optString("browser_download_url") != url) return@mapNotNull null
            ApkUpdate(tag, url, hash, order)
        }.maxByOrNull { it.order }
    }
}

private fun downloadUpdate(context: Context, update: ApkUpdate): File {
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val target = File(directory, "folder-camera.apk")
    val temp = File.createTempFile("download-", ".apk", directory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        updateClient.newCall(Request.Builder().url(update.url).build()).execute().use { response ->
            check(response.isSuccessful) { "APK download failed" }
            var size = 0L
            response.body!!.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        size += count
                        check(size <= 100L * 1024 * 1024) { "APK is too large" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "APK checksum mismatch" }
        check(temp.renameTo(target)) { "Could not save APK" }
        return target
    } finally {
        temp.delete()
    }
}

internal data class AppUpdateUi(val available: Boolean, val content: @Composable () -> Unit)

@Composable
internal fun rememberAppUpdate(): AppUpdateUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<ApkUpdate?>(null) }
    var status by remember { mutableIntStateOf(R.string.update_checking) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        busy = true
        candidate = null
        try {
            candidate = withContext(Dispatchers.IO) { checkUpdate() }
            status = if (candidate != null) R.string.update_available else R.string.update_none
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = R.string.update_check_failed
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    return AppUpdateUi(candidate != null) {
        Text(if (status == R.string.update_available) stringResource(status, candidate?.tag.orEmpty()) else stringResource(status))
        Button(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(stringResource(R.string.update_check)) }
        candidate?.let { update ->
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")))
                            status = R.string.update_allow_install
                        } else {
                            val apk = withContext(Dispatchers.IO) { downloadUpdate(context, update) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        status = R.string.update_install_failed
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.update_install)) }
        }
    }
}
