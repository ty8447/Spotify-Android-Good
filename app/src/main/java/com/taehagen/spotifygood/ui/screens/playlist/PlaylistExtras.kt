package com.taehagen.spotifygood.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/** A song's tempo and key (Spotify's mixed playlists show them on every song). */
@Serializable
internal data class AudioAttr(val bpm: Double = 0.0, val key: String = "", val camelot: String = "", val color: String = "")

@Serializable
private data class AudioAttrs(val items: Map<String, AudioAttr> = emptyMap())

/** A "Recommended Songs" row (Spotify's playlist extender). */
@Serializable
internal data class RecommendedSong(
    val uri: String,
    val name: String = "",
    val artists: String = "",
    val image: String? = null,
    val explicit: Boolean = false,
    val durationMs: Long = 0,
)

@Serializable
private data class RecommendedSongs(val items: List<RecommendedSong> = emptyList())

private fun uriArray(uris: List<String>) = JsonArray(uris.map(::JsonPrimitive))

/** Tempo and key of [uris] (looked up 100 at a time as they come), when [enabled]. */
@Composable
internal fun rememberAudioAttributes(graph: AppGraph, uris: List<String>, enabled: Boolean): Map<String, AudioAttr> {
    var known by remember { mutableStateOf<Map<String, AudioAttr>>(emptyMap()) }
    val asked = remember { mutableSetOf<String>() }
    LaunchedEffect(enabled, uris.size) {
        if (!enabled) return@LaunchedEffect
        uris.filter { it.startsWith("spotify:track:") && asked.add(it) }.chunked(100).forEach { chunk ->
            runCatching { graph.rpc.call<AudioAttrs>("catalog.audioAttributes", buildJsonObject { put("uris", uriArray(chunk)) }) }
                .onSuccess { known = known + it.items }
        }
    }
    return known
}

/** "70 bpm" over a colored Camelot key ("10A"), as in Spotify's mixed playlists. */
@Composable
internal fun BpmKeyBadge(attr: AudioAttr?) {
    if (attr == null || attr.bpm <= 0) return
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 4.dp)) {
        Text("${attr.bpm.toInt()} bpm", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        if (attr.camelot.isNotBlank()) {
            val tint = runCatching { Color(android.graphics.Color.parseColor(attr.color)) }.getOrDefault(MaterialTheme.colorScheme.surfaceContainerHighest)
            Text(attr.camelot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.Black,
                modifier = Modifier.background(tint, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** State of an owned playlist's "Recommended Songs": loads, refreshes (skipping what was shown) and adds. */
internal class RecommendedSongsState(private val graph: AppGraph, private val playlistUri: String) {
    var songs by mutableStateOf<List<RecommendedSong>>(emptyList())
    private val shown = mutableSetOf<String>()

    suspend fun load(title: String, trackUris: List<String>) {
        val skip = shown.toList()
        runCatching {
            graph.rpc.call<RecommendedSongs>("catalog.playlistRecommendations", buildJsonObject {
                put("uri", playlistUri)
                put("title", title)
                put("trackUris", uriArray(trackUris.take(200)))
                put("skipUris", uriArray(skip))
                put("count", 10)
            }).items
        }.onSuccess { list -> songs = list; shown += list.map { it.uri } }
    }

    suspend fun add(song: RecommendedSong) {
        runCatching { graph.playlists.addItems(playlistUri, listOf(song.uri)) }.onSuccess { songs = songs - song }
    }
}

/** Spotify's "Recommended Songs" section under an owned playlist's songs. */
internal fun LazyListScope.recommendedSongs(
    state: RecommendedSongsState,
    attrs: Map<String, AudioAttr>,
    mixed: Boolean,
    onPlay: (RecommendedSong) -> Unit,
    onAdd: (RecommendedSong) -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.songs.isEmpty()) return
    item(key = "recommended-title", contentType = "section") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)) {
            Text("Recommended Songs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Based on the songs in this playlist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(state.songs, key = { "recommended:${it.uri}" }, contentType = { "recommended" }) { song ->
        Row(Modifier.fillMaxWidth().clickable { onPlay(song) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.image, null, Modifier.size(48.dp), RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(song.artists, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mixed) BpmKeyBadge(attrs[song.uri])
            IconButton(onClick = { onAdd(song) }) { Icon(Icons.Rounded.AddCircleOutline, contentDescription = "Add to this playlist") }
        }
    }
    item(key = "recommended-refresh", contentType = "footer") {
        TextButton(onClick = onRefresh, modifier = Modifier.padding(start = 8.dp)) { Text("Refresh", fontWeight = FontWeight.Bold) }
    }
}

/** A prompted or mixed playlist's server changes: one at a time, the page reloading after each. */
internal class PlaylistChanges(private val graph: AppGraph, private val uri: String, private val scope: kotlinx.coroutines.CoroutineScope, private val onChanged: () -> Unit) {
    var busy by mutableStateOf(false)
        private set

    /** Calls [method] with the playlist's uri plus [args]; [settleMs]: Spotify's regeneration time before the reload. */
    fun run(method: String, settleMs: Long = 4_000, args: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { graph.rpc.call<kotlinx.serialization.json.JsonObject>(method, buildJsonObject { put("uri", uri); args() }) }
            // Mix switched while this playlist plays: the mixer takes its transitions up (or drops them).
            graph.events.playback.value.context?.uri?.takeIf { method == "playlist.setMixed" && it == uri }?.let { ctx ->
                graph.rpc.fire("dj.context", buildJsonObject { put("uri", ctx) })
            }
            kotlinx.coroutines.delay(settleMs)
            busy = false
            onChanged()
        }
    }

    /**
     * Spotify's Refine ([text]: what to change). Without an AI session to refine (or when it
     * fails) the request joins the [prompt] instead, as an edited prompt.
     */
    fun refine(text: String, prompt: String, onFallback: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val refined = runCatching {
                graph.rpc.call<kotlinx.serialization.json.JsonObject>("playlist.refinePrompted", buildJsonObject { put("uri", uri); put("text", text) })
            }.isSuccess
            if (!refined) {
                onFallback()
                runCatching {
                    graph.rpc.call<kotlinx.serialization.json.JsonObject>(
                        "playlist.editPrompt",
                        buildJsonObject { put("uri", uri); put("prompt", "${prompt.trimEnd().trimEnd('.')}. ${text.trim()}") },
                    )
                }
            }
            kotlinx.coroutines.delay(if (refined) 1_000 else 4_000)
            busy = false
            onChanged()
        }
    }
}

@Composable
internal fun rememberPlaylistChanges(graph: AppGraph, uri: String, onChanged: () -> Unit): PlaylistChanges {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(uri) { PlaylistChanges(graph, uri, scope, onChanged) }
}

/** "Update" beside a prompted playlist's song count: new songs for its prompt. */
@Composable
internal fun UpdatePromptedButton(changes: PlaylistChanges) {
    TextButton(enabled = !changes.busy, onClick = { changes.run("playlist.refreshPrompted") }) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Refresh, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (changes.busy) "Updating…" else "Update", fontWeight = FontWeight.Bold)
    }
}

/** "Prompted by <name>" (or [prefix], e.g. "Mixed by") with their picture, in place of the owner. */
@Composable
internal fun PromptedByLine(graph: AppGraph, username: String, prefix: String = "Prompted by", onClick: () -> Unit) {
    val user by androidx.compose.runtime.produceState<com.taehagen.spotifygood.model.User?>(null, username) {
        value = runCatching { graph.catalog.user(username) }.getOrNull()
    }
    val name = user?.displayName?.takeIf { it.isNotBlank() } ?: username
    com.taehagen.spotifygood.ui.screens.album.PeopleLine(
        listOf(com.taehagen.spotifygood.ui.screens.album.Person("$prefix $name", user?.images?.best(64))),
        prefix, { onClick() },
    )
}

/**
 * A prompted playlist's row under its header, like Spotify's: Notes, Mix, Refine, Edit prompt, and
 * for its prompter Add, Edit and Name & details.
 */
@Composable
internal fun PromptedPlaylistBar(
    prompt: String,
    mixed: Boolean,
    canEdit: Boolean,
    showNotes: Boolean,
    onToggleNotes: () -> Unit,
    changes: PlaylistChanges,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDetails: () -> Unit,
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var refinedViaPrompt by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = showNotes, onClick = onToggleNotes, label = { Text("Notes") })
            MixChip(mixed, changes)
            androidx.compose.material3.AssistChip(enabled = !changes.busy, onClick = { dialog = "Refine" }, label = { Text("Refine") })
            androidx.compose.material3.AssistChip(enabled = !changes.busy, onClick = { dialog = "Edit prompt" }, label = { Text("Edit prompt") })
            if (canEdit) {
                androidx.compose.material3.AssistChip(onClick = onAdd, label = { Text("Add") })
                androidx.compose.material3.AssistChip(onClick = onEdit, label = { Text("Edit") })
                androidx.compose.material3.AssistChip(onClick = onDetails, label = { Text("Name & details") })
            }
        }
        if (showNotes && prompt.isNotBlank()) {
            Text(prompt, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        }
        if (changes.busy) {
            Text("Spotify is working on it…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        } else if (refinedViaPrompt) {
            Text("Spotify couldn't refine it directly, so your request was added to the prompt.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
    dialog?.let { title ->
        // Refine: what to change (Spotify's refinement); Edit prompt: the prompt itself.
        val refine = title == "Refine"
        var text by remember { mutableStateOf(if (refine) "" else prompt) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    placeholder = if (refine) { { Text("More upbeat, fewer ballads…") } } else null)
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    dialog = null
                    if (refine) {
                        changes.refine(text, prompt) { refinedViaPrompt = true }
                    } else {
                        changes.run("playlist.editPrompt") { put("prompt", text) }
                    }
                }) { Text(if (refine) "Refine" else "Save") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

/** "Mix": smooth transitions between the songs (Spotify's mixed playlists). */
@Composable
internal fun MixChip(mixed: Boolean, changes: PlaylistChanges) {
    androidx.compose.material3.FilterChip(
        selected = mixed,
        enabled = !changes.busy,
        onClick = { changes.run("playlist.setMixed", settleMs = 500) { put("on", !mixed) } },
        leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.AllInclusive, null, Modifier.size(18.dp)) },
        label = { Text("Mix") },
    )
}

/** A prompted playlist's note under its song. */
@Composable
internal fun SongNote(note: String) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    }
}
