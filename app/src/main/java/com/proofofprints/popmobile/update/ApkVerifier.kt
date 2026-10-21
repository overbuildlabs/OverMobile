/**
 * Checks a downloaded update APK before it is handed to the system
 * installer.
 *
 * Android already refuses to install an update signed with a different
 * key over this app, but only after the user has been shown an install
 * prompt, and it can't stop an APK with a different package name (which
 * would install as a separate, unknown app). So before launching the
 * installer we require:
 *  - the file's SHA-256 matches the digest GitHub reports for the asset
 *    (when the release API provides one), and
 *  - the APK is this package, signed by the same certificate(s) as the
 *    installed app.
 *
 * Copyright (c) 2026 OverBuild Labs
 */
package com.proofofprints.popmobile.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

internal object ApkVerifier {

    private const val TAG = "ApkVerifier"

    /** Release-asset URLs we will download from. The repo moved from
     *  proofofprints/PoPMobile to overbuildlabs/overmobile; GitHub serves
     *  both, so accept both (case-insensitively, as GitHub does). */
    private val ALLOWED_URL_PREFIXES = listOf(
        "https://github.com/overbuildlabs/overmobile/releases/download/",
        "https://github.com/proofofprints/popmobile/releases/download/",
    )

    fun isAllowedDownloadUrl(url: String): Boolean =
        ALLOWED_URL_PREFIXES.any { url.startsWith(it, ignoreCase = true) }

    /** Keep a GitHub asset name usable as a single file name. */
    fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "update.apk" }

    /** Returns null when [apk] is safe to install, otherwise the reason. */
    fun verify(context: Context, apk: File, expectedSha256: String?): String? {
        if (!apk.isFile) return "Downloaded file is missing"

        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256Hex(apk)
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch: expected $expectedSha256, got $actual")
                return "Checksum mismatch"
            }
        } else {
            Log.w(TAG, "Release has no SHA-256 digest; relying on signature check")
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return "Not a valid APK"
        if (archive.packageName != context.packageName) {
            return "APK is for a different app (${archive.packageName})"
        }

        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return "Can't read installed app's signature"
        }
        val want = signerDigests(installed)
        val got = signerDigests(archive)
        if (got.isEmpty()) {
            // Some older Android versions don't report signatures for an
            // uninstalled archive. The installer still enforces a matching
            // key for an update, so don't block on our own check.
            Log.w(TAG, "Couldn't read APK signature; installer will verify it")
            return null
        }
        // Intersect rather than compare: with key rotation the APK's signing
        // history contains the installed key alongside the new one.
        if (want.intersect(got).isEmpty()) {
            Log.w(TAG, "Signer mismatch: installed=$want apk=$got")
            return "APK is not signed by OverBuild Labs"
        }
        return null
    }

    /** "sha256:<hex>" (GitHub's asset `digest` field) → "<hex>", else null. */
    fun parseGithubDigest(digest: String?): String? =
        digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')?.takeIf { it.length == 64 }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().filterNotNull()
            .map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
            .toSet()
    }
}
