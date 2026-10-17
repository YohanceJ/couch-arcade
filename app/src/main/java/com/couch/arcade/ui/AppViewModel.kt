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

data class PlayState(val game: Game, val progress: Float? = null, val status: String = "Starting…")

data class UiState(
    val loading: Boolean = true,
    val shelves: List<Shelf> = emptyList(),
    val screen: Screen = Screen.Home,
    val play: PlayState? = null,
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

    private var playJob: Job? = null
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
        if (recent.isNotEmpty()) shelves += Shelf("Continue playing", recent)
        for (p in Platforms.all) {
            val g = libraryGames.filter { it.platform == p.key }.sortedBy { it.title.lowercase() }
            if (g.isNotEmpty()) shelves += Shelf("My ${p.name} games", g)
        }
        for (p in Platforms.all) {
            val g = homebrewGames.filter { it.platform == p.key }
            if (g.isNotEmpty()) shelves += Shelf("${p.name} homebrew", g)
        }
        _state.update {
            it.copy(
                shelves = shelves,
                retroArchMissing = RetroArch.installedPackage(getApplication()) == null,
                cacheUsedMb = cache.usedBytes() / (1024 * 1024),
            )
        }
    }

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun back(): Boolean {
        val s = _state.value
        return when {
            s.play != null -> { cancelPlay(); true }
            s.screen !is Screen.Home -> { open(Screen.Home); true }
            else -> false
        }
    }

    fun isCached(game: Game) = cache.cachedFile(game) != null

    fun play(game: Game) {
        val ctx = getApplication<Application>()
        val pkg = RetroArch.installedPackage(ctx)
        if (pkg == null) {
            _state.update { it.copy(retroArchMissing = true, message = "Install RetroArch first (see Settings).") }
            return
        }
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _state.update { it.copy(play = PlayState(game, status = "Getting ${game.title}…")) }
            try {
                val rom = cache.ensure(game) { p ->
                    _state.update { s -> s.copy(play = s.play?.copy(progress = p, status = "Downloading ${(p * 100).toInt()}%")) }
                }
                _state.update { it.copy(play = it.play?.copy(progress = 1f, status = "Launching…")) }
                cache.markPlayed(game)
                RetroArch.launch(ctx, pkg, game, rom)
                _state.update { it.copy(play = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(play = null) }
            } catch (e: Exception) {
                _state.update { it.copy(play = null, message = "Couldn't start ${game.title}: ${e.message}") }
            }
        }
    }

    fun cancelPlay() {
        playJob?.cancel()
        _state.update { it.copy(play = null) }
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
        // Back from RetroArch: refresh "Continue playing" and the RetroArch check.
        if (!_state.value.loading) rebuildShelves()
    }
}
