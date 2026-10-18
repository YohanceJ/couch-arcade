package com.couch.arcade.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** A newer build published on GitHub. */
data class AppUpdate(val build: Long, val apkUrl: String)

/**
 * Self-update from GitHub releases. Every push builds a release tagged build-N,
 * where N is also the app's versionCode, so "newer" is just a number comparison.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {

    private val releaseApi = "https://api.github.com/repos/YohanceJ/couch-arcade/releases/latest"

    val currentBuild: Long = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )
    val currentVersionName: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Returns the newer release, or null if this is the latest. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(releaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CouchArcade").build()
        val body = http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("GitHub said HTTP ${res.code}")
            res.body?.string() ?: throw IOException("Empty reply from GitHub")
        }
        val rel = json.parseToJsonElement(body).jsonObject
        val tag = rel["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val build = tag.removePrefix("build-").toLongOrNull() ?: return@withContext null
        val apk = rel["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            ?: return@withContext null
        if (build > currentBuild) AppUpdate(build, apk) else null
    }

    /** Fire TV needs a one-off "allow installs from this app" before an app can update itself. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where that permission is switched on. Returns false if Fire OS hides it. */
    fun openInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Downloads the APK into the app's private cache. */
    suspend fun download(update: AppUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "couch-arcade-${update.build}.apk")
        val req = Request.Builder().url(update.apkUrl).header("User-Agent", "CouchArcade").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (HTTP ${res.code})")
            val body = res.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    /** Hands the APK to Android's installer, which asks "Update this app?". */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
