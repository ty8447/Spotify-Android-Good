package com.taehagen.spotifygood.ui.screens.show

import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.ui.graphics.Color
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.MediaRow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.DetailHeader
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.EpisodeRow
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.components.PartialContentNotice
import com.taehagen.spotifygood.ui.components.PlayFab
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.navigation.AppNavigator
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.album.savedFlow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import com.taehagen.spotifygood.ui.screens.album.canStartNow
import com.taehagen.spotifygood.ui.screens.album.AddedButton
import com.taehagen.spotifygood.ui.screens.album.CollectionDownloadButton
import com.taehagen.spotifygood.ui.screens.album.DownloadedCopyNotice
import com.taehagen.spotifygood.ui.screens.album.DetailActionRow
import com.taehagen.spotifygood.ui.screens.album.DetailScaffold
import com.taehagen.spotifygood.ui.screens.album.ExpandableText
import com.taehagen.spotifygood.ui.screens.album.FollowButton
import com.taehagen.spotifygood.ui.screens.album.HeaderMetaText
import com.taehagen.spotifygood.ui.screens.album.LoadMoreEffect
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.LoadStateContent
import com.taehagen.spotifygood.ui.screens.album.MoreButton
import com.taehagen.spotifygood.ui.screens.album.PagingFooter
import com.taehagen.spotifygood.ui.screens.album.RemoveDownloadDialog
import com.taehagen.spotifygood.ui.screens.album.currentLocale
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.durationText
import com.taehagen.spotifygood.ui.screens.album.formatShortDate
import com.taehagen.spotifygood.ui.screens.album.isRefreshing
import com.taehagen.spotifygood.ui.screens.album.rememberRichText
import com.taehagen.spotifygood.ui.screens.album.shareUrl
import java.time.LocalDate
import com.taehagen.spotifygood.ui.components.FastScroller
import com.taehagen.spotifygood.ui.screens.album.detailTopInset

@Composable
fun ShowScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> ShowViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    LoadMoreEffect(
        listState = listState,
        enabled = state.load is LoadState.Ready && !state.list.endReached && !state.list.failed,
        onLoadMore = viewModel::loadMore,
    )

    DetailScaffold(
        title = state.load.dataOrNull()?.show?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        refreshing = state.load.isRefreshing,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { header ->
            ShowList(
                header = header,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlay = viewModel::playContext,
                onToggleFollow = viewModel::toggleFollow,
                onDownload = viewModel::download,
                onRemoveDownload = { viewModel.removeCollectionDownload() },
                onPlayEpisode = viewModel::playEpisode,
                onSort = viewModel::setSort,
                onRetryPage = viewModel::loadMore,
                onRetryPartial = viewModel::retryPartial,
                onRetry = viewModel::retry,
                rows = viewModel,
            )
        }
    }
}

@Composable
private fun ShowList(
    header: ShowHeader,
    state: ShowUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlay: () -> Unit,
    onToggleFollow: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onSort: (EpisodeSort) -> Unit,
    onRetryPage: () -> Unit,
    onRetryPartial: () -> Unit,
    onRetry: () -> Unit,
    rows: ShowViewModel,
) {
    val show = header.show
    var settingsOpen by remember { mutableStateOf(false) }
    var clipsOpen by remember { mutableStateOf(false) }
    if (clipsOpen) com.taehagen.spotifygood.ui.screens.video.ClipsFeed(show.uri, onOpen = { clipsOpen = false }, onDismiss = { clipsOpen = false })
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // "Search in this show": loads the remaining pages while a search is open.
    LaunchedEffect(searching, query, state.list.episodes.size, state.list.endReached, state.list.loading) {
        if (searching && query.isNotBlank() && !state.list.endReached && !state.list.loading && !state.list.failed) onRetryPage()
    }
    val episodes = if (!searching || query.isBlank()) state.list.episodes else state.list.episodes.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    if (settingsOpen) ShowSettingsSheet(show.uri, state.list.episodes, onDismiss = { settingsOpen = false })
    // Spotify's show extras: rating, topics (the type tags) and the new-episode bell.
    val graph = com.taehagen.spotifygood.ui.components.rememberAppGraph()
    val extrasScope = androidx.compose.runtime.rememberCoroutineScope()
    var extras by remember(show.uri) { mutableStateOf<ShowExtras?>(null) }
    var rating by remember(show.uri) { mutableStateOf(false) }
    LaunchedEffect(show.uri) {
        extras = runCatching {
            graph.json.decodeFromJsonElement(ShowExtras.serializer(), graph.rpc.callRaw("catalog.showExtras", kotlinx.serialization.json.buildJsonObject { put("uri", show.uri) }))
        }.getOrNull()
    }
    // Spotify's show page tabs: Episodes, About, More like this (loaded when first opened).
    var tab by remember(show.uri) { mutableIntStateOf(0) }
    var similar by remember(show.uri) { mutableStateOf<List<MediaRef>?>(null) }
    LaunchedEffect(show.uri, tab) {
        if (tab == 2 && similar == null) similar = runCatching {
            graph.json.decodeFromJsonElement(ShowSuggestions.serializer(), graph.rpc.callRaw("catalog.showRecommendations", kotlinx.serialization.json.buildJsonObject { put("uri", show.uri) })).items
        }.getOrDefault(emptyList())
    }
    val setNotify: (Boolean) -> Unit = { on ->
        extras = extras?.copy(notify = on)
        extrasScope.launch {
            runCatching { graph.rpc.callRaw("catalog.setShowNotify", kotlinx.serialization.json.buildJsonObject { put("uri", show.uri); put("on", on) }) }
                .onFailure { extras = extras?.copy(notify = !on) }
        }
    }
    if (rating) RateShowDialog(extras?.rating?.mine ?: 0, onDismiss = { rating = false }) { stars ->
        rating = false
        extrasScope.launch {
            runCatching { graph.rpc.callRaw("catalog.rateShow", kotlinx.serialization.json.buildJsonObject { put("uri", show.uri); put("rating", stars) }) }
                .onSuccess { extras = extras?.copy(rating = extras?.rating?.copy(mine = stars)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "header", contentType = "header") {
                DetailHeader(
                    title = show.name,
                    imageUrl = show.images.best(640),
                    subtitle = show.publisher,
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                    details = {
                        if (extras?.verified == true) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Verified, contentDescription = null, tint = Color(0xFF1ED760), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Verified by Spotify", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = { Column {
                        extras?.let { x -> ShowMetaRow(x, onRate = { rating = true }) }
                        Spacer(Modifier.height(8.dp))
                        DetailActionRow(
                            leading = {
                                FollowButton(following = state.following, onClick = onToggleFollow, modifier = Modifier.padding(end = 4.dp))
                                CollectionDownloadButton(ui = state.download, onDownload = onDownload, onRemove = onRemoveDownload)
                                extras?.notify?.let { on ->
                                    IconButton(onClick = { setNotify(!on) }) {
                                        Icon(
                                            if (on) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                                            contentDescription = if (on) "Turn off new episode notifications" else "Notify me about new episodes",
                                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(onClick = { clipsOpen = true }) {
                                    Icon(Icons.Rounded.SmartDisplay, contentDescription = "Clips", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { settingsOpen = true }) {
                                    Icon(Icons.Rounded.Settings, contentDescription = "Show settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                MoreButton(
                                    onClick = { navigator.showActions(MediaActionTarget.ShowTarget(show.toRef())) },
                                    label = show.name,
                                )
                            },
                            trailing = {
                                PlayFab(isPlaying = state.playback.isPlayingContext(show.uri), onClick = onPlay)
                            },
                        )
                    } },
                )
            }
            extras?.trailerUri?.let { trailer ->
                item(key = "trailer", contentType = "trailer") {
                    // Spotify's trailer card: its cover, title, a TRAILER tag and length.
                    val episode by androidx.compose.runtime.produceState<Episode?>(null, trailer) { value = runCatching { graph.catalog.episodes(listOf(trailer)).firstOrNull() }.getOrNull() }
                    androidx.compose.material3.Surface(
                        onClick = { graph.player.play(PlayRequest(trackUris = listOf(trailer))) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center) {
                                com.taehagen.spotifygood.ui.components.Artwork(episode?.images?.best(160) ?: show.images.best(160), null, Modifier.size(64.dp), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                Icon(Icons.Rounded.PlayCircle, contentDescription = "Play trailer", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(episode?.name ?: "Trailer", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("TRAILER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                                    episode?.durationMs?.takeIf { it > 0 }?.let { ms ->
                                        Text(if (ms < 60_000) "  ${ms / 1000}s" else "  ${ms / 60_000} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            com.taehagen.spotifygood.ui.screens.video.VideoButton(trailer)
                            episode?.let { e -> MoreButton(onClick = { navigator.showActions(MediaActionTarget.EpisodeTarget(e)) }, label = e.name) }
                        }
                    }
                }
            }
            item(key = "tabs", contentType = "tabs") {
                // Spotify's left-aligned tabs with a green underline.
                androidx.compose.material3.SecondaryScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 4.dp, divider = {},
                    indicator = { androidx.compose.material3.TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = true), color = Color(0xFF1ED760)) }) {
                    listOf("Episodes", "About", "More like this").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
                    }
                }
            }
            if (tab == 1) extras?.let { x ->
                item(key = "extras", contentType = "extras") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val r = x.rating
                        if (r != null && (r.showAverage && r.total > 0 || r.canRate)) {
                            Row(
                                Modifier.clickable(enabled = r.canRate) { rating = true }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (r.showAverage && r.total > 0) "%.1f (%s)".format(r.average, compactCount(r.total)) else "Rate this show",
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                )
                                if (r.mine > 0) Text("  · You rated ${r.mine}★", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!x.topics.isNullOrEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                x.topics.forEach { t -> androidx.compose.material3.AssistChip(onClick = {}, label = { Text(t.name) }) }
                            }
                        }
                    }
                }
            }
            if (tab == 1 && !header.description.isEmpty) {
                item(key = "about", contentType = "about") {
                    val description = rememberRichText(header.description)
                    Column(Modifier.padding(top = 8.dp)) {
                        SectionHeader(stringResource(R.string.detail_about))
                        ExpandableText(
                            text = description,
                            collapsedLines = 12,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            if (tab == 2) {
                val list = similar
                when {
                    list == null -> item(key = "similar-loading") { PagingFooter(loading = true, failed = false, onRetry = {}) }
                    list.isEmpty() -> item(key = "similar-empty") { EmptyState(title = "No recommendations right now", modifier = Modifier.padding(vertical = 32.dp)) }
                    else -> items(list, key = { "similar:${it.uri}" }) { ref ->
                        val followed by graph.library.isSaved(ref.uri).collectAsStateWithLifecycle(null)
                        MediaRow(ref = ref, subtitle = listOfNotNull("Podcast", ref.subtitle?.takeIf { it.isNotBlank() }).joinToString(" • "), onClick = { navigator.open(ref) },
                            trailing = {
                                IconButton(onClick = { extrasScope.launch { runCatching { graph.library.setSaved(listOf(ref.uri), followed != true) } } }) {
                                    Icon(if (followed == true) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                        contentDescription = if (followed == true) "Following" else "Follow",
                                        tint = if (followed == true) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurface)
                                }
                            })
                    }
                }
            }
            if (tab == 0) item(key = "episodes-title", contentType = "section") {
                val sort = state.list.sort
                SectionHeader(
                    title = stringResource(R.string.detail_all_episodes),
                    modifier = Modifier.padding(top = 16.dp),
                    action = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search in this show")
                        }
                        TextButton(
                            onClick = { onSort(if (sort == EpisodeSort.NEWEST) EpisodeSort.OLDEST else EpisodeSort.NEWEST) },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = stringResource(R.string.detail_sort_episodes),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (sort == EpisodeSort.NEWEST) R.string.detail_sort_newest else R.string.detail_sort_oldest))
                        }
                    },
                )
            }
            if (tab == 0 && state.downloadedCopy) {
                item(key = "downloaded", contentType = "notice") {
                    DownloadedCopyNotice(
                        stringResource(R.string.detail_showing_downloaded_episodes),
                        onRetry = onRetry.takeUnless { state.offline },
                    )
                }
            }
            if (tab == 0 && state.partial) {
                item(key = "partial", contentType = "notice") { PartialContentNotice(onRetry = onRetryPartial) }
            }
            if (tab == 0 && searching) {
                item(key = "search", contentType = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search in this show") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (tab == 0) items(episodes, key = { it.uri }, contentType = { "episode" }) { episode ->
                val downloadState = state.rowDownloads[episode.uri]
                val playable = canStartNow(episode.playable, state.online, downloadState, episode.explicit, state.filterExplicit)
                val showActions = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) }
                val isPlaying = state.playback.isPlayingItem(episode.uri)
                // Like Spotify's: a tap opens the episode's page; its row has "+", download, more and play.
                EpisodeRow(
                    episode = episode,
                    onClick = { navigator.navigate(Route.Episode(episode.uri)) },
                    isCurrent = state.playback.isCurrent(episode.uri),
                    isPlaying = isPlaying,
                    downloadState = downloadState,
                    onLongClick = showActions,
                    // Unplayable (or not downloaded while the session isn't online): dimmed, actions still in the overflow.
                    enabled = playable,
                    openable = true,
                    actions = {
                        val graph = com.taehagen.spotifygood.ui.components.rememberAppGraph()
                        val saved by remember(episode.uri) { graph.savedFlow(episode.uri) }.collectAsStateWithLifecycle(null)
                        // Settings › Actions next to play button: the buttons sit beside it instead of across the row.
                        if (graph.settings.settings.collectAsStateWithLifecycle().value.trailingActions) Spacer(Modifier.weight(1f))
                        AddedButton(saved, { rows.toggleEpisodeSaved(episode.uri, saved) }, R.string.detail_save_episode, R.string.detail_remove_episode)
                        EpisodeDownloadButton(downloadState, onDownload = { rows.downloadEpisode(episode.uri, false) },
                            onRemove = { rows.downloadEpisode(episode.uri, true) })
                        MoreButton(onClick = showActions, label = episode.name)
                        if (!graph.settings.settings.collectAsStateWithLifecycle().value.trailingActions) Spacer(Modifier.weight(1f))
                        if (playable) {
                            androidx.compose.material3.FilledIconButton(onClick = { onPlayEpisode(episode) }) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(if (isPlaying) R.string.shell_cd_pause else R.string.shell_cd_play))
                            }
                        }
                    },
                )
            }
            if (tab == 0) item(key = "footer", contentType = "footer") {
                when {
                    state.list.episodes.isEmpty() && state.list.endReached && !state.list.loading -> EmptyState(
                        title = stringResource(R.string.detail_no_episodes),
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                    else -> PagingFooter(loading = state.list.loading, failed = state.list.failed, onRetry = onRetryPage)
                }
            }
        }
        // The rows are the episodes; the header, trailer, tabs, title, notices and footer are measured apart.
        if (tab == 0) FastScroller(
            listState,
            contentStart = 3 + (if (extras?.trailerUri != null) 1 else 0) + (if (state.downloadedCopy) 1 else 0) + (if (state.partial) 1 else 0) + (if (searching) 1 else 0),
            contentCount = state.list.episodes.size,
            topPadding = detailTopInset(),
            bottomPadding = contentPadding.calculateBottomPadding(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------------------------

@Composable
fun EpisodeScreen(uri: String, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = uri) { graph -> EpisodeViewModel(graph, uri) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    DetailScaffold(
        title = state.load.dataOrNull()?.episode?.name.orEmpty(),
        showTitle = showTitle,
        onBack = navigator::back,
        modifier = modifier,
    ) {
        LoadStateContent(state.load, offline = state.offline, onRetry = viewModel::retry) { content ->
            EpisodeDetails(
                uri = uri,
                content = content,
                state = state,
                listState = listState,
                contentPadding = contentPadding,
                navigator = navigator,
                onPlayPause = viewModel::playPause,
                onPlayFrom = viewModel::playFrom,
                onTogglePlayed = viewModel::togglePlayed,
                onToggleSaved = viewModel::toggleSaved,
                onDownload = viewModel::download,
                onRemoveDownload = viewModel::removeDownload,
            )
        }
    }
}

@Composable
private fun EpisodeDetails(
    uri: String,
    content: EpisodeContent,
    state: EpisodeUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    navigator: AppNavigator,
    onPlayPause: () -> Unit,
    onPlayFrom: (Long) -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleSaved: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val episode = content.episode
    val context = LocalContext.current
    val shareFailed = stringResource(R.string.detail_share_failed)
    val shareTitle = stringResource(R.string.detail_share)
    val description = rememberRichText(content.description)
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item(key = "header", contentType = "header") {
            DetailHeader(
                title = episode.name,
                imageUrl = episode.images.best(640) ?: episode.show?.images?.best(640),
                modifier = Modifier.fillMaxWidth(),
                actions = {
                    Column(Modifier.fillMaxWidth()) {
                        episode.show?.let { show ->
                            Box(
                                Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.detail_open_show)) {
                                        navigator.navigate(Route.Show(show.uri))
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    show.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        EpisodeMeta(episode)
                        DetailActionRow(
                            leading = {
                                AddedButton(
                                    added = state.saved,
                                    onClick = onToggleSaved,
                                    addDescription = R.string.detail_save_episode,
                                    removeDescription = R.string.detail_remove_episode,
                                )
                                EpisodeDownloadButton(state.download, onDownload = onDownload, onRemove = onRemoveDownload)
                                MarkPlayedButton(played = episode.fullyPlayed == true, onClick = onTogglePlayed)
                                com.taehagen.spotifygood.ui.screens.video.VideoButton(episode.uri)
                                IconButton(onClick = { shareLink(context, uri, shareTitle, onFailure = { navigator.showMessage(shareFailed) }) }) {
                                    Icon(
                                        Icons.Rounded.Share,
                                        contentDescription = stringResource(R.string.detail_share),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                MoreButton(
                                    onClick = { navigator.showActions(MediaActionTarget.EpisodeTarget(episode)) },
                                    label = episode.name,
                                )
                            },
                            trailing = {
                                PlayFab(isPlaying = state.playback.isPlayingItem(uri), onClick = onPlayPause)
                            },
                        )
                    }
                },
            )
        }
        if (description.isNotEmpty()) {
            item(key = "description", contentType = "description") {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
        item(key = "chapters", contentType = "chapters") {
            EpisodeChapters(uri, isCurrent = state.playback.isCurrent(uri), explicit = episode.explicit, onPlayFrom = onPlayFrom)
        }
    }
}

/** Spotify's "Chapters" on the episode page: the first few, "Show all" for the rest; a tap plays from there. */
@Composable
private fun EpisodeChapters(uri: String, isCurrent: Boolean, explicit: Boolean, onPlayFrom: (Long) -> Unit) {
    val chapters = com.taehagen.spotifygood.ui.screens.player.rememberChapters(uri, isEpisode = true)
    if (chapters.isEmpty()) return
    var all by rememberSaveable(uri) { mutableStateOf(false) }
    val position by com.taehagen.spotifygood.ui.components.rememberAppGraph().playback.snapshot.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        Text("These chapters are auto-generated", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val playing = if (isCurrent) chapters.lastOrNull { it.startMs <= position.positionAt() } else null
        (if (all) chapters else chapters.take(4)).forEach { ch ->
            com.taehagen.spotifygood.ui.screens.player.ChapterRow(ch, current = ch == playing, explicit = explicit) { onPlayFrom(ch.startMs) }
        }
        if (chapters.size > 4) {
            androidx.compose.material3.OutlinedButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(if (all) "Show less" else "Show all")
            }
        }
    }
}

/** "Mar 3 • 45 min" / "Mar 3 • 12 min left" / "Mar 3 • Played", plus a progress line when started. */
@Composable
private fun EpisodeMeta(episode: Episode) {
    val locale = currentLocale()
    val date = remember(episode.releaseDate, locale) { formatShortDate(episode.releaseDate, LocalDate.now(), locale) }
    val resume = episode.resumePosition()
    val time = when {
        episode.fullyPlayed == true -> stringResource(R.string.detail_played)
        resume > 0 && episode.durationMs > resume ->
            stringResource(R.string.detail_time_left, durationText(episode.durationMs - resume))
        else -> durationText(episode.durationMs)
    }
    HeaderMetaText(if (date != null) stringResource(R.string.detail_separator, date, time) else time)
    if (episode.fullyPlayed != true && resume > 0 && episode.durationMs > 0) {
        LinearProgressIndicator(
            progress = { (resume.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.5f),
        )
    }
}

/** The episode page's "Mark as played" / "Mark as unplayed" (filled once played, as Spotify's). */
@Composable
private fun MarkPlayedButton(played: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (played) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline,
            contentDescription = stringResource(if (played) R.string.shell_action_mark_unplayed else R.string.shell_action_mark_played),
            tint = if (played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EpisodeDownloadButton(state: DownloadState?, onDownload: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(
        when (state) {
            null, DownloadState.CANCELLED -> R.string.detail_download_episode
            DownloadState.COMPLETED -> R.string.detail_remove_episode_download
            DownloadState.FAILED -> R.string.detail_episode_download_failed
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> R.string.detail_downloading_episode
        },
    )
    IconButton(
        onClick = {
            when (state) {
                null, DownloadState.CANCELLED, DownloadState.FAILED -> onDownload()
                DownloadState.COMPLETED -> confirmRemove = true
                DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> onRemove()
            }
        },
    ) {
        when (state) {
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Icon(
                    Icons.Rounded.ArrowCircleDown,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            DownloadState.COMPLETED -> Icon(
                Icons.Rounded.DownloadForOffline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            DownloadState.FAILED -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            null, DownloadState.CANCELLED -> Icon(
                Icons.Rounded.ArrowCircleDown,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
    if (confirmRemove) {
        RemoveDownloadDialog(
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun shareLink(context: Context, uri: String, title: String, onFailure: () -> Unit) {
    val url = shareUrl(uri) ?: return onFailure()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    try {
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure()
    }
}


/** Spotify's show settings: auto-download new episodes, skip finished ones, mark all as played. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShowSettingsSheet(showUri: String, episodes: List<Episode>, onDismiss: () -> Unit) {
    val graph = com.taehagen.spotifygood.ui.components.rememberAppGraph()
    val auto by graph.podcastSettings.autoDownload.collectAsStateWithLifecycle()
    val skip by graph.podcastSettings.skipFinished.collectAsStateWithLifecycle()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SettingSwitch("Auto-download new episodes", "New episodes download as they come out", showUri in auto) {
                graph.podcastSettings.setAutoDownload(showUri, it)
            }
            SettingSwitch("Skip finished episodes", "Playing the show starts at the newest unfinished episode", showUri in skip) {
                graph.podcastSettings.setSkipFinished(showUri, it)
            }
            TextButton(onClick = {
                episodes.filter { !it.isPlaceholder && it.fullyPlayed != true }.forEach { graph.episodeProgress.markPlayed(it.uri, played = true) }
                onDismiss()
            }) { Text("Mark all loaded episodes as played") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}


@kotlinx.serialization.Serializable
private data class ShowRating(val average: Double = 0.0, val total: Long = 0, val showAverage: Boolean = false, val mine: Int = 0, val canRate: Boolean = false)

@kotlinx.serialization.Serializable
private data class ShowTopic(val uri: String = "", val name: String = "")

@kotlinx.serialization.Serializable
private data class ShowChart(val name: String = "", val uri: String = "", val position: Int = 0)

@kotlinx.serialization.Serializable
private data class ShowExtras(
    val rating: ShowRating? = null,
    val topics: List<ShowTopic>? = null,
    val notify: Boolean? = null,
    val chart: ShowChart? = null,
    val verified: Boolean = false,
    val trailerUri: String? = null,
)

@kotlinx.serialization.Serializable
private data class ShowSuggestions(val items: List<MediaRef> = emptyList())

/** Spotify's line under the header: "#41 in Top Podcasts • ☆ 5.0 (221.3K) • Comedy" (the rating opens Rate). */
@Composable
private fun ShowMetaRow(x: ShowExtras, onRate: () -> Unit) {
    val r = x.rating?.takeIf { it.showAverage && it.total > 0 || it.canRate }
    val parts = listOfNotNull(
        x.chart?.takeIf { it.position > 0 }?.let { "#${it.position} in ${it.name.ifBlank { "Top Podcasts" }}" },
        r?.let { if (it.showAverage && it.total > 0) "☆ %.1f (%s)".format(it.average, compactCount(it.total)) else "☆ Rate" },
        x.topics?.firstOrNull()?.name,
    )
    if (parts.isNotEmpty()) Text(
        parts.joinToString(" • "),
        Modifier.fillMaxWidth().clickable(enabled = r?.canRate == true, onClick = onRate).padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
    )
}

private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun RateShowDialog(current: Int, onDismiss: () -> Unit, onRate: (Int) -> Unit) {
    var stars by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rate this show") },
        text = {
            Row {
                (1..5).forEach { n ->
                    IconButton(onClick = { stars = n }) {
                        Icon(if (n <= stars) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = "$n stars",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRate(stars) }, enabled = stars > 0) { Text("Rate") } },
        dismissButton = {
            if (current > 0) TextButton(onClick = { onRate(0) }) { Text("Remove rating") } else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
