package com.taehagen.spotifygood.ui.screens.profile

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.data.SocialPerson
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import kotlinx.coroutines.launch

/** One template for Followers and Following; actions exist only where supported by the API. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialPeopleScreen(kind: String, username: String?, contentPadding: PaddingValues) {
    val isFollowers = kind == "followers"
    val navigator = LocalAppNavigator.current
    val graph = rememberAppGraph()
    val account by graph.engine.user.collectAsStateWithLifecycle()
    val target = username ?: account?.username
    var people by remember(target, kind) { mutableStateOf<List<SocialPerson>>(emptyList()) }
    var loading by remember(target, kind) { mutableStateOf(true) }
    var error by remember(target, kind) { mutableStateOf<String?>(null) }
    var revision by remember(target, kind) { mutableStateOf(0) }
    var workingOn by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<SocialPerson?>(null) }
    var zoomed by remember { mutableStateOf<SocialPerson?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target, kind, revision) {
        if (target == null) return@LaunchedEffect
        loading = true
        try {
            people = graph.catalog.socialList(kind, target)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Unable to load people"
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (isFollowers) "Followers" else "Following", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = navigator::back) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            } },
        )
        when {
            loading && people.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && people.isEmpty() -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(error ?: "Unable to load people")
                TextButton(onClick = { revision++ }) { Text("Retry") }
            }
            people.isEmpty() -> Text("No ${if (isFollowers) "followers" else "following"} yet",
                modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding)) {
                items(people, key = { it.username }) { person ->
                    Row(
                        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(person.imageUrl, null, Modifier.size(42.dp), CircleShape, Icons.Rounded.Person, avatarName = person.displayName.ifBlank { person.username })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).clickable { navigator.navigate(Route.Profile(person.username)) }) {
                            Text(person.displayName.ifBlank { person.username }, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text("${person.followersCount?.let { "%,d".format(it) } ?: "—"} followers",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        val busy = person.username in workingOn
                        IconButton(enabled = !busy, onClick = {
                            workingOn = workingOn + person.username
                            scope.launch {
                                try {
                                    graph.catalog.followUser(person.username, !person.isFollowing)
                                    people = people.map { if (it.username == person.username)
                                        it.copy(isFollowing = !person.isFollowing) else it }
                                } catch (e: Exception) {
                                    navigator.showMessage(e.message ?: "Could not update follow status")
                                } finally {
                                    workingOn = workingOn - person.username
                                }
                            }
                        }) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(if (person.isFollowing) Icons.Rounded.Check else Icons.Rounded.PersonAddAlt1,
                                tint = if (person.isFollowing) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurface,
                                contentDescription = if (person.isFollowing) "Unfollow" else "Follow")
                        }
                        if (isFollowers) {
                            IconButton(onClick = { selected = person }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { person ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Row(Modifier.fillMaxWidth().clickable { zoomed = person }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(person.imageUrl, null, Modifier.size(48.dp), CircleShape, Icons.Rounded.Person, avatarName = person.displayName.ifBlank { person.username })
                Spacer(Modifier.width(12.dp))
                Text(person.displayName.ifBlank { person.username }, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider()
            // Removing followers and blocking users use Spotify-private operations that are
            // not currently verified. Show the choices but do not pretend account changes worked.
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PersonRemove, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text("Remove follower", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text("Block user", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Follower removal and blocking aren't connected to Spotify yet.",
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    zoomed?.let { person ->
        Dialog(onDismissRequest = { zoomed = null }) {
            Box(Modifier.fillMaxWidth().clickable { zoomed = null }, contentAlignment = Alignment.Center) {
                Artwork(person.imageUrl, null, Modifier.size(260.dp), CircleShape, Icons.Rounded.Person, avatarName = person.displayName.ifBlank { person.username })
            }
        }
    }
}
