@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.taehagen.spotifygood.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.ui.components.MediaThumbnail
import com.taehagen.spotifygood.ui.components.formatBytes
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import android.os.StatFs
import java.util.Locale

/** Main-page links and search destinations for Settings category pages and inline sections. */
internal val SETTINGS_SECTIONS = listOf(
    "h_account" to "Account",
    "h_account_details" to "Account",
    "h_content" to "Content and display",
    "h_privacy" to "Privacy and social",
    "h_playback" to "Playback",
    "h_quality" to "Media quality",
    "h_data" to "Data-saving and offline",
    "h_storage" to "Storage",
    "h_notifications" to "Notification preferences",
    "h_links" to "Links",
    "h_about" to "About and support",
)

/** No persistent setting is written for features that are not wired to actual playback/account behavior. */
internal data class PlannedSetting(val key: String, val title: String, val section: String, val words: String = "")

internal val FUTURE_SETTINGS = listOf(
    PlannedSetting("smart_shuffle_option", "Include Smart Shuffle in play mode", "h_playback_listening", "shuffle suggested songs"),
    PlannedSetting("shuffle_repeats", "Shuffle mode: fewer repeats or standard", "h_playback_listening", "random repeat"),
    PlannedSetting("music_videos", "Music Videos", "h_content_video"),
    PlannedSetting("canvas", "Canvas", "h_content_video", "looping video artwork"),
    PlannedSetting("other_videos", "Other Videos", "h_content_video"),
    PlannedSetting("large_now_playing_video", "Larger video in Now Playing bar", "h_playback_video", "mini player"),
    PlannedSetting("picture_in_picture", "Picture in picture", "h_playback_video", "pip floating video"),
    PlannedSetting("kids_taste", "Include kids and family music in taste profile", "h_content_preferences", "recommendations child"),
    PlannedSetting("show_unplayable", "Show unplayable songs", "h_content_preferences", "unavailable greyed out"),
    PlannedSetting("blocked_users", "Blocked users", "h_privacy_profile", "block profiles"),
    PlannedSetting("others_playing", "See what others are playing", "h_privacy_social", "friend activity"),
    PlannedSetting("message_suggestions", "Message suggestions", "h_privacy_social", "chat suggestions"),
    PlannedSetting("appear_message_suggestions", "Show up in message suggestions", "h_privacy_social", "discoverable"),
    PlannedSetting("played_receipts", "Played receipts", "h_privacy_social", "messages read played"),
    PlannedSetting("saved_receipts", "Saved receipts", "h_privacy_social", "messages saved"),
    PlannedSetting("device_broadcast", "Device broadcast status", "h_playback_listening", "devices status"),
    PlannedSetting("reduce_animations", "Reduce animations", "h_content_display", "accessibility motion"),
    PlannedSetting("create_button", "Create button", "h_content_display", "navigation"),
    PlannedSetting("play_button_actions", "Actions next to play button", "h_content_display", "player controls"),
    PlannedSetting("lossless_quality", "Lossless audio quality", "h_quality", "flac hi res streaming"),
    PlannedSetting("wifi_video_quality", "Wi-Fi video streaming quality", "h_quality", "video high normal low"),
    PlannedSetting("cell_video_quality", "Cellular video streaming quality", "h_quality", "video cellular high normal low"),
    PlannedSetting("podcast_audio_download", "Audio-only downloads for video podcasts", "h_data_podcasts"),
    PlannedSetting("podcast_audio_stream", "Audio-only streaming for video podcasts", "h_data_podcasts"),
)

internal val PLANNED_PARENT_SECTION: Map<String, String> = mapOf(
    "h_content_video" to "h_content",
    "h_content_preferences" to "h_content",
    "h_content_display" to "h_content",
    "h_privacy_playlist" to "h_privacy",
    "h_privacy_profile" to "h_privacy",
    "h_privacy_social" to "h_privacy",
    "h_playback_listening" to "h_playback",
    "h_playback_video" to "h_playback",
    "h_data_podcasts" to "h_data",
)

/** Render only real placeholders; the gold label is for development visibility. */
internal fun LazyListScope.FutureSettingsGroup(section: String, onMessage: (String) -> Unit) {
    FUTURE_SETTINGS.filter { it.section == section }.forEach { planned ->
        item(key = planned.key) {
            val pendingColor = if (isSystemInDarkTheme()) Color(0xFFFFD166) else Color(0xFF8A5800)
            ListItem(
                headlineContent = { Text(planned.title) },
                supportingContent = { Text("Not implemented yet", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = {
                    Text("Coming soon", color = pendingColor, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium)
                },
                leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null, tint = pendingColor) },
                modifier = Modifier.fillMaxWidth().clickable { onMessage("${planned.title} — coming soon") },
            )
        }
    }
}

/** The existing Spotify rootlist `public` flag controls profile visibility, NOT a separate ACL. */
@Composable
fun PlaylistPrivacySettingsScreen(contentPadding: PaddingValues) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val user by graph.engine.user.collectAsStateWithLifecycle()
    var all by remember { mutableStateOf<List<RootlistEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(graph, user?.username) {
        graph.library.playlists().collectLatest { result ->
            val username = user?.username
            all = result.dataOrNull?.flatPlaylists().orEmpty().filter { entry ->
                entry.uri != null && entry.isPublic != null &&
                    username != null && entry.owner?.username?.equals(username, ignoreCase = true) == true
            }.distinctBy { it.uri }
            loading = result.dataOrNull == null
            selected = selected.intersect(all.mapNotNull { it.uri }.toSet())
        }
    }
    fun bulkChange(makePublic: Boolean) {
        if (saving || selected.isEmpty() || (makePublic && all.any { it.uri in selected && it.collaborative })) return
        saving = true
        error = null
        scope.launch {
            try {
                // Rootlist revisions make sequential mutations necessary; no optimistic UI state.
                selected.forEach { uri -> graph.playlists.setPublic(uri, makePublic) }
                selected = emptySet()
            } catch (e: Exception) {
                error = e.message ?: "Unable to update playlist visibility"
            } finally {
                // The server remains authoritative, including after partial bulk failures.
                try { graph.library.refresh() } catch (_: Exception) { }
                saving = false
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Playlist privacy") }, navigationIcon = {
                IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            })
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp,
                        bottom = contentPadding.calculateBottomPadding() + 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { bulkChange(true) },
                        enabled = !saving && all.none { it.uri in selected && it.collaborative },
                        modifier = Modifier.weight(1f)) {
                        Text("Make public")
                    }
                    Button(onClick = { bulkChange(false) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                        Text("Make private")
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = PaddingValues(top = inner.calculateTopPadding(),
            bottom = inner.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 16.dp)) {
            item {
                Text("Controls whether playlists appear on your public profile. This uses the existing Spotify playlist visibility setting.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                if (saving) Text("Saving changes…", Modifier.padding(horizontal = 16.dp))
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (!loading && all.isEmpty()) Text("No owned playlists available.", Modifier.padding(16.dp))
                TextButton(onClick = {
                    val ids = all.mapNotNull { it.uri }.toSet()
                    selected = if (selected.size == ids.size) emptySet() else ids
                }, enabled = !saving && all.isNotEmpty()) {
                    Text(if (selected.size == all.size && all.isNotEmpty()) "Deselect all" else "Select all")
                }
            }
            items(all, key = { requireNotNull(it.uri) }) { entry ->
                val uri = requireNotNull(entry.uri)
                val playlist = PlaylistRef(uri = uri, name = entry.name, images = entry.images, owner = entry.owner)
                Row(Modifier.fillMaxWidth().clickable(enabled = !saving) {
                    selected = if (uri in selected) selected - uri else selected + uri
                }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = uri in selected, onCheckedChange = { on ->
                        selected = if (on) selected + uri else selected - uri
                    }, enabled = !saving)
                    MediaThumbnail(ref = playlist.toMediaRef(), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text(if (entry.collaborative) "Collaborative · private" else if (entry.isPublic == true) "Public" else "Private",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { navigator.showActions(MediaActionTarget.PlaylistTarget(playlist, true)) },
                        enabled = !saving) {
                        Icon(Icons.Rounded.MoreVert, "Playlist options")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private val NOTIFICATION_TYPES = listOf(
    "Music & artists", "Podcasts", "Audiobooks", "Concerts & events", "New releases",
    "Recommendations", "Messages", "Account & security",
)

@Composable
fun NotificationSettingsScreen(contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    val pendingColor = if (isSystemInDarkTheme()) Color(0xFFFFD166) else Color(0xFF8A5800)
    Scaffold(topBar = { TopAppBar(title = { Text("Notifications") }, navigationIcon = {
        IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }) }) { inner ->
        LazyColumn(contentPadding = PaddingValues(top = inner.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + inner.calculateBottomPadding())) {
            item {
                Text("Delivery preferences are not connected to Spotify yet. Select a category to preview its planned options.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(NOTIFICATION_TYPES) { type ->
                ListItem(headlineContent = { Text(type) }, supportingContent = { Text("Coming soon") },
                    trailingContent = { Icon(Icons.Rounded.Info, null, tint = pendingColor) },
                    modifier = Modifier.clickable { navigator.navigate(Route.NotificationCategory(type)) })
            }
        }
    }
}

@Composable
fun NotificationCategorySettingsScreen(type: String, contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    val pendingColor = if (isSystemInDarkTheme()) Color(0xFFFFD166) else Color(0xFF8A5800)
    Scaffold(topBar = { TopAppBar(title = { Text(type) }, navigationIcon = {
        IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }) }) { inner ->
        Column(Modifier.padding(top = inner.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
            Text("Coming soon — these options are not currently saved or sent to Spotify.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("Push notifications", "Email notifications", "Off").forEach { option ->
                ListItem(headlineContent = { Text(option) },
                    supportingContent = { Text("Unavailable") },
                    modifier = Modifier.clickable { navigator.showMessage("$type: $option — coming soon") },
                    trailingContent = { Icon(Icons.Rounded.Info, null, tint = pendingColor) })
            }
        }
    }
}

/** Standalone route retained for existing links, but Settings embeds the same measured chart. */
@Composable
fun StorageBreakdownSettingsScreen(contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    Scaffold(topBar = { TopAppBar(title = { Text("Storage breakdown") }, navigationIcon = {
        IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
    }) }) { inner ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
            top = inner.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding())) {
            StorageBreakdownContent()
        }
    }
}

/** One internal storage volume, not a sum of every SD card or cloud location. */
private data class DeviceStorageSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val appBytes: Long?,
    val appInstallBytes: Long,
    val appDataBytes: Long,
    val cacheBytes: Long,
)

/**
 * Android permits querying our own package without usage-access permission.
 * `dataBytes` already contains `cacheBytes`; never count them twice.
 */
private fun readDeviceStorage(context: Context): DeviceStorageSnapshot {
    val statFs = StatFs(context.filesDir.absolutePath)
    val manager = context.getSystemService(StorageStatsManager::class.java)
    val volume = StorageManager.UUID_DEFAULT
    val total = runCatching { manager.getTotalBytes(volume) }.getOrDefault(statFs.totalBytes)
    val available = runCatching { manager.getFreeBytes(volume) }.getOrDefault(statFs.availableBytes)
        .coerceIn(0L, total.coerceAtLeast(0L))
    val stats = runCatching {
        manager.queryStatsForPackage(volume, context.packageName, Process.myUserHandle())
    }.getOrNull()
    val install = stats?.appBytes?.coerceAtLeast(0L) ?: 0L
    val data = stats?.dataBytes?.coerceAtLeast(0L) ?: 0L
    return DeviceStorageSnapshot(
        totalBytes = total.coerceAtLeast(0L),
        availableBytes = available,
        appBytes = stats?.let { (install + data).coerceAtMost(total.coerceAtLeast(0L)) },
        appInstallBytes = install,
        appDataBytes = data,
        cacheBytes = stats?.cacheBytes?.coerceIn(0L, data) ?: 0L,
    )
}

/** Proportional device-storage comparison with an exact Android-reported app subtotal. */
@Composable
internal fun StorageBreakdownContent() {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val downloads by graph.downloads.usedBytes.collectAsStateWithLifecycle(initialValue = 0L)
    var refresh by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val measurement by produceState<Result<DeviceStorageSnapshot>?>(initialValue = null, context, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { readDeviceStorage(context) } }
    }
    val appColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val freeColor = MaterialTheme.colorScheme.surfaceVariant
    // Distinct categories within Spotify Good. Labels accompany colors for accessibility.
    val installColor = Color(0xFF9B8AFB) // violet
    val dataColor = Color(0xFF55B8F9)    // blue
    val cacheColor = Color(0xFFFFBD59)   // amber
    val downloadsColor = Color(0xFF34D5B3) // teal; tracked separately, may overlap app data
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Device storage", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        if (measurement == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Measuring storage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (measurement?.isFailure == true) {
            Text("Storage usage is unavailable on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val usage = requireNotNull(measurement?.getOrNull())
            val total = usage.totalBytes
            val app = usage.appBytes?.coerceIn(0L, (total - usage.availableBytes).coerceAtLeast(0L)) ?: 0L
            val other = (total - usage.availableBytes - app).coerceAtLeast(0L)
            val percent = if (total > 0L) app.toDouble() * 100.0 / total else 0.0
            val percentText = when {
                usage.appBytes == null -> "Unavailable"
                percent > 0.0 && percent < 0.01 -> "<0.01%"
                else -> String.format(Locale.getDefault(), "%.2f%%", percent)
            }
            Text("Internal storage: ${formatBytes(context, total)} total",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (usage.appBytes != null) "Spotify Good uses $percentText of device storage"
                 else "Spotify Good usage could not be measured",
                color = appColor, fontWeight = FontWeight.SemiBold)
            if (total > 0L) {
                Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp))) {
                    val appWidth = size.width * (app.toDouble() / total).toFloat()
                    val otherWidth = size.width * (other.toDouble() / total).toFloat()
                    drawRect(freeColor)
                    drawRect(otherColor, topLeft = Offset(appWidth, 0f),
                        size = Size(otherWidth, size.height))
                    if (appWidth > 0f) drawRect(appColor, size = Size(appWidth, size.height))
                }
            }
            if (usage.appBytes != null) StorageLegendRow("Spotify Good", app, appColor)
            StorageLegendRow("Other apps, files & system", other, otherColor)
            StorageLegendRow("Available / free", usage.availableBytes, freeColor)
            if (usage.appBytes != null) {
                HorizontalDivider()
                Text("Spotify Good details", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                val install = usage.appInstallBytes
                val dataOnly = (usage.appDataBytes - usage.cacheBytes).coerceAtLeast(0L)
                val cache = usage.cacheBytes
                val detailTotal = install + dataOnly + cache
                // This bar is normalized to Spotify Good, not the whole phone, so even a
                // very small app footprint has a readable, color-coded breakdown.
                if (detailTotal > 0L) {
                    Canvas(Modifier.fillMaxWidth().height(14.dp)
                        .clip(RoundedCornerShape(5.dp))) {
                        var x = 0f
                        listOf(install to installColor, dataOnly to dataColor, cache to cacheColor)
                            .forEachIndexed { index, (bytes, color) ->
                                val segmentWidth = if (index == 2) (size.width - x).coerceAtLeast(0f)
                                    else size.width * (bytes.toDouble() / detailTotal).toFloat()
                                if (segmentWidth > 0f) {
                                    drawRect(color, topLeft = Offset(x, 0f),
                                        size = Size(segmentWidth, size.height))
                                }
                                x += segmentWidth
                            }
                    }
                }
                StorageLegendRow("App installation", install, installColor)
                StorageLegendRow("App data (excluding cache)", dataOnly, dataColor)
                StorageLegendRow("Cache", cache, cacheColor)
            }
            StorageLegendRow("Managed downloads (tracked)", downloads.coerceAtLeast(0L),
                downloadsColor)
            Text("Managed downloads are shown separately, not added to the storage total. " +
                "Internal downloads can already be part of app data, while downloads " +
                "on an SD card aren't included in the internal-storage bar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Text("'Other' includes Android system files and other apps. " +
                "Available space is Android's estimate and can include reclaimable cache.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StorageLegendRow(label: String, bytes: Long, color: Color) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Surface(modifier = Modifier.size(12.dp), color = color,
            shape = RoundedCornerShape(3.dp)) { }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(formatBytes(context, bytes), fontWeight = FontWeight.SemiBold)
    }
}
