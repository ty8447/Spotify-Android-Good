package com.taehagen.spotifygood.ui.screens.video

import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

@Serializable
internal data class FeedClip(
    val uri: String? = null,
    val title: String? = null,
    val image: String? = null,
    val videoUrl: String? = null,
    val videoFile: String? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val entrypoint: String? = null,
)

@Serializable
private data class FeedPage(val items: List<FeedClip> = emptyList(), val types: List<String> = emptyList())

/**
 * Clips: Spotify's watch feed for [entityUri] (a show's or episode's short videos, as the APK's
 * `related-clips` feed, then its clip recommendations), swiped vertically. A clip with its own video
 * plays here (its range of it, looping); one that is a stretch of an episode plays that episode
 * from the clip's start. Tapping the title opens the full episode.
 */
@Composable
fun ClipsFeed(entityUri: String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val graph = rememberAppGraph()
    var clips by remember(entityUri) { mutableStateOf<List<FeedClip>?>(null) }
    var error by remember(entityUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(entityUri) {
        val attempts = listOf(
            "related-clips" to rpcArgs { put("feedId", "related-clips"); put("entityId", entityUri) },
            "clip-recs" to rpcArgs { put("feedId", "clip-recs"); put("params", JsonObject(mapOf("entity_uri" to JsonPrimitive(entityUri)))) },
        )
        for ((feed, args) in attempts) {
            val page = runCatching { graph.rpc.callOffMain<FeedPage>("catalog.watchFeed", args) }
                .onFailure { error = it.message?.take(160) }.getOrNull() ?: continue
            Log.i("ClipsFeed", "$feed for $entityUri: ${page.items.size} clips, layouts ${page.types}")
            if (page.items.isNotEmpty()) {
                clips = page.items
                return@LaunchedEffect
            }
        }
        clips = emptyList()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val list = clips
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(error ?: "No clips for this yet.", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
                else -> {
                    val pager = rememberPagerState { list.size }
                    var muted by remember { mutableStateOf(false) }
                    VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                        ClipPage(list[page], active = pager.currentPage == page, muted = muted, onOpen = onOpen, onToggleMute = { muted = !muted })
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.safeDrawingPadding().align(Alignment.TopStart)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close clips", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ClipPage(clip: FeedClip, active: Boolean, muted: Boolean, onOpen: (String) -> Unit, onToggleMute: () -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().clickable(onClick = onToggleMute)) {
        AsyncImage(model = clip.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        clip.videoUrl?.let { url ->
            val player = remember(url) {
                ExoPlayer.Builder(context).build().apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    val range = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs)
                        .apply { if (clip.endMs > clip.startMs) setEndPositionMs(clip.endMs) }.build()
                    setMediaItem(MediaItem.Builder().setUri(url).setClippingConfiguration(range).build())
                    prepare()
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            androidx.compose.runtime.SideEffect {
                player.playWhenReady = active
                player.volume = if (muted) 0f else 1f
            }
            AndroidView(
                factory = { PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(AndroidColor.TRANSPARENT) } },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Column(Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            clip.title?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val episode = clip.entrypoint?.takeIf { it.startsWith("spotify:episode:") } ?: clip.uri?.takeIf { it.startsWith("spotify:episode:") }
            if (episode != null) {
                FilledTonalButton(onClick = {
                    // The full episode, from where the clip starts.
                    graph.player.play(PlayRequest(trackUris = listOf(episode), positionMs = clip.startMs))
                    onOpen(episode)
                }) { Text(if (clip.videoUrl == null && clip.startMs > 0) "Play from ${clip.startMs / 60_000}:${"%02d".format(clip.startMs / 1000 % 60)}" else "Play full episode") }
            }
        }
        if (clip.videoUrl != null) {
            Icon(
                if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(16.dp),
            )
        }
    }
}
