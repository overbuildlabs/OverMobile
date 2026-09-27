/**
 * One-time move of saved settings off the retired pool.proofofprints.com
 * host. The pool now answers at pool.overbuildlabs.com on the same ports,
 * so only the hostname changes; the port and scheme a user saved are kept.
 *
 * Called wherever the saved pool URL is read (the dashboard and the mining
 * service), so an existing install is fixed on its first launch after the
 * update, before it tries to connect. Safe to call repeatedly.
 *
 * Copyright (c) 2026 OverBuild Labs
 */
package com.proofofprints.popmobile.service

import android.content.SharedPreferences

internal object PoolMigration {

    const val LEGACY_POOL_HOST = "pool.proofofprints.com"
    const val POOL_HOST = "pool.overbuildlabs.com"

    /** Same URL with the legacy host swapped for the current one. */
    fun migrate(url: String): String =
        if (url.contains(LEGACY_POOL_HOST, ignoreCase = true))
            url.replace(LEGACY_POOL_HOST, POOL_HOST, ignoreCase = true)
        else url

    /** Rewrites the saved `pool_url` in [prefs] if it still names the legacy host. */
    fun migrateSaved(prefs: SharedPreferences) {
        val saved = prefs.getString("pool_url", null) ?: return
        val updated = migrate(saved)
        if (updated != saved) prefs.edit().putString("pool_url", updated).apply()
    }
}
