package com.couch.arcade.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    classDiscriminator = "type"
    encodeDefaults = true
}

/** A resolved, ready-to-download file. */
data class DownloadTarget(val url: String, val fileName: String, val headers: Map<String, String>)

class Repository(private val settings: Settings) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            header("User-Agent", "CouchArcade")
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for $url")
            return res.body?.string() ?: throw IOException("Empty response from $url")
        }
    }

    // ---------- Homebrew catalogue ----------

    suspend fun loadCatalog(): List<Game> = withContext(Dispatchers.IO) {
        json.decodeFromString(Catalog.serializer(), get(settings.catalogUrl))
            .games.filter { Platforms.find(it.platform) != null }
    }

    // ---------- RomM (the Pi library) ----------

    val rommHeaders: Map<String, String>
        get() = mapOf("Authorization" to "Bearer ${settings.rommToken}")

    suspend fun loadRomm(): List<Game> = withContext(Dispatchers.IO) {
        if (!settings.rommEnabled) return@withContext emptyList()
        val base = settings.rommUrl
        val platforms = json.parseToJsonElement(get("$base/api/platforms", rommHeaders)).jsonArray
        val games = mutableListOf<Game>()
        for (p in platforms) {
            val po = p.jsonObject
            val id = po.int("id") ?: continue
            val platform = Platforms.find(po.str("fs_slug")) ?: Platforms.find(po.str("slug")) ?: continue
            if ((po.int("rom_count") ?: 1) == 0) continue
            var offset = 0
            while (true) {
                val page = json.parseToJsonElement(
                    get("$base/api/roms?platform_ids=$id&platform_id=$id&limit=250&offset=$offset", rommHeaders)
                )
                val items = when (page) {
                    is JsonArray -> page
                    is JsonObject -> page["items"]?.jsonArray ?: JsonArray(emptyList())
                    else -> JsonArray(emptyList())
                }
                items.forEach { r -> rommGame(r.jsonObject, platform, base)?.let(games::add) }
                val total = (page as? JsonObject)?.int("total")
                offset += items.size
                if (page is JsonArray || items.isEmpty() || (total != null && offset >= total)) break
            }
        }
        games
    }

    private fun rommGame(r: JsonObject, platform: Platform, base: String): Game? {
        val id = r.int("id") ?: return null
        val fsName = r.str("fs_name") ?: return null
        val multi = r.bool("multi") == true || r.bool("has_multiple_files") == true ||
            ((r["files"] as? JsonArray)?.size ?: 0) > 1
        val cover = r.str("path_cover_large") ?: r.str("path_cover_small")
        val image = when {
            cover != null && cover.startsWith("http") -> cover
            cover != null -> base + "/" + cover.trimStart('/')
            else -> r.str("url_cover")
        }
        return Game(
            id = id.toString(),
            title = r.str("name") ?: fsName.substringBeforeLast('.'),
            platform = platform.key,
            description = r.str("summary"),
            image = image,
            sizeBytes = r.long("fs_size_bytes") ?: r.long("file_size_bytes"),
            source = GameSource.Romm(id, fsName, multi),
            origin = "library",
        )
    }

    // ---------- Resolving a download ----------

    suspend fun resolve(game: Game): DownloadTarget = withContext(Dispatchers.IO) {
        when (val s = game.source) {
            is GameSource.Url -> DownloadTarget(s.url, s.fileName ?: s.url.substringAfterLast('/').substringBefore('?'), emptyMap())
            is GameSource.Romm -> DownloadTarget(
                "${settings.rommUrl}/api/roms/${s.romId}/content/${android.net.Uri.encode(s.fsName)}",
                if (s.multi) "${s.fsName}.zip" else s.fsName,
                rommHeaders,
            )
            is GameSource.GitHub -> {
                val rel = json.parseToJsonElement(
                    get("https://api.github.com/repos/${s.repo}/releases/latest",
                        mapOf("Accept" to "application/vnd.github+json"))
                ).jsonObject
                val assets = rel["assets"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
                val asset = pickAsset(assets, s.asset, game.platformInfo)
                    ?: throw IOException("No downloadable file in the latest ${s.repo} release")
                DownloadTarget(asset.str("browser_download_url")!!, asset.str("name")!!, emptyMap())
            }
        }
    }

    private fun pickAsset(assets: List<JsonObject>, pattern: String?, platform: Platform?): JsonObject? {
        if (!pattern.isNullOrBlank()) {
            val re = Regex(pattern)
            assets.firstOrNull { re.containsMatchIn(it.str("name") ?: "") }?.let { return it }
        }
        val exts = platform?.extensions ?: emptyList()
        assets.firstOrNull { (it.str("name") ?: "").substringAfterLast('.').lowercase() in exts }?.let { return it }
        return assets.firstOrNull { (it.str("name") ?: "").lowercase().endsWith(".zip") }
    }
}

private fun JsonObject.prim(k: String) = (this[k] as? JsonElement)?.let { runCatching { it.jsonPrimitive }.getOrNull() }
private fun JsonObject.str(k: String): String? = prim(k)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
private fun JsonObject.int(k: String): Int? = prim(k)?.intOrNull
private fun JsonObject.long(k: String): Long? = prim(k)?.longOrNull
private fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull
