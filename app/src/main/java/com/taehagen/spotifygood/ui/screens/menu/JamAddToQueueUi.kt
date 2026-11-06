package com.taehagen.spotifygood.ui.screens.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class JamRecommendationMember(
    val username: String = "",
    val displayName: String = "",
    val profileImageUrl: String? = null,
)

@Serializable
private data class JamRecommendationAttribution(
    val uri: String = "",
    val members: List<JamRecommendationMember> = emptyList(),
)

@Serializable
private data class JamGroupRecsReply(
    val result: String = "unavailable",
    val tracks: List<String> = emptyList(),
    val attributions: List<JamRecommendationAttribution> = emptyList(),
)

private data class JamDetailItem(val uri: String, val track: Track? = null, val episode: Episode? = null)

private val jamSearchTypes = listOf(
    "Songs" to SearchType.TRACK,
    "Albums" to SearchType.ALBUM,
    "Playlists" to SearchType.PLAYLIST,
    "Podcasts" to SearchType.SHOW,
    "Artists" to SearchType.ARTIST,
)

/** Add Songs, search, and collection drilldown all remain inside this Jam modal. */
@Composable
internal fun JamAddToQueuePage(onBack: () -> Unit) {
    val graph = rememberAppGraph()
    val vm = appViewModel { g -> PlayerViewModel(g) }
    val playback by vm.snapshot.collectAsStateWithLifecycle()
    val signedIn by graph.engine.user.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val recent by graph.search.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val username = signedIn?.username

    var searchPage by remember { mutableStateOf(false) }
    // A collection is browsed inside the Jam sheet, never via global navigation.
    var detailRef by remember { mutableStateOf<MediaRef?>(null) }
    var detailItems by remember { mutableStateOf<List<JamDetailItem>>(emptyList()) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailLoadingMore by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailOffset by remember { mutableIntStateOf(0) }
    var detailTotal by remember { mutableIntStateOf(0) }
    var detailRefresh by remember { mutableIntStateOf(0) }
    var detailHasMore by remember { mutableStateOf(false) }
    var detailPageTrigger by remember { mutableIntStateOf(0) }
    val detailScroll = rememberLazyListState()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    // All chips begin off. No selection means include every supported type;
    // selecting one or more chips limits results to that union.
    var selectedSearchTypes by remember { mutableStateOf<Set<SearchType>>(emptySet()) }
    val showAllTypes = selectedSearchTypes.isEmpty()
    var searchResults by remember { mutableStateOf(SearchResults()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Shared preview controller also powers preview buttons in chat sharing.
    // These short MP3 clips never alter the Jam queue or the main player.
    val preview = rememberSpotifyTrackPreviewController()

    var groupRecommendations by remember(username) { mutableStateOf<List<Track>>(emptyList()) }
    var groupMembers by remember(username) {
        mutableStateOf<Map<String, List<JamRecommendationMember>>>(emptyMap())
    }
    var groupRecsStatus by remember(username) { mutableStateOf("loading") }
    var groupRefresh by remember(username) { mutableIntStateOf(0) }

    var liked by remember(username) { mutableStateOf<List<Track>>(emptyList()) }
    var likedNextOffset by remember(username) { mutableIntStateOf(0) }
    var likedHasMore by remember(username) { mutableStateOf(true) }
    var likedLoading by remember(username) { mutableStateOf(false) }
    var likedError by remember(username) { mutableStateOf<String?>(null) }
    var likedRequest by remember(username) { mutableIntStateOf(0) }
    var likedCompletedRequest by remember(username) { mutableIntStateOf(-1) }
    val likedListState = rememberLazyListState()
    val groupListState = rememberLazyListState()

    BackHandler(enabled = searchPage) {
        keyboard?.hide()
        if (detailRef != null) detailRef = null else searchPage = false
    }

    // Search only while the search page is visible. Toggling any filter cancels
    // the previous request and performs one search for the selected type union.
    LaunchedEffect(graph, searchPage, query, selectedSearchTypes) {
        searchResults = SearchResults()
        searchError = null
        searchLoading = false
        if (!searchPage || query.isBlank()) return@LaunchedEffect
        delay(280)
        searchLoading = true
        try {
            val types = if (selectedSearchTypes.isEmpty()) {
                setOf(SearchType.TRACK, SearchType.ALBUM, SearchType.PLAYLIST,
                    SearchType.SHOW, SearchType.EPISODE, SearchType.ARTIST)
            } else {
                selectedSearchTypes + if (SearchType.SHOW in selectedSearchTypes) {
                    setOf(SearchType.EPISODE)
                } else emptySet()
            }
            searchResults = graph.search.search(query, types, limit = 40)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { searchError = "Search is unavailable right now." }
        finally { searchLoading = false }
    }

    // Open albums, playlists, artists and podcast shows within the existing sheet.
    // Use the authenticated catalog RPCs already used by the detail screens.
    LaunchedEffect(detailRef?.uri, detailRefresh) {
        val ref = detailRef ?: return@LaunchedEffect
        detailItems = emptyList()
        detailOffset = 0
        detailTotal = 0
        detailHasMore = false
        detailError = null
        detailLoading = true
        try {
            when (ref.type) {
                MediaType.ALBUM -> {
                    val album = graph.rpc.call<Album>("catalog.album",
                        buildJsonObject { put("uri", ref.uri) })
                    detailItems = album.tracks.filter { it.uri.startsWith("spotify:track:") }
                        .map { JamDetailItem(it.uri, track = it) }
                }
                MediaType.ARTIST -> {
                    val artist = graph.rpc.call<Artist>("catalog.artist",
                        buildJsonObject { put("uri", ref.uri) })
                    detailItems = artist.topTracks.map { JamDetailItem(it.uri, track = it) }
                }
                MediaType.PLAYLIST -> {
                    val playlist = graph.catalog.playlistPage(ref.uri, 0, 50)
                    detailItems = playlist.items.mapNotNull { item ->
                        item.track?.let { JamDetailItem(it.uri, track = it) }
                            ?: item.episode?.let { JamDetailItem(it.uri, episode = it) }
                    }
                    detailOffset = playlist.items.size
                    detailTotal = playlist.total
                    detailHasMore = playlist.items.isNotEmpty() && detailOffset < detailTotal
                }
                MediaType.SHOW -> {
                    val show = graph.catalog.showPage(ref.uri, 0, 50)
                    detailItems = show.episodes.map { JamDetailItem(it.uri, episode = it) }
                    detailOffset = show.episodes.size
                    detailTotal = show.total
                    detailHasMore = show.episodes.isNotEmpty() && detailOffset < detailTotal
                }
                MediaType.EPISODE -> {
                    // A standalone episode is already a playable list item.
                    detailItems = searchResults.episodes.filter { it.uri == ref.uri }
                        .map { JamDetailItem(it.uri, episode = it) }
                }
                else -> detailError = "Unsupported collection"
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { detailError = "Couldn't load songs from this item." }
        finally { detailLoading = false }
    }

    // Paginate real playlist / podcast pages. Keep the current items visible.
    LaunchedEffect(detailRef?.uri, detailPageTrigger) {
        val ref = detailRef ?: return@LaunchedEffect
        if (detailPageTrigger == 0 || !detailHasMore || detailLoadingMore || detailLoading) return@LaunchedEffect
        if (ref.type !in setOf(MediaType.PLAYLIST, MediaType.SHOW)) return@LaunchedEffect
        detailLoadingMore = true
        detailError = null
        try {
            if (ref.type == MediaType.PLAYLIST) {
                val page = graph.catalog.playlistPage(ref.uri, detailOffset, 50)
                detailItems = detailItems + page.items.mapNotNull { item ->
                    item.track?.let { JamDetailItem(it.uri, track = it) }
                        ?: item.episode?.let { JamDetailItem(it.uri, episode = it) }
                }
                detailOffset += page.items.size
                detailTotal = page.total
                detailHasMore = page.items.isNotEmpty() && detailOffset < page.total
            } else {
                val page = graph.catalog.showPage(ref.uri, detailOffset, 50)
                detailItems = detailItems + page.episodes.map { JamDetailItem(it.uri, episode = it) }
                detailOffset += page.episodes.size
                detailTotal = page.total
                detailHasMore = page.episodes.isNotEmpty() && detailOffset < page.total
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { detailError = "Couldn't load more items." }
        finally { detailLoadingMore = false }
    }
    LaunchedEffect(detailRef?.uri, detailItems.size, detailHasMore,
        detailLoading, detailLoadingMore, detailError) {
        if (detailRef == null || detailLoading || detailLoadingMore || !detailHasMore || detailError != null) return@LaunchedEffect
        snapshotFlow { detailScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collectLatest { index ->
                if (index >= detailItems.size - 5 && !detailLoadingMore) {
                    detailPageTrigger++
                }
            }
    }

    // The official social-recs service includes Track.attributedMembers (field 8),
    // with a profile URL in Member field 3. Only display verified attribution.
    LaunchedEffect(graph, username, tab, groupRefresh) {
        if (tab != 0 || username.isNullOrBlank()) return@LaunchedEffect
        groupRecsStatus = "loading"
        try {
            val reply = graph.rpc.call<JamGroupRecsReply>(
                "catalog.jamGroupRecommendations", buildJsonObject { })
            if (reply.result == "ready") {
                val uris = reply.tracks.distinct().take(40)
                groupMembers = reply.attributions.associate { it.uri to it.members }
                val resolved = graph.catalog.tracks(uris).associateBy { it.uri }
                groupRecommendations = uris.mapNotNull { resolved[it] }
                groupRecsStatus = if (uris.isEmpty()) "empty"
                    else if (groupRecommendations.isEmpty()) "unavailable" else "ready"
            } else groupRecsStatus = "unavailable"
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { groupRecsStatus = "unavailable" }
    }

    // LibraryRepository.likedTracks is a paginated backend API; use its real
    // total and offset. Keep earlier rows while fetching the next page.
    LaunchedEffect(graph, username, tab, likedRequest) {
        if (tab != 1 || username.isNullOrBlank() || !likedHasMore ||
            likedLoading || likedRequest <= likedCompletedRequest) return@LaunchedEffect
        likedLoading = true
        likedError = null
        try {
            val page = graph.library.likedTracks(likedNextOffset, 50)
            val received = page.items.map { it.track }
            liked = (liked + received).distinctBy { it.uri }
            likedNextOffset += page.items.size
            likedHasMore = received.isNotEmpty() &&
                (page.total <= 0 || likedNextOffset < page.total)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { likedError = "Could not load more Liked Songs." }
        finally {
            likedCompletedRequest = likedRequest
            likedLoading = false
        }
    }

    // Request more before the final few visible rows, including when a short
    // first page doesn't fill the viewport. Never launch overlapping requests.
    LaunchedEffect(tab, liked.size, likedLoading, likedHasMore, likedError, searchPage) {
        if (tab != 1 || searchPage || likedLoading || !likedHasMore ||
            likedError != null || liked.isEmpty()) return@LaunchedEffect
        snapshotFlow { likedListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collectLatest { last ->
                if (last >= liked.size - 5 && likedCompletedRequest == likedRequest) {
                    likedRequest++
                }
            }
    }

    fun add(track: Track) {
        if (playback.source == PlaybackSource.NONE) {
            val ok = JamLocalQueueDraft.add(username, track)
            message = if (ok) "Added to local queue (not playing yet)" else "Sign in to add songs."
        } else {
            graph.player.addToQueue(track.uri)
            message = "Song queued for playback; check the player queue for confirmation."
        }
    }
    fun saveQuery() {
        val value = query.trim()
        if (value.isNotEmpty()) scope.launch { graph.search.addRecent(RecentSearch.Query(value)) }
        keyboard?.hide()
    }
    fun openResult(ref: MediaRef) {
        scope.launch { graph.search.addRecent(RecentSearch.Item(ref)) }
        keyboard?.hide()
        detailPageTrigger = 0
        detailRef = ref
    }

    fun addEpisode(episode: Episode) {
        if (!episode.playable) return
        if (playback.source == PlaybackSource.NONE) {
            message = "Start playback before adding podcast episodes. The local draft supports songs."
        } else {
            graph.player.addToQueue(episode.uri)
            message = "Episode queued; check the player for confirmation."
        }
    }

    // Fill the bounded Jam sheet: hard-coded height(675.dp) forces the filter
    // footer off-screen on smaller devices and causes nested scroll jitter.
    Column(Modifier.fillMaxSize().background(JamUi.black)
        .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (searchPage && detailRef != null) {
            val selected = detailRef!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detailRef = null; message = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to search", tint = JamUi.white)
                }
                Text(selected.name, color = JamUi.white, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            message?.let { Text(it, color = JamUi.green, fontSize = 12.sp) }
            if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = JamUi.green)
            detailError?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = JamUi.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (detailItems.isEmpty()) detailRefresh++
                        else detailPageTrigger++
                    }) { Text("Retry", color = JamUi.green) }
                }
            }
            LazyColumn(Modifier.weight(1f), state = detailScroll,
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(detailItems.size, key = { "detail:${it}" }) { index ->
                    val item = detailItems[index]
                    item.track?.let { track ->
                        JamMusicRow(track.album?.images?.best(96) ?: selected.images.best(96),
                            track.name, track.artists.joinToString(", ") { it.name },
                            onTap = { if (track.playable) add(track) },
                            onAdd = if (track.playable) ({ add(track) }) else null,
                            preview = preview, previewUri = track.uri,
                            previewError = { message = it })
                    }
                    item.episode?.let { episode ->
                        JamMusicRow(episode.images.best(96) ?: selected.images.best(96),
                            episode.name, episode.show?.name ?: "Podcast episode",
                            onTap = { addEpisode(episode) },
                            onAdd = if (episode.playable) ({ addEpisode(episode) }) else null)
                    }
                }
                if (!detailLoading && detailItems.isEmpty() && detailError == null) item {
                    Text("Nothing to add from this item", color = JamUi.muted)
                }
                if (detailLoadingMore) item(key = "detail-more") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = JamUi.green,
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading more…", color = JamUi.muted)
                    }
                }
            }
        } else if (searchPage) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { keyboard?.hide(); searchPage = false }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Add Songs", tint = JamUi.white)
                }
                Text("What would you like to add", color = JamUi.white,
                    fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(value = query, onValueChange = { query = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Spotify") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { saveQuery() }))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                jamSearchTypes.forEach { (label, type) ->
                    FilterChip(selected = type in selectedSearchTypes, onClick = {
                        selectedSearchTypes = if (type in selectedSearchTypes) {
                            selectedSearchTypes - type
                        } else {
                            selectedSearchTypes + type
                        }
                    },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = JamUi.green,
                            selectedLabelColor = JamUi.black))
                }
            }
            if (searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = JamUi.green)
            searchError?.let { Text(it, color = JamUi.muted) }
            message?.let { Text(it, color = JamUi.green, fontSize = 12.sp) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (query.isBlank()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", color = JamUi.white,
                                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                modifier = Modifier.weight(1f))
                            if (recent.isNotEmpty()) TextButton(onClick = {
                                scope.launch { graph.search.clearRecent() }
                            }) { Text("Clear", color = JamUi.muted) }
                        }
                    }
                    items(recent, key = { it.key }) { recentItem ->
                        val label = when (recentItem) {
                            is RecentSearch.Query -> recentItem.query
                            is RecentSearch.Item -> recentItem.ref.name
                        }
                        Row(Modifier.fillMaxWidth().clickable {
                            when (recentItem) {
                                is RecentSearch.Query -> query = recentItem.query
                                is RecentSearch.Item -> query = recentItem.ref.name
                            }
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = JamUi.muted)
                            Spacer(Modifier.width(12.dp))
                            Text(label, color = JamUi.white, maxLines = 1,
                                modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                scope.launch { graph.search.removeRecent(recentItem) }
                            }) {
                                Icon(Icons.Rounded.Close, "Remove $label from recent searches",
                                    tint = JamUi.muted)
                            }
                        }
                    }
                    if (recent.isEmpty()) item {
                        Text("No recent searches", color = JamUi.muted)
                    }
                } else if (!searchLoading && searchError == null) {
                    // Multiple selected chips can contribute results together.
                    // With all chips off, render all media categories.
                    if (showAllTypes || SearchType.TRACK in selectedSearchTypes) {
                        items(searchResults.tracks, key = { "track:${it.uri}" }) { track ->
                            JamMusicRow(track.album?.images?.best(96), track.name,
                                track.artists.joinToString(", ") { it.name },
                                onTap = { saveQuery(); add(track) },
                                onAdd = if (track.playable) ({ saveQuery(); add(track) }) else null,
                                preview = preview, previewUri = track.uri,
                                previewError = { message = it })
                        }
                    }
                    if (showAllTypes || SearchType.ALBUM in selectedSearchTypes) {
                        items(searchResults.albums, key = { "album:${it.uri}" }) { album ->
                            JamMusicRow(album.images.best(96), album.name,
                                album.artists.joinToString(", ") { it.name },
                                onTap = { saveQuery(); openResult(MediaRef(MediaType.ALBUM,
                                    album.uri, album.name, images = album.images)) })
                        }
                    }
                    if (showAllTypes || SearchType.PLAYLIST in selectedSearchTypes) {
                        items(searchResults.playlists, key = { "playlist:${it.uri}" }) { playlist ->
                            JamMusicRow(playlist.images.best(96), playlist.name,
                                playlist.owner?.displayName ?: playlist.owner?.username ?: "Playlist",
                                onTap = { saveQuery(); openResult(MediaRef(MediaType.PLAYLIST,
                                    playlist.uri, playlist.name, images = playlist.images)) })
                        }
                    }
                    if (showAllTypes || SearchType.SHOW in selectedSearchTypes) {
                        items(searchResults.shows, key = { "show:${it.uri}" }) { show ->
                            JamMusicRow(show.images.best(96), show.name, show.publisher.orEmpty(),
                                onTap = { saveQuery(); openResult(MediaRef(MediaType.SHOW,
                                    show.uri, show.name, images = show.images)) })
                        }
                        items(searchResults.episodes, key = { "episode:${it.uri}" }) { episode ->
                            JamMusicRow(episode.images.best(96), episode.name,
                                episode.show?.name ?: "Podcast episode",
                                onTap = { saveQuery(); openResult(MediaRef(MediaType.EPISODE,
                                    episode.uri, episode.name, images = episode.images)) })
                        }
                    }
                    if (showAllTypes || SearchType.ARTIST in selectedSearchTypes) {
                        items(searchResults.artists, key = { "artist:${it.uri}" }) { artist ->
                            JamMusicRow(artist.images.best(96), artist.name, "Artist",
                                onTap = { saveQuery(); openResult(MediaRef(MediaType.ARTIST,
                                    artist.uri, artist.name, images = artist.images)) })
                        }
                    }
                    if (searchResults.isEmpty) item {
                        Text("No results", color = JamUi.muted,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Jam", tint = JamUi.white)
                }
                Text("Add to Queue", color = JamUi.white, fontSize = 21.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Surface(onClick = { message = null; searchPage = true },
                modifier = Modifier.fillMaxWidth(), color = JamUi.row,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, JamUi.muted)) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = JamUi.muted)
                    Spacer(Modifier.width(12.dp))
                    Text("What would you like to add?", color = JamUi.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Group recommendations", "From your Liked Songs").forEachIndexed { i, label ->
                    Surface(onClick = { tab = i; message = null }, shape = CircleShape,
                        color = if (tab == i) JamUi.green else JamUi.row,
                        modifier = Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold,
                            color = if (tab == i) JamUi.black else JamUi.white,
                            fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 9.dp, vertical = 13.dp))
                    }
                }
            }
            message?.let { Text(it, color = JamUi.green, fontSize = 12.sp) }
            if (tab == 0) {
                Text("Group recommendations", color = JamUi.white, fontSize = 17.sp)
                Text("Based on all of your tastes and songs in this Jam",
                    color = JamUi.muted, fontSize = 13.sp)
                when (groupRecsStatus) {
                    "loading" -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = JamUi.green)
                    "empty" -> Text("No group recommendations yet. Try playing music together.",
                        color = JamUi.muted, fontSize = 13.sp)
                    "unavailable" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Group recommendations aren't available for this session.",
                            color = JamUi.muted, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { groupRefresh++ }) { Text("Retry", color = JamUi.green) }
                    }
                }
                LazyColumn(Modifier.weight(1f), state = groupListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groupRecommendations, key = { it.uri }) { track ->
                        val members = groupMembers[track.uri].orEmpty()
                        JamMusicRow(track.album?.images?.best(96), track.name,
                            track.artists.joinToString(", ") { it.name },
                            onTap = { if (track.playable) add(track) },
                            onAdd = if (track.playable) ({ add(track) }) else null,
                            attributedMembers = members,
                            preview = preview, previewUri = track.uri,
                            previewError = { message = it })
                    }
                }
            } else {
                Text("Your Liked Songs", color = JamUi.white, fontSize = 17.sp)
                if (likedLoading && liked.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = JamUi.green,
                            strokeWidth = 2.dp)
                        Text("Loading Liked Songs…", color = JamUi.muted)
                    }
                }
                likedError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it, color = JamUi.muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { likedRequest++ }) {
                            Text("Retry", color = JamUi.green)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), state = likedListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(liked, key = { it.uri }) { track ->
                        JamMusicRow(track.album?.images?.best(96), track.name,
                            track.artists.joinToString(", ") { it.name },
                            onTap = { if (track.playable) add(track) },
                            onAdd = if (track.playable) ({ add(track) }) else null,
                            preview = preview, previewUri = track.uri,
                            previewError = { message = it })
                    }
                    if (likedLoading && liked.isNotEmpty()) item(key = "loading-more") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = JamUi.green,
                                strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading more…", color = JamUi.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JamMusicRow(
    imageUrl: String?, title: String, subtitle: String,
    onTap: () -> Unit, onAdd: (() -> Unit)? = null,
    attributedMembers: List<JamRecommendationMember> = emptyList(),
    preview: SpotifyTrackPreviewController? = null,
    previewUri: String? = null,
    previewError: (String) -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(5.dp))) {
            AsyncImage(model = imageUrl, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (preview != null && previewUri != null) {
                SpotifySongPreviewOverlay(
                    controller = preview, uri = previewUri,
                    modifier = Modifier.matchParentSize(), onError = previewError)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = JamUi.white, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = JamUi.muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The attributed member comes from the real social-recs response,
        // not an inferred Jam owner. No arbitrary avatar when none is supplied.
        val member = attributedMembers.firstOrNull()
        if (member != null) {
            val name = member.displayName.ifBlank { member.username }
            Box(Modifier.size(27.dp).clip(CircleShape).background(JamUi.row),
                contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = JamUi.white, fontSize = 11.sp)
                val url = member.profileImageUrl?.takeIf { it.startsWith("https://") }
                if (url != null) AsyncImage(model = url,
                    contentDescription = "Recommended for $name" +
                        if (attributedMembers.size > 1) " and ${attributedMembers.size - 1} others" else "",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, "Add $title to queue", tint = JamUi.green)
            }
        }
    }
}
