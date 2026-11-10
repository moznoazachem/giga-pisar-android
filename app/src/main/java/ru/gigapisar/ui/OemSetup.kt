package ru.gigapisar.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Phone makers on top of Android keep their own gate in front of a sideloaded app: Xiaomi wants
 * "restricted settings" allowed before the accessibility switch stops snapping back, Huawei and
 * Oppo refuse to keep the process alive without autostart, Samsung drains it with battery care.
 * The app has no way to flip those from the inside, so the setup asks the user to do it and hands
 * out a button straight into the right screen of that particular shell.
 */
internal enum class Oem(
    /** How the maker is named in the card, so the user sees we recognised the phone. */
    val label: String,
) {
    XIAOMI("HyperOS / MIUI"),
    HUAWEI("EMUI"),
    SAMSUNG("One UI"),
    OPPO("ColorOS"),
    VIVO("Funtouch OS"),
    OTHER(""),
}

internal fun detectOem(): Oem {
    val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
    return when {
        listOf("xiaomi", "redmi", "poco", "blackshark").any { maker.contains(it) } -> Oem.XIAOMI
        maker.contains("huawei") || maker.contains("honor") -> Oem.HUAWEI
        maker.contains("samsung") -> Oem.SAMSUNG
        listOf("oppo", "realme", "oneplus").any { maker.contains(it) } -> Oem.OPPO
        maker.contains("vivo") || maker.contains("iqoo") -> Oem.VIVO
        else -> Oem.OTHER
    }
}

/** True when this shell is known to break the accessibility service or kill it in the background. */
internal fun needsOemSetup(oem: Oem): Boolean = oem != Oem.OTHER

/**
 * The screen with the maker's own switch, best first. Each entry is tried against the installed
 * packages, so a shell that renamed its activity falls through to the next one instead of crashing;
 * the app's own info page is the last resort and always exists.
 */
private fun startupIntents(context: Context, oem: Oem): List<Intent> =
    when (oem) {
        Oem.XIAOMI ->
            listOf(
                intent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                intent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            )
        Oem.HUAWEI ->
            listOf(
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
        Oem.SAMSUNG ->
            listOf(
                intent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                intent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
        Oem.OPPO ->
            listOf(
                intent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                intent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
        Oem.VIVO ->
            listOf(
                intent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                intent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
        Oem.OTHER -> emptyList()
    }

private fun intent(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))

/** The maker's autostart screen, or null when this phone has none we know how to open. */
internal fun autostartIntent(context: Context): Intent? =
    startupIntents(context, detectOem()).firstOrNull { it.isSafeFor(context) }

/** The app's own page: on Android 13+ the "restricted settings" switch sits behind its ⋮ menu. */
internal fun appInfoIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))

private fun Intent.isSafeFor(context: Context): Boolean =
    try {
        context.packageManager.resolveActivity(this, 0) != null
    } catch (_: SecurityException) {
        false
    }

/** Opening the phone maker's screen must never take the app down: a missing activity is normal. */
internal fun startIntent(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
