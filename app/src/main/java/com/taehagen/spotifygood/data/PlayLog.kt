package com.taehagen.spotifygood.data

import android.content.Context
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One song or episode that started playing, and where from. */
@Serializable
data class PlayLogEntry(val uri: String, val contextUri: String? = null, val playedAtMs: Long = 0)

/**
 * This phone's own listening history, like Spotify's client keeps one (`spotify:list:play-history`):
 * Spotify's servers only return the last 50 plays, so Recents shows more songs per item and
 * episodes from here. Kept for 120 days, at most 4 000 plays.
 */
class PlayLog(context: Context, private val json: Json) {
    private val file = File(context.filesDir, "play_log.json")
    private val lock = Mutex()
    private var cache: MutableList<PlayLogEntry>? = null

    fun start(scope: CoroutineScope, playback: StateFlow<PlaybackSnapshot>) {
        scope.launch(Dispatchers.IO) {
            playback
                .filter { it.status == PlaybackStatus.PLAYING && it.track != null }
                .distinctUntilChangedBy { it.track?.uri }
                .collect { s -> record(PlayLogEntry(s.track!!.uri, s.context?.uri, System.currentTimeMillis())) }
        }
    }

    private fun load(): MutableList<PlayLogEntry> = cache ?: runCatching {
        json.decodeFromString(ListSerializer(PlayLogEntry.serializer()), file.readText())
    }.getOrDefault(emptyList()).toMutableList().also { cache = it }

    private suspend fun record(e: PlayLogEntry) = lock.withLock {
        val list = load()
        // A resume of the same song isn't a new play.
        if (list.lastOrNull()?.let { it.uri == e.uri && e.playedAtMs - it.playedAtMs < 10 * 60_000 } == true) return@withLock
        list += e
        val cutoff = e.playedAtMs - 120L * 24 * 3600 * 1000
        list.removeAll { it.playedAtMs < cutoff }
        while (list.size > 4_000) list.removeAt(0)
        runCatching { file.writeText(json.encodeToString(ListSerializer(PlayLogEntry.serializer()), list)) }
    }

    /** Newest first. */
    suspend fun entries(): List<PlayLogEntry> = lock.withLock { load().asReversed().toList() }
}
