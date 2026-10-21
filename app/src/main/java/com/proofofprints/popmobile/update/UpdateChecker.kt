/**
 * Polls the GitHub Releases API for a newer OverMobile APK.
 *
 * Design notes:
 *  - Hits /repos/{owner}/{repo}/releases/latest, which is cached by GitHub's
 *    CDN — we never risk rate-limiting even with hundreds of thousands of
 *    installs.
 *  - Throttled to once per 24 h via SharedPreferences timestamp.
 *  - The user can dismiss a specific version; we only re-prompt when a
 *    newer-than-dismissed version ships.
 *
 * Copyright (c) 2026 OverBuild Labs
 */
package com.proofofprints.popmobile.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.proofofprints.popmobile.BuildConfig
import com.proofofprints.popmobile.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,          // e.g. "v1.0.2"
    val versionName: String,      // stripped of the leading 'v'
    val releaseName: String,      // e.g. "OverMobile v1.0.2"
    val releaseNotes: String,     // markdown body from the release
    val apkUrl: String,           // browser_download_url of the .apk asset
    val apkSizeBytes: Long,       // size of the .apk asset (0 if unknown)
    val apkAssetName: String,     // e.g. "popmobile-v1.0.2.apk" (sanitised)
    val apkSha256: String? = null // from the asset's GitHub `digest`, if present
)

class UpdateChecker(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Query GitHub for the latest release and return an UpdateInfo if it's
     * newer than the currently installed build; null otherwise.
     *
     * @param force bypass the 24-h throttle AND the dismissed-version list.
     *              Pass true when the user taps "Check for updates" in
     *              Settings — they explicitly asked to see any update,
     *              including one they previously told us to be quiet about.
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? =
        withContext(Dispatchers.IO) {
            if (!force && !throttleElapsed()) {
                Log.i(TAG, "Skip check — throttled (last check ${minutesSinceLastCheck()} min ago)")
                return@withContext null
            }
            try {
                val release = fetchLatestRelease() ?: return@withContext null
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (!VersionCompare.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                    Log.i(TAG, "Up to date (installed=${BuildConfig.VERSION_NAME}, latest=${release.versionName})")
                    return@withContext null
                }
                if (!force && wasDismissed(release.versionName)) {
                    Log.i(TAG, "User already dismissed ${release.versionName}")
                    return@withContext null
                }
                LogManager.info("Update available: ${release.tagName}")
                release
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                null
            }
        }

    /** Remember that the user tapped "Later" for this version so we stop nagging. */
    fun dismiss(versionName: String) {
        prefs.edit().putString(KEY_DISMISSED_VERSION, versionName).apply()
    }

    /**
     * Epoch-ms of the last successful (or attempted) GitHub call, or null
     * if we've never checked. Used by the About panel to show "Last checked
     * X ago".
     */
    fun lastCheckTimestampMs(): Long? {
        val v = prefs.getLong(KEY_LAST_CHECK, 0L)
        return if (v == 0L) null else v
    }

    private fun wasDismissed(versionName: String): Boolean {
        val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null) ?: return false
        // Only honour the dismissal if the dismissed version is ≥ this one,
        // so a newer release un-dismisses.
        return VersionCompare.compare(dismissed, versionName) >= 0
    }

    private fun throttleElapsed(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= THROTTLE_MS
    }

    private fun minutesSinceLastCheck(): Long {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (last == 0L) return -1
        return (System.currentTimeMillis() - last) / 60_000
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val url = URL(RELEASES_API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OverMobile/${BuildConfig.VERSION_NAME}")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GitHub API returned $code")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { r ->
                // The release JSON is a few KB; cap it so a broken or hostile
                // response can't exhaust memory.
                val buf = CharArray(1_000_000)
                var n = 0
                while (n < buf.size) {
                    val k = r.read(buf, n, buf.size - n)
                    if (k < 0) break
                    n += k
                }
                if (n == buf.size && r.read() != -1) throw java.io.IOException("Release JSON too large")
                String(buf, 0, n)
            }
            val json = Gson().fromJson(body, JsonObject::class.java)

            val tagName = json["tag_name"]?.asString ?: return null
            val versionName = tagName.removePrefix("v").removePrefix("V")
            // GitHub sends "body": null for a release without notes; JsonNull
            // .asString throws, which used to hide the update entirely.
            val releaseName = json["name"]?.takeIf { !it.isJsonNull }?.asString ?: tagName
            val releaseNotes = json["body"]?.takeIf { !it.isJsonNull }?.asString ?: ""

            // Pick the first .apk asset
            val assets = json.getAsJsonArray("assets") ?: return null
            for (i in 0 until assets.size()) {
                val asset = assets[i].asJsonObject
                val name = asset["name"]?.takeIf { !it.isJsonNull }?.asString ?: continue
                if (!name.endsWith(".apk")) continue
                val apkUrl = asset["browser_download_url"]?.takeIf { !it.isJsonNull }?.asString ?: continue
                if (!ApkVerifier.isAllowedDownloadUrl(apkUrl)) {
                    Log.w(TAG, "Ignoring asset hosted outside our releases: $apkUrl")
                    continue
                }
                return UpdateInfo(
                    tagName = tagName,
                    versionName = versionName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkUrl = apkUrl,
                    apkSizeBytes = asset["size"]?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    apkAssetName = ApkVerifier.safeFileName(name),
                    apkSha256 = ApkVerifier.parseGithubDigest(
                        asset["digest"]?.takeIf { !it.isJsonNull }?.asString
                    )
                )
            }
            Log.w(TAG, "Release $tagName has no .apk asset")
            return null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREFS = "update_checker"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val THROTTLE_MS = 24L * 60 * 60 * 1000  // 24 h

        // Swap to a configurable host if the repo ever moves.
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/overbuildlabs/OverMobile/releases/latest"
    }
}
