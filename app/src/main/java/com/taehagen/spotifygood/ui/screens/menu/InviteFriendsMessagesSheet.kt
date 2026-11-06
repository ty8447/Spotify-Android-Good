package com.taehagen.spotifygood.ui.screens.menu

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.data.SocialPerson
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private data class InvitablePerson(val person: ChatParticipant, val reason: String, val rank: Int)

/** Exclude people who can already view the account's listening activity.
 * Null global state is treated conservatively: a follower may have access.
 * All account IDs must be normalized before comparison.
 */
internal fun alreadySeesListeningActivity(
    username: String,
    allowedPeople: Set<String>,
    followerNames: Set<String>,
    allFollowers: Boolean?,
): Boolean {
    val key = username.trim().lowercase(Locale.ROOT)
    return key in allowedPeople || (allFollowers != false && key in followerNames)
}

private fun isSafeInviteUsername(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 &&
        value.all { (it.isLetterOrDigit() && it.code < 128) || it in "._-" }

/** Spotify sometimes marks a two-person conversation as a group.
 * Match the full participant set by account ID, never the isGroup flag/title. */
internal fun existingDirectConversation(
    conversations: List<ConversationSummary>, self: String, peer: String,
): ConversationSummary? = conversations.firstOrNull { chat ->
    val others = chat.participants.map { it.username.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() && it != self.trim().lowercase(Locale.ROOT) }.distinct()
    chat.hierarchyId.isNotBlank() && others.size == 1 &&
        others.single() == peer.trim().lowercase(Locale.ROOT) &&
        (chat.groupMemberCount <= 2 || chat.groupMemberCount == 0)
}

/** Route marker for an unsent direct-message draft. Does not create a server chat. */
internal const val PENDING_DIRECT_PREFIX = "pending-direct:"

/**
 * Friends entry point from Your activity > Who can see your activity.
 * This is the real Messages directory, not the unrelated music search screen.
 * Relationship explanations are only given for relationships confirmed by a read API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteFriendsMessagesSheet(
    onDismiss: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    val graph = rememberAppGraph()
    val account by graph.engine.user.collectAsStateWithLifecycle()
    val self = account?.username.orEmpty()
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("hidden_message_suggestions_v1", android.content.Context.MODE_PRIVATE)
    }
    var hidden by remember(self) {
        mutableStateOf(preferences.getStringSet("hidden:$self", emptySet())?.toSet().orEmpty())
    }
    var candidates by remember(self) { mutableStateOf<List<InvitablePerson>>(emptyList()) }
    var chats by remember(self) { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var loading by remember(self) { mutableStateOf(true) }
    var error by remember(self) { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var busyUsername by remember { mutableStateOf<String?>(null) }
    var menuUser by remember { mutableStateOf<String?>(null) }
    var toBlock by remember { mutableStateOf<ChatParticipant?>(null) }

    LaunchedEffect(graph.rpc, graph.catalog, self, revision) {
        if (self.isBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        candidates = emptyList() // Fail closed if audience permissions cannot be read.
        try {
            // One pass through the directory and social lists. Avoid catalog.user for
            // every suggested person, since large profile fan-outs caused 429s before.
            // Read-only overview once. Do not make another request for every person.
            val overview = try { graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview") }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            chats = overview?.conversations.orEmpty()
            // Use bounded lists, no individual profile lookups. Missing social data
            // never hides conversations already returned by Messages.
            var followersVerified = true
            val followers = try { graph.catalog.socialList("followers") }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { followersVerified = false; emptyList() }
            val following = try { graph.catalog.socialList("following") }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            val listeners = if (chats.isEmpty() && followers.isEmpty() && following.isEmpty()) {
                try { graph.catalog.friendActivity() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            } else emptyList()
            val followingNames = following.map { it.username.trim().lowercase(Locale.ROOT) }.toSet()
            val followerNames = followers.map { it.username.trim().lowercase(Locale.ROOT) }.toSet()
            // Read the authoritative audience ONCE, never once per suggestion.
            // If the read fails, do not show potentially already-authorized users.
            val allowedPeople = graph.catalog.listeningAudience()
                .filter { !it.restricted }
                .map { it.username.trim().lowercase(Locale.ROOT) }
                .toSet()
            val allFollowers = graph.catalog.listeningGlobal()
            // If everybody who follows us may see activity, an incomplete follower
            // list would make this filter unsafe. Show Retry, not false suggestions.
            if (allFollowers != false && !followersVerified) {
                throw IllegalStateException("Could not verify followers for your activity setting")
            }
            val directory = LinkedHashMap<String, InvitablePerson>()
            fun include(person: ChatParticipant, reason: String, priority: Int) {
                if (!isSafeInviteUsername(person.username) || person.username.equals(self, true)) return
                val key = person.username.trim().lowercase(Locale.ROOT)
                if (alreadySeesListeningActivity(key, allowedPeople, followerNames, allFollowers)) return
                val previous = directory[key]
                if (previous == null || priority < previous.rank) {
                    directory[key] = InvitablePerson(person, reason, priority)
                } else if (previous.person.avatarUrl.isNullOrBlank() && !person.avatarUrl.isNullOrBlank()) {
                    directory[key] = previous.copy(person = previous.person.copy(avatarUrl = person.avatarUrl))
                }
            }
            chats.forEach { chat ->
                chat.participants.forEach { participant ->
                    include(participant, "Connected through Messages", 0)
                }
            }
            fun addSocial(person: SocialPerson) {
                val key = person.username.lowercase(Locale.ROOT)
                val mutual = key in followerNames && key in followingNames
                include(ChatParticipant(person.username,
                    person.displayName.ifBlank { person.username }, person.imageUrl),
                    if (mutual) "You follow each other" else if (key in followingNames) "You follow them"
                    else "Follows you", if (mutual) 1 else 3)
            }
            following.forEach(::addSocial)
            followers.forEach(::addSocial)
            listeners.forEach { listener ->
                include(ChatParticipant(listener.username, listener.name, null),
                    "Shares listening activity with you", 2)
            }
            candidates = directory.values.sortedWith(
                compareBy<InvitablePerson> { it.rank }
                    .thenBy { it.person.displayName.lowercase(Locale.ROOT) }
            ).take(100)
            if (candidates.isEmpty()) {
                error = "No new people to invite. People who can already see your activity are excluded."
            } else if (overview == null) {
                error = "Messages couldn't be verified. We'll check existing chats before sending."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            candidates = emptyList() // Never show suggestions when permission verification failed.
            error = "Couldn't verify your activity audience. Retry to load suggestions. " +
                (failure.message?.take(80) ?: "")
        } finally {
            loading = false
        }
    }

    fun openConversation(person: ChatParticipant) {
        if (busyUsername != null) return
        busyUsername = person.username
        error = null
        scope.launch {
            try {
                // Already known conversation? Open immediately, even when its native
                // isGroup flag is true for a two-member Spotify conversation.
                var direct = existingDirectConversation(chats, self, person.username)
                if (direct == null) {
                    // A single read-only refresh guards against stale lists. Never
                    // create a conversation just because someone tapped a suggestion.
                    val latest = try { graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview") }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                    if (latest != null) {
                        chats = latest.conversations
                        direct = existingDirectConversation(chats, self, person.username)
                    }
                }
                val display = person.displayName.ifBlank { person.username }
                if (direct != null) {
                    onOpenChat(direct.hierarchyId, direct.displayTitle.ifBlank { display })
                } else {
                    // Local draft route: network write happens only when Send is tapped.
                    onOpenChat(PENDING_DIRECT_PREFIX + person.username, display)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                error = "Couldn't open chat: " + (failure.message?.take(100) ?: "Try again")
            } finally { busyUsername = null }
        }
    }

    val matches = candidates.filter { item ->
        item.person.username.lowercase(Locale.ROOT) !in hidden &&
            (search.isBlank() || item.person.username.contains(search.trim(), true) ||
                item.person.displayName.contains(search.trim(), true))
    }

    ModalBottomSheet(onDismissRequest = { if (busyUsername == null) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Invite friends to messages", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = busyUsername == null && self.isNotBlank()) {
                    val profileUrl = "https://open.spotify.com/user/${Uri.encode(self)}"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT,
                            "Let's connect and chat on Spotify! You can find me here: $profileUrl")
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, "Share your Spotify profile")) }
                        .onFailure { error = "No app available to share this link" }
                }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Send an invite link", fontWeight = FontWeight.SemiBold)
                    Text("Shares your Spotify profile. A direct Messages invite link isn't available yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.Share, contentDescription = null)
            }
            HorizontalDivider()
            OutlinedTextField(search, onValueChange = { search = it.take(120) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search suggested friends") })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Suggested", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
            }
            error?.let { value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (busyUsername == null) revision++ }) { Text("Retry") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(matches, key = { it.person.username.lowercase(Locale.ROOT) }) { suggested ->
                    val person = suggested.person
                    Row(Modifier.fillMaxWidth().clickable(enabled = busyUsername == null) {
                        openConversation(person)
                    }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(person.avatarUrl, null, Modifier.size(44.dp), CircleShape,
                            Icons.Rounded.Person,
                            avatarName = person.displayName.ifBlank { person.username })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person.displayName.ifBlank { person.username },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium)
                            Text(suggested.reason, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busyUsername == person.username) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        Box {
                            IconButton(onClick = { menuUser = person.username },
                                enabled = busyUsername == null) {
                                Icon(Icons.Rounded.MoreVert, contentDescription =
                                    "Options for ${person.displayName.ifBlank { person.username }}")
                            }
                            DropdownMenu(expanded = menuUser == person.username,
                                onDismissRequest = { menuUser = null }) {
                                DropdownMenuItem(text = { Text("Go to profile") },
                                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                                    onClick = { menuUser = null; onProfile(person.username) })
                                DropdownMenuItem(text = { Text("Remove this suggestion") },
                                    onClick = {
                                        menuUser = null
                                        val update = hidden + person.username.lowercase(Locale.ROOT)
                                        hidden = update
                                        preferences.edit().putStringSet("hidden:$self", update).apply()
                                    })
                                DropdownMenuItem(text = { Text("Block user") },
                                    leadingIcon = { Icon(Icons.Rounded.Block, null) },
                                    onClick = { menuUser = null; toBlock = person })
                            }
                        }
                    }
                }
                if (!loading && matches.isEmpty()) {
                    item {
                        Text(if (search.isNotBlank()) "No matching suggested people" else "No suggestions yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))
                    }
                }
            }
            Text("Suggestions use verified conversations, follows and listening activity. " +
                "Shared plans, Jams and Blends are not inferred.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.size(12.dp))
        }
    }
    toBlock?.let { person ->
        AlertDialog(onDismissRequest = { toBlock = null },
            icon = { Icon(Icons.Rounded.Block, contentDescription = null) },
            title = { Text("Block user") },
            text = { Text("Vibeify can't submit a Spotify account block yet. " +
                "Open this person's Spotify profile to block them there. " +
                "Removing a suggestion only hides it on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    toBlock = null
                    val url = "https://open.spotify.com/user/${Uri.encode(person.username)}"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { error = "Unable to open Spotify profile" }
                }) { Text("Open Spotify profile") }
            },
            dismissButton = { TextButton(onClick = { toBlock = null }) { Text("Cancel") } })
    }
}
