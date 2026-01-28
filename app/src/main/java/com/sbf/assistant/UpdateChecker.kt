package com.sbf.assistant

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL = "https://api.github.com/repos/successbyfailure/Assistant/releases/latest"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_SKIP_VERSION = "skip_version"
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var downloadId: Long = -1

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdate(forceCheck: Boolean = false): ReleaseInfo? {
        if (!forceCheck) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
            if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                return null
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val release = org.json.JSONObject(response)

                    val tagName = release.getString("tag_name").removePrefix("v")
                    val body = release.optString("body", "")

                    // Find debug APK asset
                    val assets = release.getJSONArray("assets")
                    var downloadUrl: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.contains("debug") && name.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                    if (downloadUrl != null && isNewerVersion(tagName)) {
                        val skippedVersion = prefs.getString(KEY_SKIP_VERSION, null)
                        if (skippedVersion == tagName) {
                            Log.d(TAG, "Version $tagName skipped by user")
                            return@withContext null
                        }
                        ReleaseInfo(tagName, downloadUrl, body)
                    } else {
                        null
                    }
                } else {
                    Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                null
            }
        }
    }

    private fun isNewerVersion(remoteVersion: String): Boolean {
        val currentVersion = getCurrentVersion()
        return compareVersions(remoteVersion, currentVersion) > 0
    }

    private fun getCurrentVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    fun showUpdateDialog(activity: android.app.Activity, release: ReleaseInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Nueva versión disponible: v${release.version}")
            .setMessage("${release.releaseNotes.take(500)}${if (release.releaseNotes.length > 500) "..." else ""}")
            .setPositiveButton("Actualizar") { _, _ ->
                downloadAndInstall(activity, release)
            }
            .setNegativeButton("Más tarde", null)
            .setNeutralButton("Omitir versión") { _, _ ->
                prefs.edit().putString(KEY_SKIP_VERSION, release.version).apply()
            }
            .show()
    }

    private fun downloadAndInstall(activity: android.app.Activity, release: ReleaseInfo) {
        val fileName = "Assistant-v${release.version}.apk"
        val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        // Delete old file if exists
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("Actualizando Assistant")
            .setDescription("Descargando v${release.version}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)

        // Register receiver for download completion
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)
                    installApk(activity, file)
                }
            }
        }

        activity.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(activity: android.app.Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }

    fun clearSkippedVersion() {
        prefs.edit().remove(KEY_SKIP_VERSION).apply()
    }
}
