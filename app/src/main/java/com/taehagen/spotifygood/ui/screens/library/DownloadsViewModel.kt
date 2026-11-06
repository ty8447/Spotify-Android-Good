package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.DownloadActivity
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.download.FailedCounts
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.playback.ShuffleMode
import com.taehagen.spotifygood.playback.shuffleMode
import com.taehagen.spotifygood.ui.components.SessionMessenger
import com.taehagen.spotifygood.ui.screens.album.engineReach
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class DownloadsUiState(
    val isLoading: Boolean = true,
    val usedBytes: Long = 0,
    val content: DownloadsContent = DownloadsContent(),
    /** Offline mode or no network: the banner. */
    val offline: Boolean = false,
    /** The session is ONLINE: entries that aren't downloaded can start ([canStartNow]). */
    val online: Boolean = true,
    /** Hide explicit content (or the account's filter) is on: explicit entries don't start. */
    val filterExplicit: Boolean = false,
    val nowPlaying: NowPlaying = NowPlaying(),
    /** Live downloader state (header progress, why a run stopped). */
    val activity: DownloadActivity = DownloadActivity(),
    /** Failed downloads: retryable ones and those not playable here ("Retry" leaves them failed). */
    val failed: FailedCounts = FailedCounts(),
)

class DownloadsViewModel(private val graph: AppGraph) : ViewModel() {
    private val messages = Channel<LibraryMessage>(Channel.BUFFERED)
    /** Page-only messages (nothing to play); write results go through [messenger]. */
    val events: Flow<LibraryMessage> = messages.receiveAsFlow()
    private val messenger = SessionMessenger(graph.app)

    /** Decoded metadata per URI (metadata JSON never changes for a download). */
    private val metadataCache = ConcurrentHashMap<String, DownloadMetadata>()

    /** The item the downloader works on right now (null while it is not running). */
    private val activeUri: Flow<String?> = graph.downloads.activity
        .map { activity -> activity.currentUri.takeIf { activity.running } }
        .distinctUntilChanged()

    private val content: Flow<DownloadsContent?> = combine(
        graph.downloads.items,
        graph.downloadedCollectionsFlow(),
        activeUri,
        graph.episodeProgress.version,
    ) { items, collections, active, _ ->
        // Episodes show where they were left on this phone (docs §6.5).
        buildDownloadsContent(items, collections, ::metadataOf, active).withEpisodes(graph.episodeProgress::overlay)
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(DownloadsContent()) }
        .onStart<DownloadsContent?> { emit(null) }

    private val connectivity: Flow<Triple<Boolean, Boolean, Boolean>> = combine(
        graph.offlineFlow(),
        graph.engineReachFlow(),
        graph.explicitFilterFlow(),
    ) { offline, reach, filterExplicit -> Triple(offline, reach == EngineReach.ONLINE, filterExplicit) }

    val state: StateFlow<DownloadsUiState> = combine(
        content,
        graph.downloads.usedBytes.onStart { emit(0L) }.catch { emit(0L) },
        connectivity,
        graph.nowPlayingFlow(),
        combine(graph.downloads.activity, graph.downloads.failedCounts.onStart { emit(FailedCounts()) }.catch { emit(FailedCounts()) }, ::Pair),
    ) { content, used, (offline, online, filterExplicit), nowPlaying, (activity, failed) ->
        DownloadsUiState(
            isLoading = content == null,
            usedBytes = used,
            content = (content ?: DownloadsContent()).withExplicitFilter(filterExplicit),
            offline = offline,
            online = online,
            filterExplicit = filterExplicit,
            nowPlaying = nowPlaying,
            activity = activity,
            failed = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    private fun metadataOf(item: DownloadItem): DownloadMetadata? =
        metadataCache[item.uri] ?: decodeDownloadMetadata(graph.json, item.uri, item.metadataJson)?.also { metadataCache[item.uri] = it }

    fun collectionStatus(uri: String): Flow<CollectionDownloadStatus> =
        graph.downloads.collectionStatus(uri).catch { emit(CollectionDownloadStatus.None) }.distinctUntilChanged()

    /** Shared shuffle selector; the Downloads page must not start music on toggle. */
    fun cycleShuffle() = graph.player.cycleShuffle(allowSmartWithoutContext = false)

    fun playCollection(collection: DownloadedCollection) {
        // Downloads are a local-first destination even if a weak cellular connection still
        // reports an ONLINE Spotify session. Never fetch the collection over mobile data.
        val uris = collection.playableUris(state.value.content.completed, offline = true)
        if (uris.isEmpty()) messages.trySend(LibraryMessage.NOTHING_TO_PLAY) else {
            val snapshot = graph.playback.snapshot.value
            val mode = if (snapshot.track == null) graph.player.queuedShuffleMode.value ?: snapshot.shuffleMode
                else snapshot.shuffleMode
            val shuffled = mode != ShuffleMode.OFF
            graph.player.play(PlayRequest(contextUri = collection.uri, trackUris = uris,
                startIndex = if (shuffled) null else 0, shuffle = shuffled,
                smartShuffle = false, preferOffline = true))
        }
    }

    /**
     * Plays [entry] within its section (songs or episodes): unless the session is ONLINE (by the
     * engine's reach, as playback routes) only completed items; never explicit ones while filtered.
     */
    fun playEntry(entry: DownloadEntry) {
        val current = state.value
        val section = if (entry.isEpisode) current.content.episodes else current.content.songs
        val reach = graph.engineReach()
        val downloaded = entry.uri in current.content.completed
        // A completed download must not be turned into a streaming request just because
        // the radio is connected but unusably slow.
        val online = !downloaded && reach == EngineReach.ONLINE
        when (val plan = planEntryPlay(entry, section, online, current.filterExplicit,
            connecting = !downloaded && reach == EngineReach.CONNECTING)) {
            is EntryPlay.Tracks -> graph.player.play(PlayRequest(trackUris = plan.uris,
                startIndex = plan.index, preferOffline = downloaded))
            EntryPlay.Unavailable -> messenger.post(R.string.player_unavailable)
            EntryPlay.NotDownloaded -> messages.trySend(LibraryMessage.NOTHING_TO_PLAY)
        }
    }

    fun removeCollection(uri: String) = mutate { graph.downloads.removeCollection(uri) }

    fun removeItem(uri: String) = mutate { graph.downloads.removeItems(listOf(uri)) }

    fun removeAll() = mutate { graph.downloads.removeAll() }

    fun retryFailed() = mutate(notify = false) { graph.downloads.retryFailed() }

    /** Retry of one row: says so when nothing could start (its SD card is away). */
    fun retryItem(uri: String) = mutate(notify = false) {
        graph.downloads.downloadItems(listOf(uri)).notice?.let { messenger.post(it) }
    }

    /**
     * Runs in the app scope: leaving the screen must not interrupt a removal half-way. The result
     * is shown also when the page was left meanwhile.
     */
    private fun mutate(notify: Boolean = true, block: suspend () -> Unit) {
        graph.appScope.launch {
            attempt { block() }
                .onSuccess { if (notify) messenger.post(LibraryMessage.DOWNLOAD_REMOVED.messageRes()) }
                .onFailure { messenger.post(LibraryMessage.DOWNLOAD_FAILED.messageRes()) }
        }
    }
}
