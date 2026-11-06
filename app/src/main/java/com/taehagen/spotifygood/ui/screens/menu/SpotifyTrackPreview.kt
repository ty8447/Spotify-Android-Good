package com.taehagen.spotifygood.ui.screens.menu

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class SpotifyPreviewResult(val previewUrl: String? = null)

private val previewTrackUri = Regex("^spotify:track:[A-Za-z0-9]{22}$")
private val previewAudioUrl = Regex("^https://p\\.scdn\\.co/mp3-preview/[0-9a-f]{40}$")

/** Independent MP3 preview. No main-player or Jam queue mutations. */
internal class SpotifyTrackPreviewController(
    private val scope: CoroutineScope,
    private val resolveUrl: suspend (String) -> String?,
) {
    var activeUri by mutableStateOf<String?>(null)
        private set
    var loadingUri by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    private var player: MediaPlayer? = null
    private var generation = 0
    private var ticker: Job? = null
    private var loadJob: Job? = null

    fun stop() {
        generation++
        loadJob?.cancel()
        loadJob = null
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.release() } }
        player = null
        activeUri = null
        loadingUri = null
        progress = 0f
    }

    fun toggle(uri: String, onError: (String) -> Unit = {}) {
        if (activeUri == uri || loadingUri == uri) {
            stop()
            return
        }
        stop()
        if (!previewTrackUri.matches(uri)) {
            onError("Only Spotify songs have audio previews.")
            return
        }
        loadingUri = uri
        val callGeneration = generation
        loadJob = scope.launch {
            try {
                val url = withTimeoutOrNull(12_000L) { resolveUrl(uri) }
                    ?.takeIf { previewAudioUrl.matches(it) }
                if (callGeneration != generation) return@launch
                if (url == null) {
                    stop(); onError("No audio preview is available for this song.")
                    return@launch
                }
                val mp = MediaPlayer()
                player = mp
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                mp.setDataSource(url)
                mp.setOnPreparedListener { ready ->
                    if (callGeneration == generation) {
                        runCatching { ready.start() }.onSuccess {
                            loadingUri = null
                            activeUri = uri
                            progress = 0f
                            // Use the actual player position; Spotify previews may be shorter than 30s.
                            val duration = runCatching { ready.duration }.getOrDefault(0)
                            val limitMs = if (duration > 0) minOf(duration, 30_000) else 30_000
                            ticker = scope.launch {
                                var elapsedMs = 0L
                                while (callGeneration == generation && activeUri == uri) {
                                    val position = runCatching { ready.currentPosition }.getOrDefault(0)
                                    progress = (position.toFloat() / limitMs.toFloat()).coerceIn(0f, 1f)
                                    if (position >= limitMs || elapsedMs >= 30_000L) {
                                        stop()
                                        break
                                    }
                                    delay(80L)
                                    elapsedMs += 80L
                                }
                            }
                        }.onFailure {
                            if (callGeneration == generation) {
                                stop(); onError("Audio preview couldn't play.")
                            }
                        }
                    }
                }
                mp.setOnCompletionListener {
                    if (callGeneration == generation) stop()
                }
                mp.setOnErrorListener { _, _, _ ->
                    if (callGeneration == generation) {
                        stop(); onError("Audio preview isn't available right now.")
                    }
                    true
                }
                mp.prepareAsync()
                scope.launch {
                    delay(12_000L)
                    if (callGeneration == generation && loadingUri == uri) {
                        stop(); onError("Audio preview timed out.")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (callGeneration == generation) {
                    stop(); onError("Couldn't load the audio preview.")
                }
            }
        }
    }
}

@Composable
internal fun rememberSpotifyTrackPreviewController(): SpotifyTrackPreviewController {
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    val controller = remember(graph.rpc, scope) {
        SpotifyTrackPreviewController(scope) { uri ->
            graph.rpc.call<SpotifyPreviewResult>("catalog.trackPreview", buildJsonObject {
                put("uri", uri)
            }).previewUrl
        }
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    return controller
}

/** Artwork overlay: player-position arc starts at 12 o'clock and moves clockwise. */
@Composable
internal fun SpotifySongPreviewOverlay(
    controller: SpotifyTrackPreviewController,
    uri: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
) {
    val playing = controller.activeUri == uri
    val loading = controller.loadingUri == uri
    Box(modifier.background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(30.dp),
                strokeWidth = 2.dp, color = Color.White)
        } else {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    Canvas(Modifier.size(38.dp)) {
                        val stroke = 2.5.dp.toPx()
                        drawCircle(Color.White.copy(alpha = 0.30f),
                            style = Stroke(width = stroke))
                        drawArc(color = Color.White, startAngle = -90f,
                            sweepAngle = 360f * controller.progress.coerceIn(0f, 1f),
                            useCenter = false, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    }
                }
                IconButton(onClick = { controller.toggle(uri, onError) },
                    modifier = Modifier.size(40.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Stop song preview" else "Preview song",
                        tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}
