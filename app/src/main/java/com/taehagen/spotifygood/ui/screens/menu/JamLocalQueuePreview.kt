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
 * The fallback is populated from the last context *this phone* played. It's read-only: it
 * must never be promoted to a Connect queue until someone actually starts playback.
 * The parent replaces this entire view immediately when PlaybackSource stops being NONE.
 */
@Composable
internal fun JamLocalQueuePreview(modifier: Modifier = Modifier) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val signedIn by graph.engine.user.collectAsStateWithLifecycle()
    val vm = appViewModel { g -> PlayerViewModel(g) }
    val playback by vm.snapshot.collectAsStateWithLifecycle()
    val revision = JamLastLocalContext.revision
    val stored = remember(signedIn?.username, revision) {
        JamLastLocalContext.read(context, signedIn?.username)
    }
    // The player header's current song and context take precedence over older saved
    // history. Do not replace Catfish / Discover Weekly with an older saved album.
    val nowUri = playback.context?.uri
    val nowTrack = playback.track?.uri
    val current = if (signedIn?.username != null && nowUri != null && nowTrack != null &&
        JamLastLocalContext.supported(nowUri) && nowTrack.startsWith("spotify:track:"))
        JamLastLocalContext.Saved(signedIn!!.username, nowUri, nowTrack) else null
    val saved = current ?: stored
    var name by remember(saved) { mutableStateOf<String?>(null) }
    var playingTrack by remember(saved) { mutableStateOf<Track?>(null) }
    var upcoming by remember(saved) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(saved) { mutableStateOf(saved != null) }
    var error by remember(saved) { mutableStateOf<String?>(null) }

    LaunchedEffect(graph, saved) {
        if (saved == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            // Both lookups are READ ONLY. The playlist/album is only this device's last context.
            val uris = if (saved.uri.startsWith("spotify:playlist:")) {
                val playlist = graph.rpc.call<Playlist>("catalog.playlist", buildJsonObject {
                    put("uri", saved.uri); put("offset", 0); put("limit", 1)
                })
                name = playlist.name
                graph.catalog.playlistItemUris(saved.uri)
            } else {
                val album = graph.rpc.call<Album>("catalog.album", buildJsonObject {
                    put("uri", saved.uri)
                })
                name = album.name
                album.tracks.map { it.uri }
            }
            // Keep the player-header song first; these are the remaining tracks in
            // its playlist/album, not a mutation of the shared Jam queue.
            val lastPosition = uris.indexOfLast { it == saved.trackUri }
            val candidates = uris.drop(if (lastPosition >= 0) lastPosition + 1 else 0)
                .filter { it.startsWith("spotify:track:") }.take(40)
            val byUri = graph.catalog.tracks((listOf(saved.trackUri) + candidates).distinct())
                .associateBy { it.uri }
            playingTrack = byUri[saved.trackUri]
            upcoming = candidates.mapNotNull(byUri::get)
            Log.i("SpotifyGoodJam", "queue-fallback source=LOCAL_PREVIEW " +
                "contextType=${if (saved.uri.startsWith("spotify:playlist:")) "playlist" else "album"} " +
                "total=${uris.size} preview=${upcoming.size}")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = "This device's last playlist couldn't be loaded."
            upcoming = emptyList()
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Queue", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = JamUi.white)
        Text(name?.let { "Playing from $it" } ?: "Current playback",
            fontSize = 14.sp, color = JamUi.muted)
        if (saved == null) {
            Text("Your player has no current playlist or album context.",
                fontSize = 14.sp, color = JamUi.muted)
        } else {
            // Render the current visible song immediately, even if the catalog GET
            // is still pending. A stopped Connect device should not erase it.
            val live = playback.track?.takeIf { it.uri == saved.trackUri }
            if (live != null || playingTrack != null) {
                JamLocalPreviewRow(
                    title = live?.name ?: playingTrack?.name.orEmpty(),
                    artist = live?.artistLine ?: playingTrack?.artists?.joinToString(", ") { it.name }.orEmpty(),
                    artwork = live?.imageUrl ?: playingTrack?.album?.images?.best(96),
                    current = true,
                )
            } else if (loading) {
                Text("Loading current song…", color = JamUi.muted, fontSize = 14.sp)
            }
            if (upcoming.isNotEmpty()) {
                Text(name?.let { "Next from $it" } ?: "Up next",
                    fontSize = 14.sp, color = JamUi.muted)
                LazyColumn(modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(upcoming, key = { i, track -> "preview:$i:${track.uri}" }) { _, track ->
                        JamLocalPreviewRow(
                            title = track.name,
                            artist = track.artists.joinToString(", ") { it.name },
                            artwork = track.album?.images?.best(96),
                            current = false,
                        )
                    }
                }
            } else if (loading) {
                Text("Loading upcoming songs…", color = JamUi.muted, fontSize = 13.sp)
            } else if (error != null) {
                Text("Upcoming songs aren't available yet.", color = JamUi.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun JamLocalPreviewRow(title: String, artist: String, artwork: String?, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(5.dp))
            .background(JamUi.row), contentAlignment = Alignment.Center) {
            if (artwork?.startsWith("https://") == true) {
                AsyncImage(model = artwork, contentDescription = null,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(5.dp)))
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = JamUi.muted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (current) JamPlayingBars(false)
                Text(title, color = if (current) JamUi.green else JamUi.white,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(artist, color = JamUi.muted, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
