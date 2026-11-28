package uwu.realness.gitveu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import uwu.realness.gitveu.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class AppReleaseInfo(
    val currentVersion: String,
    val latestVersion: String,
    val tagName: String,
    val isPrerelease: Boolean,
    val downloadUrl: String,
    val htmlUrl: String,
    val assetName: String,
    val apkSizeBytes: Long,
    val releaseNotes: String
) {
    val formattedSize: String
        get() = when {
            apkSizeBytes <= 0L -> ""
            apkSizeBytes < 1024L -> "$apkSizeBytes B"
            apkSizeBytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", apkSizeBytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", apkSizeBytes / (1024.0 * 1024.0))
        }
}

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(val currentVersion: String) : UpdateCheckState()
    data class Available(val release: AppReleaseInfo) : UpdateCheckState()
    data class Error(val message: String) : UpdateCheckState()
}

sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState()
    data class ReadyToInstall(val apkFile: File) : UpdateDownloadState()
    data class Error(val message: String) : UpdateDownloadState()
}

/**
 * Self-contained GitHub Releases updater inspired by keqdroid's update service:
 * - Supports Stable and Pre-release channels
 * - 30-minute auto-check throttle & "skip version" persistence
 * - Direct APK download with live progress + FileProvider install handoff
 */
object AppUpdater {

    private const val OWNER = "Gitveu"
    private const val REPO = "Realness"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"
    private const val USER_AGENT = "realness-updater/${BuildConfig.VERSION_NAME}"
    private const val PREFS_NAME = "realness_prefs"
    private const val KEY_INCLUDE_PRERELEASE = "update_include_prerelease"
    private const val KEY_SKIP_VERSION = "update_skip_version"
    private const val MIN_AUTO_CHECK_GAP_MS = 30 * 60 * 1000L

    private val _checkState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val checkState: StateFlow<UpdateCheckState> = _checkState.asStateFlow()

    private val _downloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = _downloadState.asStateFlow()

    @Volatile private var lastAutoCheckMs: Long = 0L
    @Volatile private var cachedRelease: AppReleaseInfo? = null

    fun includePrereleases(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INCLUDE_PRERELEASE, false)

    fun setIncludePrereleases(context: Context, include: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INCLUDE_PRERELEASE, include)
            .apply()
        lastAutoCheckMs = 0L
    }

    fun getSkippedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SKIP_VERSION, null)

    fun skipVersion(context: Context, versionTag: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIP_VERSION, normalizeVersion(versionTag))
            .apply()
    }

    fun isSkipped(context: Context, versionTag: String): Boolean {
        val skipped = getSkippedVersion(context) ?: return false
        return skipped.equals(normalizeVersion(versionTag), ignoreCase = true)
    }

    fun resetDownloadState() {
        _downloadState.value = UpdateDownloadState.Idle
    }

    suspend fun checkForUpdate(context: Context, force: Boolean = false): AppReleaseInfo? {
        val now = System.currentTimeMillis()
        if (!force && lastAutoCheckMs > 0L && now - lastAutoCheckMs < MIN_AUTO_CHECK_GAP_MS) {
            return cachedRelease
        }

        _checkState.value = UpdateCheckState.Checking
        val allowPre = includePrereleases(context)
        val current = BuildConfig.VERSION_NAME

        return withContext(Dispatchers.IO) {
            try {
                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = "HTTP $code"
                    _checkState.value = UpdateCheckState.Error(err)
                    conn.disconnect()
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val releases = JSONArray(body)
                var bestRelease: AppReleaseInfo? = null

                for (i in 0 until releases.length()) {
                    val obj = releases.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    val isPre = obj.optBoolean("prerelease", false)
                    if (isPre && !allowPre) continue

                    val tag = obj.optString("tag_name", "").trim()
                    if (tag.isBlank()) continue
                    val normTag = normalizeVersion(tag)
                    if (!isNewerVersion(normTag, current)) continue

                    // Pick main realness APK (exclude tiny)
                    val assets = obj.optJSONArray("assets") ?: continue
                    var apkUrl: String? = null
                    var apkName = ""
                    var apkSize = 0L
                    for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        val name = asset.optString("name", "")
                        val lower = name.lowercase(Locale.US)
                        if (lower.endsWith(".apk") && lower.startsWith("realness") && !lower.contains("tiny")) {
                            apkUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                    if (apkUrl.isNullOrBlank()) continue

                    val candidate = AppReleaseInfo(
                        currentVersion = current,
                        latestVersion = normTag,
                        tagName = tag,
                        isPrerelease = isPre,
                        downloadUrl = apkUrl,
                        htmlUrl = obj.optString("html_url", "https://github.com/$OWNER/$REPO/releases"),
                        assetName = apkName,
                        apkSizeBytes = apkSize,
                        releaseNotes = obj.optString("body", "").trim()
                    )
                    if (bestRelease == null || isNewerVersion(candidate.latestVersion, bestRelease.latestVersion)) {
                        bestRelease = candidate
                    }
                }

                lastAutoCheckMs = System.currentTimeMillis()
                cachedRelease = bestRelease
                _checkState.value = if (bestRelease != null) {
                    UpdateCheckState.Available(bestRelease)
                } else {
                    UpdateCheckState.UpToDate(current)
                }
                bestRelease
            } catch (t: Throwable) {
                _checkState.value = UpdateCheckState.Error(t.localizedMessage ?: "Network error")
                null
            }
        }
    }

    suspend fun downloadUpdate(context: Context, release: AppReleaseInfo): File? {
        _downloadState.value = UpdateDownloadState.Downloading(0f, 0L, release.apkSizeBytes)
        return withContext(Dispatchers.IO) {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Remove older cached APKs
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name.endsWith(".apk")) old.delete()
            }
            val safeName = "realness-${release.latestVersion}.apk".replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tmpFile = File(updatesDir, "$safeName.part")
            val finalFile = File(updatesDir, safeName)

            var conn: HttpURLConnection? = null
            try {
                conn = openFollowingRedirects(release.downloadUrl)
                val code = conn.responseCode
                if (code !in 200..299) {
                    _downloadState.value = UpdateDownloadState.Error("HTTP $code")
                    return@withContext null
                }

                val total = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)
                var lastEmitMs = 0L

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        while (true) {
                            if (!isActive) {
                                tmpFile.delete()
                                _downloadState.value = UpdateDownloadState.Idle
                                return@withContext null
                            }
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 100L || (total > 0L && downloaded >= total)) {
                                lastEmitMs = now
                                val p = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                _downloadState.value = UpdateDownloadState.Downloading(p, downloaded, total)
                            }
                        }
                    }
                }

                // Basic APK/ZIP header sanity check (PK\x03\x04)
                val header = ByteArray(4)
                val headerRead = tmpFile.inputStream().use { it.read(header) }
                if (headerRead < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                    tmpFile.delete()
                    _downloadState.value = UpdateDownloadState.Error("Invalid APK payload")
                    return@withContext null
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                _downloadState.value = UpdateDownloadState.ReadyToInstall(finalFile)
                finalFile
            } catch (t: Throwable) {
                tmpFile.delete()
                _downloadState.value = UpdateDownloadState.Error(t.localizedMessage ?: "Download failed")
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: Throwable) {
                true
            }
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun openFollowingRedirects(urlStr: String, maxRedirects: Int = 5): HttpURLConnection {
        var current = urlStr
        repeat(maxRedirects) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return conn
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        return (URL(current).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun isNewerVersion(candidateRaw: String, currentRaw: String): Boolean {
        val cand = parseVersionParts(candidateRaw) ?: return false
        val curr = parseVersionParts(currentRaw) ?: return false
        val maxLen = maxOf(cand.first.size, curr.first.size)
        for (i in 0 until maxLen) {
            val a = cand.first.getOrElse(i) { 0 }
            val b = curr.first.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Same numeric version: stable beats pre-release suffix (e.g. 2.0 > 2.0-beta.1)
        val candSuffix = cand.second
        val currSuffix = curr.second
        if (candSuffix.isEmpty() && currSuffix.isNotEmpty()) return true
        if (candSuffix.isNotEmpty() && currSuffix.isEmpty()) return false
        return candSuffix > currSuffix
    }

    private fun parseVersionParts(raw: String): Pair<List<Int>, String>? {
        val norm = normalizeVersion(raw)
        val parts = norm.split("-", limit = 2)
        val nums = parts[0].split(".").map { it.toIntOrNull() ?: return null }
        if (nums.isEmpty()) return null
        val suffix = parts.getOrNull(1)?.lowercase(Locale.US) ?: ""
        return nums to suffix
    }
}
