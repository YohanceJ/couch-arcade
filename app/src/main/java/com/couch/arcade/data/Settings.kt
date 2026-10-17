package com.couch.arcade.data

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("couch_arcade", Context.MODE_PRIVATE)

    var catalogUrl: String
        get() = prefs.getString("catalog_url", null) ?: DEFAULT_CATALOG
        set(v) = prefs.edit().putString("catalog_url", v.trim().ifEmpty { DEFAULT_CATALOG }).apply()

    /** e.g. http://192.168.1.20:8080 — blank means the Pi library is off. */
    var rommUrl: String
        get() = prefs.getString("romm_url", "") ?: ""
        set(v) = prefs.edit().putString("romm_url", v.trim().trimEnd('/')).apply()

    var rommToken: String
        get() = prefs.getString("romm_token", "") ?: ""
        set(v) = prefs.edit().putString("romm_token", v.trim()).apply()

    var cacheLimitMb: Int
        get() = prefs.getInt("cache_limit_mb", 4096)
        set(v) = prefs.edit().putInt("cache_limit_mb", v.coerceIn(256, 12288)).apply()

    val rommEnabled: Boolean get() = rommUrl.isNotBlank() && rommToken.isNotBlank()

    companion object {
        const val DEFAULT_CATALOG =
            "https://raw.githubusercontent.com/YohanceJ/couch-arcade/main/catalog/catalog.json"
    }
}
