package com.couch.arcade.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

/** Starts RetroArch straight into a game with the right core. */
object RetroArch {
    private val packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    private const val ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    fun installedPackage(context: Context): String? = packages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun coreFile(pkg: String, core: String) = File("/data/data/$pkg/cores/${core}_libretro_android.so")

    fun launch(context: Context, pkg: String, game: Game, rom: File) {
        val platform = game.platformInfo ?: error("Unknown console ${game.platform}")
        val sdcard = Environment.getExternalStorageDirectory().absolutePath
        val external = "$sdcard/Android/data/$pkg/files"
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(pkg, ACTIVITY)
            putExtra("ROM", rom.absolutePath)
            putExtra("LIBRETRO", coreFile(pkg, platform.core).absolutePath)
            putExtra("CONFIGFILE", "$external/retroarch.cfg")
            putExtra("IME", "com.android.inputmethod.latin/.LatinIME")
            putExtra("DATADIR", "/data/data/$pkg")
            putExtra("APK", context.packageManager.getApplicationInfo(pkg, 0).sourceDir)
            putExtra("SDCARD", sdcard)
            putExtra("EXTERNAL", external)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }
}
