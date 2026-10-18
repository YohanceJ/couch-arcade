package com.couch.arcade.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A console the app knows how to launch, with the RetroArch core used for it. */
data class Platform(
    val key: String,
    val name: String,
    val core: String,
    val extensions: List<String>,
    val aliases: List<String> = emptyList(),
)

object Platforms {
    val all = listOf(
        Platform("nes", "NES", "fceumm", listOf("nes"), listOf("famicom")),
        Platform("snes", "SNES", "snes9x", listOf("sfc", "smc"), listOf("sfam")),
        Platform("gb", "Game Boy", "gambatte", listOf("gb")),
        Platform("gbc", "Game Boy Color", "gambatte", listOf("gbc", "gb")),
        Platform("gba", "Game Boy Advance", "mgba", listOf("gba")),
        Platform("megadrive", "Mega Drive", "genesis_plus_gx", listOf("md", "bin", "gen", "smd"),
            listOf("genesis", "genesis-slash-megadrive", "md")),
        Platform("mastersystem", "Master System", "genesis_plus_gx", listOf("sms"), listOf("sms")),
        Platform("n64", "Nintendo 64", "mupen64plus_next_gles3", listOf("z64", "n64", "v64")),
        Platform("psx", "PlayStation", "pcsx_rearmed", listOf("chd", "cue", "pbp", "m3u"),
            listOf("ps", "ps1", "playstation")),
        Platform("psp", "PSP", "ppsspp", listOf("iso", "cso", "chd")),
        Platform("dreamcast", "Dreamcast", "flycast", listOf("chd", "gdi", "cdi"), listOf("dc")),
        Platform("mame", "Arcade", "mame2003_plus", listOf("zip"), listOf("arcade", "fbneo", "mame2003")),
    )

    fun find(slug: String?): Platform? {
        if (slug == null) return null
        val s = slug.lowercase()
        return all.firstOrNull { it.key == s || s in it.aliases }
    }
}

/** Where a game's file comes from. */
@Serializable
sealed class GameSource {
    /** Latest GitHub release asset of a homebrew project. */
    @Serializable @SerialName("github")
    data class GitHub(val repo: String, val asset: String? = null) : GameSource()

    /** A direct download link. */
    @Serializable @SerialName("url")
    data class Url(val url: String, val fileName: String? = null) : GameSource()

    /** A game in the user's own RomM library on the Pi. */
    @Serializable @SerialName("romm")
    data class Romm(val romId: Int, val fsName: String, val multi: Boolean = false) : GameSource()
}

@Serializable
data class Game(
    val id: String,
    val title: String,
    val platform: String,
    val author: String? = null,
    val description: String? = null,
    val image: String? = null,
    val sizeBytes: Long? = null,
    val tags: List<String> = emptyList(),
    val license: String? = null,
    val source: GameSource,
    /** "homebrew" or "library" — which shelf the game came from. */
    val origin: String = "homebrew",
) {
    val key: String get() = "$origin-$id"
    val platformInfo: Platform? get() = Platforms.find(platform)
}

@Serializable
data class Catalog(
    val version: Int = 1,
    val name: String? = null,
    /** Other catalogue files to merge in, relative to this one (e.g. "homebrewhub.json"). */
    val includes: List<String> = emptyList(),
    val games: List<Game> = emptyList(),
)
