package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
internal data class MessageOverviewResult(
    val latestNodes: Int = 0,
    val withHierarchy: Int = 0,
    val withClientNode: Int = 0,
    val distinctHierarchies: Int = 0,
    val conversations: List<ConversationSummary> = emptyList(),
)

@Serializable
internal data class ConversationSummary(
    val hierarchyId: String = "",
    val groupName: String? = null,
    val isGroup: Boolean = false,
    val groupMemberCount: Int = 0,
    val unseenEvents: Int = 0,
    val unseenContributions: Int = 0,
    val latestNodeKind: String = "",
    val latestContentLabel: String? = null,
    val latestPreviewText: String? = null,
    val latestSenderUsername: String? = null,
    val latestSenderName: String? = null,
    val latestOccurredAtMs: Long? = null,
    val displayTitle: String = "",
    val participants: List<ChatParticipant> = emptyList(),
)

@Serializable
internal data class ChatParticipant(
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
)

@Serializable
internal data class ChatMuteStatus(val muted: Boolean = false)

/** A shared snapshot of authoritative unread-contribution counts (not activity events).
 * Updated by the inbox/drawer and by verified read acknowledgements. */
internal object ChatUnreadStates {
    val counts = mutableStateMapOf<String, Int>()
    fun update(conversations: List<ConversationSummary>) {
        val fresh = conversations.filter { it.hierarchyId.isNotBlank() }
            .associate { it.hierarchyId to it.unseenContributions.coerceAtLeast(0) }
        counts.clear()
        counts.putAll(fresh)
    }
}

/** Server-backed in-memory state shared by Chat Details, full Messages, and the drawer. */
internal object ChatMuteStates {
    val statuses = mutableStateMapOf<String, Boolean>()
}

/** Fetches current chat settings; do not infer mute state from message activity. */
@Composable
internal fun ChatMuteIndicator(hierarchyId: String, modifier: Modifier = Modifier) {
    val rpc = rememberAppGraph().rpc
    val muted = ChatMuteStates.statuses[hierarchyId]
    LaunchedEffect(hierarchyId, rpc) {
        if (hierarchyId.isNotBlank()) {
            runCatching {
                rpc.call<ChatMuteStatus>("catalog.chatMuteStatus", buildJsonObject {
                    put("hierarchyId", hierarchyId)
                })
            }.onSuccess { ChatMuteStates.statuses[hierarchyId] = it.muted }
        }
    }
    if (muted == true) {
        Icon(Icons.Rounded.NotificationsOff, contentDescription = "Notifications muted",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(17.dp))
    }
}

/** Keep the server-backed mute indicator in the same compact date cluster. */
@Composable
internal fun ConversationDateAndMute(hierarchyId: String, timestampMs: Long?, unreadMessages: Int = 0) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (unreadMessages > 0) ChatUnreadBadge(unreadMessages)
        ChatMuteIndicator(hierarchyId)
        conversationTime(timestampMs)?.let { time ->
            Text(time, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** HierarchyStatistic field 4: unseen contributions, excluding activity/reaction events. */
@Composable
internal fun ChatUnreadBadge(count: Int) {
    if (count <= 0) return
    Surface(color = Color(0xFFE53935), shape = CircleShape,
        modifier = Modifier.semantics { contentDescription = "$count unread chat messages" }) {
        Text(if (count > 99) "99+" else count.toString(),
            color = Color.White, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

/** Read-only conversation directory with latest-text excerpts; does not mutate read status. */
internal fun conversationTime(ms: Long?): String? {
    if (ms == null) return null
    return try {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(ms).atZone(zone)
        val today = LocalDate.now(zone)
        when {
            dateTime.toLocalDate() == today -> "Today"
            dateTime.toLocalDate() == today.minusDays(1) -> "Yesterday"
            dateTime.toLocalDate() > today.minusDays(7) -> dateTime.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
            else -> dateTime.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        }
    } catch (_: Exception) {
        null
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesProbeScreen(contentPadding: PaddingValues) {
    val navigator = LocalAppNavigator.current
    val rpc = rememberAppGraph().rpc
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<MessageOverviewResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNewMessage by remember { mutableStateOf(false) }
    // Only a user-initiated pull starts the refresh indicator; initial loading stays in the list.
    var pulledRefreshActive by remember { mutableStateOf(false) }

    LaunchedEffect(refresh, rpc) {
        loading = true
        error = null
        // Retain the previous list while fetching so it doesn't blink on each refresh.
        try {
            val overview = rpc.call<MessageOverviewResult>("catalog.messagesOverview")
            result = overview
            ChatUnreadStates.update(overview.conversations)
        } catch (failure: Exception) {
            error = failure.message?.take(160) ?: failure.javaClass.simpleName
        } finally {
            loading = false
            pulledRefreshActive = false
        }
    }

    var pulling by remember { mutableStateOf(false) }
    // Latest previews and event counts update while the Messages screen is visible.
    // This does not generate Android push notifications or mark messages seen.
    LaunchedEffect(rpc, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(12_000)
                if (loading || pulledRefreshActive || pulling) continue
                try {
                    val overview = rpc.call<MessageOverviewResult>("catalog.messagesOverview")
                    result = overview
                    ChatUnreadStates.update(overview.conversations)
                    error = null
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) { /* Keep last successfully loaded list. */ }
            }
        }
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // Deliberately longer than the normal swipe to prevent accidental refreshes.
    val refreshThresholdPx = with(density) { 128.dp.toPx() }
    val maxRevealPx = with(density) { 76.dp.toPx() }
    var pulledPx by remember { mutableFloatStateOf(0f) }
    // Haptic feedback is emitted just once per pull, exactly when it becomes actionable.
    var refreshArmedHapticFired by remember { mutableStateOf(false) }
    val latestLoading by rememberUpdatedState(loading)
    val latestRefresh by rememberUpdatedState<() -> Unit>({ refresh++ })
    val pullConnection = remember(listState, refreshThresholdPx, haptic) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || pulledPx <= 0f) {
                    return Offset.Zero
                }
                val consumedPull = (available.y * 0.55f).coerceAtLeast(-pulledPx)
                pulledPx += consumedPull
                pulling = true
                return Offset(0f, consumedPull / 0.55f)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f || latestLoading ||
                    pulledRefreshActive || listState.firstVisibleItemIndex != 0 ||
                    listState.firstVisibleItemScrollOffset != 0) return Offset.Zero
                pulledPx = (pulledPx + available.y * 0.55f).coerceAtMost(refreshThresholdPx * 1.3f)
                if (pulledPx >= refreshThresholdPx && !refreshArmedHapticFired) {
                    refreshArmedHapticFired = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                pulling = true
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (!pulling) return androidx.compose.ui.unit.Velocity.Zero
                val reached = pulledPx >= refreshThresholdPx
                pulling = false
                pulledPx = 0f
                refreshArmedHapticFired = false
                if (reached && !latestLoading && !pulledRefreshActive) {
                    pulledRefreshActive = true
                    latestRefresh()
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val revealTarget = when {
        pulledRefreshActive -> maxRevealPx
        pulling -> pulledPx.coerceAtMost(maxRevealPx)
        else -> 0f
    }
    val reveal by animateFloatAsState(revealTarget, animationSpec = spring(), label = "chat-refresh-reveal")
    val gauge = (pulledPx / refreshThresholdPx).coerceIn(0f, 1f)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Messages", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = navigator::back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { showNewMessage = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "New message")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )
        Box(Modifier.fillMaxSize().padding(contentPadding).nestedScroll(pullConnection)) {
            // Revealed space sits behind the message rows and returns under the header on release.
            if (pulling || pulledRefreshActive || reveal > 1f) {
                Row(Modifier.align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (pulledRefreshActive) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Refreshing…", style = MaterialTheme.typography.labelMedium)
                    } else {
                        CircularProgressIndicator(progress = { gauge }, modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp)
                        if (pulling) Text(if (gauge >= 1f) "Release to refresh" else "Pull to refresh",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().offset { IntOffset(0, reveal.roundToInt()) },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (loading && result == null) {
                    item { CircularProgressIndicator() }
                }
                if (error != null) {
                    item { Text("Messaging request failed: $error", color = MaterialTheme.colorScheme.error) }
                }
                if (result?.conversations?.isEmpty() == true) {
                    item { Text("No conversations yet") }
                }
            itemsIndexed(result?.conversations.orEmpty()) { index, conversation ->
                val name = conversation.displayTitle.ifBlank {
                    conversation.groupName?.takeIf { it.isNotBlank() }
                        ?: "Conversation ${index + 1}"
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = conversation.hierarchyId.isNotBlank()) {
                            navigator.navigate(Route.MessageConversation(conversation.hierarchyId, name))
                        }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val avatar = conversation.participants.singleOrNull()?.avatarUrl
                    if (conversation.isGroup && conversation.participants.isNotEmpty()) {
                        MessageGroupAvatar(conversation.participants, 48.dp)
                    } else if (!avatar.isNullOrBlank()) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(Modifier.padding(start = 8.dp)) {
                                ConversationDateAndMute(
                                    conversation.hierarchyId,
                                    conversation.latestOccurredAtMs,
                                    ChatUnreadStates.counts[conversation.hierarchyId]
                                        ?: conversation.unseenContributions,
                                )
                            }
                        }
                        val kind = when (conversation.latestNodeKind) {
                            "contribution" -> "Message"
                            "activity" -> "Chat activity"
                            "no payload" -> "No recent message payload"
                            "missing" -> "No latest node"
                            else -> "Conversation update"
                        }
                        val detail = conversation.latestPreviewText
                            ?: conversation.latestContentLabel ?: kind
                        val sender = conversation.latestSenderName
                        val isMessage = conversation.latestNodeKind == "contribution"
                        val preview = when {
                            !isMessage || sender.isNullOrBlank() -> detail
                            conversation.latestPreviewText != null -> "$sender: $detail"
                            detail.startsWith("Shared ") -> "$sender ${detail.replaceFirstChar { it.lowercase() }}"
                            else -> "$sender · $detail"
                        }
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (conversation.groupMemberCount > 2) {
                            Text(
                                "${conversation.groupMemberCount} members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 5.dp))
            }
        }
        }
    }
    if (showNewMessage) {
        NewMessageSheet(onDismiss = { showNewMessage = false }, onOpenChat = { id, title ->
            showNewMessage = false
            navigator.navigate(Route.MessageConversation(id, title))
        })
    }
}

/** Four-member portrait collage for group chats in the inbox and drawer. */
@Composable
internal fun MessageGroupAvatar(people: List<ChatParticipant>, size: Dp) {
    val shown = people.distinctBy { it.username.ifBlank { it.avatarUrl ?: it.displayName } }.take(4)
    if (shown.isEmpty()) return
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        when (shown.size) {
            1 -> GroupAvatarTile(shown[0], Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize()) {
                GroupAvatarTile(shown[0], Modifier.weight(1f).fillMaxHeight())
                GroupAvatarTile(shown[1], Modifier.weight(1f).fillMaxHeight())
            }
            else -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.57f).fillMaxHeight()) {
                    GroupAvatarTile(shown[0], Modifier.weight(if (shown.size == 3) 1f else 0.65f).fillMaxWidth())
                    if (shown.size == 4) GroupAvatarTile(shown[2], Modifier.weight(0.35f).fillMaxWidth())
                }
                Column(Modifier.weight(0.43f).fillMaxHeight()) {
                    GroupAvatarTile(shown[1], Modifier.weight(0.5f).fillMaxWidth())
                    GroupAvatarTile(shown[if (shown.size == 3) 2 else 3], Modifier.weight(0.5f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun GroupAvatarTile(person: ChatParticipant, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        if (person.avatarUrl?.startsWith("https://") == true) {
            AsyncImage(model = person.avatarUrl, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(person.displayName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
