package com.taehagen.spotifygood.playback

import android.content.Intent
import androidx.compose.ui.MotionDurationScale
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.rpcArgs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings that act on their own while the app runs: Reduce animations ([ReducedMotion]), Include
 * Smart Shuffle in play modes, Shuffle › Fewer repeats (the songs heard lately, sent to the native
 * shuffle) and Device broadcast status (Spotify's broadcasts other apps listen for).
 */
internal fun AppGraph.startSettingsEffects() {
    appScope.launch {
        settings.settings.collect { s ->
            ReducedMotion.reduce = s.reduceAnimations
            PlaybackModes.smartShuffleInModes = s.smartShuffleMode
        }
    }
    // Fewer repeats: the songs of the last three days, again as each new one starts.
    appScope.launch {
        combine(
            settings.settings.map { it.shuffleFewerRepeats }.distinctUntilChanged(),
            playback.snapshot.map { it.track?.uri }.distinctUntilChanged(),
        ) { on, _ -> on }.collectLatest { on ->
            val since = System.currentTimeMillis() - RECENT_PLAYS_MS
            val uris = if (on) playLog.entries().filter { it.playedAtMs >= since }.map { it.uri }.distinct().take(500) else null
            rpc.fire("player.setShuffleHistory", rpcArgs { put("uris", uris?.let { u -> JsonArray(u.map(::JsonPrimitive)) } ?: JsonNull) })
        }
    }
    // Device broadcast status: com.spotify.music.* broadcasts, as Spotify sends them.
    appScope.launch {
        combine(settings.settings.map { it.deviceBroadcast }.distinctUntilChanged(), playback.snapshot) { on, s -> s.takeIf { on } }
            .distinctUntilChangedBy { s -> s?.let { Triple(it.track?.uri, it.isPlaying, it.positionMs / 1000) } }
            .collect { s ->
                val track = s?.track ?: return@collect
                val now = System.currentTimeMillis()
                app.sendBroadcast(Intent(BROADCAST_METADATA)
                    .putExtra("id", track.uri)
                    .putExtra("artist", track.artistLine)
                    .putExtra("album", track.album?.name ?: track.show?.name.orEmpty())
                    .putExtra("track", track.name.orEmpty())
                    .putExtra("length", (track.durationMs ?: s.durationMs).toInt())
                    .putExtra("timeSent", now))
                app.sendBroadcast(Intent(BROADCAST_STATE)
                    .putExtra("playing", s.isPlaying)
                    .putExtra("playbackPosition", s.positionAt().toInt())
                    .putExtra("timeSent", now))
            }
    }
}

private const val RECENT_PLAYS_MS = 3L * 24 * 3600 * 1000
private const val BROADCAST_METADATA = "com.spotify.music.metadatachanged"
private const val BROADCAST_STATE = "com.spotify.music.playbackstatechanged"

/** Settings › Reduce animations: Compose animations under it finish at once (see MainActivity). */
object ReducedMotion : MotionDurationScale {
    @Volatile var reduce = false
    override val scaleFactor: Float get() = if (reduce) 0f else 1f
}
