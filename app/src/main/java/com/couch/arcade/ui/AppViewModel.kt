package com.couch.arcade.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.couch.arcade.data.Game
import com.couch.arcade.data.GameCache
import com.couch.arcade.data.Platforms
import com.couch.arcade.data.Repository
import com.couch.arcade.data.RetroArch
import com.couch.arcade.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Shelf(val title: String, val games: List<Game>)

sealed class Screen {
    data object Home : Screen()
    data class Detail(val game: Game) : Screen()
    data object Settings : Screen()
}

/** An in-progress download. [progress] is null until the size is known. */
data class Download(val progress: Float? = null, val status: String = "Starting…")

data class UiState(
    val loading: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val screen: Screen = Screen.Home,
    /** Downloads in progress, keyed by Game.key. Several can run at once. */
    val downloads: Map<String, Download> = emptyMap(),
    /** Keys of games that are downloaded and ready to play. */
    val ready: Set<String> = emptySet(),
    val message: String? = null,
    val retroArchMissing: Boolean = false,
    val cacheUsedMb: Long = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val repo = Repository(settings)
    private val cache = GameCache(repo, settings)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val jobs = mutableMapOf<String, Job>()
    private var homebrewGames: List<Game> = emptyList()
    private var libraryGames: List<Game> = emptyList()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val homebrew = async { runCatching { repo.loadCatalog() } }
            val library = async { runCatching { repo.loadRomm() } }
            val hb = homebrew.await()
            val lib = library.await()
            homebrewGames = hb.getOrDefault(emptyList())
            libraryGames = lib.getOrDefault(emptyList())

            val errors = listOfNotNull(
                hb.exceptionOrNull()?.let { "Couldn't load the homebrew catalogue: ${it.message}" },
                lib.exceptionOrNull()?.let { "Couldn't reach your Pi library: ${it.message}" },
            )
            _state.update { it.copy(loading = false, message = errors.joinToString("\n").ifEmpty { null }) }
            rebuildShelves()
        }
    }

    private fun rebuildShelves() {
        val shelves = mutableListOf<Shelf>()
        val recent = cache.recentGames()
        if (recent.isNotEmpty()) shelves += Shelf("Ready to play", recent)
        for (p in Platforms.all) {
            val g = libraryGames.filter { it.platform == p.key }.sortedBy { it.title.lowercase() }
            if (g.isNotEmpty()) shelves += Shelf("My ${p.name} games", g)
        }
        for (p in Platforms.all) {
            val g = homebrewGames.filter { it.platform == p.key }
            if (g.isNotEmpty()) shelves += Shelf("${p.name} homebrew", g)
        }
        val all = (homebrewGames + libraryGames + recent).distinctBy { it.key }
        _state.update {
            it.copy(
                shelves = shelves,
                ready = all.filter { g -> cache.cachedFile(g) != null }.map { g -> g.key }.toSet(),
                retroArchMissing = RetroArch.installedPackage(getApplication()) == null,
                cacheUsedMb = cache.usedBytes() / (1024 * 1024),
            )
        }
    }

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun back(): Boolean {
        if (_state.value.screen !is Screen.Home) { open(Screen.Home); return true }
        return false
    }

    /** Download a game in the background. You can keep browsing while it runs. */
    fun download(game: Game) {
        if (jobs[game.key]?.isActive == true) return
        jobs[game.key] = viewModelScope.launch {
            setDownload(game, Download(status = "Finding file…"))
            try {
                cache.ensure(game) { p ->
                    setDownload(game, Download(p, "Downloading ${(p * 100).toInt()}%"))
                }
                clearDownload(game)
                rebuildShelves()
            } catch (e: CancellationException) {
                clearDownload(game)
                cache.remove(game) // drop the partial file
            } catch (e: Exception) {
                clearDownload(game)
                cache.remove(game)
                _state.update { it.copy(message = "Couldn't download ${game.title}: ${e.message}") }
            }
        }
    }

    fun cancelDownload(game: Game) {
        jobs.remove(game.key)?.cancel()
    }

    private fun setDownload(game: Game, d: Download) =
        _state.update { it.copy(downloads = it.downloads + (game.key to d)) }

    private fun clearDownload(game: Game) =
        _state.update { it.copy(downloads = it.downloads - game.key) }

    fun play(game: Game) {
        val ctx = getApplication<Application>()
        val pkg = RetroArch.installedPackage(ctx)
        if (pkg == null) {
            _state.update { it.copy(retroArchMissing = true, message = "Install RetroArch first (see Settings).") }
            return
        }
        val rom = cache.cachedFile(game)
        if (rom == null) {
            _state.update { it.copy(message = "${game.title} isn't downloaded yet.") }
            rebuildShelves()
            return
        }
        try {
            cache.markPlayed(game)
            RetroArch.launch(ctx, pkg, game, rom)
        } catch (e: Exception) {
            _state.update { it.copy(message = "Couldn't start ${game.title}: ${e.message}") }
        }
    }

    fun removeDownload(game: Game) {
        cache.remove(game)
        rebuildShelves()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun saveSettings(rommUrl: String, rommToken: String, cacheMb: Int, catalogUrl: String) {
        settings.rommUrl = rommUrl
        settings.rommToken = rommToken
        settings.cacheLimitMb = cacheMb
        settings.catalogUrl = catalogUrl
        open(Screen.Home)
        refresh()
    }

    fun onResume() {
        // Back from RetroArch: refresh "Ready to play" and the RetroArch check.
        if (!_state.value.loading) rebuildShelves()
    }
}
