package ai.paai.link.update

import ai.paai.link.BuildConfig
import ai.paai.link.core.node.LogBuffer
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer SRCS release found on GitHub. */
data class Release(val version: String, val apkUrl: String, val sizeBytes: Long, val notes: String)

data class UpdateState(
    val checking: Boolean = false,
    /** Newer than this app; null when up to date or not checked yet. */
    val available: Release? = null,
    /** 0..1 while downloading, null otherwise. */
    val progress: Float? = null,
    val error: String? = null,
    val lastResult: String? = null,
    val autoCheck: Boolean = true,
    /** The user closed the banner for this version. */
    val dismissedVersion: String? = null,
)

/**
 * Checks GitHub for a newer SRCS release and installs it. This is the only time the app talks to
 * an internet server apart from map downloads: one small request to api.github.com, at most
 * once an hour, only when the phone has internet. It can be turned off in Settings.
 *
 * The downloaded APK is only handed to the Android installer when it is the same app, signed
 * with the same key and newer than this one.
 */
class UpdateManager(private val app: Application, private val log: LogBuffer, private val scope: CoroutineScope) {
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UpdateState(autoCheck = prefs.getBoolean(K_AUTO, true), dismissedVersion = prefs.getString(K_DISMISSED, null)),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var download: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Called when the app opens: checks at most once an hour (if the user allows it). */
    fun checkInBackground() {
        if (!_state.value.autoCheck) return
        val last = prefs.getLong(K_LAST, 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            // Still show what the last check found.
            prefs.getString(K_FOUND, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }?.let { r ->
                if (isNewer(r.version, currentVersion)) _state.update { it.copy(available = r) }
            }
            return
        }
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, error = null, lastResult = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val json = httpGet(LATEST_URL)
                val release = parse(JSONObject(json))
                prefs.edit().putLong(K_LAST, System.currentTimeMillis()).putString(K_FOUND, json.take(64_000)).apply()
                val newer = isNewer(release.version, currentVersion)
                log.i("Update", "latest release ${release.version}, this app $currentVersion, newer=$newer")
                _state.update {
                    it.copy(
                        checking = false,
                        available = if (newer) release else null,
                        lastResult = if (newer) "Version ${release.version} is available" else "SRCS is up to date ($currentVersion)",
                    )
                }
            } catch (e: Exception) {
                log.w("Update", "update check failed: ${e.message}")
                _state.update { it.copy(checking = false, error = if (manual) "Could not check for updates. Is the phone connected to the internet?" else null) }
            }
        }
    }

    fun setAutoCheck(on: Boolean) {
        prefs.edit().putBoolean(K_AUTO, on).apply()
        _state.update { it.copy(autoCheck = on) }
        if (on) checkInBackground()
    }

    fun dismiss() {
        val v = _state.value.available?.version ?: return
        prefs.edit().putString(K_DISMISSED, v).apply()
        _state.update { it.copy(dismissedVersion = v) }
    }

    /** Downloads the APK, checks it and opens the Android installer. */
    fun downloadAndInstall(context: Context) {
        val r = _state.value.available ?: return
        if (download?.isActive == true) return
        if (!canInstall()) {
            _state.update { it.copy(error = "Allow SRCS to install updates, then tap Install again.") }
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        download = scope.launch(Dispatchers.IO) {
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "SRCS-${r.version}.apk")
            try {
                _state.update { it.copy(progress = 0f, error = null) }
                fetch(r.apkUrl, file, r.sizeBytes) { p -> _state.update { it.copy(progress = p) } }
                verify(file)
                _state.update { it.copy(progress = null) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(install)
            } catch (e: Exception) {
                log.w("Update", "update download failed: ${e.message}")
                file.delete()
                _state.update { it.copy(progress = null, error = e.message ?: "Download failed") }
            }
        }
    }

    private fun canInstall(): Boolean =
        app.packageManager.canRequestPackageInstalls()

    /** Same package, same signing key, newer version: otherwise refuse to install it. */
    private fun verify(file: File) {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive: PackageInfo = pm.getPackageArchiveInfo(file.path, flags) ?: error("The download is not a valid app file")
        if (archive.packageName != app.packageName) error("The download is a different app")
        val mine = pm.getPackageInfo(app.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(mine)) error("The download is not newer than this app")
        val theirs = signers(archive)
        if (theirs.isEmpty() || theirs != signers(mine)) {
            error("This update is signed with a different key. Uninstall SRCS and install it from the GitHub page instead.")
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        else info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetch(url: String, to: File, expected: Long, onProgress: (Float) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) error("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (total > 0 && done != total) error("Download incomplete")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SRCS-app")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    companion object {
        const val REPO = "ArunSikarwar36/short-range-communication-system"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        private const val CHECK_EVERY_MS = 3_600_000L
        private const val K_AUTO = "auto_check"
        private const val K_LAST = "last_check"
        private const val K_FOUND = "last_release_json"
        private const val K_DISMISSED = "dismissed_version"

        internal fun parse(o: JSONObject): Release {
            val version = o.getString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets")
            var url: String? = null
            var size = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "SRCS.apk") {
                        url = a.getString("browser_download_url")
                        size = a.optLong("size")
                    }
                }
            }
            return Release(version, url ?: error("No SRCS.apk in release $version"), size, o.optString("body").take(2000))
        }

        /** "0.5.0" > "0.4.1"; extra parts and suffixes after a dash are compared loosely. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
