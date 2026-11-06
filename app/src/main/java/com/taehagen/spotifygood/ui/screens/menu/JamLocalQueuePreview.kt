package com.taehagen.spotifygood.ui.screens.menu

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A local-only preview source, never part of the Spotify Connect or Jam queue. */
internal object JamLastLocalContext {
    data class Saved(val user: String, val uri: String, val trackUri: String)
    private const val PREFS = "jam_local_context_v1"
    var revision by mutableIntStateOf(0)
        private set

    internal fun supported(uri: String): Boolean =
        (uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:album:")) &&
            uri.substringAfterLast(':').isNotBlank()

    fun capture(context: Context, user: String?, snapshot: PlaybackSnapshot) {
        // Preserve what this app's player last displayed, even when Connect's remote
        // device vanishes. A future live Connect source always overrides this fallback.
        if (user.isNullOrBlank()) return
        val uri = snapshot.context?.uri ?: return
        val track = snapshot.track?.uri ?: return
        if (!supported(uri) || !track.startsWith("spotify:track:")) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = "${user}:"
        if (prefs.getString(prefix + "uri", null) == uri &&
            prefs.getString(prefix + "track", null) == track) return
        prefs.edit().putString(prefix + "uri", uri).putString(prefix + "track", track).apply()
        revision++
    }

    fun read(context: Context, user: String?): Saved? {
        if (user.isNullOrBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = "${user}:"
        val uri = prefs.getString(prefix + "uri", null) ?: return null
        val track = prefs.getString(prefix + "track", null) ?: return null
        return if (supported(uri) && track.startsWith("spotify:track:")) Saved(user, uri, track) else null
    }
}

/**
 * Editable local draft while there is no active Connect source. Reading the last
 * playlist seeds it once; edit operations do not call Spotify until Play is tapped.
 */
@Composable
internal fun JamLocalQueuePreview(modifier: Modifier = Modifier) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val signedIn by graph.engine.user.collectAsStateWithLifecycle()
    val vm = appViewModel { g -> PlayerViewModel(g) }
    val playback by vm.snapshot.collectAsStateWithLifecycle()
    val username = signedIn?.username
    val revision = JamLastLocalContext.revision
    val saved = remember(username, revision) { JamLastLocalContext.read(context, username) }
    LaunchedEffect(username) { JamLocalQueueDraft.forAccount(username) }
    var contextName by remember(username, saved) { mutableStateOf<String?>(null) }
    var loading by remember(username, saved) { mutableStateOf(saved != null) }
    var error by remember(username, saved) { mutableStateOf<String?>(null) }

    LaunchedEffect(graph, saved, username) {
        if (saved == null) {
            loading = false
            JamLocalQueueDraft.seed(username, emptyList())
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val uris = if (saved.uri.startsWith("spotify:playlist:")) {
                val playlist = graph.rpc.call<Playlist>("catalog.playlist", buildJsonObject {
                    put("uri", saved.uri); put("offset", 0); put("limit", 1)
                })
                contextName = playlist.name
                graph.catalog.playlistItemUris(saved.uri)
            } else {
                val album = graph.rpc.call<Album>("catalog.album", buildJsonObject { put("uri", saved.uri) })
                contextName = album.name
                album.tracks.map { it.uri }
            }
            val at = uris.indexOfLast { it == saved.trackUri }
            val tail = uris.drop(if (at >= 0) at else 0)
                .filter { it.startsWith("spotify:track:") }.take(41)
            val requested = (listOf(saved.trackUri) + tail).distinct()
            val byUri = graph.catalog.tracks(requested).associateBy { it.uri }
            val initial = (listOf(saved.trackUri) + tail.filterIndexed { i, _ -> i != 0 || at < 0 })
                .mapNotNull(byUri::get)
            JamLocalQueueDraft.seed(username, initial)
            Log.i("SpotifyGoodJam", "local draft seeded from last device context; count=${initial.size}")
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { error = "Couldn't load the previous playlist." }
        finally { loading = false }
    }

    var editing by remember(username) { mutableStateOf(false) }
    val staged = JamLocalQueueDraft.tracks
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Edit local queue" else "Local queue", color = JamUi.white,
                fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { editing = !editing }) {
                Text(if (editing) "Done" else "Edit", color = JamUi.green)
            }
        }
        Text(contextName?.let { "Draft from $it — not playing" }
            ?: "On this phone — not playing", color = JamUi.muted, fontSize = 13.sp)
        if (staged.isEmpty()) {
            Text(if (loading) "Loading previous songs…"
                else error ?: "No tracks queued. Use Add songs to build your local queue.",
                color = JamUi.muted, fontSize = 14.sp)
        } else {
            Text("${staged.size} songs · Changes stay on this phone until you press Play",
                color = JamUi.muted, fontSize = 12.sp)
            LazyColumn(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(staged, key = { i, track -> "local:$i:${track.uri}" }) { index, track ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(5.dp)).background(JamUi.row),
                            contentAlignment = Alignment.Center) {
                            val cover = track.album?.images?.best(96)
                            if (cover?.startsWith("https://") == true) {
                                AsyncImage(model = cover, contentDescription = null,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(5.dp)))
                            } else Icon(Icons.Rounded.MusicNote, null, tint = JamUi.muted)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(track.name, color = JamUi.white, fontSize = 15.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(track.artists.joinToString(", ") { it.name }, color = JamUi.muted,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (editing) {
                            androidx.compose.material3.IconButton(onClick = { JamLocalQueueDraft.move(index, index - 1) },
                                enabled = index > 0) {
                                Icon(Icons.Rounded.ArrowUpward, "Move up", tint = JamUi.white)
                            }
                            androidx.compose.material3.IconButton(onClick = { JamLocalQueueDraft.move(index, index + 1) },
                                enabled = index < staged.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, "Move down", tint = JamUi.white)
                            }
                            androidx.compose.material3.IconButton(onClick = { JamLocalQueueDraft.remove(index) }) {
                                Icon(Icons.Rounded.Close, "Remove from local queue", tint = JamUi.white)
                            }
                        } else {
                            androidx.compose.material3.IconButton(onClick = {
                                graph.player.playTracks(staged.map { it.uri }, index)
                            }) {
                                Icon(Icons.Rounded.PlayArrow,
                                    "Play ${track.name}", tint = JamUi.green)
                            }
                        }
                    }
                }
            }
        }
    }
}
