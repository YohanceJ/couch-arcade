@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.couch.arcade.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
                is Screen.Detail -> DetailScreen(vm, state, s.game)
                is Screen.Settings -> SettingsScreen(vm, state)
            }
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
                            GameCard(game, vm, state) { vm.open(Screen.Detail(game)) }
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
private fun GameCard(game: Game, vm: AppViewModel, state: UiState, onClick: () -> Unit) {
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
                val dl = state.downloads[game.key]
                val status = when {
                    dl != null -> dl.progress?.let { "  ·  ${(it * 100).toInt()}%" } ?: "  ·  Downloading"
                    game.key in state.ready -> "  ·  Ready"
                    else -> ""
                }
                Text(
                    (game.platformInfo?.name ?: game.platform) + status,
                    color = if (dl != null || game.key in state.ready) Accent else Soft, fontSize = 12.sp, maxLines = 1,
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
private fun DetailScreen(vm: AppViewModel, state: UiState, game: Game) {
    val mainFocus = remember { FocusRequester() }
    val ready = game.key in state.ready
    val dl = state.downloads[game.key]
    // Move focus to the main button when the screen opens and whenever it changes
    // (Download -> Cancel -> Play), so the controller never loses its place.
    LaunchedEffect(game.key, ready, dl != null) { runCatching { mainFocus.requestFocus() } }

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
            }
            Text(info.joinToString("  ·  "), color = Soft, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))

            // Status line + progress bar
            val statusText = when {
                dl != null -> dl.status
                ready -> "Downloaded — ready to play"
                else -> "Not downloaded"
            }
            Text(statusText, color = if (ready || dl != null) Accent else Soft, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (dl != null) {
                ProgressBar(dl.progress)
                Spacer(Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    dl != null -> OutlinedButton(onClick = { vm.cancelDownload(game) }, modifier = Modifier.focusRequester(mainFocus)) {
                        Text("Cancel download", fontSize = 18.sp)
                    }
                    ready -> {
                        Button(onClick = { vm.play(game) }, modifier = Modifier.focusRequester(mainFocus)) {
                            Text("Play", fontSize = 18.sp)
                        }
                        OutlinedButton(onClick = { vm.removeDownload(game) }) { Text("Remove download") }
                    }
                    else -> Button(onClick = { vm.download(game) }, modifier = Modifier.focusRequester(mainFocus)) {
                        Text("Download", fontSize = 18.sp)
                    }
                }
                OutlinedButton(onClick = { vm.open(Screen.Home) }) { Text("Back") }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float?) {
    Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Card)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress ?: 0.05f)
                .clip(RoundedCornerShape(6.dp)).background(Accent)
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toFloat())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toFloat())
    else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
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
        item { Field("RomM API token (starts rmm_)", token, secret = true) { token = it } }

        item { Section("Storage") }
        item { Field("Max space for downloaded games, in MB (now using ${state.cacheUsedMb} MB)", cache, numeric = true) { cache = it.filter(Char::isDigit) } }

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

/**
 * A settings row. Selecting it opens a native Android text dialog: the Fire TV
 * keyboard behaves properly there (Done / Back close it), unlike an inline field.
 */
@Composable
private fun Field(label: String, value: String, numeric: Boolean = false, secret: Boolean = false, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val shown = when {
        value.isBlank() -> "Not set — select to enter"
        secret && value.length > 10 -> value.take(8) + "…" + value.takeLast(4)
        else -> value
    }
    Column {
        Text(label, color = Soft, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = { editTextDialog(context, label, value, numeric, onChange) },
            modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Card, focusedContainerColor = Card),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, Accent), shape = RoundedCornerShape(10.dp)),
            ),
        ) {
            Text(
                shown, color = if (value.isBlank()) Soft else Color.White, fontSize = 18.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

private fun editTextDialog(context: Context, title: String, value: String, numeric: Boolean, onDone: (String) -> Unit) {
    val input = EditText(context).apply {
        setText(value)
        setSelection(value.length)
        isSingleLine = true
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    val container = FrameLayout(context).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setView(container)
        .setPositiveButton("OK") { _, _ -> onDone(input.text.toString()) }
        .setNegativeButton("Cancel", null)
        .create()
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            onDone(input.text.toString())
            dialog.dismiss()
            true
        } else false
    }
    dialog.show()
    input.requestFocus()
}
