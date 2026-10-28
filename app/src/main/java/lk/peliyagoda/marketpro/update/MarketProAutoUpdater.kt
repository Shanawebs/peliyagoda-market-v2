package lk.peliyagoda.marketpro.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lk.peliyagoda.marketpro.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Safe in-app updater for PUBLIC Market Pro GitHub Releases.
 *
 * Both QA and Stable published APKs are eligible; unpublished CI artifacts
 * are intentionally excluded because they have no anonymously downloadable
 * and independently checksum-verifiable asset URL.
 *
 * Box can remain the owner's archive. A private Box folder does NOT grant an
 * Android application access to the user's Box login/session.
 *
 * Never silently installs packages. Android always owns the install prompt.
 * Does not modify Room, cloud state, sign-in details or external storage.
 */
internal object MarketProAutoUpdater {
    data class Ready(val version: String, val file: File, val versionCode: Long)
    sealed class Outcome {
        data class Downloaded(val ready: Ready) : Outcome()
        object NoUpdate : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    private const val API =
        "https://api.github.com/repos/Shanawebs/peliyagoda-market-v2/releases?per_page=30"
    private const val PREFS = "marketpro_signed_updates_v1"
    private const val MIN_INTERVAL_MS = 2L * 60 * 60 * 1000
    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private const val MAX_JSON_BYTES = 650 * 1024
    // Startup UI and WorkManager can run at the same instant: never let
    // two fetchers write the same staging file or race the cached manifest.
    private val downloadLock = Mutex()
    private val versionRegex = Regex("""v(\d+)\.(\d+)\.(\d+)(?!\d)""")
    private val hashRegex = Regex("^[a-f0-9]{64}$")

    private data class Version(val major: Int, val minor: Int, val patch: Int) :
        Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
        override fun toString(): String = "$major.$minor.$patch"
    }
    private data class Candidate(
        val version: Version, val url: String, val sha256: String, val bytes: Long
    )
    private fun version(text: String): Version? {
        val m = versionRegex.find(text) ?: return null
        return try {
            Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt())
        } catch (_: NumberFormatException) { null }
    }
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun signingFingerprint(context: Context, path: String? = null): List<String> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (path == null) pm.getPackageInfo(context.packageName, flags)
            else pm.getPackageArchiveInfo(path, flags) ?: error("Invalid downloaded APK")
        val signers = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        require(!signers.isNullOrEmpty()) { "APK signing certificate is missing" }
        return signers.map {
            hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.sorted()
    }

    @Suppress("DEPRECATION")
    private fun apkVersionCode(context: Context, file: File): Long {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK")
        require(info.packageName == context.packageName) { "Unexpected APK package" }
        require(signingFingerprint(context) == signingFingerprint(context, file.absolutePath)) {
            "APK signer does not match installed Market Pro"
        }
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else info.versionCode.toLong()
    }

    private fun currentCode(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else info.versionCode.toLong()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun allowedSource(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.port == -1 &&
            (host == "github.com" || host == "api.github.com" ||
                host == "objects.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "github-releases.githubusercontent.com")
    }

    private fun openSafe(url: String): HttpURLConnection {
        var next = url
        repeat(7) {
            require(allowedSource(next)) { "Update URL is not on a trusted GitHub host" }
            val connection = URL(next).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", "MarketPro-Android-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code in 300..399) {
                val destination = connection.getHeaderField("Location")
                connection.disconnect()
                next = URL(URL(next), destination ?: error("Missing redirect")).toString()
            } else {
                if (code !in 200..299) {
                    connection.disconnect()
                    error("Update server unavailable (HTTP $code)")
                }
                return connection
            }
        }
        error("Too many update server redirects")
    }

    private fun readLatest(): Candidate? {
        val connection = openSafe(API)
        val data = try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= MAX_JSON_BYTES) {
                        "Update catalog is too large"
                    }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
        val current = version("v" + BuildConfig.VERSION_NAME)
            ?: error("Unknown Market Pro application version")
        val releases = JSONArray(data)
        var best: Candidate? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", true)) continue
            // Intentionally accept BOTH prerelease/QA and stable channel releases.
            val releaseVersion = version(release.optString("tag_name")) ?: continue
            if (releaseVersion <= current || best != null && releaseVersion <= best.version) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (!name.startsWith("MarketPro-v$releaseVersion-") ||
                    !name.endsWith("-signed.apk")) continue
                val sum = asset.optString("digest").removePrefix("sha256:").lowercase()
                val size = asset.optLong("size")
                val download = asset.optString("browser_download_url")
                if (!hashRegex.matches(sum) || size !in 1L..MAX_APK_BYTES ||
                    !allowedSource(download)) continue
                best = Candidate(releaseVersion, download, sum, size)
                break
            }
        }
        return best
    }

    private fun storedReady(context: Context): Ready? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString("file", null) ?: return null
        if (!Regex("^MarketPro-[0-9.]+-signed[.]apk$").matches(name)) return null
        val file = File(File(context.cacheDir, "apk_updates"), name)
        val hash = prefs.getString("sha256", null) ?: return null
        if (!hashRegex.matches(hash) || !file.isFile ||
            file.length() !in 1L..MAX_APK_BYTES) return null
        return runCatching {
            require(sha256(file) == hash) { "Cached update hash changed" }
            val code = apkVersionCode(context, file)
            require(code > currentCode(context)) { "Cached version is not newer" }
            Ready(prefs.getString("version", "New") ?: "New", file, code)
        }.getOrNull()
    }

    suspend fun check(context: Context, force: Boolean = false): Outcome =
        downloadLock.withLock {
        withContext(Dispatchers.IO) {
            if (!BuildConfig.MARKETPRO_AUTO_UPDATE) return@withContext Outcome.NoUpdate
            try {
                val cached = storedReady(context)
                if (cached != null) return@withContext Outcome.Downloaded(cached)
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val last = prefs.getLong("last_check", 0)
                if (!force && now >= last && now - last < MIN_INTERVAL_MS)
                    return@withContext Outcome.NoUpdate

                val candidate = readLatest()
                prefs.edit().putLong("last_check", now).apply()
                if (candidate == null) return@withContext Outcome.NoUpdate

                val dir = File(context.cacheDir, "apk_updates")
                require(dir.isDirectory || dir.mkdirs())
                val temp = File(dir, "pending-download.apk")
                val finish = File(dir, "MarketPro-${candidate.version}-signed.apk")
                temp.delete()
                val connection = openSafe(candidate.url)
                try {
                    connection.inputStream.use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= MAX_APK_BYTES && count <= candidate.bytes) {
                                    "Update download exceeds advertised size"
                                }
                                output.write(buffer, 0, n)
                            }
                            require(count == candidate.bytes) { "Incomplete update download" }
                        }
                    }
                } finally { connection.disconnect() }
                require(sha256(temp) == candidate.sha256) {
                    "APK SHA-256 did not match the published GitHub release"
                }
                val code = apkVersionCode(context, temp)
                require(code > currentCode(context)) { "Update must increase versionCode" }
                if (finish.exists()) finish.delete()
                require(temp.renameTo(finish)) { "Unable to save verified update" }
                prefs.edit().putString("file", finish.name)
                    .putString("sha256", candidate.sha256)
                    .putString("version", candidate.version.toString()).apply()
                Outcome.Downloaded(Ready(candidate.version.toString(), finish, code))
            } catch (e: Exception) {
                Outcome.Failure(e.message ?: "Update check failed")
            }
        }
        }

    fun installWithAndroidApproval(context: Context, update: Ready) {
        require(BuildConfig.MARKETPRO_AUTO_UPDATE)
        require(update.file.parentFile?.canonicalFile ==
            File(context.cacheDir, "apk_updates").canonicalFile)
        if (Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        val contentUri = FileProvider.getUriForFile(context,
            "${context.packageName}.invoiceprovider", update.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun schedule(context: Context) {
        if (!BuildConfig.MARKETPRO_AUTO_UPDATE) return
        val request = PeriodicWorkRequestBuilder<MarketProUpdateWorker>(
            6, TimeUnit.HOURS
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            "marketpro_safely_signed_apk_updates",
            ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Background download only; user approves installation inside the app. */
internal class MarketProUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (MarketProAutoUpdater.check(applicationContext)) {
            is MarketProAutoUpdater.Outcome.Failure -> Result.retry()
            else -> Result.success()
        }
}
