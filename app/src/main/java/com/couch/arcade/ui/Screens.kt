@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.couch.arcade.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.couch.arcade.data.Game
import com.couch.arcade.data.GameSource

private val Bg = Color(0xFF14102A)
private val Card = Color(0xFF241C45)
private val Accent = Color(0xFFFF4F81)
private val Soft = Color(0xFFB9B2D9)

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            when (val s = state.screen) {
                is Screen.Home -> HomeScreen(vm, state)
                is Screen.Detail -> DetailScreen(vm, s.game)
                is Screen.Settings -> SettingsScreen(vm, state)
            }
            state.play?.let { PlayOverlay(it, vm::cancelPlay) }
            state.message?.let { MessageBar(it, vm::dismissMessage) }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(vm: AppViewModel, state: UiState) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Couch Arcade", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.width(12.dp))
            if (state.loading) Text("Loading…", color = Soft, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::refresh) { Text("Refresh") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { vm.open(Screen.Settings) }) { Text("Settings") }
        }

        if (state.retroArchMissing) {
            Hint("RetroArch isn't installed yet. Open Settings for how to add it.")
        }
        if (!state.loading && state.shelves.isEmpty()) {
            Hint("No games yet. Check your internet connection, or add your Pi in Settings.")
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(state.shelves, key = { it.title }) { shelf ->
                Column {
                    Text(
                        shelf.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(shelf.games, key = { it.key }) { game ->
                            GameCard(game, vm) { vm.open(Screen.Detail(game)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, color = Color.White, fontSize = 15.sp,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Card).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun GameCard(game: Game, vm: AppViewModel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(190.dp).height(250.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Card, focusedContainerColor = Card),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            Cover(game, vm, Modifier.fillMaxWidth().height(180.dp))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(game.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (game.platformInfo?.name ?: game.platform) + if (vm.isCached(game)) "  ·  Ready" else "",
                    color = Soft, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Cover(game: Game, vm: AppViewModel, modifier: Modifier) {
    Box(modifier.background(placeholderBrush(game)), contentAlignment = Alignment.Center) {
        Text(
            game.platformInfo?.name ?: game.platform, color = Color.White.copy(alpha = 0.85f),
            fontSize = 18.sp, fontWeight = FontWeight.Black,
        )
        if (game.image != null) {
            val req = ImageRequest.Builder(LocalContext.current).data(game.image).crossfade(true).apply {
                if (game.source is GameSource.Romm) vm.repo.rommHeaders.forEach { (k, v) -> addHeader(k, v) }
            }.build()
            AsyncImage(model = req, contentDescription = game.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
    }
}

private fun placeholderBrush(game: Game): Brush {
    val palette = listOf(
        Color(0xFF7B5CFF) to Color(0xFFFF4F81), Color(0xFF00B4D8) to Color(0xFF7B5CFF),
        Color(0xFFFF8C42) to Color(0xFFFF4F81), Color(0xFF2EC4B6) to Color(0xFF3A86FF),
    )
    val (a, b) = palette[(game.platform.hashCode() and 0x7fffffff) % palette.size]
    return Brush.linearGradient(listOf(a, b))
}

// ---------------------------------------------------------------- Detail

@Composable
private fun DetailScreen(vm: AppViewModel, game: Game) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(game.key) { runCatching { playFocus.requestFocus() } }
    val cached = vm.isCached(game)

    Row(Modifier.fillMaxSize().padding(56.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Cover(game, vm, Modifier.width(320.dp).height(320.dp).clip(RoundedCornerShape(18.dp)))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(game.platformInfo?.name ?: game.platform, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(game.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
            game.author?.let { Text("by $it", color = Soft, fontSize = 16.sp) }
            Spacer(Modifier.height(16.dp))
            game.description?.let {
                Text(it, color = Color.White, fontSize = 17.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            val info = buildList {
                add(if (game.origin == "library") "From your Pi" else "Free homebrew")
                game.sizeBytes?.let { add(formatSize(it)) }
                add(if (cached) "Downloaded" else "Downloads when you press Play")
            }
            Text(info.joinToString("  ·  "), color = Soft, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { vm.play(game) }, modifier = Modifier.focusRequester(playFocus)) {
                    Text(if (cached) "Play" else "Download & play", fontSize = 18.sp)
                }
                if (cached) OutlinedButton(onClick = { vm.removeDownload(game) }) { Text("Remove download") }
                OutlinedButton(onClick = { vm.open(Screen.Home) }) { Text("Back") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toFloat())
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
}

// ---------------------------------------------------------------- Play overlay

@Composable
private fun PlayOverlay(play: PlayState, onCancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(play.game.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(play.status, color = Soft, fontSize = 18.sp)
            Spacer(Modifier.height(20.dp))
            Box(Modifier.width(480.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(play.progress ?: 0.05f)
                        .clip(RoundedCornerShape(6.dp)).background(Accent)
                )
            }
            Spacer(Modifier.height(28.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------- Message bar

@Composable
private fun MessageBar(text: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A1730))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen(vm: AppViewModel, state: UiState) {
    var url by remember { mutableStateOf(vm.settings.rommUrl) }
    var token by remember { mutableStateOf(vm.settings.rommToken) }
    var cache by remember { mutableStateOf(vm.settings.cacheLimitMb.toString()) }
    var catalog by remember { mutableStateOf(vm.settings.catalogUrl) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black) }

        item { Section("RetroArch") }
        item {
            Text(
                if (state.retroArchMissing)
                    "Not installed. On the Fire TV open Downloader and enter:\n" +
                        "https://buildbot.libretro.com/stable/ → latest version → android → RetroArch_aarch64.apk " +
                        "(or RetroArch_ra32.apk if that won't install). Then in RetroArch use Online Updater → Core Downloader " +
                        "to add the emulators you want."
                else "Installed. Add emulators in RetroArch: Online Updater → Core Downloader. " +
                    "Cores used: NES fceumm, SNES snes9x, GB/GBC gambatte, GBA mgba, Mega Drive genesis_plus_gx, " +
                    "N64 mupen64plus_next, PS1 pcsx_rearmed, PSP ppsspp, Dreamcast flycast, Arcade mame2003_plus.",
                color = Soft, fontSize = 15.sp,
            )
        }

        item { Section("Your Pi library (RomM) — optional") }
        item { Field("RomM address, e.g. http://192.168.1.20:8080", url) { url = it } }
        item { Field("RomM API token (starts rmm_)", token) { token = it } }

        item { Section("Storage") }
        item { Field("Max space for downloaded games, in MB (now using ${state.cacheUsedMb} MB)", cache) { cache = it.filter(Char::isDigit) } }

        item { Section("Homebrew catalogue") }
        item { Field("Catalogue address", catalog) { catalog = it } }

        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { vm.saveSettings(url, token, cache.toIntOrNull() ?: 4096, catalog) }) { Text("Save") }
                OutlinedButton(onClick = { vm.open(Screen.Home) }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(label, color = Soft, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(10.dp))
                .background(Card)
                .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
