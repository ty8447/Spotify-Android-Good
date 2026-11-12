package com.taehagen.spotifygood.ui.screens.video

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** A music video or video episode as Spotify serves it: HLS, Widevine keys from its license server. */
@Serializable
data class VideoSource(val manifestUrl: String = "", val licenseUrl: String = "", val headers: Map<String, String> = emptyMap())

/**
 * The video of what's playing on this phone, as Spotify shows it: in Now Playing's art and the mini
 * player's thumbnail. The video is muted and follows the playing song or episode (its position,
 * play and pause), so the sound, Spotify Connect, Bluetooth and Android Auto stay as they are.
 */
object NowPlayingVideo {
    /** "Switch to audio" turns the video off (for every song until switched back). */
    val enabled = MutableStateFlow(true)
    /** What's playing has a video (also while switched to audio). */
    val available = MutableStateFlow(false)
    val player = MutableStateFlow<ExoPlayer?>(null)
    val fullscreen = MutableStateFlow(false)
    /** The playing video's width / height (Now Playing's video takes its shape). */
    val aspect = MutableStateFlow(16f / 9f)
    /** Shown in Picture in Picture (the app left while the video played). */
    val inPip = MutableStateFlow(false)

    // Spotify's video settings (kept across launches).
    val subtitles = MutableStateFlow(false)
    /** The highest video height to stream (0: automatic). */
    val quality = MutableStateFlow(0)
    val largerInBar = MutableStateFlow(false)
    val pipEnabled = MutableStateFlow(true)
    private var prefs: android.content.SharedPreferences? = null

    fun set(flow: MutableStateFlow<Boolean>, key: String, on: Boolean) {
        flow.value = on
        prefs?.edit()?.putBoolean(key, on)?.apply()
    }

    fun setQuality(height: Int) {
        quality.value = height
        prefs?.edit()?.putInt("quality", height)?.apply()
    }

    /** Leaving the app while the video plays: into Picture in Picture, when that's on. */
    fun enterPip(activity: android.app.Activity) {
        val p = player.value ?: return
        if (!pipEnabled.value || !p.playWhenReady || !pipSupported(activity)) return
        val ratio = aspect.value.coerceIn(1 / 2.39f, 2.39f)
        runCatching {
            activity.enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000)).build(),
            )
        }
    }

    private fun pipSupported(activity: android.app.Activity) =
        activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Videos by URI with when they were looked up; null: none (kept for the app's lifetime). */
    private val sources = ConcurrentHashMap<String, Pair<Long, VideoSource?>>()

    /** Whether [uri] has a video (asked once; the answer is kept). */
    suspend fun has(graph: AppGraph, uri: String): Boolean = source(graph, uri) != null

    private suspend fun source(graph: AppGraph, uri: String): VideoSource? {
        sources[uri]?.let { (at, s) -> if (s == null || System.currentTimeMillis() - at < 30 * 60_000) return s }
        val found = runCatching { graph.rpc.callOffMain<VideoSource>("catalog.video", buildJsonObject { put("uri", uri) }) }
            .getOrElse { return null } // a failed lookup is asked again next time
            .takeIf { it.manifestUrl.isNotEmpty() }
        sources[uri] = System.currentTimeMillis() to found
        return found
    }

    private fun build(context: Context, s: VideoSource): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(s.headers)
        return ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) aspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            })
            setMediaItem(
                MediaItem.Builder().setUri(s.manifestUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(s.licenseUrl).setLicenseRequestHeaders(s.headers).build(),
                    ).build(),
            )
            prepare()
        }
    }

    /** Runs while the app's UI does (on the main thread): one player per item, released on change. */
    suspend fun run(context: Context, graph: AppGraph, lifecycle: androidx.lifecycle.Lifecycle) {
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences("video", Context.MODE_PRIVATE)
            prefs = p
            subtitles.value = p.getBoolean("subtitles", false)
            quality.value = p.getInt("quality", 0)
            largerInBar.value = p.getBoolean("largerInBar", false)
            pipEnabled.value = p.getBoolean("pip", true)
        }
        val local = graph.playback.snapshot.map { s -> s.track?.uri?.takeIf { s.source != PlaybackSource.REMOTE } }.distinctUntilChanged()
        combine(local, enabled) { uri, on -> uri to on }.collectLatest { (uri, on) ->
            available.value = false
            if (uri == null) return@collectLatest
            val s = source(graph, uri) ?: return@collectLatest
            available.value = true
            if (!on) return@collectLatest
            val p = build(context.applicationContext, s)
            player.value = p
            try {
                while (true) {
                    val now = graph.playback.snapshot.value
                    if (now.track?.uri == uri) {
                        val target = now.positionAt()
                        if (p.playbackState != Player.STATE_IDLE && abs(p.currentPosition - target) > 700) p.seekTo(target)
                        // Not in the background: no one sees it there (and it would use data).
                        p.playWhenReady = now.isPlaying && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                    }
                    val maxHeight = quality.value.takeIf { it > 0 } ?: Int.MAX_VALUE
                    if (p.trackSelectionParameters.maxVideoHeight != maxHeight) {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, maxHeight).build()
                    }
                    delay(1_000)
                }
            } finally {
                player.value = null
                p.release()
            }
        }
    }
}

/** Where the video shows: one place at a time. */
enum class VideoPlace { INLINE, FULLSCREEN, PIP }

/**
 * The playing video drawn in [modifier] while [attach] and [place] is where it shows now:
 * [fit] shows the whole frame (letterboxed on black, never cropped or stretched), else it fills.
 */
@Composable
fun NowPlayingVideoSurface(attach: Boolean, modifier: Modifier = Modifier, fit: Boolean = false, place: VideoPlace = VideoPlace.INLINE) {
    val player by NowPlayingVideo.player.collectAsState()
    val fullscreen by NowPlayingVideo.fullscreen.collectAsState()
    val pip by NowPlayingVideo.inPip.collectAsState()
    val current = when {
        pip -> VideoPlace.PIP
        fullscreen -> VideoPlace.FULLSCREEN
        else -> VideoPlace.INLINE
    }
    val p = player ?: return
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                setBackgroundColor(AndroidColor.BLACK)
                resizeMode = if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        update = { view -> view.player = if (attach && place == current) p else null },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

/** Whether the video is showing (Now Playing then shows the cover small beside the title). */
@Composable
fun videoShowing(): Boolean = NowPlayingVideo.player.collectAsState().value != null

/** Spotify's "Switch to audio" / "Switch to video" under Now Playing's art (and Subtitles with the video off). */
@Composable
fun VideoModeRow(modifier: Modifier = Modifier) {
    val available by NowPlayingVideo.available.collectAsState()
    val on by NowPlayingVideo.enabled.collectAsState()
    val fullscreen by NowPlayingVideo.fullscreen.collectAsState()
    val subs by NowPlayingVideo.subtitles.collectAsState()
    if (!available) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { NowPlayingVideo.enabled.value = !on },
            label = { Text(if (on) "Switch to audio" else "Switch to video") },
            leadingIcon = { Icon(if (on) Icons.Rounded.MusicNote else Icons.Rounded.OndemandVideo, null, Modifier.size(18.dp)) },
        )
        if (!on) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            androidx.compose.material3.FilterChip(
                selected = subs, onClick = { NowPlayingVideo.set(NowPlayingVideo.subtitles, "subtitles", !subs) },
                label = { Text("Subtitles") }, leadingIcon = { Icon(Icons.Rounded.ClosedCaption, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (fullscreen && on) {
        Dialog(onDismissRequest = { NowPlayingVideo.fullscreen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NowPlayingVideoSurface(attach = true, modifier = Modifier.fillMaxSize(), fit = true, place = VideoPlace.FULLSCREEN)
                IconButton(onClick = { NowPlayingVideo.fullscreen.value = false }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Exit full screen", tint = Color.White)
                }
            }
        }
    }
}

/** "Watch" for an episode or trailer with a video: plays it here with the video on, in Now Playing. */
@Composable
fun VideoButton(uri: String?, modifier: Modifier = Modifier) {
    if (uri == null) return
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val has by androidx.compose.runtime.produceState(false, uri) { value = NowPlayingVideo.has(graph, uri) }
    if (!has) return
    IconButton(
        onClick = {
            NowPlayingVideo.enabled.value = true
            graph.player.play(PlayRequest(trackUris = listOf(uri)))
            navigator.openNowPlaying()
        },
        modifier = modifier,
    ) { Icon(Icons.Rounded.OndemandVideo, contentDescription = "Watch video") }
}

/** The subtitles of what's playing: an episode's transcript, a song's lyrics ([lyrics]); null while unknown. */
@Composable
fun rememberSubtitles(uri: String, isEpisode: Boolean, lyrics: com.taehagen.spotifygood.model.Lyrics?): List<com.taehagen.spotifygood.model.LyricsLine> {
    val graph = rememberAppGraph()
    val available by NowPlayingVideo.available.collectAsState()
    val transcript by androidx.compose.runtime.produceState<List<com.taehagen.spotifygood.model.LyricsLine>>(emptyList(), uri, isEpisode && available) {
        if (isEpisode && available) {
            value = runCatching {
                graph.rpc.callOffMain<com.taehagen.spotifygood.model.Lyrics>("catalog.transcript", buildJsonObject { put("uri", uri) }).lines
            }.getOrDefault(emptyList())
        }
    }
    return if (isEpisode) transcript else lyrics?.lines.orEmpty()
}

private fun lineAt(lines: List<com.taehagen.spotifygood.model.LyricsLine>, ms: Long): Int = lines.indexOfLast { it.startTimeMs <= ms }

/**
 * Spotify's controls on Now Playing's video: Subtitles and the settings gear at the top right, full
 * screen at the bottom right, and the subtitle line along the bottom when they're on.
 */
@Composable
fun VideoOverlay(lines: List<com.taehagen.spotifygood.model.LyricsLine>, positionMs: androidx.compose.runtime.State<Long>, modifier: Modifier = Modifier) {
    val subs by NowPlayingVideo.subtitles.collectAsState()
    var settings by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val line by androidx.compose.runtime.remember(lines) {
        androidx.compose.runtime.derivedStateOf { lines.getOrNull(lineAt(lines, positionMs.value))?.words }
    }
    Box(modifier) {
        Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { NowPlayingVideo.set(NowPlayingVideo.subtitles, "subtitles", !subs) }) {
                Icon(if (subs) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, contentDescription = "Subtitles", tint = Color.White)
            }
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Video settings", tint = Color.White) }
        }
        IconButton(onClick = { NowPlayingVideo.fullscreen.value = true }, modifier = Modifier.align(Alignment.BottomEnd)) {
            Icon(Icons.Rounded.Fullscreen, contentDescription = "Full screen", tint = Color.White)
        }
        val text = line
        if (subs && !text.isNullOrBlank()) {
            Text(
                text, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 48.dp, end = 48.dp, bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
    if (settings) VideoSettingsSheet(onDismiss = { settings = false })
}

/** Spotify's video settings: Subtitles, Video quality, Larger video in Now Playing Bar, Picture in Picture. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VideoSettingsSheet(onDismiss: () -> Unit) {
    val subs by NowPlayingVideo.subtitles.collectAsState()
    val quality by NowPlayingVideo.quality.collectAsState()
    val larger by NowPlayingVideo.largerInBar.collectAsState()
    val pip by NowPlayingVideo.pipEnabled.collectAsState()
    var pickQuality by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val qualities = listOf(0 to "Automatic", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        @Composable
        fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) = androidx.compose.material3.ListItem(
            headlineContent = { Text(title) },
            trailingContent = { androidx.compose.material3.Switch(on, onChange) },
            modifier = Modifier.clickable { onChange(!on) },
        )
        SwitchRow("Subtitles", subs) { NowPlayingVideo.set(NowPlayingVideo.subtitles, "subtitles", it) }
        androidx.compose.material3.ListItem(
            headlineContent = { Text("Video quality") },
            supportingContent = { Text(qualities.firstOrNull { it.first == quality }?.second ?: "Automatic") },
            modifier = Modifier.clickable { pickQuality = !pickQuality },
        )
        if (pickQuality) qualities.forEach { (h, label) ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(label) },
                leadingContent = { androidx.compose.material3.RadioButton(selected = h == quality, onClick = null) },
                modifier = Modifier.padding(start = 16.dp).clickable { NowPlayingVideo.setQuality(h); pickQuality = false },
            )
        }
        SwitchRow("Larger video in Now Playing Bar", larger) { NowPlayingVideo.set(NowPlayingVideo.largerInBar, "largerInBar", it) }
        SwitchRow("Enable Picture in Picture", pip) { NowPlayingVideo.set(NowPlayingVideo.pipEnabled, "pip", it) }
        androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
    }
}

/**
 * Subtitles in the video's place while it's switched off, like the lyrics: the current line bright,
 * the list following along.
 */
@Composable
fun SubtitlesPanel(lines: List<com.taehagen.spotifygood.model.LyricsLine>, positionMs: androidx.compose.runtime.State<Long>, modifier: Modifier = Modifier) {
    val current by androidx.compose.runtime.remember(lines) { androidx.compose.runtime.derivedStateOf { lineAt(lines, positionMs.value) } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyColumn(
        state = list,
        userScrollEnabled = false,
        modifier = modifier.background(Color(0xFF1F1F1F), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp),
    ) {
        items(lines.size) { i ->
            Text(
                lines[i].words,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = if (i == current) Color.White else Color.White.copy(alpha = if (i < current) 0.55f else 0.35f),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/** The whole window while in Picture in Picture: only the video. */
@Composable
fun PipVideo() {
    val pip by NowPlayingVideo.inPip.collectAsState()
    if (pip) Box(Modifier.fillMaxSize().background(Color.Black)) {
        NowPlayingVideoSurface(attach = true, modifier = Modifier.fillMaxSize(), fit = true, place = VideoPlace.PIP)
    }
}
