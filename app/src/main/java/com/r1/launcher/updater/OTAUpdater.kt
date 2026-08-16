package com.r1.launcher.updater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.r1.launcher.LauncherState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OTAUpdater {
    private const val TAG = "OTAUpdater"
    // Points at this fork, not upstream — otherwise the on-device updater would
    // happily overwrite your build with khalifa007's next release.
    private const val API_URL = "https://api.github.com/repos/muratangin187/rabbitR1Luncher/releases/latest"
    // Free-space floor for /data before download + install. ~3x the typical
    // APK size: one for cacheDir, one for /data/local/tmp/update.apk, one
    // for /data/app's pre-commit staging area.
    private const val REQUIRED_FREE_BYTES = 50L * 1024 * 1024

    // Root command executor injected from LauncherActivity
    var executeRootCommand: ((String) -> Boolean)? = null

    fun checkForUpdates(
        context: Context,
        state: LauncherState,
        forcePrompt: Boolean = false,
        onResult: ((String) -> Unit)? = null
    ) {
        state.updateIconState = 1 // Checking indicator
        Thread {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val code = conn.responseCode
                if (code == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tagName = json.getString("tag_name") // e.g. "v3.6.1"

                    // Extract remote version code from tag (strip leading "v")
                    val remoteVersionStr = tagName.removePrefix("v") // "3.6.1"

                    // Get local version name from PackageManager
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val localVersionStr = pInfo.versionName ?: "0.0.0" // e.g. "3.6.0"

                    Log.i(TAG, "Local: v$localVersionStr | Remote: $tagName")

                    if (isNewerVersion(remoteVersionStr, localVersionStr)) {
                        // Check if we've already tried and failed for this exact tag (anti-loop)
                        val prefs = context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
                        val lastFailed = prefs.getString("last_failed_tag", "")

                        if (tagName == lastFailed && !forcePrompt) {
                            // Silently skip — we already tried this one and it didn't work
                            Log.i(TAG, "Skipping $tagName — previous install attempt failed")
                            resetState(state, null, null)
                            return@Thread
                        }

                        // Pick the APK and the optional SHA-256 sidecar. The
                        // workflow uploads `<name>.apk` plus `<name>.apk.sha256`
                        // (single hex line). Older releases predate the
                        // sidecar — we proceed without integrity verification
                        // in that case rather than refusing the update.
                        val assets = json.getJSONArray("assets")
                        var downloadUrl = ""
                        var shaUrl = ""
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            val url = asset.getString("browser_download_url")
                            when {
                                name.endsWith(".apk.sha256") -> shaUrl = url
                                name.endsWith(".apk") -> downloadUrl = url
                            }
                        }

                        if (downloadUrl.isNotEmpty()) {
                            notify(onResult, "Update $tagName found! Downloading...")
                            downloadAndInstall(context, tagName, downloadUrl, shaUrl, state, onResult)
                        } else {
                            resetState(state, onResult, if (forcePrompt) "No APK in release $tagName" else null)
                        }
                    } else {
                        resetState(state, onResult, if (forcePrompt) "Already up to date (v$localVersionStr)" else null)
                    }
                } else if (code == 404) {
                    resetState(state, onResult, if (forcePrompt) "No releases on GitHub yet" else null)
                } else {
                    resetState(state, onResult, if (forcePrompt) "GitHub check failed (HTTP $code)" else null)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "OTA check error", e)
                resetState(state, onResult, if (forcePrompt) "Check failed: ${e.message}" else null)
            }
        }.start()
    }

    /**
     * Compares semantic version strings like "3.6.1" > "3.6.0"
     * Returns true if remote is strictly newer than local.
     */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun downloadAndInstall(
        context: Context,
        tagName: String,
        downloadUrl: String,
        shaUrl: String,
        state: LauncherState,
        onResult: ((String) -> Unit)?
    ) {
        state.updateIconState = 2 // Downloading indicator
        Thread {
            val cacheFile = File(context.cacheDir, "update_cache.apk")
            try {
                if (cacheFile.exists()) cacheFile.delete()

                // /data full silently kills pm install. cacheDir lives on the
                // same partition as /data/app, so a StatFs check here gives us
                // both download room AND install headroom in one call.
                if (!hasEnoughFreeSpace(context, REQUIRED_FREE_BYTES)) {
                    resetState(
                        state,
                        onResult,
                        "Not enough free space — need ~${REQUIRED_FREE_BYTES / (1024 * 1024)} MB free on /data",
                    )
                    return@Thread
                }

                // Follow redirects manually (GitHub -> AWS S3 CDN)
                var currentUrl = downloadUrl
                var conn: HttpURLConnection
                var attempts = 0
                while (true) {
                    conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 90000
                    val status = conn.responseCode
                    if (status in 300..399) {
                        val location = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        currentUrl = location
                        if (++attempts > 5) break // safety limit
                    } else {
                        break
                    }
                }

                // Only a final HTTP 200 is a real APK body. If the loop broke on
                // a 3xx with no Location (or hit the hop limit), `conn` still
                // points at that redirect response — streaming it would write a
                // redirect/error page into the .apk and hand it to pm install.
                if (conn.responseCode != 200) {
                    val rc = conn.responseCode
                    conn.disconnect()
                    resetState(state, onResult, "Download failed (HTTP $rc)")
                    return@Thread
                }

                // Stream download to cacheDir (app has full write access here)
                FileOutputStream(cacheFile).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(16384)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()

                Log.i(TAG, "Downloaded ${cacheFile.length() / 1024}KB to ${cacheFile.absolutePath}")

                // SHA-256 integrity check. Skipped (with a warning log) when
                // the release has no .sha256 sidecar — older releases predate
                // the workflow that publishes one. Mismatch aborts BEFORE we
                // hand the APK to pm install: a corrupt APK that pm rejects
                // is harder to diagnose than an explicit "checksum failed".
                if (shaUrl.isNotEmpty()) {
                    val expectedSha = fetchSha256(shaUrl)
                    if (expectedSha == null) {
                        cacheFile.delete()
                        resetState(state, onResult, "Could not fetch checksum — aborting")
                        return@Thread
                    }
                    val actualSha = computeSha256(cacheFile)
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        Log.w(TAG, "SHA mismatch: expected=$expectedSha actual=$actualSha")
                        cacheFile.delete()
                        // Don't mark last_failed_tag here — corruption is
                        // transient; let the next check retry the download.
                        resetState(state, onResult, "Download corrupt (SHA mismatch) — try again")
                        return@Thread
                    }
                    Log.i(TAG, "SHA-256 verified ($expectedSha)")
                } else {
                    Log.w(TAG, "No .sha256 sidecar in release — installing without integrity check")
                }

                val rootFunc = executeRootCommand
                if (rootFunc != null) {
                    val src = cacheFile.absolutePath
                    val dst = "/data/local/tmp/update.apk"
                    // Stage in /data/local/tmp then install via root. chmod 600
                    // (not 644) — the copy runs as root via carroot so the file
                    // is root-owned, and a root `pm install` reads it fine; 600
                    // keeps any other local process from reading the staged APK
                    // during the install window.
                    rootFunc("cp \"$src\" $dst && chmod 600 $dst")
                    notify(onResult, "Installing update...")
                    // sendToCarroot returns true on socket-write success, NOT on
                    // command success — pm install can fail (signature mismatch,
                    // version downgrade, parser error, full /data) and we'd see
                    // the same `true`. Verify by re-reading PackageManager and
                    // confirming the installed versionName matches the remote
                    // tag. Without this check, a silently-failing install ends
                    // up in a boot loop: auto-restart → onCreate check → same
                    // remote version still newer → reinstall → repeat.
                    //
                    // `-d` allows downgrade. The local debug pin keeps
                    // versionCode at 1000 between releases, so a fresh
                    // OTA-published versionCode (e.g. 8) would otherwise
                    // hit INSTALL_FAILED_VERSION_DOWNGRADE and look like a
                    // total failure — for a launcher where the user
                    // controls the OS image, downgrade tolerance is the
                    // pragmatic default.
                    rootFunc("pm install -r -d $dst")
                    val expected = tagName.removePrefix("v")
                    val verified = verifyInstall(context, expected)
                    val prefs = context.getSharedPreferences("ota_prefs", Context.MODE_PRIVATE)
                    if (verified) {
                        prefs.edit().remove("last_failed_tag").apply()
                        // Do NOT auto-restart. The new APK is committed to
                        // PackageManager; subsequent OTA checks will see
                        // "up to date" against pInfo.versionName regardless
                        // of whether this process restarted. Letting the user
                        // reopen naturally avoids an OTA-driven restart loop
                        // even in the presence of regressions elsewhere.
                        resetState(state, onResult, "Update v$expected installed — reopen launcher to apply")
                    } else {
                        prefs.edit().putString("last_failed_tag", tagName).apply()
                        resetState(state, onResult, "Install failed — version did not change")
                    }
                } else {
                    resetState(state, onResult, "Root shell unavailable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                cacheFile.delete()
                resetState(state, onResult, "Download failed: ${e.message}")
            }
        }.start()
    }

    /** True when at least [requiredBytes] is free on the partition backing
     *  [Context.cacheDir]. cacheDir lives under /data, so this also reflects
     *  /data/app's available bytes — single check covers download AND install. */
    private fun hasEnoughFreeSpace(context: android.content.Context, requiredBytes: Long): Boolean {
        return runCatching {
            val stat = android.os.StatFs(context.cacheDir.absolutePath)
            stat.availableBytes >= requiredBytes
        }.getOrDefault(true)  // On StatFs failure, fall through — better to attempt than spuriously refuse
    }

    /** Fetch the SHA-256 sidecar's body. Format: one line, hex digest only
     *  (extra whitespace tolerated). Returns null on transport/parse failure. */
    private fun fetchSha256(url: String): String? {
        return runCatching {
            var currentUrl = url
            var conn: HttpURLConnection
            var attempts = 0
            while (true) {
                conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                val status = conn.responseCode
                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    currentUrl = loc
                    if (++attempts > 5) break
                } else break
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // Sidecar may be raw hex ("ab12...") or `sha256sum`-formatted
            // ("ab12...  app-release.apk"). Take the first whitespace-bounded
            // token and validate it.
            val token = body.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (token.matches(Regex("[0-9a-fA-F]{64}"))) token.lowercase() else null
        }.getOrNull()
    }

    /** Stream-hash the file (8 KiB buffer) so a 20 MB APK doesn't sit in
     *  memory all at once. Returns lower-case hex. */
    private fun computeSha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Poll PackageManager until `versionName` matches `expected` (the
     *  remote tag minus its `v` prefix), or until [timeoutMs] elapses.
     *
     *  PackageManagerService is updated synchronously by `pm install -r`'s
     *  success path, so a positive match here is the authoritative signal
     *  the new APK is committed — even without our process restarting.
     *  Polling (rather than reading once) absorbs the ~hundreds-of-ms
     *  between `sendToCarroot` returning and the install actually
     *  finishing on the carroot side. */
    private fun verifyInstall(
        context: android.content.Context,
        expected: String,
        timeoutMs: Long = 8000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            if (installed == expected) return true
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun notify(onResult: ((String) -> Unit)?, msg: String) {
        Handler(Looper.getMainLooper()).post { onResult?.invoke(msg) }
    }

    private fun resetState(state: LauncherState, onResult: ((String) -> Unit)?, msg: String?) {
        state.updateIconState = 0
        if (msg != null) notify(onResult, msg)
    }
}
