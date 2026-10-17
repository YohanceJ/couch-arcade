package com.couch.arcade.data

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Games live in /sdcard/CouchArcade/games/<game key>/ so RetroArch can read them.
 * Only downloaded games are stored here; RetroArch keeps saves in its own folder,
 * so clearing the cache never touches progress.
 */
class GameCache(private val repo: Repository, private val settings: Settings) {

    val root: File = File(Environment.getExternalStorageDirectory(), "CouchArcade/games")

    private fun dirFor(game: Game) = File(root, game.key.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun metaFile(dir: File) = File(dir, ".game.json")
    private fun playedMarker(dir: File) = File(dir, ".lastplayed")

    /** The playable file for a game if it's already downloaded. */
    fun cachedFile(game: Game): File? {
        val dir = dirFor(game)
        val name = File(dir, ".rom").takeIf { it.exists() }?.readText()?.trim() ?: return null
        return File(dir, name).takeIf { it.exists() }
    }

    /** Downloaded games, most recently played first (for "Continue playing"). */
    fun recentGames(): List<Game> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.sortedByDescending { lastPlayed(it) }.mapNotNull { dir ->
            runCatching { json.decodeFromString(Game.serializer(), metaFile(dir).readText()) }.getOrNull()
                ?.takeIf { cachedFile(it) != null }
        }
    }

    fun markPlayed(game: Game) {
        val dir = dirFor(game)
        if (dir.exists()) playedMarker(dir).writeText(System.currentTimeMillis().toString())
    }

    fun remove(game: Game) {
        dirFor(game).deleteRecursively()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun lastPlayed(dir: File): Long =
        playedMarker(dir).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: dir.lastModified()

    /** Delete least-recently-played games until [incoming] more bytes fit under the limit. */
    private fun makeRoom(incoming: Long, keep: File) {
        val limit = settings.cacheLimitMb.toLong() * 1024 * 1024
        var used = usedBytes()
        // Only finished games (with a .rom marker) are eligible, so parallel downloads are never touched.
        val dirs = (root.listFiles()?.filter { it.isDirectory && it != keep && File(it, ".rom").exists() } ?: emptyList())
            .sortedBy { lastPlayed(it) }
        for (d in dirs) {
            if (used + incoming <= limit) break
            used -= d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
        }
    }

    /** Download (if needed) and return the file RetroArch should open. */
    suspend fun ensure(game: Game, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        cachedFile(game)?.let { return@withContext it }

        val target = repo.resolve(game)
        val dir = dirFor(game)
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Can't write to ${dir.path}. Allow storage access for Couch Arcade.")

        val req = Request.Builder().url(target.url).apply {
            header("User-Agent", "CouchArcade")
            target.headers.forEach { (k, v) -> header(k, v) }
        }.build()

        val download = File(dir, target.fileName.replace('/', '_'))
        repo.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed (HTTP ${res.code})")
            val body = res.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: game.sizeBytes ?: -1L
            makeRoom(if (total > 0) total else 0, dir)
            body.byteStream().use { input ->
                download.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }

        val playable = prepare(download, game, dir)
        File(dir, ".rom").writeText(playable.name)
        metaFile(dir).writeText(json.encodeToString(Game.serializer(), game))
        playedMarker(dir).writeText(System.currentTimeMillis().toString())
        playable
    }

    /** Unzip when the console's emulator wants the raw file (arcade keeps its zip). */
    private fun prepare(file: File, game: Game, dir: File): File {
        val platform = game.platformInfo ?: return file
        if (!file.name.lowercase().endsWith(".zip") || platform.key == "mame") return file

        val extracted = mutableListOf<File>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = File(entry.name).name
                if (name.startsWith(".")) continue
                val out = File(dir, name)
                out.outputStream().use { zip.copyTo(it) }
                extracted += out
            }
        }
        // Prefer playlist/cue files for disc games, then any file the core understands.
        val preferred = listOf("m3u", "cue", "gdi") + platform.extensions
        val playable = preferred.firstNotNullOfOrNull { ext ->
            extracted.firstOrNull { it.extension.lowercase() == ext }
        }
        return if (playable != null) {
            file.delete()
            playable
        } else {
            extracted.forEach { it.delete() }
            file // let RetroArch try the zip itself
        }
    }
}
