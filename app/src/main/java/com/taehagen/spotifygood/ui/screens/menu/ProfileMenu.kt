package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.runtime.remember

import android.text.format.DateUtils
import java.time.Instant
import java.time.format.DateTimeParseException
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.auth.BrowserLauncher
import com.taehagen.spotifygood.model.FriendActivity
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.SpotifyNotification
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.EmptyState
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.album.awaitConnectingSession
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.settings.PrivacySwitches
import com.taehagen.spotifygood.ui.screens.library.StateBox
import com.taehagen.spotifygood.ui.screens.library.attempt
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.ui.screens.library.messageRes
import com.taehagen.spotifygood.ui.screens.library.toBrowseError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.screens.library.toActionTarget
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Opens the profile menu (the avatar at the top left of Home and Your Library). */
val LocalProfileMenu = staticCompositionLocalOf<() -> Unit> { {} }

/** Spotify's own page for the account's plan. */
private const val PREMIUM_URL = "https://www.spotify.com/account/overview/"

/**
 * The left-side profile menu, drawn over the whole app (navigation bar and mini player too). It
 * opens as it enters composition and calls [onClosed] once closed again, so the caller removes it:
 * a closed ModalNavigationDrawer still takes every touch.
 */
@Composable
fun ProfileMenu(onClosed: () -> Unit) {
    val state = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(state) {
        withFrameNanos {} // its anchors are set by the first layout
        try {
            state.open()
        } catch (e: CancellationException) {
            // A swipe or tap took over the opening: it settles on its own.
        }
        snapshotFlow { state.currentValue == DrawerValue.Closed && !state.isAnimationRunning }.first { it }
        onClosed()
    }
    val navigator = LocalAppNavigator.current
    val user by rememberAppGraph().engine.user.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    @Suppress("UNCHECKED_CAST")
    val updatesVm = appViewModel<FeedViewModel<*>>(key = "menu:updates-count") {
        FeedViewModel(it) { graph -> graph.catalog.notifications() }
    } as FeedViewModel<SpotifyNotification>
    val updatesState by updatesVm.state.collectAsStateWithLifecycle()
    AutoRefresh(updatesVm, 30_000)
    val unreadUpdates = updatesState.items.count { it.isNew }
    var selectedFriend by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<FriendActivity?>(null)
    }
    var showOurActivity by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var showAudienceSettings by remember { androidx.compose.runtime.mutableStateOf(false) }
    var audienceSummary by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val graphForAudience = rememberAppGraph()
    LaunchedEffect(showOurActivity, showAudienceSettings) {
        if (showOurActivity && !showAudienceSettings) {
            audienceSummary = null
            val global = runCatching { graphForAudience.catalog.listeningGlobal() }.getOrNull()
            val people = runCatching { graphForAudience.catalog.listeningAudience() }.getOrNull()
            audienceSummary = when {
                global == true -> "All followers"
                global == null || people == null -> null
                else -> {
                    val count = people.count { !it.restricted }
                    when (count) {
                        0 -> "No one"
                        1 -> "1 person"
                        else -> "$count people"
                    }
                }
            }
        }
    }
    val go = { action: () -> Unit ->
        scope.launch { state.close() }
        action()
    }
    BackHandler(enabled = state.isOpen) { scope.launch { state.close() } }
    ModalNavigationDrawer(
        drawerState = state,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = state,
                drawerShape = RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { go { navigator.navigate(Route.Profile()) } }
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(user?.images?.best(96), null, Modifier.size(52.dp), CircleShape, Icons.Rounded.Person, avatarName = user?.let { it.displayName ?: it.username })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = user?.let { it.displayName ?: it.username }.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.browse_menu_view_profile),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f)) {
                        MenuItem(Icons.Rounded.WorkspacePremium, R.string.browse_menu_premium) {
                            go { activity?.let { BrowserLauncher.open(it, PREMIUM_URL) } }
                        }
                    }
                    if (user?.product.equals("premium", ignoreCase = true)) {
                        // Spotify web plan colors: vivid text on a dark pill.
                        val plan = when (user?.planType?.lowercase()?.removePrefix("premium_")) {
                            "family" -> "Family" to Color(0xFFD8A3FF)
                            "student" -> "Student" to Color(0xFF91EEDA)
                            "duo" -> "Duo" to Color(0xFFFFA178)
                            "individual" -> "Individual" to Color(0xFF1ED760)
                            else -> "Premium" to Color(0xFF1ED760)
                        }
                        Surface(shape = CircleShape, color = Color(0xFF282828)) {
                            Text(plan.first, color = plan.second,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                }
                MenuItem(Icons.Rounded.History, R.string.browse_menu_recents) { go { navigator.navigate(Route.Recents) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        MenuItem(Icons.Rounded.Bolt, R.string.browse_menu_updates) {
                            go { navigator.navigate(Route.Updates) }
                        }
                    }
                    if (unreadUpdates > 0) {
                        UnreadBadge(unreadUpdates)
                        Spacer(Modifier.width(20.dp))
                    }
                }
                MenuItem(Icons.Rounded.Notifications, R.string.browse_menu_whats_new) { go { navigator.navigate(Route.WhatsNew) } }
                MenuItem(Icons.Rounded.Settings, R.string.browse_menu_settings) { go { navigator.navigate(Route.Settings) } }
                ActivityDrawerPreview(onOurActivity = { showOurActivity = true },
                    selfImage = user?.images?.best(96),
                    onViewMore = { go { navigator.navigate(Route.Activity) } },
                    onUser = { friend -> selectedFriend = friend })
                MessagesDrawerPreview(onViewAll = { go { navigator.navigate(Route.Messages) } })
                }
            }
        },
    ) {}
    selectedFriend?.let { friend ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { selectedFriend = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            FriendActivitySheet(
                friend = friend,
                onProfile = {
                    selectedFriend = null
                    go { navigator.navigate(Route.Profile(friend.username)) }
                },
                onSong = {
                    selectedFriend = null
                    go { navigator.open(friend.track) }
                },
                onContext = friend.contextUri?.let { uri ->
                    {
                        selectedFriend = null
                        go { navigator.openUri(uri) }
                    }
                },
            )
        }
    }
    if (showAudienceSettings) {
        ActivityAudienceDialog(
            onDismiss = { showAudienceSettings = false },
            onAddFriends = {
                showAudienceSettings = false
                go { navigator.navigate(Route.Search) }
            },
            onViewFollowers = {
                showAudienceSettings = false
                go { navigator.navigate(Route.SocialPeople("followers")) }
            },
        )
    }
    if (showOurActivity) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showOurActivity = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(user?.images?.best(96), null, Modifier.size(56.dp), CircleShape, Icons.Rounded.Person, avatarName = user?.let { it.displayName ?: it.username })
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Your activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(user?.displayName ?: user?.username.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                val graph = rememberAppGraph()
                val prefs by graph.settings.settings.collectAsStateWithLifecycle()
                PrivacySwitches(prefs) { t -> scope.launch { graph.settings.update(t) } }
                HorizontalDivider()
                TextButton(onClick = { showOurActivity = false; showAudienceSettings = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Who can see your activity")
                        Text(audienceSummary ?: "Choose who can see your activity",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer { rotationZ = 180f })
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

// ---- Recents, Your Updates, Activity ---------------------------------------------------------

@Immutable
data class FeedState<T>(val items: List<T> = emptyList(), val loading: Boolean = true, val error: BrowseError? = null)

/** A list loaded once the session can answer, again on [refresh]. */
class FeedViewModel<T>(private val graph: AppGraph, private val load: suspend (AppGraph) -> List<T>) : ViewModel() {
    private val _state = MutableStateFlow(FeedState<T>())
    val state: StateFlow<FeedState<T>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            graph.awaitConnectingSession()
            attempt { load(graph) }
                .onSuccess { _state.value = FeedState(it, loading = false) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toBrowseError()) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> FeedScreen(
    key: String,
    title: Int,
    empty: Int,
    contentPadding: PaddingValues,
    load: suspend (AppGraph) -> List<T>,
    itemKey: (T) -> String,
    row: @Composable (T) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = key) { FeedViewModel(it, load) } as FeedViewModel<T>
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val error = state.error
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(title), fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = navigator::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when {
            state.items.isEmpty() && state.loading -> LoadingState()
            state.items.isEmpty() && error != null -> StateBox { ErrorState(stringResource(error.messageRes()), onRetry = vm::refresh) }
            state.items.isEmpty() -> StateBox { EmptyState(title = stringResource(empty)) }
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
                items(state.items, key = itemKey) { row(it) }
            }
        }
    }
}

@Composable
private fun MediaFeedScreen(key: String, title: Int, empty: Int, contentPadding: PaddingValues, load: suspend (AppGraph) -> List<MediaRef>) {
    val navigator = LocalAppNavigator.current
    FeedScreen(key, title, empty, contentPadding, load, itemKey = { it.uri }) { ref ->
        MediaRow(ref = ref, onClick = { navigator.open(ref) })
    }
}

/** One Recents row: a context (or a day's loose songs, [loose]) and the songs played from it. */
@Immutable
data class RecentRow(val ref: MediaRef, val timestampMs: Long, val songs: List<MediaRef>, val loose: Boolean = false)

/**
 * Contexts from the listening history, each with the songs played from it that day (play history keeps the
 * last 50 plays; older contexts fall back to their last track, without a count). Songs played outside any
 * context are grouped per day as "N songs played".
 */
private suspend fun recentRows(graph: AppGraph): List<RecentRow> {
    val history = graph.catalog.recentHistory()
    val plays = runCatching { graph.catalog.playHistory() }.getOrDefault(emptyList())
    val zone = java.time.ZoneId.systemDefault()
    val day = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    val played = { n: Int -> "$n song${if (n == 1) "" else "s"} played" }
    val kind = { r: MediaRef -> r.type.name.lowercase().replaceFirstChar { it.uppercase() } }
    val songsOf = { ps: List<com.taehagen.spotifygood.data.PlayedTrack> -> ps.map { p -> p.track }.distinctBy { t -> t.uri } }
    val last = runCatching { graph.catalog.tracks(history.mapNotNull { e -> e.lastTrackUri }.distinct()) }
        .getOrDefault(emptyList()).associateBy { t -> t.uri }
    val rows = history.map { e ->
        val songs = songsOf(plays.filter { p -> p.contextUri == e.context.uri && day(p.playedAtMs) == day(e.timestampMs) })
        val subtitle = listOfNotNull(songs.size.takeIf { it > 0 }?.let(played), kind(e.context), e.context.subtitle).joinToString(" â€¢ ")
        RecentRow(e.context.copy(subtitle = subtitle), e.timestampMs,
            songs.ifEmpty { listOfNotNull(e.lastTrackUri?.let(last::get)?.toMediaRef()) })
    }
    val loose = plays.filter { p -> p.contextUri == null }.groupBy { p -> day(p.playedAtMs) }.map { (d, ps) ->
        val songs = songsOf(ps)
        RecentRow(MediaRef(MediaType.TRACK, "loose:$d", played(songs.size), images = songs.first().images), ps.first().playedAtMs, songs, loose = true)
    }
    return (rows + loose).sortedByDescending { r -> r.timestampMs }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(contentPadding: PaddingValues) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = "menu:recents-history") {
        FeedViewModel(it) { graph -> recentRows(graph) }
    } as FeedViewModel<RecentRow>
    val graph = rememberAppGraph()
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val grouped = state.items.groupBy { entry ->
        if (entry.timestampMs <= 0) "Earlier" else {
            val day = java.time.Instant.ofEpochMilli(entry.timestampMs).atZone(zone).toLocalDate()
            when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Recents", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            } })
        when {
            state.loading && state.items.isEmpty() -> LoadingState()
            state.error != null && state.items.isEmpty() -> StateBox {
                ErrorState(stringResource(state.error!!.messageRes()), onRetry = vm::refresh)
            }
            state.items.isEmpty() -> StateBox { EmptyState(title = stringResource(R.string.browse_recents_empty)) }
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding)) {
                grouped.forEach { (day, entries) ->
                    item(key = "date:$day") {
                        Text(day, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(entries, key = { r -> "${r.ref.uri}:${r.timestampMs}" }) { row ->
                        var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        val flip by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                        val expandable = row.loose || (row.songs.isNotEmpty() && row.ref.type != MediaType.TRACK)
                        Column {
                            MediaRow(ref = row.ref, onClick = { if (row.loose) expanded = !expanded else navigator.open(row.ref) },
                                trailing = if (!expandable) null else { {
                                    IconButton(onClick = { expanded = !expanded }) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.graphicsLayer { rotationX = flip })
                                    }
                                } })
                            if (expanded) row.songs.forEach { song ->
                                MediaRow(ref = song, modifier = Modifier.padding(start = 24.dp),
                                    onClick = { graph.player.play(if (row.loose) PlayRequest(trackUris = listOf(song.uri)) else PlayRequest(contextUri = row.ref.uri, startUri = song.uri)) },
                                    onMoreClick = { song.toActionTarget()?.let(navigator::showActions) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsNewScreen(contentPadding: PaddingValues) =
    MediaFeedScreen("menu:whats-new", R.string.browse_menu_whats_new, R.string.browse_updates_empty, contentPadding) { it.catalog.whatsNew() }

@Composable
private fun UnreadBadge(count: Int) {
    Surface(color = Color(0xFF2188FF), shape = CircleShape) {
        Text(
            count.coerceAtMost(99).toString() + if (count > 99) "+" else "",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The server uses a *cursor*, not a per-notification READ enum: marking an item read
 * also marks earlier notifications read, matching the Gander read-through behavior. */
class UpdatesViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(FeedState<SpotifyNotification>())
    val state: StateFlow<FeedState<SpotifyNotification>> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            graph.awaitConnectingSession()
            attempt { graph.catalog.notifications() }
                .onSuccess { _state.value = FeedState(it, loading = false) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toBrowseError()) } }
        }
    }

    fun markRead(notification: SpotifyNotification) = mutate {
        val cursor = Instant.parse(notification.createdTimestamp).toEpochMilli().toString()
        graph.catalog.markNotificationRead(cursor)
    }

    fun delete(notification: SpotifyNotification) = mutate {
        graph.catalog.deleteNotification(notification.storageId)
    }

    /** Gander's cursor marks everything up to the latest visible notification read. */
    fun markAllRead() {
        val newest = _state.value.items.filter { it.isNew }.mapNotNull { notification ->
            runCatching { Instant.parse(notification.createdTimestamp).toEpochMilli() }.getOrNull()
        }.maxOrNull() ?: return
        mutate { graph.catalog.markNotificationRead(newest.toString()) }
    }

    /** Delete fetched notifications in server-side batches until the feed is empty.
     * Limit retries/pages to avoid an infinite loop if the backend is eventually consistent. */
    fun deleteAll() = mutate {
        var remaining = graph.catalog.notifications()
        val processed = mutableSetOf<String>()
        repeat(50) {
            val ids = remaining.map { it.storageId }.filter { it.isNotBlank() && processed.add(it) }
            if (ids.isEmpty()) throw IllegalStateException("Could not delete remaining updates; reload and retry.")
            graph.catalog.deleteNotifications(ids)
            remaining = graph.catalog.notifications()
            if (remaining.isEmpty()) return@mutate
        }
        throw IllegalStateException("More updates remain. Reopen Delete all to continue.")
    }

    /** Open an update even if marking it read fails, but surface the server error. */
    fun markReadOnOpen(notification: SpotifyNotification) {
        if (!notification.isNew) return
        // Mark-read is not an optimistic local toggle: refresh authoritative state.
        markRead(notification)
    }

    private fun mutate(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                action()
                val fresh = graph.catalog.notifications()
                _state.value = FeedState(fresh, loading = false)
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not update notification"
            } finally {
                _busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UpdatesScreen(contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    val activity = LocalActivity.current
    val vm = appViewModel<UpdatesViewModel>(key = "menu:updates-managed") { UpdatesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val unread = state.items.count { it.isNew }
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SpotifyNotification?>(null) }
    var bulkMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmDeleteAll by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.browse_menu_updates), fontWeight = FontWeight.Bold)
                    if (unread > 0) {
                        Spacer(Modifier.width(10.dp))
                        UnreadBadge(unread)
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = navigator::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
                }
            },
            actions = {
                IconButton(onClick = { bulkMenu = true }, enabled = !busy && state.items.isNotEmpty()) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "All update options")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (message != null) Text(message.orEmpty(), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            state.items.isEmpty() && state.loading -> LoadingState()
            state.items.isEmpty() && state.error != null -> StateBox { ErrorState("Could not load updates", onRetry = vm::refresh) }
            state.items.isEmpty() -> StateBox { EmptyState(title = stringResource(R.string.browse_updates_empty)) }
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { notification ->
                    val target = notification.action?.uri ?: notification.entityImage?.entityUrl
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    vm.markReadOnOpen(notification)
                                    if (!target.isNullOrBlank() && !navigator.openUri(target)) activity?.let { a ->
                                        BrowserLauncher.open(a, "https://open.spotify.com/" + target.removePrefix("spotify:").replace(':', '/'))
                                    }
                                },
                                onLongClick = { selected = notification },
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(56.dp)) {
                            Artwork(notification.entityImage?.imageUrl, null, Modifier.size(56.dp),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp), Icons.Rounded.Notifications)
                            if (notification.isNew) Surface(
                                shape = CircleShape, color = Color(0xFF2188FF),
                                modifier = Modifier.size(11.dp).align(Alignment.TopStart).offset((-3).dp, (-3).dp),
                            ) {}
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(notification.title, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (notification.isNew) FontWeight.Bold else FontWeight.Normal)
                            val timestamp = try { Instant.parse(notification.createdTimestamp).toEpochMilli() }
                                catch (_: DateTimeParseException) { 0L }
                            if (timestamp > 0L) Text(
                                DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { selected = notification }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Update options")
                        }
                    }
                }
            }
        }
    }
    if (bulkMenu) {
        ModalBottomSheet(onDismissRequest = { bulkMenu = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("Your Updates", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(enabled = unread > 0 && !busy, onClick = {
                    bulkMenu = false
                    vm.markAllRead()
                }) { Text("Mark all as read") }
                TextButton(enabled = state.items.any { it.storageId.isNotBlank() } && !busy, onClick = {
                    bulkMenu = false
                    confirmDeleteAll = true
                }) { Text("Delete all") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (confirmDeleteAll) {
        ModalBottomSheet(onDismissRequest = { confirmDeleteAll = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("Delete all updates?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("This deletes your updates from your Spotify account, not just this phone.")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") }
                TextButton(enabled = !busy, onClick = {
                    confirmDeleteAll = false
                    vm.deleteAll()
                }) { Text("Delete all updates") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    selected?.let { notification ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(notification.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(enabled = notification.isNew && !busy, onClick = {
                    selected = null
                    vm.markRead(notification)
                }) { Text(if (notification.isNew) "Mark as read" else "Already read") }
                TextButton(enabled = notification.storageId.isNotBlank() && !busy, onClick = {
                    selected = null
                    vm.delete(notification)
                }) { Text("Delete") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** Reloads [vm] every [periodMs] while on screen; stops once closed or the app is in the background. */
@Composable
private fun AutoRefresh(vm: FeedViewModel<*>, periodMs: Long = 10_000) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(periodMs); vm.refresh() }
        }
    }
}

/** Activity grid order: people currently listening first, then by most recent activity. */
private fun List<FriendActivity>.byActivity(): List<FriendActivity> =
    sortedWith(compareByDescending<FriendActivity> { it.isActive == true }.thenByDescending { it.timestampMs })

/** Compact friends module inside the profile drawer: most recently active first. */
@Composable
private fun ActivityDrawerPreview(onOurActivity: () -> Unit, selfImage: String?, onViewMore: () -> Unit, onUser: (FriendActivity) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = "menu:activity-preview") {
        FeedViewModel(it) { graph -> graph.catalog.friendActivity() }
    } as FeedViewModel<FriendActivity>
    val state by vm.state.collectAsStateWithLifecycle()
    AutoRefresh(vm)
    val friends = state.items.byActivity().take(9)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onViewMore) { Text("View more") }
        }
        when {
            state.loading && friends.isEmpty() -> Text("Loading activityâ€¦", style = MaterialTheme.typography.bodySmall)
            state.error != null && friends.isEmpty() -> TextButton(onClick = vm::refresh) { Text("Retry activity") }
            friends.isEmpty() -> Text("No friend activity yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                // Place our activity in cell 0 of the same three-column grid as friends.
                val tiles: List<FriendActivity?> = listOf(null) + friends.take(8)
                tiles.chunked(3).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { friend ->
                            if (friend == null) {
                                Column(
                                    Modifier.weight(1f).clickable(onClick = onOurActivity)
                                        .border(1.dp, MaterialTheme.colorScheme.primary,
                                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Artwork(selfImage, null, Modifier.size(54.dp), CircleShape, Icons.Rounded.Person)
                                    Text("Activity", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Text("On", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1DB954))
                                }
                            } else {
                                Box(Modifier.weight(1f)) {
                                    FriendTile(friend, Modifier.fillMaxWidth()) { onUser(friend) }
                                }
                            }
                        }
                        repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

/** Activity details presented as a bottom sheet without leaving the drawer. */
@Composable
private fun FriendActivitySheet(
    friend: FriendActivity,
    onProfile: () -> Unit,
    onSong: () -> Unit,
    onContext: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onProfile),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(friend.image, null, Modifier.size(46.dp), CircleShape, Icons.Rounded.Person,
                avatarName = friend.name)
            Column(Modifier.weight(1f)) {
                Text(friend.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (friend.isActive == true) "Listening now" else "Recently listened",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onSong),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(friend.track.images.best(160), null, Modifier.size(62.dp))
                    Column(Modifier.weight(1f)) {
                        Text(friend.track.name, fontWeight = FontWeight.Bold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(friend.track.subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Open song")
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = onSong) {
                        Text("Preview song")
                    }
                    if (onContext != null) {
                        TextButton(onClick = onContext) { Text("Open source") }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onSong) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Open song")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Live status requires a positive service signal; a recent timestamp alone is not proof. */
@Composable
private fun FriendTile(friend: FriendActivity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val live = friend.isActive == true
    Column(modifier.clickable(onClick = onClick).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Artwork(friend.image, null, Modifier.size(54.dp), CircleShape, Icons.Rounded.Person, avatarName = friend.name)
        Text(friend.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()) {
            if (live) LiveBars()
            Text(friend.track.name, style = MaterialTheme.typography.labelSmall,
                color = if (live) Color(0xFF1DB954) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun LiveBars() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "listening")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(end = 3.dp).heightIn(min = 12.dp)) {
        repeat(3) { index ->
            val height by transition.animateFloat(
                initialValue = 3f, targetValue = 11f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(420 + 130 * index),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
                label = "bar$index")
            Surface(color = Color(0xFF1DB954), modifier = Modifier.width(2.dp).height(androidx.compose.ui.unit.Dp(height))) { }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(contentPadding: PaddingValues) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = "menu:activity") {
        FeedViewModel(it) { graph -> graph.catalog.friendActivity() }
    } as FeedViewModel<FriendActivity>
    val state by vm.state.collectAsStateWithLifecycle()
    AutoRefresh(vm)
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Friend Activity", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        when {
            state.loading && state.items.isEmpty() -> LoadingState()
            state.error != null && state.items.isEmpty() -> StateBox {
                ErrorState(stringResource(state.error!!.messageRes()), onRetry = vm::refresh)
            }
            state.items.isEmpty() -> StateBox { EmptyState(title = "No friend activity yet") }
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding)) {
                items(state.items.byActivity(), key = { it.username }) { friend ->
                    FriendRow(friend, onUser = { navigator.navigate(Route.Profile(friend.username)) },
                        onTrack = { navigator.open(friend.track) },
                        onContext = friend.contextUri?.let { uri -> { navigator.openUri(uri) } })
                }
            }
        }
    }
}

/** A friend's avatar, name, when, the song and artist, and where it plays from. */
@Composable
private fun FriendRow(friend: FriendActivity, onUser: () -> Unit, onTrack: () -> Unit, onContext: (() -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTrack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Artwork(friend.image, null, Modifier.size(48.dp).clickable(onClick = onUser), CircleShape, Icons.Rounded.Person, avatarName = friend.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (friend.timestampMs > 0) {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(friend.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
            Text(
                text = listOfNotNull(friend.track.name, friend.track.subtitle).joinToString(" â€¢ "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            friend.contextName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onContext != null) Modifier.clickable(onClick = onContext) else Modifier,
                )
            }
        }
    }
}

/** Messaging is not available from the current native catalog. Never display invented conversations. */
@Composable
private fun MessagesDrawerPreview(onViewAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Messages", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clickable(onClick = onViewAll))
            TextButton(onClick = onViewAll) { Text("View all") }
        }
        Text("Messages aren't connected yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onViewAll).padding(vertical = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Messages", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.MailOutline, contentDescription = null)
                Text("Messages aren't available yet", fontWeight = FontWeight.Bold)
                Text("Spotify Good doesn't have a connected messaging service.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }
    }
}
