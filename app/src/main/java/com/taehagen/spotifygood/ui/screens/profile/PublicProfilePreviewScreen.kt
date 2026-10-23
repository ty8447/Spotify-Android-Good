package com.taehagen.spotifygood.ui.screens.profile

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.PublicProfileArtist
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** A read-only visitor's view. All content comes from the public profile endpoint. */
@Composable
internal fun PublicProfilePreviewScreen(
    username: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    var profile by remember(username) { mutableStateOf<User?>(null) }
    var loading by remember(username) { mutableStateOf(true) }
    var error by remember(username) { mutableStateOf<String?>(null) }
    var revision by remember(username) { mutableStateOf(0) }
    var visibility by remember(username) { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var noticeVisible by rememberSaveable(username) { mutableStateOf(true) }
    // 0 = overview; 1 = all public playlists; 2 = all publicly shown recent artists.
    var detail by rememberSaveable(username) { mutableStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    val listState = remember(detail) { LazyListState() }
    BackHandler { if (detail != 0) detail = 0 else onBack() }

    LaunchedEffect(username, revision) {
        loading = true
        error = null
        try {
            // Same saved switches as Profile settings: don't expose disabled sections
            // if the authenticated public-profile API returns owner-visible fields.
            val fetched = graph.catalog.publicProfilePreview(username)
            visibility = try {
                val reply = graph.rpc.call<JsonObject>("catalog.profileVisibility")
                listOf("followers", "playlists", "artists").associateWith { key ->
                    reply[key]?.jsonPrimitive?.booleanOrNull
                }
            } catch (_: Exception) { emptyMap() }
            profile = fetched
        } catch (e: Exception) {
            error = "Couldn't load the public profile. ${e.message ?: "Try again."}"
        } finally {
            loading = false
        }
    }
    // Start the five seconds when public data is visible, not while it is loading.
    LaunchedEffect(username, loading) {
        if (!loading && error == null) {
            noticeVisible = true
            delay(5_000)
            noticeVisible = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        noticeVisible = false
    }

    val visibleProfile = profile
    val playlists = visibleProfile?.publicPlaylists.orEmpty().takeIf { visibility["playlists"] != false }.orEmpty()
    val artists = visibleProfile?.recentlyPlayedArtists.orEmpty().takeIf { visibility["artists"] != false }.orEmpty()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
            when {
                loading && visibleProfile == null -> item {
                    Box(Modifier.fillMaxWidth().padding(top = 130.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && visibleProfile == null -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 130.dp, start = 24.dp, end = 24.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { revision++ }) { Text("Retry") }
                    }
                }
                visibleProfile != null && detail == 0 -> {
                    item(key = "visitor-header") {
                        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(
                            Color(0xFF9A6648), Color(0xFF574034), MaterialTheme.colorScheme.background,
                        )))) {
                            Column(Modifier.fillMaxWidth().statusBarsPadding()
                                .padding(start = 24.dp, end = 24.dp, top = 76.dp, bottom = 26.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Artwork(url = visibleProfile.images.best(320), contentDescription = "Profile picture",
                                        shape = CircleShape, placeholderIcon = Icons.Rounded.Person,
                                        modifier = Modifier.size(112.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(visibleProfile.displayName?.takeIf { it.isNotBlank() } ?: visibleProfile.username,
                                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (visibleProfile.showFollows != false && visibility["followers"] != false) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text("${visibleProfile.followersCount ?: "—"} followers",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("${visibleProfile.followingCount ?: "—"} following",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(28.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Previewing yourself is read-only; following yourself is not an action.
                                    OutlinedButton(onClick = {}, enabled = false) { Text("Follow") }
                                    Box {
                                        IconButton(onClick = { showMore = true }) {
                                            Icon(Icons.Rounded.MoreVert, contentDescription = "More profile options")
                                        }
                                        DropdownMenu(expanded = showMore,
                                            onDismissRequest = { showMore = false }) {
                                            DropdownMenuItem(text = { Text("Share profile") }, onClick = {
                                                showMore = false
                                                val url = "https://open.spotify.com/user/${Uri.encode(username)}"
                                                val share = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, url)
                                                }
                                                context.startActivity(Intent.createChooser(share, "Share profile"))
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (playlists.isNotEmpty()) {
                        item(key = "playlists-title") { PreviewSectionTitle("Playlists") }
                        items(playlists.take(3), key = { "pl:${it.uri}" }) { playlist ->
                            PreviewPlaylistRow(playlist,
                                savesCount = visibleProfile.publicPlaylistSaves[playlist.uri],
                                onClick = { navigator.navigate(Route.Playlist(playlist.uri)) })
                        }
                        if (playlists.size > 3) item(key = "all-playlists") {
                            PreviewSeeAll("See all playlists") { noticeVisible = false; detail = 1 }
                        }
                    }
                    if (artists.isNotEmpty()) {
                        item(key = "artists-title") { PreviewSectionTitle("Recently played artists") }
                        items(artists.take(3), key = { "artist:${it.uri}" }) { artist ->
                            PreviewArtistRow(artist, onClick = { navigator.navigate(Route.Artist(artist.uri)) })
                        }
                        if (artists.size > 3) item(key = "all-artists") {
                            PreviewSeeAll("See all artists") { noticeVisible = false; detail = 2 }
                        }
                    }
                    if (playlists.isEmpty() && artists.isEmpty()) item {
                        Text("There is no public activity to display.",
                            modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visibleProfile != null -> {
                    item { PreviewSectionTitle(if (detail == 1) "Public playlists" else "Recently played artists") }
                    if (detail == 1) {
                        items(playlists, key = { "pl:${it.uri}" }) { playlist ->
                            PreviewPlaylistRow(playlist,
                                savesCount = visibleProfile.publicPlaylistSaves[playlist.uri],
                                onClick = { navigator.navigate(Route.Playlist(playlist.uri)) })
                        }
                    } else {
                        items(artists, key = { "artist:${it.uri}" }) { artist ->
                            PreviewArtistRow(artist, onClick = { navigator.navigate(Route.Artist(artist.uri)) })
                        }
                    }
                }
            }
        }
        IconButton(onClick = { if (detail != 0) detail = 0 else onBack() },
            modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        if (noticeVisible && detail == 0) {
            Surface(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 54.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("This is how your profile shows up for others.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { noticeVisible = false }) { Text("Dismiss") }
                }
            }
        }
    }
}

@Composable
private fun PreviewSectionTitle(label: String) {
    Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 12.dp))
}

@Composable
private fun PreviewPlaylistRow(playlist: PlaylistRef, savesCount: Long?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Artwork(url = playlist.images.best(100), contentDescription = null,
            modifier = Modifier.size(58.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Text(savesCount?.let { "%,d saves".format(it) } ?: "Public playlist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PreviewArtistRow(artist: PublicProfileArtist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Artwork(url = artist.images.best(100), contentDescription = null,
            shape = CircleShape, placeholderIcon = Icons.Rounded.Person,
            modifier = Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            artist.followersCount?.let { count ->
                Text("%,d followers".format(count), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewSeeAll(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
