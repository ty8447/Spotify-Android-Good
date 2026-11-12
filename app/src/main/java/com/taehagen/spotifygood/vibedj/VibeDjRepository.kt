package com.taehagen.spotifygood.vibedj

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Vibe DJ and crossfade (native `dj.*`, docs §6.7): builds the pool of songs to mix from, keeps
 * the user's choices (in their own preferences file), and mirrors the native state for the UI.
 */
class VibeDjRepository(private val graph: AppGraph) {
    private val prefsFile = graph.app.getSharedPreferences("vibedj", Context.MODE_PRIVATE)
    private val json = graph.json

    private val _prefs = MutableStateFlow(
        prefsFile.getString(KEY_PREFS, null)?.let { runCatching { json.decodeFromString<DjPrefs>(it) }.getOrNull() } ?: DjPrefs()
    )
    val prefs: StateFlow<DjPrefs> = _prefs.asStateFlow()

    private val _source = MutableStateFlow(DjSource.entries.firstOrNull { it.id == prefsFile.getString(KEY_SOURCE, null) } ?: DjSource.CONTEXT)
    val source: StateFlow<DjSource> = _source.asStateFlow()

    private val _analyzeDownloads = MutableStateFlow(prefsFile.getBoolean(KEY_BACKGROUND, true))
    val analyzeDownloads: StateFlow<Boolean> = _analyzeDownloads.asStateFlow()

    val state: StateFlow<DjState> get() = graph.events.djState
    val plan: StateFlow<DjPlan?> get() = graph.events.djPlan

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    init {
        // Settings' Crossfade and Automix, sent again whenever a session (re)starts (the native side
        // keeps them in memory only).
        graph.appScope.launch {
            combine(graph.settings.settings, graph.events.session.distinctUntilChangedBy { it.state }) { s, _ ->
                s.crossfadeSeconds to s.automix
            }.collect { (seconds, automix) ->
                // Automix alone blends on the beat over up to 6 s; with a crossfade it uses that length.
                val length = if (seconds > 0) seconds else if (automix) 6 else 0
                graph.rpc.fire("dj.crossfade", rpcArgs { put("seconds", length); put("smart", automix) })
            }
        }
        scheduleBackgroundAnalysis()
    }

    fun setPrefs(p: DjPrefs) {
        _prefs.value = p
        prefsFile.edit().putString(KEY_PREFS, json.encodeToString(DjPrefs.serializer(), p)).apply()
        if (state.value.active) graph.rpc.fire("dj.setPrefs", json.encodeToJsonElement(p) as kotlinx.serialization.json.JsonObject)
    }

    fun setSource(s: DjSource) {
        _source.value = s
        prefsFile.edit().putString(KEY_SOURCE, s.id).apply()
    }

    fun setAnalyzeDownloads(on: Boolean) {
        _analyzeDownloads.value = on
        prefsFile.edit().putBoolean(KEY_BACKGROUND, on).apply()
        scheduleBackgroundAnalysis()
    }

    suspend fun start() {
        // Vibe DJ mixes music: a podcast or nothing playing has nothing to mix from.
        val now = graph.events.playback.value.track
        if (now == null || now.isEpisode) return
        _starting.value = true
        try {
            val pool = buildPool(_source.value)
            val snap = graph.events.playback.value
            graph.rpc.callRaw("dj.start", rpcArgs {
                put("pool", json.encodeToJsonElement(pool))
                put("source", rpcArgs { put("kind", _source.value.id); put("name", snap.context?.name ?: _source.value.label) })
                put("prefs", json.encodeToJsonElement(_prefs.value))
            })
        } finally {
            _starting.value = false
        }
    }

    fun stop() = graph.rpc.fire("dj.stop")

    fun lockNext(uri: String?) = graph.rpc.fire("dj.lockNext", rpcArgs { put("uri", uri) })

    fun swapNext() = graph.rpc.fire("dj.swapNext")

    fun mixNow() = graph.rpc.fire("dj.mixNow")

    fun setTarget(track: DjPoolTrack?, asap: Boolean) = graph.rpc.fire("dj.setTarget", rpcArgs {
        put("uri", track?.uri)
        put("asap", asap)
        put("name", track?.name)
        put("artist", track?.artist)
    })

    /** More songs for the pool (Spotify radio from the playing song). */
    suspend fun refillFromRadio() {
        val seed = graph.events.playback.value.track?.uri ?: return
        val pool = radioPool(seed, downloaded())
        if (pool.isNotEmpty()) {
            graph.rpc.callRaw("dj.setPool", rpcArgs { put("pool", json.encodeToJsonElement(pool)); put("append", true) })
        }
    }

    /** The songs of the pool, for the "Get to a song" picker. */
    suspend fun poolTracks(): List<DjPoolTrack> = buildPool(_source.value)

    suspend fun trackInfo(uri: String): DjTrack? =
        runCatching { graph.rpc.callOffMain<DjTrack?>("dj.trackInfo", rpcArgs { put("uri", uri) }) }.getOrNull()

    suspend fun cacheInfo(): DjCacheInfo = runCatching { graph.rpc.callOffMain<DjCacheInfo>("dj.cacheInfo") }.getOrDefault(DjCacheInfo())

    suspend fun clearCache() = runCatching { graph.rpc.callRaw("dj.clearCache") }

    /** Analyses up to [limit] downloaded songs (for the background worker). */
    suspend fun analyzeDownloads(limit: Int = 200): Int {
        val uris = downloaded().filter { it.startsWith("spotify:track:") }.take(limit)
        var done = 0
        for (batch in uris.chunked(20)) {
            val result = graph.rpc.callOffMain<AnalyzeResult>("dj.analyze", rpcArgs {
                put("uris", JsonArray(batch.map { JsonPrimitive(it) }))
                put("background", true)
            })
            done += result.analyzed
        }
        return done
    }

    @Serializable
    private data class AnalyzeResult(val analyzed: Int = 0, val failed: Int = 0)

    @Serializable
    private data class Radio(val contextUri: String? = null, val trackUris: List<String> = emptyList())

    private suspend fun downloaded(): Set<String> =
        runCatching { graph.database.downloads().observeCompletedUris().first().toSet() }.getOrDefault(emptySet())

    private suspend fun named(uris: List<String>, local: Set<String>): List<DjPoolTrack> {
        val tracks = uris.distinct().filter { it.startsWith("spotify:track:") }
        val meta = mutableMapOf<String, Track>()
        for (chunk in tracks.chunked(50)) {
            runCatching { graph.catalog.tracks(chunk) }.getOrNull()?.forEach { meta[it.uri] = it }
        }
        return tracks.filter { meta[it]?.playable != false }.map { uri ->
            val t = meta[uri]
            DjPoolTrack(uri, t?.name.orEmpty(), t?.artists?.firstOrNull()?.name.orEmpty(), uri in local)
        }
    }

    private suspend fun radioPool(seed: String, local: Set<String>): List<DjPoolTrack> {
        val radio = runCatching { graph.rpc.callOffMain<Radio>("catalog.radio", rpcArgs { put("uri", seed) }) }.getOrNull()
            ?: return emptyList()
        val uris = radio.contextUri?.let { runCatching { graph.catalog.playlistItemUris(it) }.getOrNull() } ?: radio.trackUris
        return named(uris.take(MAX_POOL), local)
    }

    /** The songs Vibe DJ may pick from. Offline it keeps only downloaded songs. */
    private suspend fun buildPool(source: DjSource): List<DjPoolTrack> {
        val local = downloaded()
        val snap = graph.events.playback.value
        val offline = snap.offline || !graph.engine.isOnline.value
        val queue = (listOfNotNull(snap.track) + snap.nextTracks).filter { !it.isEpisode }
        val uris: List<String> = when (source) {
            DjSource.DOWNLOADS -> local.toList()
            DjSource.QUEUE -> queue.map { it.uri }
            DjSource.RADIO -> if (offline) local.toList() else return radioPool(snap.track?.uri ?: return emptyList(), local)
            DjSource.CONTEXT -> {
                val ctx = snap.context
                val all = when {
                    offline -> null
                    ctx?.type == ContextType.PLAYLIST -> runCatching { graph.catalog.playlistItemUris(ctx.uri) }.getOrNull()
                    // "This album": its songs, topped up with similar ones (an album is short).
                    ctx?.type == ContextType.ALBUM -> runCatching {
                        graph.rpc.callOffMain<com.taehagen.spotifygood.model.Album>("catalog.album", rpcArgs { put("uri", ctx.uri) }).tracks.map { it.uri }
                    }.getOrNull()
                    else -> null
                }
                // Anything else (or too few songs): songs from Spotify that match the playing one.
                if (!offline && (all == null || all.size < MIN_CONTEXT_POOL)) {
                    val seed = snap.track?.uri ?: return emptyList()
                    val radio = radioPool(seed, local)
                    val own = named(all.orEmpty(), local)
                    return (own + radio).distinctBy { it.uri }.take(MAX_POOL)
                }
                all ?: (snap.prevTracks.map { it.uri } + queue.map { it.uri })
            }
        }
        val chosen = (if (offline) uris.filter { it in local } else uris).take(MAX_POOL)
        if (offline) return chosen.map { DjPoolTrack(it, local = true) }
        // Names for the queue's songs are already known.
        val known = queue.associateBy { it.uri }
        val missing = chosen.filter { it !in known }
        val named = named(missing, local).associateBy { it.uri }
        return chosen.mapNotNull { uri ->
            known[uri]?.let { DjPoolTrack(uri, it.name.orEmpty(), it.artists.firstOrNull()?.name.orEmpty(), uri in local) } ?: named[uri]
        }
    }

    private fun scheduleBackgroundAnalysis() {
        val wm = WorkManager.getInstance(graph.app)
        if (!_analyzeDownloads.value) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<VibeDjAnalysisWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).setRequiresStorageNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        // v2: new defaults (no key lock, no cuts, longer blends) replace the first ones.
        private const val KEY_PREFS = "prefs2"
        private const val KEY_SOURCE = "source"
        /** Below this many songs, the context is topped up with Spotify radio. */
        private const val MIN_CONTEXT_POOL = 25
        private const val KEY_BACKGROUND = "analyzeDownloads"
        private const val WORK_NAME = "vibedj-analysis"
        private const val MAX_POOL = 400
    }
}
