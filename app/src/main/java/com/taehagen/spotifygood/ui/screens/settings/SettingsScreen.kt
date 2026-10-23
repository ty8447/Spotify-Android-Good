package com.taehagen.spotifygood.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SpatialAudioOff
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.auth.BrowserLauncher
import com.taehagen.spotifygood.BuildConfig
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.ResponseCache
import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.data.settings.LibraryView
import com.taehagen.spotifygood.data.settings.Settings
import com.taehagen.spotifygood.data.settings.ThemeMode
import com.taehagen.spotifygood.engine.defaultDeviceName
import com.taehagen.spotifygood.model.Bitrate
import com.taehagen.spotifygood.download.DownloadLocation
import com.taehagen.spotifygood.download.DownloadRelocation
import com.taehagen.spotifygood.download.FailedCounts
import com.taehagen.spotifygood.model.NormalizePregain
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.formatBytes
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.navigation.openSleepTimer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Link
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import com.taehagen.spotifygood.data.settings.DataSaver

/** Settings state and actions. Mutations run in the app scope so they finish after navigation. */
/** Cache keys of catalog pages (album, artist, show, playlist pages, home) with track `playable` flags. */
private const val PLAYABLE_CACHE_PREFIX = "catalog."
private const val EXPLICIT_APPLY_TIMEOUT_MS = 15_000L

/**
 * The explicit filter changed: catalog pages (their track `playable` flags) are marked stale,
 * kept for offline display and refetched when next shown online. Library lists, saved state and
 * everything else are untouched (no [ResponseCache.clear]).
 */
internal suspend fun invalidatePlayableCatalog(cache: ResponseCache) = cache.invalidatePrefix(PLAYABLE_CACHE_PREFIX)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val settings: StateFlow<Settings> = graph.settings.settings
    val user: StateFlow<User?> = graph.engine.user
    val sleepTimer: StateFlow<SleepTimerState> = graph.sleepTimer.state

    val usedBytes: StateFlow<Long> = graph.downloads.usedBytes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val downloadedCount: StateFlow<Int> = graph.downloads.downloadedUris
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), graph.downloads.downloadedUris.value.size)
    /** Failed downloads: the retryable ones (offered by "Retry") and those not playable here. */
    val failedCounts: StateFlow<FailedCounts> = graph.downloads.failedCounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FailedCounts())

    /** Where downloads can go now (internal storage, mounted SD cards), with their free space. */
    val downloadLocations: StateFlow<List<DownloadLocation>> = graph.downloads.volumeChanges
        .map { graph.downloads.locations() }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Moving downloads to a newly chosen location. */
    val relocation: StateFlow<DownloadRelocation> = graph.downloads.relocation

    /** Downloads on an SD card that is no longer used (it died, or another card was chosen). */
    val onUnusedCard: StateFlow<Int> = graph.downloads.onUnusedCard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Restarts a move of the downloads that stopped. */
    fun retryMove() = graph.downloads.retryMove()

    fun redownloadFromUnusedCard(onDone: (Int?) -> Unit) {
        graph.appScope.launch {
            val notice = runCatching { graph.downloads.redownloadFromUnusedCard().notice }.getOrNull()
            withContext(Dispatchers.Main) { onDone(notice) }
        }
    }

    fun setDownloadLocation(id: String) {
        graph.appScope.launch { graph.downloads.setDownloadLocation(id) }
    }

    /** Audio session of the local player, for the system equalizer. */
    val audioSessionId: Int get() = graph.audioSink.audioSessionId

    fun update(transform: (Settings) -> Settings) {
        graph.appScope.launch { graph.settings.update(transform) }
    }

    /**
     * "Hide explicit content" (engine: `EngineSettings.filterExplicit`). Cached catalog pages
     * carry the old track `playable` flags: once the engine filters with the new value, they are
     * marked stale (kept for offline display, refetched when next shown online; shown ones reload
     * at once). Library lists and everything else stay as they are.
     */
    fun setHideExplicit(hide: Boolean) {
        graph.appScope.launch {
            graph.settings.update { it.copy(hideExplicit = hide) }
            graph.engine.awaitSettingsApplied(EXPLICIT_APPLY_TIMEOUT_MS) { it.filterExplicit == hide }
            invalidatePlayableCatalog(graph.responseCache)
        }
    }

    fun removeAllDownloads(onDone: () -> Unit) = runInApp(onDone) { graph.downloads.removeAll() }

    fun retryFailed(onDone: () -> Unit) = runInApp(onDone) { graph.downloads.retryFailed() }

    fun clearCache(context: Context, onDone: () -> Unit) = runInApp(onDone) {
        graph.responseCache.clear()
        val loader = SingletonImageLoader.get(context.applicationContext)
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }

    fun logout() = runInApp({}) { graph.logout() }

    private fun runInApp(onDone: () -> Unit, block: suspend () -> Unit) {
        graph.appScope.launch {
            try {
                block()
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("Settings", "Settings action failed", t)
            }
        }
    }
}

private val CACHE_SIZES_MB = listOf(256, 512, 1024, 2048, 3072, 4096)

private data class LicenseEntry(val name: String, val license: String)

private val LICENSES = listOf(
    LicenseEntry("librespot (core, connect, playback, audio, metadata, oauth, protocol)", "MIT"),
    LicenseEntry("Symphonia", "MPL-2.0"),
    LicenseEntry("Tokio", "MIT"),
    LicenseEntry("hyper", "MIT"),
    LicenseEntry("reqwest", "MIT OR Apache-2.0"),
    LicenseEntry("rustls", "Apache-2.0 OR ISC OR MIT"),
    LicenseEntry("ring", "Apache-2.0 AND ISC"),
    LicenseEntry("serde, serde_json", "MIT OR Apache-2.0"),
    LicenseEntry("rust-protobuf", "MIT"),
    LicenseEntry("jni-rs", "MIT OR Apache-2.0"),
    LicenseEntry("Android Jetpack (Compose, Media3, Navigation, Room, WorkManager, DataStore, Palette)", "Apache-2.0"),
    LicenseEntry("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
    LicenseEntry("OkHttp, Okio", "Apache-2.0"),
    LicenseEntry("Coil", "Apache-2.0"),
    LicenseEntry("Material Symbols", "Apache-2.0"),
)

private sealed interface SettingsDialog {
    data object Logout : SettingsDialog
    data object RemoveDownloads : SettingsDialog
    data object DeviceName : SettingsDialog
    data object PresenceWarning : SettingsDialog
    data object Licenses : SettingsDialog
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val vm = appViewModel { SettingsViewModel(it) }
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    val downloadedCount by vm.downloadedCount.collectAsStateWithLifecycle()
    val failedCounts by vm.failedCounts.collectAsStateWithLifecycle()
    val downloadLocations by vm.downloadLocations.collectAsStateWithLifecycle()
    val relocation by vm.relocation.collectAsStateWithLifecycle()
    val onUnusedCard by vm.onUnusedCard.collectAsStateWithLifecycle()
    val msgRedownloading = stringResource(R.string.shell_settings_unused_card_started)
    val sleepTimer by vm.sleepTimer.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var query by remember { mutableStateOf<String?>(null) } // null: search closed
    // Category pages are local to Settings: no extra app routes or collapsed sections.
    var activePage by rememberSaveable { mutableStateOf<String?>(null) }
    var recentlyPlayedArtists by remember { mutableStateOf<Boolean?>(null) }
    var artistsStatus by remember { mutableStateOf<String?>(null) }
    var artistsLoading by remember { mutableStateOf(false) }
    var artistsSaving by remember { mutableStateOf(false) }
    var artistsError by remember { mutableStateOf<String?>(null) }
    // The Profile options sheet uses these same catalog RPCs and verification semantics.
    LaunchedEffect(activePage, user?.username) {
        if (activePage != "h_privacy" || user == null) return@LaunchedEffect
        recentlyPlayedArtists = null
        artistsStatus = null
        artistsError = null
        artistsLoading = true
        try {
            val reply = graph.rpc.call<JsonObject>("catalog.profileVisibility")
            recentlyPlayedArtists = reply["artists"]?.jsonPrimitive?.booleanOrNull
            artistsStatus = reply["artistsStatus"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            artistsError = "Couldn't load profile visibility: ${e.message ?: "Network unavailable"}"
        } finally {
            artistsLoading = false
        }
    }
    fun setRecentlyPlayedArtists(enabled: Boolean) {
        if (artistsSaving || recentlyPlayedArtists == null) return
        artistsSaving = true
        artistsError = null
        scope.launch {
            try {
                val reply = graph.rpc.call<JsonObject>("catalog.setProfileVisibility", buildJsonObject {
                    put("key", "artists")
                    put("enabled", enabled)
                })
                val saved = reply["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: error("Spotify did not acknowledge the change")
                val verified = reply["verified"]?.jsonPrimitive?.booleanOrNull == true
                recentlyPlayedArtists = saved
                if (!verified) {
                    artistsError = "Saved. Spotify hasn't exposed a fresh verification read for recently played artists yet."
                }
            } catch (e: Exception) {
                artistsError = "Couldn't save setting: ${e.message ?: "Network unavailable"}"
            } finally {
                artistsSaving = false
            }
        }
    }
    // Every page gets a fresh scroll state; otherwise a long main Settings list can leave
    // the shorter Account page anchored past its last item, rendering it blank.
    val listState = remember(activePage) { LazyListState() }
    var storageFromData by rememberSaveable { mutableStateOf(false) }
    var showSectionPicker by remember { mutableStateOf(false) }
    var pendingJump by remember { mutableStateOf<String?>(null) }
    var highlightKey by remember { mutableStateOf<String?>(null) }
    val subpages = setOf("h_account_details", "h_content", "h_privacy", "h_playback", "h_quality", "h_data", "h_storage")
    LaunchedEffect(activePage) {
        // Opening a page from the main menu starts at its first setting.
        if (pendingJump == null) {
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(pendingJump, query, activePage) {
        val target = pendingJump ?: return@LaunchedEffect
        if (query != null) return@LaunchedEffect
        // Wait until the new page and its LazyColumn keys have been composed.
        withFrameNanos { }
        withFrameNanos { }
        listState.scrollToKey(target)
        highlightKey = target
        delay(1_700)
        highlightKey = null
        pendingJump = null
    }
    fun jumpTo(key: String, section: String) {
        storageFromData = false // Search always goes to the destination directly.
        activePage = if (section in subpages) section else null
        showSectionPicker = false
        query = null
        // A category selection opens the page itself, not a nonexistent child row.
        pendingJump = if (key == section && section in subpages) null else key
        highlightKey = null
    }
    val openAccountWeb: (String) -> Unit = { url ->
        if (activity == null || !BrowserLauncher.open(activity, url)) {
            navigator.showMessage("No web browser is available")
        }
    }
    BackHandler(enabled = query != null || activePage != null) {
        when {
            query != null -> query = null
            activePage == "h_storage" && storageFromData -> {
                activePage = "h_data"
                storageFromData = false
            }
            else -> activePage = null
        }
    }
    // open.spotify.com links need the user's approval on Android 12+; checked again whenever the
    // screen comes back (from the system's "Open by default" page).
    val lifecycleOwner = LocalLifecycleOwner.current
    var linksNeedApproval by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            linksNeedApproval = withContext(Dispatchers.IO) { SpotifyLinkApproval.needed(context) }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // System equalizer (only offered when an app can handle it).
    val equalizerIntent = remember(vm) {
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, vm.audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
    }
    val equalizerAvailable by produceState(initialValue = false, equalizerIntent) {
        value = withContext(Dispatchers.IO) { equalizerIntent.resolveActivity(context.packageManager) != null }
    }
    val equalizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val bitrateOptions = listOf(
        Bitrate.LOW to stringResource(R.string.shell_quality_low),
        Bitrate.NORMAL to stringResource(R.string.shell_quality_normal),
        Bitrate.HIGH to stringResource(R.string.shell_quality_high),
    )
    val msgCacheCleared = stringResource(R.string.shell_msg_cache_cleared)
    val msgDownloadsRemoved = stringResource(R.string.shell_msg_downloads_removed)
    val msgRetrying = stringResource(R.string.shell_msg_retrying_downloads)

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(SETTINGS_SECTIONS.firstOrNull { it.first == activePage }?.second
                        ?: stringResource(R.string.shell_settings_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            query != null -> query = null
                            activePage == "h_storage" && storageFromData -> {
                                activePage = "h_data"
                                storageFromData = false
                            }
                            activePage != null -> activePage = null
                            else -> navigator.back()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.shell_cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { query = if (query == null) "" else null }) {
                        Icon(if (query == null) Icons.Rounded.Search else Icons.Rounded.Close, contentDescription = "Search settings")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { inner ->
        if (query != null) SettingsSearch(
            query = query.orEmpty(), onQuery = { query = it }, padding = inner,
            visible = buildSet {
                addAll(SEARCH_INDEX.map { it.key })
                addAll(FUTURE_SETTINGS.map { it.key })
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) remove("dynamic_color")
                if (downloadLocations.size <= 1 && settings.downloadLocation.isEmpty()) remove("download_location")
                if (!linksNeedApproval) remove("open_links")
                if (failedCounts.retryable == 0) remove("retry_failed")
                if (onUnusedCard == 0) remove("unused_card")
                if (!relocation.moving && relocation.error == null && relocation.unreadable == 0) remove("download_moving")
            },
        ) { key, section -> jumpTo(key, section) } else if (activePage == "h_account_details") {
            // Render Account in its own list, not the dynamic main-settings item provider.
            // This also guarantees that an unloaded User shows the static Account rows.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().drawBehind {
                    val chosen = highlightKey?.let { selected ->
                        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == selected }
                    }
                    if (chosen != null) {
                        drawRoundRect(
                            color = androidx.compose.ui.graphics.Color(0xFFE5AF39).copy(alpha = 0.29f),
                            topLeft = Offset(4.dp.toPx(), chosen.offset.toFloat()),
                            size = Size(size.width - 8.dp.toPx(), chosen.size.toFloat()),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                },
                contentPadding = PaddingValues(
                    top = inner.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                accountSettingsRows(user, openAccountWeb)
            }
        } else LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().drawBehind {
                // Highlight the actual lazy item after returning from search; no per-row wrappers.
                val chosen = highlightKey?.let { selected ->
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == selected }
                }
                if (chosen != null) {
                    drawRoundRect(
                        color = androidx.compose.ui.graphics.Color(0xFFE5AF39).copy(alpha = 0.29f),
                        topLeft = Offset(4.dp.toPx(), chosen.offset.toFloat()),
                        size = Size(size.width - 8.dp.toPx(), chosen.size.toFloat()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
            },
            contentPadding = PaddingValues(
                top = inner.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            if (activePage == null) {
            item(key = "quick") {
                var explainPrivate by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Data saver", Icons.Rounded.SignalCellularAlt,
                        when (settings.dataSaver) { DataSaver.AUTO -> "Automatic"; DataSaver.ON -> "Always on"; DataSaver.OFF -> "Always off" },
                        settings.dataSaver != DataSaver.OFF) {
                        vm.update { it.copy(dataSaver = DataSaver.entries[(it.dataSaver.ordinal + 1) % DataSaver.entries.size]) }
                    }
                    QuickTile("Offline mode", Icons.Rounded.CloudOff, if (settings.offlineMode) "On" else "Off", settings.offlineMode) {
                        vm.update { it.copy(offlineMode = !it.offlineMode) }
                    }
                    QuickTile("Private session", Icons.Rounded.VisibilityOff, if (settings.privateSession) "On" else "Off", settings.privateSession) {
                        if (!settings.privateSession) explainPrivate = true
                        vm.update { it.copy(privateSession = !it.privateSession) }
                    }
                }
                if (explainPrivate) PrivateSessionExplainer { explainPrivate = false }
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { showSectionPicker = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Jump to settings section")
                    }
                    DropdownMenu(expanded = showSectionPicker, onDismissRequest = { showSectionPicker = false }) {
                        SETTINGS_SECTIONS.filterNot { it.first == "h_account_details" }.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { jumpTo(id, id) })
                        }
                    }
                }
            }
            // Current account stays visible on the landing page and opens its detail page.
            item(key = "h_account") { SettingsInlineHeader("Account") }
            item(key = "account") {
                val name = user?.let { it.displayName ?: it.username } ?: stringResource(R.string.shell_settings_account_unknown)
                val product = user?.product?.replaceFirstChar { it.uppercase() }
                ListItem(
                    headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            listOfNotNull(product, user?.country).joinToString(" • ")
                                .ifEmpty { stringResource(R.string.shell_settings_view_profile) },
                        )
                    },
                    leadingContent = {
                        Artwork(user?.images?.best(120), null, Modifier.size(48.dp), CircleShape, Icons.Rounded.Person)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = transparentListItem(),
                    modifier = Modifier.clickableRow { activePage = "h_account_details" },
                )
                        }
            // Main Settings navigation: categories open clean, full-height subpages.
            item(key = "h_content") { SettingsPageLink("Content and display", "Videos, content preferences, appearance", Icons.Rounded.ColorLens) { activePage = "h_content" } }
            item(key = "h_privacy") { SettingsPageLink("Privacy and social", "Activity, playlists, followers, messages", Icons.Rounded.VisibilityOff) { activePage = "h_privacy" } }
            item(key = "h_playback") { SettingsPageLink("Playback", "Transitions, listening and volume controls", Icons.Rounded.GraphicEq) { activePage = "h_playback" } }
            item(key = "h_quality") { SettingsPageLink("Media quality", "Streaming and download quality", Icons.Rounded.HighQuality) { activePage = "h_quality" } }
            item(key = "h_data") { SettingsPageLink("Data-saving and offline", "Data saver, offline mode, downloads and podcasts", Icons.Rounded.CloudOff) { activePage = "h_data" } }
            item(key = "h_storage") { SettingsPageLink("Storage", "Usage breakdown, downloads and cache", Icons.Rounded.Storage) {
                storageFromData = false
                activePage = "h_storage"
            } }
            }

            // Content and display settings page.
            if (activePage == "h_content") {
            item(key = "sub_videos_and_canvas") { SettingsSubsection("Videos and Canvas") }
            FutureSettingsGroup("h_content_video", navigator::showMessage)
            item(key = "sub_content_preferences") { SettingsSubsection("Content preferences") }
            item(key = "explicit") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_hide_explicit),
                    summary = stringResource(R.string.shell_settings_hide_explicit_summary),
                    icon = Icons.Rounded.Explicit,
                    checked = settings.hideExplicit,
                    onCheckedChange = vm::setHideExplicit,
                )
                        }
            FutureSettingsGroup("h_content_preferences", navigator::showMessage)
            item(key = "sub_display_preferences") { SettingsSubsection("Display preferences") }
            item(key = "theme") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_theme),
                    icon = Icons.Rounded.DarkMode,
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.shell_theme_system),
                        ThemeMode.DARK to stringResource(R.string.shell_theme_dark),
                        ThemeMode.LIGHT to stringResource(R.string.shell_theme_light),
                    ),
                    selected = settings.theme,
                    onSelect = { mode -> vm.update { it.copy(theme = mode) } },
                )
                        }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item(key = "dynamic_color") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_dynamic_color),
                    summary = stringResource(R.string.shell_settings_dynamic_color_summary),
                    icon = Icons.Rounded.ColorLens,
                    checked = settings.dynamicColor,
                    onCheckedChange = { v -> vm.update { it.copy(dynamicColor = v) } },
                )
                            }
            }
            item(key = "lyrics") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_lyrics),
                    summary = stringResource(R.string.shell_settings_lyrics_summary),
                    icon = Icons.Rounded.Lyrics,
                    checked = settings.showLyricsOnNowPlaying,
                    onCheckedChange = { v -> vm.update { it.copy(showLyricsOnNowPlaying = v) } },
                )
                        }
            item(key = "library_view") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_library_view),
                    icon = Icons.Rounded.GridView,
                    options = listOf(
                        LibraryView.LIST to stringResource(R.string.shell_library_view_list),
                        LibraryView.GRID to stringResource(R.string.shell_library_view_grid),
                    ),
                    selected = settings.libraryView,
                    onSelect = { v -> vm.update { it.copy(libraryView = v) } },
                )
                        }
            item(key = "library_sort") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_library_sort),
                    icon = Icons.AutoMirrored.Rounded.Sort,
                    options = listOf(
                        LibrarySort.RECENT to stringResource(R.string.shell_sort_recent),
                        LibrarySort.RECENTLY_ADDED to stringResource(R.string.shell_sort_recently_added),
                        LibrarySort.ALPHABETICAL to stringResource(R.string.shell_sort_alphabetical),
                        LibrarySort.CREATOR to stringResource(R.string.shell_sort_creator),
                    ),
                    selected = settings.librarySort,
                    onSelect = { v -> vm.update { it.copy(librarySort = v) } },
                )
                        }
            FutureSettingsGroup("h_content_display", navigator::showMessage)
            }

            // Privacy and social settings page; playlist management keeps its existing editor.
            if (activePage == "h_privacy") {
            item(key = "sub_listening_visibility") { SettingsSubsection("Listening visibility") }
            item(key = "privacy") { PrivacySwitches(settings, vm::update) }
            item(key = "sub_playlist_visibility") { SettingsSubsection("Playlist visibility") }
            item(key = "public_playlists") { PrefItem("Public playlists", Icons.Rounded.GridView,
                                "Review and change public playlist visibility", onClick = { navigator.navigate(Route.PlaylistPrivacy) }) }
            item(key = "profile_playlists") { PrefItem("Playlists on your profile", Icons.Rounded.Person,
                                "Choose which playlists appear on your profile", onClick = { navigator.navigate(Route.ProfilePlaylists) }) }
            item(key = "playlist_privacy") { PrefItem("Playlist privacy", Icons.Rounded.GridView,
                                "Select your playlists and make them public or private", onClick = { navigator.navigate(Route.PlaylistPrivacy) }) }
            FutureSettingsGroup("h_privacy_playlist", navigator::showMessage)
            item(key = "sub_profile_visibility") { SettingsSubsection("Profile visibility") }
            item(key = "followers") { PrefItem("Followers", Icons.Rounded.Group,
                                onClick = { navigator.navigate(Route.SocialPeople("followers")) }) }
            item(key = "following") { PrefItem("Following", Icons.Rounded.Group,
                                onClick = { navigator.navigate(Route.SocialPeople("following")) }) }
            item(key = "recent_artists") {
                SwitchPref(
                    title = "Recently played artists",
                    summary = "People can see who you recently listened to on your profile.",
                    icon = Icons.Rounded.Person,
                    checked = recentlyPlayedArtists == true,
                    enabled = recentlyPlayedArtists != null && !artistsLoading && !artistsSaving,
                    onCheckedChange = ::setRecentlyPlayedArtists,
                )
            }
            if (artistsError != null || (!artistsLoading && recentlyPlayedArtists == null)) {
                item(key = "recent_artists_status") {
                    Text(
                        artistsError ?: when (artistsStatus) {
                            "missing_from_session" -> "Recently played artists cannot be read from the current Spotify session. No setting has been changed."
                            "unrecognized_session_value" -> "Spotify returned an unsupported Recently played artists value. No setting has been changed."
                            else -> "Recently played artists is unavailable until Spotify confirms its saved value."
                        },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (artistsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FutureSettingsGroup("h_privacy_profile", navigator::showMessage)
            item(key = "sub_social_features") { SettingsSubsection("Social features") }
            item(key = "messages") { PrefItem("Messages", Icons.Rounded.Group,
                                "Open conversations", onClick = { navigator.navigate(Route.Messages) }) }
            FutureSettingsGroup("h_privacy_social", navigator::showMessage)
            item(key = "sub_devices_and_spotify_connect") { SettingsSubsection("Devices and Spotify Connect") }
            item(key = "device_name") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_device_name),
                    summary = settings.deviceName.ifBlank { stringResource(R.string.shell_settings_device_name_default, defaultDeviceName()) },
                    icon = Icons.Rounded.Devices,
                    onClick = { dialog = SettingsDialog.DeviceName },
                )
                        }
            item(key = "presence") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_presence),
                    summary = stringResource(R.string.shell_settings_presence_summary),
                    icon = Icons.Rounded.CellTower,
                    checked = settings.connectPresence,
                    onCheckedChange = { v ->
                        if (v) dialog = SettingsDialog.PresenceWarning else vm.update { it.copy(connectPresence = false) }
                    },
                )
                        }
            }

            // Playback settings page.
            if (activePage == "h_playback") {
            item(key = "sub_track_transitions") { SettingsSubsection("Track transitions") }
            item(key = "gapless") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_gapless),
                    summary = stringResource(R.string.shell_settings_gapless_summary),
                    icon = Icons.Rounded.GraphicEq,
                    checked = settings.gapless,
                    onCheckedChange = { v -> vm.update { it.copy(gapless = v) } },
                )
                        }
            item(key = "automix") {
                SwitchPref(
                    title = "Automix",
                    summary = "Saved preference; automatic DJ-style transitions are not yet supported by the playback engine",
                    icon = Icons.Rounded.GraphicEq,
                    checked = settings.automix,
                    onCheckedChange = { enabled -> vm.update { it.copy(automix = enabled) } },
                )
                        }
            item(key = "crossfade") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text("Crossfade: ${settings.crossfadeSeconds}s", style = MaterialTheme.typography.titleMedium)
                    Text("0–12 seconds · saved preference; audio crossfade is not yet implemented",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = settings.crossfadeSeconds.coerceIn(0, 12).toFloat(),
                        onValueChange = { seconds -> vm.update { it.copy(crossfadeSeconds = seconds.toInt().coerceIn(0, 12)) } },
                        valueRange = 0f..12f,
                        steps = 11,
                    )
                }
                        }
            item(key = "sub_listening_controls") { SettingsSubsection("Listening controls") }
            item(key = "autoplay") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_autoplay),
                    summary = stringResource(R.string.shell_settings_autoplay_summary),
                    icon = Icons.Rounded.SpatialAudioOff,
                    checked = settings.autoplay,
                    onCheckedChange = { v -> vm.update { it.copy(autoplay = v) } },
                )
                        }
            item(key = "mono_audio") {
                SwitchPref(
                    title = "Mono audio",
                    summary = "Saved preference; stereo-to-mono mixing is not yet implemented in the audio engine",
                    icon = Icons.Rounded.GraphicEq,
                    checked = settings.monoAudio,
                    onCheckedChange = { enabled -> vm.update { it.copy(monoAudio = enabled) } },
                )
                        }
            item(key = "sleep") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_sleep_timer),
                    summary = sleepTimerSummary(sleepTimer),
                    icon = Icons.Rounded.Bedtime,
                    onClick = { navigator.openSleepTimer() },
                )
                        }
            FutureSettingsGroup("h_playback_listening", navigator::showMessage)
            item(key = "sub_video_controls") { SettingsSubsection("Video controls") }
            FutureSettingsGroup("h_playback_video", navigator::showMessage)
            item(key = "sub_volume_controls") { SettingsSubsection("Volume controls") }
            item(key = "normalize") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_normalize),
                    summary = stringResource(R.string.shell_settings_normalize_summary),
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    checked = settings.normalize,
                    onCheckedChange = { v -> vm.update { it.copy(normalize = v) } },
                )
                        }
            item(key = "normalize_level") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_volume_level),
                    icon = Icons.Rounded.Tune,
                    enabled = settings.normalize,
                    options = listOf(
                        NormalizePregain.QUIET to stringResource(R.string.shell_level_quiet),
                        NormalizePregain.NORMAL to stringResource(R.string.shell_level_normal),
                        NormalizePregain.LOUD to stringResource(R.string.shell_level_loud),
                    ),
                    selected = settings.normalizePregain,
                    onSelect = { level -> vm.update { it.copy(normalizePregain = level) } },
                )
            }
            item(key = "equalizer") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_equalizer),
                    summary = stringResource(
                        if (equalizerAvailable) R.string.shell_settings_equalizer_summary else R.string.shell_settings_equalizer_unavailable,
                    ),
                    icon = Icons.Rounded.Equalizer,
                    enabled = equalizerAvailable,
                    onClick = { runCatching { equalizerLauncher.launch(equalizerIntent) } },
                )
            }
            }

            // Media quality settings page.
            if (activePage == "h_quality") {
            item(key = "sub_audio_streaming_quality") { SettingsSubsection("Audio streaming quality") }
            item(key = "quality_wifi") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_quality_wifi),
                    icon = Icons.Rounded.Wifi,
                    options = bitrateOptions,
                    selected = settings.streamingQuality,
                    onSelect = { q -> vm.update { it.copy(streamingQuality = q) } },
                )
                        }
            item(key = "quality_cell") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_quality_cellular),
                    icon = Icons.Rounded.SignalCellularAlt,
                    options = bitrateOptions,
                    selected = settings.cellularQuality,
                    onSelect = { q -> vm.update { it.copy(cellularQuality = q) } },
                )
                        }
            item(key = "sub_download_quality") { SettingsSubsection("Download quality") }
            item(key = "download_quality") {
                ChoicePref(
                    title = stringResource(R.string.shell_settings_download_quality),
                    icon = Icons.Rounded.HighQuality,
                    options = bitrateOptions,
                    selected = settings.downloadQuality,
                    onSelect = { q -> vm.update { it.copy(downloadQuality = q) } },
                )
                        }
            FutureSettingsGroup("h_quality", navigator::showMessage)
            }

            // Data saver, Offline, downloads, and podcast preferences share one page.
            if (activePage == "h_data") {
            item(key = "sub_data_saver_mode") { SettingsSubsection("Data saver mode") }
            item(key = "data_saver") {
                ChoicePref(
                    title = "Data saver",
                    icon = Icons.Rounded.SignalCellularAlt,
                    options = listOf(
                        DataSaver.AUTO to "Automatic",
                        DataSaver.ON to "Always on",
                        DataSaver.OFF to "Always off",
                    ),
                    selected = settings.dataSaver,
                    onSelect = { value -> vm.update { it.copy(dataSaver = value) } },
                )
                        }
            item(key = "sub_offline") { SettingsSubsection("Offline") }
            item(key = "offline_mode") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_offline_mode),
                    summary = stringResource(R.string.shell_settings_offline_mode_summary),
                    icon = Icons.Rounded.CloudOff,
                    checked = settings.offlineMode,
                    onCheckedChange = { v -> vm.update { it.copy(offlineMode = v) } },
                )
            }
            item(key = "sub_downloads_and_streaming") { SettingsSubsection("Downloads and streaming") }
            item(key = "download_cellular") {
                SwitchPref(
                    title = stringResource(R.string.shell_settings_download_cellular),
                    summary = stringResource(R.string.shell_settings_download_cellular_summary),
                    icon = Icons.Rounded.Download,
                    checked = settings.downloadOverCellular,
                    onCheckedChange = { v -> vm.update { it.copy(downloadOverCellular = v) } },
                )
                        }
            FutureSettingsGroup("h_data_podcasts", navigator::showMessage)
            item(key = "sub_downloads") { SettingsSubsection("Downloads") }
            item(key = "storage_used") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_storage_used),
                    summary = stringResource(R.string.shell_settings_storage_used_summary, formatBytes(context, usedBytes), downloadedCount),
                    icon = Icons.Rounded.SdStorage,
                    onClick = { navigator.navigate(Route.Downloads) },
                )
                        }
            if (failedCounts.retryable > 0) {
            item(key = "retry_failed") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_retry_failed),
                    summary = stringResource(R.string.shell_settings_retry_failed_summary, failedCounts.retryable),
                    icon = Icons.Rounded.Refresh,
                    onClick = { vm.retryFailed { navigator.showMessage(msgRetrying) } },
                )
                            }
            }
            item(key = "remove_downloads") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_remove_downloads),
                    summary = stringResource(R.string.shell_settings_remove_downloads_summary),
                    icon = Icons.Rounded.DeleteSweep,
                    // Failed rows can still own (partial) files, so they count too.
                    enabled = downloadedCount > 0 || usedBytes > 0 || failedCounts.total > 0,
                    onClick = { dialog = SettingsDialog.RemoveDownloads },
                    destructive = true,
                )
                        }
            item(key = "storage_link") {
                SettingsPageLink("Storage", "Space used, cache and download location", Icons.Rounded.Storage) {
                    storageFromData = true
                    activePage = "h_storage"
                }
            }
            }

            // Storage is visible on the main page and linked from Data-saving and offline.
            if (activePage == "h_storage") {
            item(key = "sub_storage") { SettingsSubsection("Storage") }
            item(key = "storage_breakdown") { StorageBreakdownContent() }
            if (downloadLocations.size > 1 || settings.downloadLocation.isNotEmpty()) {
            item(key = "download_location") {
                val options = downloadLocations.map { location ->
                    location.id to stringResource(R.string.shell_settings_download_location_option, location.label, formatBytes(context, location.freeBytes))
                }
                val chosenMissing = downloadLocations.isNotEmpty() && downloadLocations.none { it.id == settings.downloadLocation }
                ChoicePref(
                    title = stringResource(R.string.shell_settings_download_location),
                    icon = Icons.Rounded.SdCard,
                    options = if (chosenMissing) {
                        options + (settings.downloadLocation to stringResource(R.string.shell_settings_download_location_missing, stringResource(R.string.data_dl_location_card)))
                    } else {
                        options
                    },
                    selected = settings.downloadLocation,
                    onSelect = { id -> vm.setDownloadLocation(id) },
                )
                            }
            }
            if (onUnusedCard > 0) {
            item(key = "unused_card") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_unused_card),
                    summary = stringResource(R.string.shell_settings_unused_card_summary, onUnusedCard),
                    icon = Icons.Rounded.SdCard,
                    onClick = {
                        vm.redownloadFromUnusedCard { notice ->
                            navigator.showMessage(notice?.let { context.getString(it) } ?: msgRedownloading)
                        }
                    },
                )
                            }
            }
            if (relocation.moving || relocation.error != null || relocation.unreadable > 0) {
            item(key = "download_moving") {
                PrefItem(
                    title = stringResource(
                        when {
                            relocation.moving -> R.string.shell_settings_download_moving
                            relocation.error != null -> R.string.shell_settings_download_move_stopped
                            else -> R.string.shell_settings_download_moved
                        },
                    ),
                    summary = relocation.error?.let { stringResource(R.string.shell_settings_download_move_stopped_summary, it) }
                        ?: if (!relocation.moving && relocation.unreadable > 0) {
                            stringResource(R.string.shell_settings_download_move_unreadable, relocation.unreadable)
                        } else {
                            stringResource(R.string.shell_settings_download_moving_summary, relocation.moved, relocation.total)
                        },
                    icon = Icons.Rounded.SdCard,
                    // A move that stopped starts again from here, also for the same location.
                    onClick = { if (relocation.error != null && !relocation.moving) vm.retryMove() else navigator.navigate(Route.Downloads) },
                )
                            }
            }
            item(key = "cache_size") {
                CacheSizePref(
                    valueMb = settings.streamingCacheMb,
                    onValueChange = { mb -> vm.update { it.copy(streamingCacheMb = mb) } },
                )
                        }
            item(key = "clear_cache") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_clear_cache),
                    summary = stringResource(R.string.shell_settings_clear_cache_summary),
                    icon = Icons.Rounded.CleaningServices,
                    onClick = { vm.clearCache(context) { navigator.showMessage(msgCacheCleared) } },
                )
                        }
            }

            // These remain on the Settings landing page, without additional category pages.
            if (activePage == null) {
            item(key = "h_notifications") { SettingsInlineHeader("Notification preferences") }
            item(key = "notifications") { PrefItem("Notification preferences", Icons.Rounded.Info,
                                "Push, email, or off, by notification type", onClick = { navigator.navigate(Route.NotificationSettings) }) }
            item(key = "h_links") { SettingsInlineHeader("Links") }
            if (linksNeedApproval) {
            item(key = "open_links") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_open_links),
                    summary = stringResource(R.string.shell_settings_open_links_summary),
                    icon = Icons.Rounded.Link,
                    onClick = { SpotifyLinkApproval.openSettings(context) },
                )
                            }
            } else {
                item(key = "links_status") {
                    PrefItem("Spotify links", Icons.Rounded.Link,
                        "Supported Spotify links are already enabled on this device")
                }
            }
            item(key = "h_about") { SettingsInlineHeader("About and support") }
            item(key = "version") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_version),
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · debug" else "",
                    icon = Icons.Rounded.Info,
                )
                        }
            item(key = "licenses") {
                PrefItem(
                    title = stringResource(R.string.shell_settings_licenses),
                    summary = stringResource(R.string.shell_settings_licenses_summary),
                    icon = Icons.Rounded.Code,
                    onClick = { dialog = SettingsDialog.Licenses },
                )
                        }
            item(key = "disclaimer") {
                Text(
                    text = stringResource(R.string.shell_settings_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
                        }
            item(key = "logout") {
                PrefItem(
                    title = stringResource(R.string.shell_logout),
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    summary = stringResource(R.string.shell_settings_logout_summary),
                    onClick = { dialog = SettingsDialog.Logout },
                    destructive = true,
                )
                        }
            } // Main-page notification preferences, links, and about

        }
    }

    when (dialog) {
        SettingsDialog.Logout -> ConfirmDialog(
            title = stringResource(R.string.shell_logout_confirm_title),
            message = stringResource(R.string.shell_logout_confirm_message),
            confirm = stringResource(R.string.shell_logout),
            onConfirm = {
                dialog = null
                vm.logout()
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.RemoveDownloads -> ConfirmDialog(
            title = stringResource(R.string.shell_remove_downloads_confirm_title),
            message = stringResource(R.string.shell_remove_downloads_confirm_message, formatBytes(context, usedBytes)),
            confirm = stringResource(R.string.shell_remove),
            onConfirm = {
                dialog = null
                vm.removeAllDownloads { navigator.showMessage(msgDownloadsRemoved) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.PresenceWarning -> ConfirmDialog(
            title = stringResource(R.string.shell_presence_confirm_title),
            message = stringResource(R.string.shell_presence_confirm_message),
            confirm = stringResource(R.string.shell_turn_on),
            destructive = false,
            onConfirm = {
                dialog = null
                vm.update { it.copy(connectPresence = true) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DeviceName -> DeviceNameDialog(
            current = settings.deviceName,
            onSave = { name ->
                dialog = null
                vm.update { it.copy(deviceName = name) }
            },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Licenses -> LicensesDialog(onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun sleepTimerSummary(state: SleepTimerState): String {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (state is SleepTimerState.Running) {
        LaunchedEffect(state, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    now = SystemClock.elapsedRealtime()
                    delay(15_000)
                }
            }
        }
    }
    return when (state) {
        SleepTimerState.Off -> stringResource(R.string.shell_sleep_off)
        SleepTimerState.EndOfTrack -> stringResource(R.string.shell_sleep_end_of_track)
        is SleepTimerState.Running -> {
            val minutes = ((state.endsAtElapsedMs - now).coerceAtLeast(0) + 59_999) / 60_000
            stringResource(R.string.shell_sleep_running, minutes.toInt())
        }
    }
}

// ---- Preference building blocks ------------------------------------------------------------------

@Composable
private fun transparentListItem() = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)

private fun Modifier.clickableRow(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 56.dp)

@Composable
private fun RowScope.QuickTile(label: String, icon: ImageVector, value: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
        color = if (active) colors.primaryContainer else colors.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Every searchable result is linked to an actual lazy-list key and its section. */
private data class SettingEntry(val key: String, val title: Any, val section: String, val words: String = "")

private val SEARCH_INDEX = listOf(
    SettingEntry("h_content", "Content and display", "h_content", "video canvas theme appearance"),
    SettingEntry("h_privacy", "Privacy and social", "h_privacy", "playlists followers messages"),
    SettingEntry("h_playback", "Playback", "h_playback", "equalizer shuffle volume"),
    SettingEntry("h_quality", "Media quality", "h_quality", "audio video download quality"),
    SettingEntry("h_data", "Data-saving and offline", "h_data", "podcasts offline cellular downloads"),
    SettingEntry("h_storage", "Storage", "h_storage", "cache breakdown app space"),
    SettingEntry("account", R.string.shell_settings_account, "h_account", "profile name username"),
    SettingEntry("account_username", "Username", "h_account_details", "spotify id account"),
    SettingEntry("account_email", "Email", "h_account_details", "login address"),
    SettingEntry("account_address", "Address", "h_account_details", "location home"),
    SettingEntry("account_overview", "Account overview", "h_account_details", "account details web"),
    SettingEntry("account_plan", "Your plan", "h_account_details", "premium family student duo individual free subscription billing"),
    SettingEntry("account_close", "Close your account", "h_account_details", "delete account data"),
    SettingEntry("explicit", R.string.shell_settings_hide_explicit, "h_content", "parental clean songs"),
    SettingEntry("theme", R.string.shell_settings_theme, "h_content", "dark light mode"),
    SettingEntry("dynamic_color", R.string.shell_settings_dynamic_color, "h_content", "wallpaper"),
    SettingEntry("lyrics", R.string.shell_settings_lyrics, "h_content", "karaoke"),
    SettingEntry("library_view", R.string.shell_settings_library_view, "h_content", "grid list"),
    SettingEntry("library_sort", R.string.shell_settings_library_sort, "h_content", "alphabetical order"),
    SettingEntry("privacy", "Private session", "h_privacy", "hide listening activity"),
    SettingEntry("public_playlists", "Public playlists", "h_privacy", "visibility"),
    SettingEntry("profile_playlists", "Playlists on your profile", "h_privacy", "display show hide"),
    SettingEntry("playlist_privacy", "Playlist privacy", "h_privacy", "public private bulk edit"),
    SettingEntry("followers", "Followers", "h_privacy"),
    SettingEntry("following", "Following", "h_privacy"),
    SettingEntry("recent_artists", "Recently played artists", "h_privacy", "profile visibility listening history recents"),
    SettingEntry("messages", "Messages", "h_privacy", "chat"),
    SettingEntry("device_name", R.string.shell_settings_device_name, "h_privacy", "spotify connect"),
    SettingEntry("presence", R.string.shell_settings_presence, "h_privacy", "connect visible background"),
    SettingEntry("gapless", R.string.shell_settings_gapless, "h_playback", "crossfade transition"),
    SettingEntry("automix", "Automix", "h_playback", "crossmix dj transitions"),
    SettingEntry("crossfade", "Crossfade", "h_playback", "transition seconds"),
    SettingEntry("autoplay", R.string.shell_settings_autoplay, "h_playback", "continue similar"),
    SettingEntry("mono_audio", "Mono audio", "h_playback", "stereo accessibility"),
    SettingEntry("sleep", R.string.shell_settings_sleep_timer, "h_playback", "bedtime"),
    SettingEntry("normalize", R.string.shell_settings_normalize, "h_playback", "volume loudness"),
    SettingEntry("normalize_level", R.string.shell_settings_volume_level, "h_playback", "quiet loudness"),
    SettingEntry("quality_wifi", R.string.shell_settings_quality_wifi, "h_quality", "audio streaming bitrate sound"),
    SettingEntry("quality_cell", R.string.shell_settings_quality_cellular, "h_quality", "data saver mobile bitrate"),
    SettingEntry("download_quality", R.string.shell_settings_download_quality, "h_quality", "offline bitrate"),
    SettingEntry("offline_mode", "Offline mode", "h_data", "no internet offline"),
    SettingEntry("data_saver", "Data saver", "h_data", "mobile cellular metered bandwidth"),
    SettingEntry("download_cellular", R.string.shell_settings_download_cellular, "h_data", "mobile data"),
    SettingEntry("storage_used", R.string.shell_settings_storage_used, "h_data", "disk downloads"),
    SettingEntry("retry_failed", R.string.shell_settings_retry_failed, "h_data", "failed"),
    SettingEntry("remove_downloads", R.string.shell_settings_remove_downloads, "h_data", "delete"),
    SettingEntry("download_location", R.string.shell_settings_download_location, "h_storage", "sd card"),
    SettingEntry("unused_card", R.string.shell_settings_unused_card, "h_storage", "redownload missing"),
    SettingEntry("download_moving", R.string.shell_settings_download_moving, "h_storage", "move relocating"),
    SettingEntry("storage_breakdown", "Storage breakdown", "h_storage", "cache app downloads usage graph"),
    SettingEntry("cache_size", R.string.shell_settings_cache_size, "h_storage", "space streaming"),
    SettingEntry("clear_cache", R.string.shell_settings_clear_cache, "h_storage", "delete"),
    SettingEntry("equalizer", R.string.shell_settings_equalizer, "h_playback", "bass treble eq"),
    SettingEntry("notifications", "Notification preferences", "h_notifications", "email push off alerts"),
    SettingEntry("open_links", R.string.shell_settings_open_links, "h_links", "open by default"),
    SettingEntry("version", R.string.shell_settings_version, "h_about", "build"),
    SettingEntry("licenses", R.string.shell_settings_licenses, "h_about", "legal"),
    SettingEntry("logout", R.string.shell_logout, "h_about", "sign out"),
)

/** Always search the *rendered* settings and expand the owning section before scrolling. */
@Composable
private fun SettingsSearch(
    query: String,
    onQuery: (String) -> Unit,
    padding: PaddingValues,
    visible: Set<String>,
    onPick: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query, visible) {
        (SEARCH_INDEX + FUTURE_SETTINGS.map { SettingEntry(it.key, it.title, PLANNED_PARENT_SECTION[it.section] ?: it.section, it.words) })
            .filter { it.key in visible }
            .mapNotNull { entry ->
                val title = (entry.title as? Int)?.let(context::getString) ?: entry.title.toString()
                val sectionLabel = SETTINGS_SECTIONS.firstOrNull { it.first == entry.section }?.second.orEmpty()
                val score = maxOf(fuzzyScore(query, title)?.times(2) ?: -1,
                    fuzzyScore(query, entry.words) ?: -1,
                    fuzzyScore(query, sectionLabel)?.div(3) ?: -1)
                if (score < 0) null else Triple(entry, title, score)
            }.sortedByDescending { it.third }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        TextField(query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus), singleLine = true,
            placeholder = { Text("Search settings") }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) })
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.first.key + ":" + it.second }) { (entry, title) ->
                PrefItem(title = title, icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    summary = SETTINGS_SECTIONS.firstOrNull { it.first == entry.section }?.second?.let { section ->
                        if (FUTURE_SETTINGS.any { it.key == entry.key }) "$section · Coming soon" else section
                    },
                    onClick = { onPick(entry.key, entry.section) })
            }
            if (query.isNotBlank() && results.isEmpty()) item { PrefItem(title = "No matching settings", icon = Icons.Rounded.Search) }
        }
    }
}

/** Higher is better, null is no match: whole phrase > every word starts a word (small typos allowed) > letters in order. */
private fun fuzzyScore(query: String, text: String): Int? {
    val q = query.trim().lowercase()
    val t = text.lowercase()
    if (q.isEmpty()) return null
    t.indexOf(q).takeIf { it >= 0 }?.let { return 100 - it.coerceAtMost(50) }
    if (t.replace(" ", "").contains(q.replace(" ", ""))) return 60
    val words = t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val typos = { n: Int -> if (n >= 6) 2 else if (n >= 3) 1 else 0 }
    if (q.split(' ').filter { it.isNotEmpty() }.all { qw -> words.any { w -> editDistance(qw, w.take(qw.length)) <= typos(qw.length) } }) return 40
    var i = 0
    for (c in t) if (i < q.length && c == q[i]) i++
    return if (i == q.length && q.length >= 4) 10 else null
}

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val cur = IntArray(b.length + 1).also { it[0] = i + 1 }
        for (j in b.indices) cur[j + 1] = minOf(prev[j + 1] + 1, cur[j] + 1, prev[j] + if (a[i] == b[j]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

/** Scrolls until the item with [key] is on screen (a lazy list only knows the keys it has laid out). */
private suspend fun LazyListState.scrollToKey(key: Any) {
    repeat(2) { withFrameNanos {} } // the settings list was just put back in place of the search
    scrollToItem(0)
    while (true) {
        layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { return animateScrollToItem(it.index) }
        val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return
        if (last >= layoutInfo.totalItemsCount - 1) return
        scrollToItem(last)
    }
}

@Composable
private fun SettingsInlineHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsSubsection(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            text = title,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingsPageLink(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = transparentListItem(),
        modifier = Modifier.fillMaxWidth().clickableRow(onClick = onClick),
    )
}

@Composable
private fun PrefItem(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    val titleColor = (if (destructive) colors.error else colors.onSurface).copy(alpha = alpha)
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = summary?.let { { Text(it, color = colors.onSurfaceVariant.copy(alpha = alpha)) } },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = (if (destructive) colors.error else colors.onSurfaceVariant).copy(alpha = alpha))
        },
        colors = transparentListItem(),
        modifier = if (onClick != null) Modifier.clickableRow(enabled = enabled, onClick = onClick) else Modifier,
    )
}

@Composable
private fun SwitchPref(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title, color = colors.onSurface.copy(alpha = alpha)) },
        supportingContent = summary?.let { { Text(it, color = colors.onSurfaceVariant.copy(alpha = alpha)) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = alpha)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = transparentListItem(),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun <T> ChoicePref(
    title: String,
    icon: ImageVector,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PrefItem(
        title = title,
        icon = icon,
        summary = options.firstOrNull { it.first == selected }?.second,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = value == selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        open = false
                                        if (value != selected) onSelect(value)
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.shell_cancel)) }
            },
        )
    }
}

@Composable
private fun CacheSizePref(valueMb: Int, onValueChange: (Int) -> Unit) {
    val context = LocalContext.current
    val currentIndex = CACHE_SIZES_MB.indexOfFirst { it >= valueMb }.let { if (it < 0) CACHE_SIZES_MB.lastIndex else it }
    var position by remember(valueMb) { mutableFloatStateOf(currentIndex.toFloat()) }
    val shownMb = CACHE_SIZES_MB[position.roundToInt().coerceIn(0, CACHE_SIZES_MB.lastIndex)]
    val label = formatBytes(context, shownMb * 1024L * 1024L)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.shell_settings_cache_size), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.shell_settings_cache_size_summary, label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                val mb = CACHE_SIZES_MB[position.roundToInt().coerceIn(0, CACHE_SIZES_MB.lastIndex)]
                if (mb != valueMb) onValueChange(mb)
            },
            valueRange = 0f..CACHE_SIZES_MB.lastIndex.toFloat(),
            steps = CACHE_SIZES_MB.size - 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp)
                .semantics { stateDescription = label },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}

@Composable
private fun DeviceNameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val save = { onSave(text.trim().take(MAX_DEVICE_NAME)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_settings_device_name)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.shell_settings_device_name_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_DEVICE_NAME) },
                    singleLine = true,
                    placeholder = { Text(defaultDeviceName()) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = save) { Text(stringResource(R.string.shell_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_cancel)) } },
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shell_settings_licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.shell_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LICENSES.forEach { entry ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(entry.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shell_close)) } },
    )
}

private const val MAX_DEVICE_NAME = 64

/**
 * Private session and listening activity, as in Spotify: listening activity is off and locked while
 * a private session runs. Shown in Settings and in the "Your activity" sheet of the profile menu.
 */
@Composable
internal fun PrivacySwitches(settings: Settings, update: ((Settings) -> Settings) -> Unit) {
    var explain by remember { mutableStateOf(false) }
    SwitchPref(
        "Private session", Icons.Rounded.VisibilityOff, settings.privateSession,
        { on -> if (on) explain = true; update { it.copy(privateSession = on) } },
        "Temporarily hides your listening activity everywhere, and ends after 6 hours.",
    )
    SwitchPref(
        "Listening activity", Icons.Rounded.Group, settings.listeningActivity && !settings.privateSession,
        { on -> update { it.copy(listeningActivity = on) } },
        if (settings.privateSession) "Unavailable during a private session." else "Share what you listen to with your followers.",
        enabled = !settings.privateSession,
    )
    if (explain) PrivateSessionExplainer { explain = false }
}

/** Spotify's "How private sessions work" explainer, shown when a private session starts. */
@Composable
internal fun PrivateSessionExplainer(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How private sessions work") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "Your listening activity is hidden on any device you are logged into, and on any connected third-party apps.",
                    "What you listen to will not appear in your Recents or your profile.",
                    "What you listen to will not impact your recommendations, listening stats or Wrapped.",
                    "Private sessions end after 6 hours. You can start another private session right after one ends.",
                ).forEach { Text("\u2022  $it", style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
