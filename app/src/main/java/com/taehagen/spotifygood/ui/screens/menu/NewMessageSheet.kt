package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

@Serializable
private data class NewMessageCreationResult(
    val acknowledged: Boolean = false,
    val hierarchyId: String = "",
)

/** The overview's participant list intentionally excludes the signed-in user.
 * Compare exact sets, not titles, since group names are not unique. */
private fun matchingConversation(
    all: List<ConversationSummary>, members: Set<String>, self: String?,
): ConversationSummary? {
    if (members.isEmpty()) return null
    return all.firstOrNull { chat ->
        chat.hierarchyId.isNotBlank() &&
            chat.participants.map { it.username }.filter { it.isNotBlank() && it != self }.toSet() == members &&
            chat.participants.count { it.username.isNotBlank() && it.username != self } == members.size
    }
}

private fun validChatUsername(value: String): Boolean =
    value.isNotBlank() && value.length <= 128 &&
        value.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" }

/** Shared composer launched by the drawer pencil, inbox pencil, or a direct chat.
 * Uses the authenticated chat overview and verified group-create mutation.
 * No Spotify APK source is reused. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewMessageSheet(
    initialRecipients: List<ChatParticipant> = emptyList(),
    requireGroup: Boolean = false,
    onDismiss: () -> Unit,
    onOpenChat: (hierarchyId: String, title: String) -> Unit,
) {
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = { focusManager.clearFocus(); keyboard?.hide() }
    val self = graph.engine.user.collectAsStateWithLifecycle().value?.username
    var search by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var selected by remember(initialRecipients) {
        mutableStateOf(initialRecipients.filter { it.username.isNotBlank() }
            .distinctBy { it.username }.filter { it.username != self })
    }
    var directory by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<ChatParticipant>>(emptyList()) }
    var listeningUsernames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attemptedAvatars by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(graph.rpc, graph.catalog, self) {
        loading = true
        try {
            val latest = graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview")
            directory = latest.conversations
            val contactChats = latest.conversations.flatMap { it.participants }
            val listening = runCatching { graph.catalog.friendActivity() }.getOrDefault(emptyList())
                .map { ChatParticipant(it.username, it.name, null) }
            listeningUsernames = listening.map { it.username }.toSet()
            // Prefer overview profiles (they have resolved avatar URLs) over buddy-list entries,
            // whose activity schema does not carry profile artwork.
            suggestions = (contactChats + listening)
                .filter { validChatUsername(it.username) && it.username != self }
                .distinctBy { it.username.lowercase(Locale.ROOT) }
                .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            error = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = "Couldn't verify existing conversations: " +
                (failure.message?.take(130) ?: "unknown error")
        } finally { loading = false }
    }

    // Buddy-list people do not include an avatar; load missing public profile art
    // through Spotify Good's existing native profile resolver, not a GIPHY-like URL guess.
    LaunchedEffect(graph.rpc, suggestions, selected) {
        val missing = (selected + suggestions).filter { it.avatarUrl.isNullOrBlank() && it.username !in attemptedAvatars }
            .distinctBy { it.username }.take(30).map { it.username }
        attemptedAvatars = attemptedAvatars + missing
        if (missing.isEmpty()) return@LaunchedEffect
        try {
            val profiles = graph.rpc.call<List<ChatParticipant>>(
                "catalog.chatRecipientProfiles", buildJsonObject {
                    put("usernames", JsonArray(missing.map(::JsonPrimitive)))
                })
            val resolved = profiles.associateBy { it.username }
            fun applyProfile(person: ChatParticipant): ChatParticipant =
                resolved[person.username]?.let { profile ->
                    person.copy(
                        displayName = if (person.displayName == person.username &&
                            profile.displayName.isNotBlank()) profile.displayName else person.displayName,
                        avatarUrl = profile.avatarUrl ?: person.avatarUrl,
                    )
                } ?: person
            suggestions = suggestions.map(::applyProfile)
            selected = selected.map(::applyProfile)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // No profile photo isn't a reason to block chat composition.
        }
    }

    val conversationUsernames = directory.flatMap { it.participants }.map { it.username }.toSet()
    // These descriptions describe only relationships actually demonstrated by the APIs.
    // Neither recent conversation membership nor listening activity proves a family plan,
    // mutual follow, Jam, Blend, or that both participants exchanged messages.
    fun suggestionContext(username: String): String? = when {
        username in conversationUsernames -> "In your recent conversations"
        username in listeningUsernames -> "Listening activity shared with you"
        else -> null
    }
    val selectedUsernames = selected.map { it.username }.toSet()
    val term = search.trim()
    val matching = suggestions.filter {
        it.username !in selectedUsernames &&
            (term.isBlank() || it.username.contains(term, ignoreCase = true) ||
                it.displayName.contains(term, ignoreCase = true))
    }
    val existing = matchingConversation(directory, selectedUsernames, self)
    val canSubmit = !loading && !busy && error == null && selected.isNotEmpty() &&
        (!requireGroup || selected.size >= 2) && selected.size <= 39
    val heading = if (selected.size > 1) "Create group" else "New message"

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { dismissKeyboard() }) },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New message", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedTextField(search, onValueChange = { search = it.take(128) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy,
                placeholder = { Text("Search friends or enter username") },
                singleLine = true, shape = RoundedCornerShape(12.dp))
            if (selected.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("To", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        selected.forEach { original ->
                            val person = suggestions.firstOrNull { it.username == original.username } ?: original
                            val display = person.displayName.ifBlank { person.username }.trim()
                            val first = display.substringBefore(' ')
                            val last = display.substringAfter(' ', "")
                            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(contentAlignment = Alignment.TopEnd) {
                                    NewMessageAvatar(person)
                                    IconButton(onClick = {
                                        selected = selected.filterNot { it.username == person.username }
                                    }, enabled = !busy, modifier = Modifier.size(22.dp)) {
                                        Icon(Icons.Rounded.Close,
                                            contentDescription = "Remove ${person.displayName}",
                                            modifier = Modifier.size(17.dp))
                                    }
                                }
                                Text(first, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium)
                                if (last.isNotEmpty()) Text(last, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (selected.size >= 2) {
                OutlinedTextField(name, onValueChange = { name = it.take(200) },
                    label = { Text("Group name (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            }
            HorizontalDivider()
            if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { onDismiss() }) { Text("Close and retry") }
            }
            if (!loading && error == null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (term.isBlank() && selected.isEmpty() && directory.isNotEmpty()) {
                        item { Text("Recent chats", style = MaterialTheme.typography.titleSmall) }
                        items(directory.filter { it.hierarchyId.isNotBlank() }.take(7),
                            key = { "chat-" + it.hierarchyId }) { chat ->
                            // Separate existing conversations visually from the unfilled
                            // suggested-person rows; retain the same direct-open action.
                            Row(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(13.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(enabled = !busy) {
                                    onOpenChat(chat.hierarchyId,
                                        chat.displayTitle.ifBlank { "Conversation" })
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (chat.participants.size > 1) {
                                    MessageGroupAvatar(chat.participants, 44.dp)
                                } else {
                                    NewMessageAvatar(chat.participants.firstOrNull()
                                        ?: ChatParticipant(displayName = chat.displayTitle))
                                }
                                Text(chat.displayTitle.ifBlank { "Conversation" },
                                    modifier = Modifier.weight(1f).padding(start = 12.dp), maxLines = 1)
                                Text("›")
                            }
                        }
                    }
                    item { Text("Suggested", style = MaterialTheme.typography.titleSmall) }
                    items(matching.take(60), key = { "user-" + it.username }) { person ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            selected = selected + person
                            search = ""
                        }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NewMessageAvatar(person)
                            Column(Modifier.weight(1f)) {
                                Text(person.displayName.ifBlank { person.username },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                suggestionContext(person.username)?.let { context ->
                                    Text(context, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Rounded.Add, contentDescription = "Select ${person.displayName}")
                        }
                    }
                    if (term.isNotEmpty() && validChatUsername(term) &&
                        term != self && suggestions.none { it.username.equals(term, ignoreCase = true) } &&
                        selected.none { it.username.equals(term, ignoreCase = true) }) {
                        item {
                            TextButton(onClick = {
                                selected = selected + ChatParticipant(term, term, null)
                                search = ""
                            }, enabled = !busy) { Text("Add exact username: $term") }
                        }
                    }
                    if (matching.isEmpty() && term.isBlank()) {
                        item { Text("Only people from known chats and listening activity are shown. " +
                            "You can also enter an exact Spotify username.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (existing != null && selected.isNotEmpty()) {
                Text("This conversation already exists; it will be opened.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (requireGroup && selected.size < 2) {
                Text(if (selected.isEmpty()) "Choose at least two people to create a group."
                    else "Choose one more person to create a group.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(enabled = canSubmit, modifier = Modifier.fillMaxWidth(), onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    try {
                        // Fetch a fresh server directory immediately before any mutation.
                        // Never create a new chat when the duplicate check cannot run.
                        val latest = graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview")
                        directory = latest.conversations
                        val found = matchingConversation(latest.conversations, selectedUsernames, self)
                        if (found != null) {
                            onOpenChat(found.hierarchyId, found.displayTitle.ifBlank { heading })
                        } else {
                            val created = graph.rpc.call<NewMessageCreationResult>(
                                "catalog.createGroupChat", buildJsonObject {
                                    put("memberUsernames", JsonArray(selected.map { JsonPrimitive(it.username) }))
                                    if (selected.size > 1 && name.isNotBlank()) put("name", name.trim())
                                })
                            if (created.acknowledged && created.hierarchyId.isNotBlank()) {
                                onOpenChat(created.hierarchyId,
                                    name.ifBlank { selected.joinToString(", ") { it.displayName } })
                            } else {
                                error = "Creation status isn't confirmed. Check Messages before retrying."
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = "Outcome unknown: " + (failure.message?.take(150) ?: "check Messages before retrying")
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Opening…" else if (existing != null) "Open existing chat"
                else if (selected.size == 1) "Start chat" else "Create group chat") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun NewMessageAvatar(person: ChatParticipant) {
    Box(Modifier.size(44.dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        if (person.avatarUrl?.startsWith("https://") == true) {
            AsyncImage(person.avatarUrl, contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
                contentScale = ContentScale.Crop)
        } else {
            Text(person.displayName.take(1).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.titleMedium)
        }
    }
}
