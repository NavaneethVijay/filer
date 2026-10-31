package com.navaneeth.filer.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.navaneeth.filer.data.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val publishedAt: String
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val currentVersion: String, val checkedAt: Long) : UpdateStatus
    data class Available(val release: AppRelease) : UpdateStatus
    data class Downloading(
        val release: AppRelease,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateStatus
    data class ReadyToInstall(val release: AppRelease, val apkFile: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

class UpdateManager private constructor(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val isDownloading = AtomicBoolean(false)

    fun currentVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /**
     * Checks on app launch if enough time (6 hours) has elapsed since the last check.
     */
    fun checkOnLaunch(scope: CoroutineScope) {
        if (!prefs.isAutoCheckUpdates) return
        val lastCheck = prefs.lastUpdateCheckTime
        val now = System.currentTimeMillis()
        if (now - lastCheck < TimeUnit.HOURS.toMillis(6) && _status.value !is UpdateStatus.Idle) return
        scope.launch { checkForUpdate(isManual = false) }
    }

    /**
     * Checks GitHub API for the latest release.
     */
    suspend fun checkForUpdate(isManual: Boolean = false): UpdateStatus = withContext(Dispatchers.IO) {
        if (isDownloading.get()) return@withContext _status.value
        _status.value = UpdateStatus.Checking

        val currentVer = currentVersion()
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Filer-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "GitHub API returned HTTP ${response.code}"
                    Log.w(TAG, msg)
                    val status = UpdateStatus.Failed(msg)
                    _status.value = status
                    return@withContext status
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").trim()
                val rawVersion = tagName.removePrefix("v").removePrefix("V").trim()
                val title = json.optString("name", "Filer $tagName")
                val releaseNotes = json.optString("body", "")
                val publishedAt = json.optString("published_at", "")

                prefs.lastUpdateCheckTime = System.currentTimeMillis()

                if (!isNewerVersion(rawVersion, currentVer)) {
                    val status = UpdateStatus.UpToDate(currentVer, prefs.lastUpdateCheckTime)
                    _status.value = status
                    return@withContext status
                }

                // Match appropriate APK asset for this device's ABI
                val assets = json.optJSONArray("assets")
                val matchedAsset = pickAssetForDevice(assets)

                if (matchedAsset == null) {
                    val msg = "No compatible APK asset found in release $tagName"
                    Log.w(TAG, msg)
                    val status = UpdateStatus.Failed(msg)
                    _status.value = status
                    return@withContext status
                }

                val release = AppRelease(
                    tagName = tagName,
                    versionName = rawVersion,
                    title = title,
                    releaseNotes = releaseNotes,
                    apkUrl = matchedAsset.downloadUrl,
                    apkFileName = matchedAsset.fileName,
                    apkSizeBytes = matchedAsset.sizeBytes,
                    publishedAt = publishedAt
                )

                // Check if already downloaded
                val cachedApk = File(updatesDir(), release.apkFileName)
                val status = if (cachedApk.exists() && cachedApk.length() == release.apkSizeBytes && release.apkSizeBytes > 0) {
                    UpdateStatus.ReadyToInstall(release, cachedApk)
                } else {
                    UpdateStatus.Available(release)
                }
                _status.value = status
                return@withContext status
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            val status = UpdateStatus.Failed(e.message ?: "Network error")
            _status.value = status
            return@withContext status
        }
    }

    /**
     * Downloads the APK file to cacheDir/updates and notifies state with progress.
     */
    suspend fun downloadUpdate(release: AppRelease): Boolean = withContext(Dispatchers.IO) {
        if (!isDownloading.compareAndSet(false, true)) return@withContext false

        val dir = updatesDir()
        val destFile = File(dir, release.apkFileName)
        val partFile = File(dir, "${release.apkFileName}.part")

        // Already complete
        if (destFile.exists() && destFile.length() == release.apkSizeBytes && release.apkSizeBytes > 0) {
            isDownloading.set(false)
            _status.value = UpdateStatus.ReadyToInstall(release, destFile)
            return@withContext true
        }

        try {
            _status.value = UpdateStatus.Downloading(release, 0f, 0L, release.apkSizeBytes)

            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "Filer-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalLength = if (body.contentLength() > 0) body.contentLength() else release.apkSizeBytes
                var downloaded = 0L

                partFile.parentFile?.mkdirs()
                if (partFile.exists()) partFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var lastProgressUpdate = System.currentTimeMillis()

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 100 || downloaded == totalLength) {
                                lastProgressUpdate = now
                                val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                                _status.value = UpdateStatus.Downloading(release, progress, downloaded, totalLength)
                            }
                        }
                        output.flush()
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    throw IllegalStateException("Failed to move completed APK into place")
                }

                // Clean up any older APK files in directory
                dir.listFiles()?.filter { it.name != release.apkFileName }?.forEach { it.delete() }

                _status.value = UpdateStatus.ReadyToInstall(release, destFile)
                isDownloading.set(false)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            if (partFile.exists()) partFile.delete()
            isDownloading.set(false)
            _status.value = UpdateStatus.Failed(e.message ?: "Download failed")
            return@withContext false
        }
    }

    /**
     * Prompts the system package installer to install the downloaded APK.
     */
    fun installUpdate(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        // Check if unknown app installs are permitted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch package installer", e)
            false
        }
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private data class AssetCandidate(val fileName: String, val downloadUrl: String, val sizeBytes: Long)

    private fun pickAssetForDevice(assets: org.json.JSONArray?): AssetCandidate? {
        if (assets == null || assets.length() == 0) return null

        val candidates = mutableListOf<AssetCandidate>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            val size = obj.optLong("size", 0L)
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                candidates.add(AssetCandidate(name, url, size))
            }
        }

        if (candidates.isEmpty()) return null

        // Match device's supported ABIs in order
        for (abi in Build.SUPPORTED_ABIS) {
            val match = candidates.firstOrNull { it.fileName.contains(abi, ignoreCase = true) }
            if (match != null) return match
        }

        // Fallback to any arm64-v8a if supported
        val defaultArm = candidates.firstOrNull { it.fileName.contains("arm64-v8a", ignoreCase = true) }
        if (defaultArm != null) return defaultArm

        return candidates.firstOrNull()
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/NavaneethVijay/filer/releases/latest"

        @Volatile
        private var INSTANCE: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Compares semantic versions (e.g. "1.0.4" vs "1.0.3").
         * Returns true if [latest] is strictly newer than [current].
         */
        fun isNewerVersion(latest: String, current: String): Boolean {
            if (latest.isBlank() || current.isBlank()) return false
            val lParts = latest.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
            val cParts = current.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(lParts.size, cParts.size)

            for (i in 0 until maxLen) {
                val l = lParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }
    }
}
