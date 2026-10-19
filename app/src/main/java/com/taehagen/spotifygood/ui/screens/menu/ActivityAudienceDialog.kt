package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Add
import com.taehagen.spotifygood.model.best
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taehagen.spotifygood.data.ListeningAudienceUser
import com.taehagen.spotifygood.data.SocialPerson
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.launch

/** Full-screen account-backed audience screen reached from the Your activity bottom sheet.
 * A switch changes Spotify's restriction for that user; local state updates only after success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityAudienceDialog(
    onDismiss: () -> Unit,
    onAddFriends: () -> Unit,
    onViewFollowers: () -> Unit,
) {
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    var audience by remember { mutableStateOf<List<ListeningAudienceUser>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, SocialPerson>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var allFollowers by remember { mutableStateOf<Boolean?>(null) }
    var savingGlobal by remember { mutableStateOf(false) }
    var busyUser by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        try {
            audience = graph.catalog.listeningAudience()
            allFollowers = graph.catalog.listeningGlobal()
            error = null
            // Presentation-only data: account authority comes exclusively from listeningAudience.
            // The listening audience includes people who are not necessarily followers.
            // Merge both social lists, then resolve missing or placeholder public profiles.
            val followers = runCatching { graph.catalog.socialList("followers") }.getOrDefault(emptyList())
            val following = runCatching { graph.catalog.socialList("following") }.getOrDefault(emptyList())
            val known = (followers + following).associateBy { it.username }.toMutableMap()
            val needsLookup = audience.filter { member ->
                val p = known[member.username]
                p == null || p.imageUrl.isNullOrBlank() || p.displayName.isBlank() || p.displayName == member.username
            }
            profiles = known.toMap()
            // Limit concurrency and don't block the audience page if a profile is private/unavailable.
            supervisorScope {
                needsLookup.chunked(4).forEach { batch ->
                    batch.map { member ->
                        async {
                            member.username to runCatching { graph.catalog.user(member.username) }.getOrNull()
                        }
                    }.awaitAll().forEach { (username, user) ->
                        if (user != null) {
                            val previous = known[username]
                            known[username] = SocialPerson(
                                username = username,
                                displayName = user.displayName?.takeIf { it.isNotBlank() }
                                    ?: previous?.displayName.orEmpty(),
                                imageUrl = user.images.best(160) ?: previous?.imageUrl,
                                followersCount = previous?.followersCount,
                                isFollowing = previous?.isFollowing ?: false,
                            )
                            profiles = known.toMap()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not load Spotify's listening audience"
        } finally {
            loading = false
        }
    }

    fun updatePerson(username: String, allowed: Boolean) {
        if (busyUser != null) return
        busyUser = username
        scope.launch {
            try {
                graph.catalog.restrictListeningAudience(username, !allowed)
                // Reload from Spotify; don't claim success from a local checkbox alone.
                audience = graph.catalog.listeningAudience()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Couldn't update account privacy"
            } finally {
                busyUser = null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopAppBar(
                    title = { Text("Who can see your activity", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { reload++ }) { Text("Retry") }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("All followers", fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                // Writes publish-activity on the account (the desktop clientsettings
                                // PUT); optimistic, reverted if the write fails. Independent of the
                                // individual audience below.
                                Switch(checked = allFollowers == true,
                                    enabled = allFollowers != null && !savingGlobal && !loading,
                                    onCheckedChange = { want ->
                                        val previous = allFollowers
                                        allFollowers = want
                                        savingGlobal = true
                                        error = null
                                        scope.launch {
                                            try {
                                                allFollowers = graph.catalog.setListeningGlobal(want)
                                            } catch (e: Exception) {
                                                allFollowers = previous
                                                error = e.message ?: "Could not update the All followers setting"
                                            } finally {
                                                savingGlobal = false
                                            }
                                        }
                                    })
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("When on, anyone who follows you can see your listening activity.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = androidx.compose.ui.text.buildAnnotatedString {
                                    append("Anyone who follows you can see your activity. ")
                                    pushStyle(androidx.compose.ui.text.SpanStyle(
                                        color = Color.White,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    ))
                                    append("View followers")
                                    pop()
                                },
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onViewFollowers),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("People you choose", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text("Choose who can see your listening activity and compare tastes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onAddFriends)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Icon(Icons.Rounded.Add, contentDescription = null,
                                    modifier = Modifier.padding(12.dp).size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Add friends", style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    items(audience, key = { it.username }) { person ->
                        val profile = profiles[person.username]
                        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Artwork(profile?.imageUrl, null, Modifier.size(40.dp), CircleShape,
                                Icons.Rounded.Person,
                                avatarName = profile?.displayName?.ifBlank { person.username } ?: person.username)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile?.displayName?.ifBlank { person.username } ?: person.username,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("In a group with you", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (busyUser == person.username) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Checkbox(checked = !person.restricted,
                                    enabled = busyUser == null && !loading,
                                    onCheckedChange = { updatePerson(person.username, it) })
                            }
                        }
                    }
                    if (!loading && audience.isEmpty()) {
                        item { Text("Spotify returned no people in your listening audience.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
