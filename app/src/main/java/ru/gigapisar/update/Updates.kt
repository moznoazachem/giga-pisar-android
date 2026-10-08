package ru.gigapisar.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import ru.gigapisar.BuildConfig
import ru.gigapisar.MainActivity
import ru.gigapisar.R
import ru.gigapisar.settings.AppLanguage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer version announced in update.json on GitHub. */
data class UpdateInfo(
    val version: String,
    val versionCode: Long,
    val url: String,
    val sha256: String,
    val size: Long,
    val notesRu: String,
    val notesEn: String,
) {
    fun notes(context: Context): String = if (AppLanguage.isRussian(context)) notesRu else notesEn
}

/**
 * Self-update, like the desktop apps: no push server. The service and the app look into a
 * small update.json on GitHub now and then; a newer version shows as a notification and a
 * banner. "Update" downloads the APK, checks its SHA-256 and opens Android's own installer
 * (Android also checks the APK is signed with our key). Settings and the model stay.
 */
object Updates {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/moznoazachem/giga-pisar-android/main/update.json"
    private const val TRUSTED_PREFIX = "https://github.com/moznoazachem/giga-pisar-android/releases/download/"
    private const val PREFS = "giga_pisar_updates"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7001
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedCode(context: Context): Long =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            0
        }

    /** The newer version found by the last check, if any (survives restarts). */
    fun pending(context: Context): UpdateInfo? {
        if (!BuildConfig.SELF_UPDATE) return null
        val raw = prefs(context).getString("latest", null) ?: return null
        val info = runCatching { parse(JSONObject(raw)) }.getOrNull() ?: return null
        // APKs come only from this repository's releases.
        return info.takeIf { it.versionCode > installedCode(context) && it.url.startsWith(TRUSTED_PREFIX) }
    }

    /** Whether it is time for another look (the service and the app share the clock). */
    fun due(context: Context): Boolean = BuildConfig.SELF_UPDATE && System.currentTimeMillis() - prefs(context).getLong("checkedAt", 0) > CHECK_EVERY_MS

    /** Blocking: fetches update.json; returns a newer version or null. Never throws. */
    fun check(context: Context): UpdateInfo? =
        try {
            val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Cache-Control", "no-cache")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            prefs(context)
                .edit()
                .putLong("checkedAt", System.currentTimeMillis())
                .putString("latest", text)
                .apply()
            pending(context)
        } catch (_: Exception) {
            null
        }

    private fun parse(json: JSONObject): UpdateInfo {
        val notes = json.optJSONObject("notes")
        return UpdateInfo(
            version = json.getString("version"),
            versionCode = json.getLong("versionCode"),
            url = json.getString("url"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.optLong("size"),
            notesRu = notes?.optString("ru").orEmpty(),
            notesEn = notes?.optString("en").orEmpty(),
        )
    }

    /** Tells about a new version once, in the notification shade. */
    fun notifyOnce(
        context: Context,
        info: UpdateInfo,
    ) {
        val p = prefs(context)
        if (p.getLong("notifiedCode", 0) >= info.versionCode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // the banner in the app still shows it
        }
        val ui = AppLanguage.wrap(context.applicationContext)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, ui.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ui.getString(R.string.update_title, info.version))
                .setContentText(info.notes(context).ifEmpty { ui.getString(R.string.update_tap) })
                .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes(context)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
        p.edit().putLong("notifiedCode", info.versionCode).apply()
    }

    /**
     * Blocking: downloads the APK into the app's cache, reporting 0..100, and checks its
     * SHA-256. Returns the file, or throws with a reason a person can read.
     */
    fun download(
        context: Context,
        info: UpdateInfo,
        progress: (Int) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val file = File(dir, "GigaPisar-${info.version}.apk")
        val connection = URL(info.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.size
        val digest = MessageDigest.getInstance("SHA-256")
        connection.inputStream.use { input ->
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (100 * done / total).toInt()
                        if (p != last) {
                            last = p
                            progress(p)
                        }
                    }
                }
            }
        }
        connection.disconnect()
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != info.sha256) {
            file.delete()
            throw IllegalStateException(AppLanguage.wrap(context).getString(R.string.update_bad_file))
        }
        return file
    }

    /** Android asks once whether Pisar may install apps; until then, its settings page opens instead. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Opens Android's installer on the downloaded APK. */
    fun install(
        context: Context,
        file: File,
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
