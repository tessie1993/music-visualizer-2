package dev.geode.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens this app's page in system settings.
 *
 * The recovery path for a permanently denied permission. Android stops
 * delivering the permission dialog after two refusals, so a button that
 * re-launches the request is a button that does nothing forever — and the app
 * had exactly that everywhere a permission is asked for. Settings is the only
 * remaining route, and every serious media app offers it.
 *
 * Falls back to the top-level settings screen if the per-app page is
 * unavailable, and does nothing at all if even that is missing: some
 * manufacturer builds and most work profiles restrict one or both, and a
 * crash is a worse outcome than a button that could not help.
 */
fun Context.openAppSettings() {
    val appPage =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (start(appPage)) return
    start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // A restricted or work profile can forbid the settings deep link
        // outright rather than simply not resolving it.
        false
    }
