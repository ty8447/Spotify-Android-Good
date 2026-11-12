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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.GraphicEq
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
import com.taehagen.spotifygood.data.ServerRecent
import com.taehagen.spotifygood.model.FriendActivity
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.MediaActionRunner
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
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
    // The content must be hit-testable from the first frame. The previous
    // Closed -> open() animation let a rapid first tap dismiss the scrim
    // before the drawer had reached its interactive anchors.
    val state = rememberDrawerState(DrawerValue.Open)
    // Defer navigation until the closing animation is complete.
    var pendingAction by remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue == DrawerValue.Closed && !state.isAnimationRunning }.first { it }
        // Run the click exactly once while the navigator is still in composition,
        // then remove the drawer. This also works for taps during opening.
        val action = pendingAction
        pendingAction = null
        action?.invoke()
        onClosed()
    }
    val navigator = LocalAppNavigator.current
    val user by rememberAppGraph().engine.user.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    // The signed-in scaffold keeps this count fresh even when the drawer is closed.
    // Do not start a second notifications poller just to draw this badge.
    val unreadUpdates = UpdatesUnreadState.unreadCount
    val unreadChats = ChatUnreadStates.counts.values.sum()
    var showNewMessage by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showInviteFriends by remember { androidx.compose.runtime.mutableStateOf(false) }
    var selectedFriend by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<FriendActivity?>(null)
    }
    var selectedGroup by remember { androidx.compose.runtime.mutableStateOf<ConversationSummary?>(null) }
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
        if (pendingAction == null) {
            pendingAction = action
            scope.launch { state.close() }
        }
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
                    Box(Modifier.size(56.dp)) {
                        Artwork(user?.images?.best(96), null, Modifier.size(52.dp), CircleShape,
                            Icons.Rounded.Person, avatarName = user?.let { it.displayName ?: it.username })
                        if (unreadUpdates > 0) {
                            Surface(color = Color(0xFF2188FF), shape = CircleShape,
                                modifier = Modifier.size(11.dp).align(Alignment.TopEnd).offset(2.dp, (-2).dp)) {}
                        }
                        if (unreadChats > 0) {
                            Box(Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp)) {
                                ChatUnreadBadge(unreadChats)
                            }
                        }
                    }
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
                MenuItem(Icons.Rounded.GraphicEq, R.string.browse_menu_vibe_dj) { go { navigator.navigate(Route.VibeDj) } }
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
                    onUser = { friend -> selectedFriend = friend },
                    onGroup = { chat -> selectedGroup = chat })
                MessagesDrawerPreview(
                    onViewAll = { go { navigator.navigate(Route.Messages) } },
                    onNewMessage = { showNewMessage = true },
                    onConversation = { id, name ->
                        go { navigator.navigate(Route.MessageConversation(id, name)) }
                    },
                )
                }
            }
        },
    ) {}
    if (showNewMessage) {
        NewMessageSheet(onDismiss = { showNewMessage = false }, onOpenChat = { id, title ->
            showNewMessage = false
            go { navigator.navigate(Route.MessageConversation(id, title)) }
        })
    }
    selectedGroup?.let { group ->
        GroupActivityModal(group, onDismiss = { selectedGroup = null },
            onNavigate = { route -> go { navigator.navigate(route) } })
    }
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
            onAddFriends = { showInviteFriends = true },
            onViewFollowers = {
                showAudienceSettings = false
                go { navigator.navigate(Route.SocialPeople("followers")) }
            },
        )
    }
    if (showInviteFriends) {
        InviteFriendsMessagesSheet(
            onDismiss = { showInviteFriends = false },
            onOpenChat = { id, title ->
                showInviteFriends = false
                showAudienceSettings = false
                go { navigator.navigate(Route.MessageConversation(id, title)) }
            },
            onProfile = { username ->
                showInviteFriends = false
                showAudienceSettings = false
                go { navigator.navigate(Route.Profile(username)) }
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
class FeedViewModel<T>(
    private val graph: AppGraph,
    /** The next page after the items loaded so far (empty at the end), for lists that page. */
    private val more: (suspend (AppGraph, List<T>) -> List<T>)? = null,
    private val load: suspend (AppGraph) -> List<T>,
) : ViewModel() {
    private val _state = MutableStateFlow(FeedState<T>())
    val state: StateFlow<FeedState<T>> = _state.asStateFlow()

    init {
        refresh()
    }

    private var refreshJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true) return // Don't overlap polling, retries or manual refresh.
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = viewModelScope.launch {
            try {
                val items = retryChatRead {
                    graph.awaitConnectingSession()
                    load(graph)
                }
                _state.value = FeedState(items, loading = false)
                ended = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Keep the previous list until a successful refresh.
                _state.update { it.copy(loading = false, error = failure.toBrowseError()) }
            }
        }
    }

    private var ended = false

    /** Appends the next page (once the list's end is on screen); a failed page is retried next time. */
    fun loadMore() {
        val next = more ?: return
        if (ended || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val page = runCatching { next(graph, _state.value.items) }.getOrNull() ?: return@launch
            if (page.isEmpty()) ended = true else _state.update { it.copy(items = it.items + page) }
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
    val vm = appViewModel<FeedViewModel<*>>(key = key) { FeedViewModel(it, load = load) } as FeedViewModel<T>
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
data class RecentRow(
    val ref: MediaRef,
    val timestampMs: Long,
    val songs: List<MediaRef>,
    val loose: Boolean = false,
    /** Explicit flags and podcast progress of the row and its songs, by URI. */
    val info: Map<String, RecentInfo> = emptyMap(),
    /** Offset of Spotify's next Recents page (0: none). */
    val next: Int = 0,
)

data class RecentInfo(val explicit: Boolean = false, val progress: Float? = null, val left: String? = null)

/** Explicit flags (tracks, episodes) and how much of each episode is left, like Spotify's Recents. */
private suspend fun recentInfo(graph: AppGraph, refs: List<MediaRef>): Map<String, RecentInfo> {
    val tracks = refs.filter { it.type == MediaType.TRACK }.map { it.uri }.distinct()
    val episodes = refs.filter { it.type == MediaType.EPISODE }.map { it.uri }.distinct()
    val out = mutableMapOf<String, RecentInfo>()
    tracks.chunked(50).forEach { chunk ->
        runCatching { graph.catalog.tracks(chunk) }.getOrNull()?.forEach { out[it.uri] = RecentInfo(explicit = it.explicit) }
    }
    episodes.chunked(20).forEach { chunk ->
        runCatching { graph.catalog.episodes(chunk) }.getOrNull()?.forEach { e ->
            val pos = if (e.fullyPlayed == true) e.durationMs else e.resumePositionMs ?: 0L
            val left = (e.durationMs - pos).coerceAtLeast(0)
            out[e.uri] = RecentInfo(
                explicit = e.explicit,
                progress = if (e.durationMs > 0) pos.toFloat() / e.durationMs else null,
                left = when {
                    e.fullyPlayed == true -> "Played"
                    left >= 3_600_000 -> "${left / 3_600_000} hr ${left % 3_600_000 / 60_000} min left"
                    else -> "${(left / 60_000).coerceAtLeast(1)} min left"
                },
            )
        }
    }
    return out
}

private fun recentKind(r: MediaRef) = if (r.type == MediaType.COLLECTION) "Playlist" else r.type.name.lowercase().replaceFirstChar { it.uppercase() }

private fun playedLabel(refs: List<MediaRef>, n: Int = refs.size): String =
    if (refs.isNotEmpty() && refs.all { it.type == MediaType.EPISODE }) "$n episode${if (n == 1) "" else "s"} played"
    else "$n song${if (n == 1) "" else "s"} played"

/** Rows for song and episode URIs (metadata lookups for the ones not in [known]). */
private suspend fun songRefs(graph: AppGraph, uris: List<String>, known: Map<String, MediaRef> = emptyMap()): MutableMap<String, MediaRef> {
    val out = known.toMutableMap()
    val wanted = uris.distinct().filter { it !in out }
    wanted.filter { it.startsWith("spotify:track:") }.chunked(50).forEach { chunk ->
        runCatching { graph.catalog.tracks(chunk) }.getOrNull()?.forEach { out[it.uri] = it.toMediaRef() }
    }
    wanted.filter { it.startsWith("spotify:episode:") }.chunked(20).forEach { chunk ->
        runCatching { graph.catalog.episodes(chunk) }.getOrNull()?.forEach { out[it.uri] = it.toMediaRef() }
    }
    return out
}

/** Explicit flags and progress; a show's row carries the progress of its latest episode, like Spotify. */
private suspend fun withInfo(graph: AppGraph, rows: List<RecentRow>): List<RecentRow> {
    val info = recentInfo(graph, rows.flatMap { r -> listOf(r.ref) + r.songs }).toMutableMap()
    rows.filter { it.ref.type == MediaType.SHOW }.forEach { r -> r.songs.firstNotNullOfOrNull { info[it.uri] }?.let { info[r.ref.uri] = it } }
    return rows.map { r -> r.copy(info = info) }
}

/**
 * Spotify's Recents page from [offset], as the official app shows it: each context with every song
 * played from it. Null when Spotify's page can't be read (the caller falls back to the play history).
 */
private suspend fun serverRecentRows(graph: AppGraph, offset: Int): List<RecentRow>? {
    val page = runCatching { graph.catalog.recents(offset) }.getOrNull()?.takeIf { it.items.isNotEmpty() } ?: return null
    val items = page.items.filter { it.kind != "shared" }
    val songs = songRefs(graph, items.flatMap { it.entityUris } + items.map { it.uri }.filter { it.startsWith("spotify:track:") || it.startsWith("spotify:episode:") })
    val contexts = runCatching { graph.catalog.refs(items.map { it.uri }.filter { it !in songs }.distinct()) }.getOrDefault(emptyList()).associateBy { it.uri }
    val zone = java.time.ZoneId.systemDefault()
    val day = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    // Consecutive plays from the same context on the same day are one row, like Spotify's Recents.
    val merged = mutableListOf<ServerRecent>()
    items.forEach { r ->
        val last = merged.lastOrNull()
        if (last != null && last.uri == r.uri && last.kind == r.kind && r.uri !in songs && day(last.timestampMs) == day(r.timestampMs)) {
            val uris = (last.entityUris + r.entityUris).distinct()
            merged[merged.lastIndex] = last.copy(entityUris = uris, itemsCount = maxOf(uris.size, last.itemsCount + r.itemsCount), timestampMs = maxOf(last.timestampMs, r.timestampMs))
        } else merged += r
    }
    val rows = merged.mapNotNull { r ->
        songs[r.uri]?.let { return@mapNotNull RecentRow(it, r.timestampMs, emptyList(), next = page.next ?: 0) }
        val ctx = contexts[r.uri] ?: return@mapNotNull null
        val played = r.entityUris.mapNotNull(songs::get).distinctBy { it.uri }
        val n = maxOf(r.itemsCount, played.size)
        val what = when {
            n <= 0 -> null
            r.kind == "saved" -> "$n song${if (n == 1) "" else "s"} added"
            else -> playedLabel(played, n)
        }
        val subtitle = listOfNotNull(what, recentKind(ctx), ctx.subtitle).distinct().joinToString(" • ")
        RecentRow(ctx.copy(subtitle = subtitle), r.timestampMs, played, next = page.next ?: 0)
    }
    return withInfo(graph, rows)
}

private suspend fun recentRows(graph: AppGraph): List<RecentRow> = serverRecentRows(graph, 0) ?: historyRows(graph)

/**
 * Fallback when Spotify's Recents page can't be read: contexts from the listening history, each with the songs
 * played from it that day (play history keeps the last 50 plays; older contexts fall back to their last track).
 * Songs played outside any context are grouped per day as "N songs played".
 */
private suspend fun historyRows(graph: AppGraph): List<RecentRow> {
    val history = graph.catalog.recentHistory()
    val server = runCatching { graph.catalog.playHistory() }.getOrDefault(emptyList())
    val local = runCatching { graph.playLog.entries() }.getOrDefault(emptyList())
    val zone = java.time.ZoneId.systemDefault()
    val day = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    val kind = ::recentKind

    // Every play known: Spotify's last 50 (any device) and this phone's own log, one per song and minute.
    data class Play(val uri: String, val context: String?, val at: Long)
    val plays = (server.map { Play(it.track.uri, it.contextUri, it.playedAtMs) } + local.map { Play(it.uri, it.contextUri, it.playedAtMs) })
        .sortedByDescending { it.at }
        .fold(mutableListOf<Play>()) { acc, p ->
            if (acc.none { it.uri == p.uri && kotlin.math.abs(it.at - p.at) < 120_000 }) acc += p
            acc
        }
    // Rows for the songs and episodes: Spotify's answer first, then metadata lookups.
    val songRefs = songRefs(graph, plays.map { it.uri } + history.mapNotNull { it.lastTrackUri }, server.associate { it.track.uri to it.track })
    // Rows for the contexts: the server's, then lookups for the ones only this phone played.
    val contexts = history.associate { it.context.uri to it.context }.toMutableMap()
    runCatching { graph.catalog.refs(plays.mapNotNull { it.context }.distinct().filter { it !in contexts }) }
        .getOrNull()?.forEach { contexts[it.uri] = it }

    val played = { refs: List<MediaRef> -> playedLabel(refs) }
    // One row per context and day (Spotify's Recents), the songs played from it that day.
    val grouped = plays.groupBy { (it.context?.takeIf { c -> c in contexts } ?: "loose") to day(it.at) }
    val rows = grouped.map { (key, ps) ->
        val songs = ps.mapNotNull { songRefs[it.uri] }.distinctBy { it.uri }
        val at = ps.maxOf { it.at }
        val ctx = contexts[key.first]
        if (ctx == null) {
            RecentRow(MediaRef(MediaType.TRACK, "loose:${key.second}", played(songs), images = songs.firstOrNull()?.images.orEmpty()), at, songs, loose = true)
        } else {
            val subtitle = listOfNotNull(songs.size.takeIf { it > 0 }?.let { played(songs) }, kind(ctx), ctx.subtitle).distinct().joinToString(" • ")
            RecentRow(ctx.copy(subtitle = subtitle), at, songs)
        }
    }.filter { it.songs.isNotEmpty() || !it.loose }
    // Contexts older than the known plays: their last song only.
    val covered = rows.map { it.ref.uri }.toSet()
    val older = history.filter { it.context.uri !in covered }.map { e ->
        val songs = listOfNotNull(e.lastTrackUri?.let(songRefs::get))
        RecentRow(e.context.copy(subtitle = listOfNotNull(kind(e.context), e.context.subtitle).distinct().joinToString(" • ")), e.timestampMs, songs)
    }
    return withInfo(graph, (rows + older).sortedByDescending { r -> r.timestampMs })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(contentPadding: PaddingValues) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = "menu:recents-history") {
        FeedViewModel(it, more = { graph, rows -> rows.lastOrNull()?.next?.takeIf { n -> n > 0 }?.let { n -> serverRecentRows(graph, n) }.orEmpty() }) { graph -> recentRows(graph) }
    } as FeedViewModel<RecentRow>
    val graph = rememberAppGraph()
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    // Spotify's Music / Podcasts chips.
    var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    val podcast = { r: RecentRow -> r.ref.type == MediaType.SHOW || r.ref.type == MediaType.EPISODE }
    val grouped = state.items.filter { filter == null || podcast(it) == filter }.groupBy { entry ->
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
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Music" to false, "Podcasts" to true).forEach { (label, value) ->
                androidx.compose.material3.FilterChip(selected = filter == value, onClick = { filter = if (filter == value) null else value }, label = { Text(label) })
            }
        }
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
                    // Two rows can share a context and a time (a song added and played): the position keeps keys unique.
                    itemsIndexed(entries, key = { i, r -> "$day:$i:${r.ref.uri}" }) { _, row ->
                        var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        val flip by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                        val expandable = row.loose || (row.songs.isNotEmpty() && row.ref.type != MediaType.TRACK)
                        Column {
                            val own = row.info[row.ref.uri]
                            MediaRow(ref = row.ref, onClick = { if (row.loose) expanded = !expanded else navigator.open(row.ref) },
                                explicit = own?.explicit == true, progress = own?.progress, progressLabel = own?.left,
                                trailing = if (!expandable) null else { {
                                    IconButton(onClick = { expanded = !expanded }) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.graphicsLayer { rotationX = flip })
                                    }
                                } })
                            if (expanded) row.songs.forEach { song ->
                                val songInfo = row.info[song.uri]
                                MediaRow(ref = song, modifier = Modifier.padding(start = 24.dp),
                                    explicit = songInfo?.explicit == true, progress = songInfo?.progress, progressLabel = songInfo?.left,
                                    onClick = { graph.player.play(if (row.loose) PlayRequest(trackUris = listOf(song.uri)) else PlayRequest(contextUri = row.ref.uri, startUri = song.uri)) },
                                    onMoreClick = { song.toActionTarget()?.let(navigator::showActions) })
                            }
                        }
                    }
                }
                // The end of the list is on screen: Spotify's next page (50 at a time, never all at once).
                item(key = "more") { LaunchedEffect(state.items.size) { vm.loadMore() } }
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
                .onSuccess {
                    _state.value = FeedState(it, loading = false)
                    UpdatesUnreadState.update(it)
                }
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
                UpdatesUnreadState.update(fresh)
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
            while (true) {
                delay(periodMs)
                // Once all 4 automatic attempts have failed, let Retry be user-driven.
                if (vm.state.value.error == null) vm.refresh()
            }
        }
    }
}

/** Activity grid order: people currently listening first, then by most recent activity. */
private fun List<FriendActivity>.byActivity(): List<FriendActivity> =
    sortedWith(compareByDescending<FriendActivity> { it.isActive == true }.thenByDescending { it.timestampMs })

/** Only conversations with actual server-returned activity from another member qualify.
 * This does not assert a chat-level listening-visibility setting, which this API does not expose.
 */
private data class GroupActivityPreview(
    val chat: ConversationSummary,
    val people: List<FriendActivity>,
) {
    val currentlyPlaying: Int get() = people.count { it.isActive == true }
    val mostRecentMs: Long get() = people.maxOfOrNull { it.timestampMs } ?: 0L
    // Show what the most relevant member is playing, not a generic count.
    // A positive isPlaying signal always takes precedence over a recent timestamp.
    val leadingListener: FriendActivity? get() = people.maxWithOrNull(
        compareBy<FriendActivity> { it.isActive == true }
            .thenBy { it.timestampMs })
    val title: String get() = chat.displayTitle.ifBlank { chat.groupName ?: "Group chat" }
}

/** Group activity is fetched separately from the friend feed. Expose terminal errors
 * to the drawer/activity screen instead of silently returning an empty list.
 */
private class GroupActivityLoad(
    val groups: List<GroupActivityPreview>,
    val loading: Boolean,
    val failed: Boolean,
    val retry: () -> Unit,
)

@Composable
private fun rememberGroupActivityPreviews(): GroupActivityLoad {
    val graph = rememberAppGraph()
    val selfUsername = graph.engine.user.collectAsStateWithLifecycle().value?.username
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var groups by remember(selfUsername) {
        androidx.compose.runtime.mutableStateOf<List<GroupActivityPreview>>(emptyList())
    }
    var loading by remember(selfUsername) { androidx.compose.runtime.mutableStateOf(true) }
    var failed by remember(selfUsername) { androidx.compose.runtime.mutableStateOf(false) }
    var retry by remember(selfUsername) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(graph, selfUsername, lifecycle, retry) {
        if (selfUsername.isNullOrBlank()) {
            loading = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                failed = false
                try {
                    val overview = retryChatRead {
                        graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview")
                    }
                    // Bound the number of background calls; never enumerate unrelated usernames.
                    val candidates = overview.conversations.asSequence()
                        .filter { it.hierarchyId.isNotBlank() && (it.isGroup || it.groupMemberCount > 2) }
                        .distinctBy { it.hierarchyId }
                        .filter { chat ->
                            chat.participants.map { it.username }.filter(String::isNotBlank)
                                .distinct().size in 3..40
                        }
                        .sortedByDescending { it.latestOccurredAtMs ?: 0L }
                        .take(10).toList()
                    val found = mutableListOf<GroupActivityPreview>()
                    for (chat in candidates) {
                        val usernames = chat.participants.map { it.username }
                            .filter(String::isNotBlank).distinct()
                        val people = retryChatRead {
                            graph.catalog.groupMemberActivity(usernames)
                                .filter { it.username != selfUsername }
                        }
                        if (people.isNotEmpty()) found.add(GroupActivityPreview(chat, people))
                    }
                    groups = found.sortedWith(
                        compareByDescending<GroupActivityPreview> { it.currentlyPlaying > 0 }
                            .thenByDescending { it.mostRecentMs })
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Retain previously confirmed group activity and surface a Retry action.
                    failed = true
                } finally {
                    loading = false
                }
                if (failed) break
                delay(45_000)
            }
        }
    }
    return GroupActivityLoad(groups, loading, failed, retry = { retry++ })
}

private data class ActivityGridEntry(
    val friend: FriendActivity? = null,
    val group: GroupActivityPreview? = null,
) {
    val playing: Boolean get() = friend?.isActive == true || (group?.currentlyPlaying ?: 0) > 0
    val timestamp: Long get() = friend?.timestampMs ?: group?.mostRecentMs ?: 0L
}

@Composable
private fun GroupActivityTile(group: GroupActivityPreview, modifier: Modifier = Modifier,
                              onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MessageGroupAvatar(group.chat.participants, 54.dp)
        Text(group.title, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            val listener = group.leadingListener
            val live = listener?.isActive == true
            if (live) LiveBars()
            Text(listener?.track?.name ?: "Recently listened",
                style = MaterialTheme.typography.labelSmall,
                color = if (live) Color(0xFF1DB954)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** A group bubble uses the same member sheet/card as the conversation itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupActivityModal(
    chat: ConversationSummary,
    onDismiss: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val runner = remember(graph, navigator, context) { MediaActionRunner(graph, navigator, context) }
    val signedInUsername = graph.engine.user.collectAsStateWithLifecycle().value?.username
    val title = chat.displayTitle.ifBlank { chat.groupName ?: "Group chat" }
    val conversation = Route.MessageConversation(chat.hierarchyId, title)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        GroupConversationActivitySheet(
            participants = chat.participants,
            onPlay = { friend ->
                if (friend.track.type == MediaType.TRACK)
                    graph.player.play(PlayRequest(trackUris = listOf(friend.track.uri)))
                else navigator.open(friend.track)
            },
            onProfile = { friend -> onDismiss(); onNavigate(Route.Profile(friend.username)) },
            onDraftChatMessage = { message ->
                PendingListeningChatDraft.stage(signedInUsername, chat.hierarchyId, message)
                onDismiss()
                onNavigate(conversation)
            },
            onQueue = { friend ->
                if (friend.track.type == MediaType.TRACK)
                    runner.addToQueue(listOf(friend.track.uri))
                else navigator.showMessage("Queue is available for songs")
            },
            onAddToPlaylist = { friend ->
                if (friend.track.type == MediaType.TRACK)
                    navigator.addToPlaylist(listOf(friend.track.uri))
                else navigator.showMessage("Add to playlist is available for songs")
            },
            onSongActions = { friend ->
                if (friend.track.type != MediaType.TRACK) navigator.open(friend.track)
                else scope.launch {
                    try {
                        val track = graph.catalog.tracks(listOf(friend.track.uri)).firstOrNull()
                        if (track != null) navigator.showActions(MediaActionTarget.TrackTarget(track))
                        else navigator.showMessage("Song actions are unavailable")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        navigator.showMessage("Unable to load song actions")
                    }
                }
            },
            onGoToChat = { onDismiss(); onNavigate(conversation) },
        )
    }
}

/** Compact friends module inside the profile drawer: most recently active first. */
@Composable
private fun ActivityDrawerPreview(onOurActivity: () -> Unit, selfImage: String?, onViewMore: () -> Unit,
                                  onUser: (FriendActivity) -> Unit, onGroup: (ConversationSummary) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val vm = appViewModel<FeedViewModel<*>>(key = "menu:activity-preview") {
        FeedViewModel(it) { graph -> graph.catalog.friendActivity() }
    } as FeedViewModel<FriendActivity>
    val state by vm.state.collectAsStateWithLifecycle()
    AutoRefresh(vm)
    val friends = state.items.byActivity()
    val groupFeed = rememberGroupActivityPreviews()
    val groups = groupFeed.groups
    val entries = (friends.map { ActivityGridEntry(friend = it) } +
        groups.map { ActivityGridEntry(group = it) })
        .sortedWith(compareByDescending<ActivityGridEntry> { it.playing }
            .thenByDescending { it.timestamp }).take(8)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onViewMore) { Text("View more") }
        }
        if ((state.error != null || groupFeed.failed) && entries.isNotEmpty()) {
            TextButton(onClick = { vm.refresh(); groupFeed.retry() },
                enabled = !state.loading && !groupFeed.loading) { Text("Retry activity refresh") }
        }
        when {
            (state.loading || groupFeed.loading) && entries.isEmpty() ->
                Text("Loading activity…", style = MaterialTheme.typography.bodySmall)
            (state.error != null || groupFeed.failed) && entries.isEmpty() ->
                TextButton(onClick = { vm.refresh(); groupFeed.retry() },
                    enabled = !state.loading && !groupFeed.loading) { Text("Retry activity") }
            entries.isEmpty() -> Text("No friend activity yet", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                // Place our activity in cell 0 of the same three-column grid as friends.
                val tiles: List<ActivityGridEntry?> = listOf(null) + entries
                tiles.chunked(3).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { entry ->
                            if (entry == null) {
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
                                    entry.friend?.let { friend ->
                                        FriendTile(friend, Modifier.fillMaxWidth()) { onUser(friend) }
                                    }
                                    entry.group?.let { groupActivity ->
                                        GroupActivityTile(groupActivity, Modifier.fillMaxWidth()) {
                                            onGroup(groupActivity.chat)
                                        }
                                    }
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
internal fun LiveBars() {
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
    val groupFeed = rememberGroupActivityPreviews()
    val groups = groupFeed.groups
    // View More has the same active-first, newest-next order as the drawer grid.
    // Previously every group was displayed ahead of individuals regardless of recency.
    val entries = (groups.map { ActivityGridEntry(group = it) } +
        state.items.map { ActivityGridEntry(friend = it) })
        .sortedWith(compareByDescending<ActivityGridEntry> { it.playing }
            .thenByDescending { it.timestamp })
    var selectedGroup by remember { androidx.compose.runtime.mutableStateOf<ConversationSummary?>(null) }
    val navigator = LocalAppNavigator.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Friend Activity", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        if ((state.error != null || groupFeed.failed) &&
            (state.items.isNotEmpty() || groups.isNotEmpty())) {
            TextButton(onClick = { vm.refresh(); groupFeed.retry() },
                enabled = !state.loading && !groupFeed.loading) {
                Text("Couldn't refresh activity — Retry")
            }
        }
        when {
            (state.loading || groupFeed.loading) && state.items.isEmpty() && groups.isEmpty() -> LoadingState()
            (state.error != null || groupFeed.failed) && state.items.isEmpty() && groups.isEmpty() -> StateBox {
                ErrorState("Couldn't load activity after 4 attempts", onRetry = {
                    vm.refresh(); groupFeed.retry()
                })
            }
            state.items.isEmpty() && groups.isEmpty() -> StateBox { EmptyState(title = "No friend activity yet") }
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding)) {
                items(entries, key = { entry ->
                    entry.group?.let { "group-" + it.chat.hierarchyId }
                        ?: "friend-${entry.friend?.username}"
                }) { entry ->
                    entry.group?.let { group ->
                        Row(Modifier.fillMaxWidth().clickable { selectedGroup = group.chat }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MessageGroupAvatar(group.chat.participants, 48.dp)
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(group.title, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val listener = group.leadingListener
                                    val live = listener?.isActive == true
                                    if (live) LiveBars()
                                    Text(listener?.track?.name ?: "Recently listened",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (live) Color(0xFF1DB954)
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                }
                            }
                            Icon(Icons.Rounded.People, contentDescription = "Group activity")
                        }
                    }
                    entry.friend?.let { friend ->
                        FriendRow(friend, onUser = { navigator.navigate(Route.Profile(friend.username)) },
                            onTrack = { navigator.open(friend.track) },
                            onContext = friend.contextUri?.let { uri -> { navigator.openUri(uri) } })
                    }
                }
            }
        }
    }
    selectedGroup?.let { group ->
        GroupActivityModal(group, onDismiss = { selectedGroup = null },
            onNavigate = { route -> navigator.navigate(route) })
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
                text = listOfNotNull(friend.track.name, friend.track.subtitle).joinToString(" • "),
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

/** The drawer and full Messages page consume the same authenticated overview response. */
@Composable
private fun MessagesDrawerPreview(
    onViewAll: () -> Unit,
    onNewMessage: () -> Unit,
    onConversation: (String, String) -> Unit,
) {
    val rpc = rememberAppGraph().rpc
    var latest by remember { androidx.compose.runtime.mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var loading by remember { androidx.compose.runtime.mutableStateOf(true) }
    var failed by remember { androidx.compose.runtime.mutableStateOf(false) }
    var retryRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(rpc, retryRequest) {
        loading = latest.isEmpty()
        failed = false
        while (true) {
            try {
                val overview = retryChatRead {
                    rpc.call<MessageOverviewResult>("catalog.messagesOverview")
                }
                ChatUnreadStates.update(overview.conversations)
                latest = overview.conversations.filter { it.hierarchyId.isNotBlank() }
                    .distinctBy { it.hierarchyId }
                    .sortedByDescending { it.latestOccurredAtMs ?: Long.MIN_VALUE }
                    .take(5)
                failed = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
            // After four failed calls don't silently spin forever: show Retry.
            if (failed) break
            // Drawer is removed from composition when closed: polling stops with it.
            delay(15_000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).clickable(onClick = onViewAll),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Messages", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open all messages",
                    modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = onNewMessage) {
                Icon(Icons.Rounded.Edit, contentDescription = "New message")
            }
        }
        when {
            loading -> Text("Loading chats…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            failed -> Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Couldn't load recent chats after 4 attempts.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retryRequest++ }) { Text("Retry chats") }
            }
            latest.isEmpty() -> Text("No recent chats", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
        latest.forEach { chat ->
            val title = chat.displayTitle.ifBlank { chat.groupName ?: "Conversation" }
            val avatar = chat.participants.singleOrNull()?.avatarUrl
            Row(Modifier.fillMaxWidth().clickable { onConversation(chat.hierarchyId, title) }
                    .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chat.isGroup && chat.participants.isNotEmpty()) {
                    MessageGroupAvatar(chat.participants, 38.dp)
                } else {
                    Artwork(avatar, null, Modifier.size(38.dp), CircleShape,
                        Icons.Rounded.Person, avatarName = title)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sender = chat.latestSenderName?.takeIf { it.isNotBlank() }
                    val detail = chat.latestPreviewText ?: chat.latestContentLabel ?: "Chat activity"
                    Text(if (sender != null) "$sender · $detail" else detail,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConversationDateAndMute(chat.hierarchyId, chat.latestOccurredAtMs,
                    ChatUnreadStates.counts[chat.hierarchyId] ?: chat.unseenContributions)
            }
        }
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
