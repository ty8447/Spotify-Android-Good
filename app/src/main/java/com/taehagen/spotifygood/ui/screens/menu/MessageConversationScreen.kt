package com.taehagen.spotifygood.ui.screens.menu

import android.content.Intent
import java.util.UUID
import android.content.ClipData
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Gif
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.giphy.sdk.core.models.Media
import com.giphy.sdk.ui.Giphy
import com.giphy.sdk.ui.pagination.GPHContent
import com.giphy.sdk.ui.views.GiphyGridView
import com.taehagen.spotifygood.BuildConfig
import com.taehagen.spotifygood.ui.messaging.GiphyMessagePickerActivity
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.data.mosaicCovers
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.FriendActivity
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.MediaActionRunner
import com.taehagen.spotifygood.ui.components.MediaActionRunner.Companion.awaitData
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class MessageHistoryPage(
    val hierarchyId: String = "",
    val groupName: String? = null,
    val groupMemberCount: Int = 0,
    val pendingMemberCount: Int = 0,
    val activeOtherMemberCount: Int = 0,
    val participants: List<HistoryParticipant> = emptyList(),
    val messages: List<HistoryMessage> = emptyList(),
    val nextPageToken: String? = null,
    val previousPageToken: String? = null,
)

@Serializable
private data class HistoryParticipant(
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
)

/** Real Jam links carried by the confirmed free-text message transport.
 * These are not Spotify's separate Request-to-Jam approval cards.
 */
private fun jamLinkFromMessage(text: String?, shareUri: String?): String? =
    JamInviteLinks.fromMessage(text, shareUri)

@Serializable
private data class HistoryMessage(
    val id: String = "",
    val kind: String = "",
    val senderUsername: String? = null,
    val senderName: String? = null,
    val senderAvatarUrl: String? = null,
    val occurredAtMs: Long? = null,
    val contentLabel: String? = null,
    val text: String? = null,
    val shareUri: String? = null,
    val gifId: String? = null,
    val stickerId: String? = null,
    val parentNodeId: String? = null,
    val activityType: String? = null,
    val activityInitiator: String? = null,
    val receiptType: String? = null,
    val reactionEmoji: String? = null,
    val systemEventType: String? = null,
    val shareOrigin: String? = null,
    val isMine: Boolean = false,
)

/** Metadata for actual shares, regardless of whether the node is a track, album or playlist. */
@Serializable
private data class SendTextResult(
    val acknowledged: Boolean = false,
    val clientNodeId: String? = null,
)

@Serializable
private data class ChatDeleteRequestResult(val submitted: Boolean = false)

@Serializable
private data class DeleteMessageResult(
    val submitted: Boolean = false,
    val confirmed: Boolean = false,
)

/** One GIPHY SDK selection. Never use an arbitrary URL as a provider ID. */
private data class GifAttachment(val id: String, val ageRating: Int)

/** Sticker ID and image come directly from Spotify's authenticated sticker catalog. */
@Serializable
private data class ChatLyricSticker(
    val stickerId: String = "",
    val imageUrl: String? = null,
    val altText: String = "",
    val lyricsText: String = "",
    val trackUri: String = "",
    val trackPreviewUrl: String? = null,
)

@Serializable
private data class ChatStickerPack(
    val title: String = "",
    val stickers: List<ChatLyricSticker> = emptyList(),
)

@Serializable
private data class ChatStickerCatalog(
    val packs: List<ChatStickerPack> = emptyList(),
    val nextPageToken: String = "",
)

private data class ChatSharedMedia(
    val canonicalUri: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
    val mosaicUrls: List<String> = emptyList(),
    val track: Track? = null,
    val album: Album? = null,
    val playlist: Playlist? = null,
)

private fun canonicalSharedMedia(link: String): Pair<String, String>? {
    val raw = if (link.startsWith("spotify:")) {
        val fields = link.substringBefore('?').split(':')
        if (fields.size < 3) return null
        fields[1] to fields[2]
    } else if (link.startsWith("https://open.spotify.com/")) {
        val parts = link.substringAfter("open.spotify.com/").substringBefore('?')
            .trim('/').split('/')
        val index = parts.indexOfFirst { it in setOf("track", "album", "playlist") }
        if (index < 0) return null
        parts[index] to (parts.getOrNull(index + 1) ?: return null)
    } else return null
    val (kind, id) = raw
    if (kind !in setOf("track", "album", "playlist") ||
        id.length != 22 || !id.all(Char::isLetterOrDigit)) return null
    return kind to "spotify:$kind:$id"
}

private fun messageDay(timeMs: Long?): LocalDate? = timeMs?.let {
    runCatching { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
}

private fun dayHeading(timeMs: Long?): String {
    val day = messageDay(timeMs) ?: return ""
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
    }
}

private fun monthHeading(timeMs: Long?): String = timeMs?.let {
    runCatching { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) }.getOrDefault("")
} ?: ""

/** An activity is attached only when the hierarchy explicitly nests it under a message. */
private fun groupedChatHistory(all: List<HistoryMessage>): List<HistoryMessage> {
    val contributionIds = all.filter { it.kind != "activity" }.map { it.id }.toSet()
    val attachedActivityIds = all.filter {
        it.parentNodeId in contributionIds && (
            (it.activityType == "receipt" && it.receiptType in setOf("played", "saved")) ||
                it.activityType == "reaction")
    }.map { it.id }.toSet()
    return all.filter { it.id !in attachedActivityIds }
}

private fun activitiesUnderMessage(all: List<HistoryMessage>): Map<String, List<HistoryMessage>> {
    val parents = all.filter { it.kind != "activity" }.map { it.id }.toSet()
    return all.filter { it.parentNodeId in parents && (
        (it.activityType == "receipt" && it.receiptType in setOf("played", "saved")) ||
            it.activityType == "reaction") }
        .groupBy { it.parentNodeId!! }
}

private fun shareOptionsTarget(media: ChatSharedMedia): MediaActionTarget? = when (media.kind) {
    "track" -> media.track?.let { MediaActionTarget.TrackTarget(it) }
    "album" -> media.album?.let { MediaActionTarget.AlbumTarget(it.toRef()) }
    "playlist" -> media.playlist?.let { MediaActionTarget.PlaylistTarget(it.toRef(), it.isOwnedByMe) }
    else -> null
}

/** Rounded pink Jam invite card, distinct from ordinary Shared music contributions.
 * The decorative collage uses local Compose graphics; no unverified remote art.
 */
@Composable
private fun JamConversationInviteCard(onClick: () -> Unit, onLongClick: () -> Unit) {
    val pink = Color(0xFFE992C7)
    Surface(
        modifier = Modifier.width(194.dp).combinedClickable(onClick = onClick,
            onLongClick = onLongClick),
        shape = RoundedCornerShape(15.dp), color = pink,
    ) {
        Column(Modifier.padding(5.dp)) {
            Box(Modifier.fillMaxWidth().height(166.dp)
                .clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF30182D), Color(0xFF180E27))))) {
                // Small record-and-sticker collage inspired by Spotify's card.
                Box(Modifier.align(Alignment.Center).size(108.dp)
                    .clip(CircleShape).background(Brush.sweepGradient(listOf(
                        Color(0xFF3323A6), Color(0xFF087CA7), Color(0xFFAB256B),
                        Color(0xFF0B123A), Color(0xFF3323A6))))
                    .border(5.dp, Color(0xFF4A2675), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(49.dp))
                }
                Box(Modifier.align(Alignment.TopStart).offset(x = 14.dp, y = 19.dp)
                    .size(37.dp).rotate(-13f)
                    .background(Color(0xFFF6AFD6), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Color(0xFF5134AB))
                }
                Box(Modifier.align(Alignment.CenterEnd).offset(x = (-10).dp, y = (-48).dp)
                    .size(47.dp).background(Color(0xFFF385D5), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.People, null, tint = Color(0xFF6532A6),
                        modifier = Modifier.size(29.dp))
                }
                Box(Modifier.align(Alignment.CenterStart).offset(x = 7.dp, y = 38.dp)
                    .size(38.dp).rotate(12f)
                    .background(Color(0xFFFCAA24), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Headphones, null, tint = Color(0xFFCB35A1))
                }
                Box(Modifier.align(Alignment.BottomEnd).offset(x = (-14).dp, y = (-15).dp)
                    .size(38.dp).rotate(15f)
                    .background(Color(0xFFFFCF69), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = Color(0xFFE04C88))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Join my Jam", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp)
                Text("Listen together in real time", color = Color(0xFFF3DCEB),
                    fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Message timeline with verified Spotify mutations and foreground live refresh. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageConversationScreen(hierarchyId: String, title: String, contentPadding: PaddingValues) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val runner = remember(graph, navigator, context) { MediaActionRunner(graph, navigator, context) }
    var refresh by remember(hierarchyId) { mutableIntStateOf(0) }
    var requestedToken by remember(hierarchyId) { mutableStateOf("") }
    var loading by remember(hierarchyId) { mutableStateOf(true) }
    var loadingOlder by remember(hierarchyId) { mutableStateOf(false) }
    var error by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var page by remember(hierarchyId) { mutableStateOf<MessageHistoryPage?>(null) }
    var messages by remember(hierarchyId) { mutableStateOf<List<HistoryMessage>>(emptyList()) }
    var nextToken by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var shareToken by remember(hierarchyId) { mutableStateOf("") }
    var shareRefresh by remember(hierarchyId) { mutableIntStateOf(0) }
    var archiveShares by remember(hierarchyId) { mutableStateOf<List<HistoryMessage>>(emptyList()) }
    var sharesNextToken by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var sharesLoading by remember(hierarchyId) { mutableStateOf(false) }
    var sharesError by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(hierarchyId) { mutableStateOf("") }
    // One-shot composer handoff from a group listening card opened in the drawer.
    // Kept account-scoped in memory; never sent until the user presses Send.
    val activityDraftAccount = graph.engine.user.collectAsStateWithLifecycle().value?.username
    LaunchedEffect(hierarchyId, activityDraftAccount) {
        PendingListeningChatDraft.take(activityDraftAccount, hierarchyId)?.let { incoming ->
            draft = if (draft.isBlank()) incoming else draft.trimEnd() + "\n" + incoming
        }
    }
    var sending by remember(hierarchyId) { mutableStateOf(false) }
    var reacting by remember(hierarchyId) { mutableStateOf(false) }
    var deleting by remember(hierarchyId) { mutableStateOf(false) }
    var deleteTarget by remember(hierarchyId) { mutableStateOf<HistoryMessage?>(null) }
    var deleteForAllConfirmation by remember(hierarchyId) { mutableStateOf<Boolean?>(null) }
    var sendWarning by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var showActivity by remember(hierarchyId) { mutableStateOf(false) }
    var showJam by remember(hierarchyId) { mutableStateOf(false) }
    var jamEntryMode by remember(hierarchyId) { mutableIntStateOf(-1) }
    var showEndedJamDialog by remember(hierarchyId) { mutableStateOf(false) }
    // An incoming canonical Jam link can request approval without leaving Spotify Good.
    // The host's approval and the actual playback transfer are separate server actions.
    var incomingJoinUrl by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var listenOnThisPhone by remember(hierarchyId) { mutableStateOf(true) }
    var askToJoinBusy by remember(hierarchyId) { mutableStateOf(false) }
    var askToJoinFeedback by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var joinDiscoveryMethod by remember(hierarchyId) { mutableStateOf("share-link") }
    // Unlike activeChatJam (our own session), this state is account-bound to
    // the *other* participant and is populated only by the official token lookup.
    var peerJamUrl by remember(hierarchyId) { mutableStateOf<String?>(null) }

    // Read-only server-verified status for the persistent Jam ribbon.
    var activeChatJam by remember(hierarchyId) { mutableStateOf<JamSessionSummary?>(null) }
    LaunchedEffect(hierarchyId, graph.rpc, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val status = graph.rpc.call<JamServiceStatus>(
                        "catalog.jamServiceStatus", buildJsonObject {})
                    activeChatJam = status.current.session?.takeIf { it.active }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Keep the last verified state during a transient network interruption.
                }
                delay(30_000)
            }
        }
    }
    // This check can verify only invitations for the Jam hosted by this device.
    // An unrelated sender's join link is opened via the system Spotify handler;
    // the server alone can decide whether that external session is still live.
    fun openJamInvitation(url: String) {
        if (!JamInviteLinks.isCurrentSessionInvitation(url)) {
            // The approval API requires the actual 22-character join token;
            // spotify.link shortcodes are opaque redirects, not join tokens.
            if (JamInviteLinks.canonical(url) != null) {
                incomingJoinUrl = url
                joinDiscoveryMethod = "share-link"
                listenOnThisPhone = true
                askToJoinFeedback = null
            } else {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(url))) }
                    .onFailure { navigator.showMessage("Cannot open this Jam invitation") }
            }
            return
        }
        scope.launch {
            try {
                val check = graph.rpc.call<JamServiceStatus>(
                    "catalog.jamServiceStatus", buildJsonObject {}).current
                when {
                    check.result == "reply" && check.session?.active == true -> {
                        // A different Jam could have started since this card was sent.
                        // Match the fresh server-issued link before opening its invite page.
                        val latest = graph.rpc.call<JamInvitationLink>(
                            "catalog.jamInvitationLink", buildJsonObject {})
                        val fresh = JamInviteLinks.canonical(latest.url)
                        val original = JamInviteLinks.canonicalForObservedInvite(url)
                        if (latest.result == "ready" && fresh != null && fresh == original) {
                            activeChatJam = check.session
                            JamGlobalState.session = check.session
                            jamEntryMode = -1
                            showJam = true
                        } else if (fresh != null && fresh != original) {
                            showEndedJamDialog = true
                        } else navigator.showMessage("Couldn't verify this invitation")
                    }
                    check.result == "not-found" ||
                        (check.result == "reply" && check.session?.active == false) -> {
                        activeChatJam = null
                        JamGlobalState.session = null
                        showEndedJamDialog = true
                    }
                    else -> navigator.showMessage("Couldn't verify whether this Jam is active")
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                navigator.showMessage("Couldn't verify whether this Jam is active")
            }
        }
    }
    var selectedMessageId by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var reactionDetailsId by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var emojiPickerMessageId by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var readEpoch by remember(hierarchyId) { mutableIntStateOf(0) }
    var acknowledgedReadEpoch by remember(hierarchyId) { mutableIntStateOf(-1) }
    var markingSeen by remember(hierarchyId) { mutableStateOf(false) }
    var showMusicPicker by remember(hierarchyId) { mutableStateOf(false) }
    var attachedMusic by remember(hierarchyId) { mutableStateOf<MediaRef?>(null) }
    var attachedGif by remember(hierarchyId) { mutableStateOf<GifAttachment?>(null) }
    var attachedSticker by remember(hierarchyId) { mutableStateOf<ChatLyricSticker?>(null) }
    val giphyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        if (response.resultCode == android.app.Activity.RESULT_OK) {
            val id = response.data?.getStringExtra(GiphyMessagePickerActivity.EXTRA_MEDIA_ID).orEmpty()
            val rating = response.data?.getIntExtra(GiphyMessagePickerActivity.EXTRA_RATING, -1) ?: -1
            if (id.length in 3..128 && id.all { (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') || it == '-' || it == '_' } &&
                rating in 1..5) {
                attachedGif = GifAttachment(id, rating)
                attachedMusic = null // one attachment, same as Spotify
                attachedSticker = null
                sendWarning = null
            }
        }
    }
    var attachmentArtwork by remember(hierarchyId) { mutableStateOf<ChatSharedMedia?>(null) }
    var showStickers by remember(hierarchyId) { mutableStateOf(false) }
    var showManagement by remember(hierarchyId) { mutableStateOf(false) }
    var showDeleteChatConfirmation by remember(hierarchyId) { mutableStateOf(false) }
    var deletingChat by remember(hierarchyId) { mutableStateOf(false) }
    var showCreateGroup by remember(hierarchyId) { mutableStateOf(false) }
    // null = no confirmation; false = leave but retain history; true = leave and delete
    var pendingLeaveMode by remember(hierarchyId) { mutableStateOf<Boolean?>(null) }
    var leavingGroup by remember(hierarchyId) { mutableStateOf(false) }
    var showMute by remember(hierarchyId) { mutableStateOf(false) }
    var muteBusy by remember(hierarchyId) { mutableStateOf(false) }
    var muteError by remember(hierarchyId) { mutableStateOf<String?>(null) }
    val currentMuteState = ChatMuteStates.statuses[hierarchyId]
    LaunchedEffect(hierarchyId, graph.rpc) {
        runCatching {
            graph.rpc.call<ChatMuteStatus>("catalog.chatMuteStatus", buildJsonObject {
                put("hierarchyId", hierarchyId)
            })
        }.onSuccess { ChatMuteStates.statuses[hierarchyId] = it.muted }
    }
    val unmute: () -> Unit = {
        if (!muteBusy) {
            muteBusy = true
            muteError = null
            scope.launch {
                try {
                    graph.rpc.call<Boolean>("catalog.unmuteChat", buildJsonObject {
                        put("hierarchyId", hierarchyId)
                    })
                    val verified = graph.rpc.call<ChatMuteStatus>("catalog.chatMuteStatus",
                        buildJsonObject { put("hierarchyId", hierarchyId) })
                    ChatMuteStates.statuses[hierarchyId] = verified.muted
                    if (verified.muted) throw IllegalStateException("The server still reports this chat as muted")
                    showMute = false
                    navigator.showMessage("Chat unmuted")
                } catch (e: Exception) {
                    muteError = e.message?.take(140) ?: "Unable to unmute chat"
                    navigator.showMessage(muteError ?: "Unable to unmute chat")
                } finally { muteBusy = false }
            }
        }
    }
    var showSettings by remember(hierarchyId) { mutableStateOf(false) }
    // 0 = history, 1 = Chat details, 2 = People
    var subpage by rememberSaveable(hierarchyId) { mutableIntStateOf(0) }
    BackHandler(enabled = subpage != 0) {
        subpage = when (subpage) {
            2 -> 1
            else -> 0
        }
    }
    var listening by remember(hierarchyId) { mutableStateOf<List<FriendActivity>>(emptyList()) }
    var sharedDetails by remember(hierarchyId) { mutableStateOf<Map<String, ChatSharedMedia>>(emptyMap()) }
    var attemptedShares by remember(hierarchyId) { mutableStateOf<Set<String>>(emptySet()) }
    var revealMessageTimes by remember(hierarchyId) { mutableStateOf(false) }
    var initialScrollCompleted by remember(hierarchyId) { mutableStateOf(false) }
    // Message ID of the first new contribution arriving while the user reads older content.
    var newMessagesDividerId by remember(hierarchyId) { mutableStateOf<String?>(null) }
    var autoScrollToLatest by remember(hierarchyId) { mutableStateOf(false) }

    LaunchedEffect(hierarchyId, refresh, requestedToken) {
        if (hierarchyId.isBlank()) {
            error = "Conversation identifier unavailable"
            loading = false
            return@LaunchedEffect
        }
        val isFirstPage = requestedToken.isEmpty()
        if (isFirstPage) loading = true else loadingOlder = true
        error = null
        try {
            val response = graph.rpc.call<MessageHistoryPage>(
                "catalog.messageHistory", buildJsonObject {
                    put("hierarchyId", hierarchyId)
                    if (requestedToken.isNotEmpty()) put("pageToken", requestedToken)
                },
            )
            val ids = messages.asSequence().map { it.id }.toHashSet()
            page = if (isFirstPage || page == null) response else page
            messages = if (isFirstPage) response.messages else (
                messages + response.messages.filter { it.id !in ids }
            ).sortedWith(compareBy<HistoryMessage> { it.occurredAtMs ?: Long.MIN_VALUE }.thenBy { it.id })
            // The API uses descending order. The next-page cursor should lead to older nodes.
            nextToken = response.nextPageToken?.takeIf { it.isNotBlank() && it != requestedToken }
        } catch (failure: Exception) {
            error = failure.message?.take(200) ?: "Unable to load conversation history"
        } finally {
            loading = false
            loadingOlder = false
        }
    }

    // Chat Details has its own paginated Shares endpoint, independent of loaded
    // history pages. Page tokens are opaque and never fabricated client-side.
    LaunchedEffect(hierarchyId, subpage, shareToken, shareRefresh) {
        if (subpage != 1 || (shareToken.isEmpty() && archiveShares.isNotEmpty() && shareRefresh == 0)) return@LaunchedEffect
        sharesLoading = true
        sharesError = null
        try {
            val result = graph.rpc.call<MessageHistoryPage>("catalog.messageShares", buildJsonObject {
                put("hierarchyId", hierarchyId)
                if (shareToken.isNotEmpty()) put("pageToken", shareToken)
            })
            val seen = archiveShares.map { it.id }.toHashSet()
            archiveShares = if (shareToken.isEmpty()) result.messages else
                archiveShares + result.messages.filter { it.id !in seen }
            sharesNextToken = result.nextPageToken?.takeIf { it.isNotBlank() && it != shareToken }
        } catch (e: Exception) {
            sharesError = e.message?.take(180) ?: "Shares could not be retrieved"
        } finally {
            sharesLoading = false
        }
    }

    // The old chat queried only tracks. Album and playlist shares have different
    // metadata endpoints and artwork lists. Resolve each URI at most once per visit.
    LaunchedEffect(messages, archiveShares) {
        val pending = (messages + archiveShares).mapNotNull { it.shareUri }.distinct()
            .filter { it !in attemptedShares }.take(40)
        if (pending.isEmpty()) return@LaunchedEffect
        attemptedShares = attemptedShares + pending
        val resolved = mutableMapOf<String, ChatSharedMedia>()
        val normalized = pending.mapNotNull { source ->
            canonicalSharedMedia(source)?.let { source to it }
        }
        val trackLinks = normalized.filter { it.second.first == "track" }
        if (trackLinks.isNotEmpty()) {
            val tracks = runCatching { graph.catalog.tracks(trackLinks.map { it.second.second }.distinct()) }
                .getOrDefault(emptyList())
            val byUri = tracks.associateBy { it.uri }
            trackLinks.forEach { (source, canonical) ->
                byUri[canonical.second]?.let { track ->
                    resolved[source] = ChatSharedMedia(canonical.second, "track", track.name,
                        track.artists.joinToString(", ") { it.name },
                        track.album?.images?.best(160), track = track)
                }
            }
        }
        for ((source, canonical) in normalized) {
            when (canonical.first) {
                "album" -> runCatching {
                    graph.rpc.call<Album>("catalog.album", buildJsonObject { put("uri", canonical.second) })
                }.getOrNull()?.let { album ->
                    resolved[source] = ChatSharedMedia(canonical.second, "album", album.name,
                        album.artists.joinToString(", ") { it.name }, album.images.best(160), album = album)
                }
                "playlist" -> runCatching {
                    graph.rpc.call<Playlist>("catalog.playlist", buildJsonObject {
                        put("uri", canonical.second); put("offset", 0); put("limit", 20)
                    })
                }.getOrNull()?.let { playlist ->
                    resolved[source] = ChatSharedMedia(canonical.second, "playlist", playlist.name,
                        playlist.owner?.displayName ?: playlist.owner?.username ?: "Playlist",
                        playlist.images.best(160),
                        mosaicUrls = if (playlist.images.best(160) == null)
                            mosaicCovers(playlist.items).mapNotNull { it.url(160) } else emptyList(),
                        playlist = playlist)
                }
            }
        }
        sharedDetails = sharedDetails + resolved
    }

    // Selected playlists often have no custom cover; use the same album mosaic
    // as an incoming playlist share. Work is cancelled if selection changes.
    LaunchedEffect(attachedMusic?.uri) {
        val music = attachedMusic
        attachmentArtwork = music?.let {
            ChatSharedMedia(it.uri, it.type.name.lowercase(), it.name,
                it.subtitle.orEmpty(), it.images.best(120))
        }
        if (music?.type == MediaType.PLAYLIST && music.images.best(120) == null) {
            val playlist = runCatching {
                graph.rpc.call<Playlist>("catalog.playlist", buildJsonObject {
                    put("uri", music.uri); put("offset", 0); put("limit", 20)
                })
            }.getOrNull()
            if (playlist != null) {
                attachmentArtwork = ChatSharedMedia(music.uri, "playlist", music.name,
                    music.subtitle.orEmpty(), playlist.images.best(120),
                    mosaicUrls = mosaicCovers(playlist.items).mapNotNull { it.url(120) })
            }
        }
    }

    val participants = page?.participants.orEmpty()
    val participantIds = participants.map { it.username }.filter { it.isNotBlank() }.toSet()
    LaunchedEffect(participantIds) {
        listening = if (participantIds.isEmpty()) emptyList() else {
            runCatching { graph.catalog.friendActivity().filter { it.username in participantIds } }
                .getOrDefault(emptyList())
        }
    }
    val currentlyListening = listening.filter { it.isActive == true }
    val currentTitle = page?.groupName?.takeIf { it.isNotBlank() } ?: title
    val clipboard = LocalClipboard.current
    val locale = LocalConfiguration.current.locales[0]
    val selfUsernameForDeletes = graph.engine.user.collectAsStateWithLifecycle().value?.username
    // Store only opaque message IDs, scoped to the signed-in account and hierarchy.
    // A successful Delete-for-you request must hide the entire contribution and
    // all its reactions/receipts, including if a stale history page is returned.
    val hiddenPrefs = remember(context) { context.getSharedPreferences("spotifygood_hidden_chat_nodes", 0) }
    val hiddenKey = remember(hierarchyId, selfUsernameForDeletes) {
        if (selfUsernameForDeletes.isNullOrBlank()) null
        else "hidden/$selfUsernameForDeletes/$hierarchyId"
    }
    var locallyHidden by remember(hiddenKey) {
        mutableStateOf(hiddenKey?.let { hiddenPrefs.getStringSet(it, emptySet())?.toSet() }.orEmpty())
    }
    val visibleMessages = remember(messages, locallyHidden) {
        groupedChatHistory(messages).filterNot {
            it.id in locallyHidden || it.parentNodeId in locallyHidden
        }
    }
    // Only enforce the two-message request limit when the server explicitly
    // identifies pending recipients and no other non-pending participant. Do not infer
    // pending status from creation time, and never auto-submit queued messages.
    val pendingInvite = (page?.pendingMemberCount ?: 0) > 0 &&
        (page?.activeOtherMemberCount ?: 0) == 0
    val messagesSentWhilePending = if (pendingInvite)
        messages.count { it.kind == "contribution" && it.isMine && it.systemEventType == null } else 0
    val pendingSendLimitReached = pendingInvite && messagesSentWhilePending >= 2
    val activitiesByParent = remember(messages, locallyHidden) {
        activitiesUnderMessage(messages).filterKeys { it !in locallyHidden }
    }
    // Wait for LazyColumn to measure the new message items before jumping to the end.
    // Scrolling directly from the fetch coroutine races the first layout and can land
    // near the top because the items do not exist in the LazyList yet.
    LaunchedEffect(hierarchyId, loading, visibleMessages.size) {
        if (!loading && !initialScrollCompleted && visibleMessages.isNotEmpty()) {
            val expectedCount = visibleMessages.size + 1 // leading pagination/status item
            snapshotFlow { scroll.layoutInfo.totalItemsCount }
                .first { it >= expectedCount }
            scroll.scrollToItem(expectedCount - 1)
            initialScrollCompleted = true
        }
    }
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.isImeVisible
    val selfUsername = graph.engine.user.collectAsStateWithLifecycle().value?.username
    val isDirectChat = (page?.groupMemberCount ?: participants.size) == 2
    val directPeer = if (isDirectChat) participants.firstOrNull {
        it.username != selfUsername
    } else null
    // Our own /sessions/current never tells us whether this person is hosting.
    // Spotify's /tokens/lookup returns their currently discoverable Jam token.
    // Recheck while foregrounded and reset immediately on peer/account changes.
    LaunchedEffect(hierarchyId, directPeer?.username, selfUsername, graph.rpc, lifecycle) {
        peerJamUrl = null
        val peer = directPeer?.username?.takeIf { it.isNotBlank() && it != selfUsername }
        if (peer != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val result = graph.rpc.call<JamPeerLookupResult>(
                        "catalog.jamPeerLookup", buildJsonObject { put("username", peer) })
                    when (result.result) {
                        "active" -> peerJamUrl = JamInviteLinks.canonical(result.invitationUrl)
                        "inactive" -> peerJamUrl = null
                        // Transient errors must not make a currently verified Jam flicker.
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Keep the last confirmed state during a short-lived error.
                }
                delay(15_000)
            }
        }
    }
    val nearBottom by remember(scroll) {
        derivedStateOf {
            val visible = scroll.layoutInfo.visibleItemsInfo
            val total = scroll.layoutInfo.totalItemsCount
            total == 0 || (visible.lastOrNull()?.index ?: 0) >= total - 2
        }
    }

    // Poll only while this chat is on screen and the app is in the foreground.
    // No mutation/receipt is sent. Merge recent nodes by immutable server IDs so
    // paging older history does not get overwritten or force the reader to the bottom.
    LaunchedEffect(hierarchyId, subpage, graph.rpc, lifecycle) {
        if (subpage != 0 || hierarchyId.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(8.seconds)
                if (loading || loadingOlder || sending || !initialScrollCompleted) continue
                try {
                    val fresh = graph.rpc.call<MessageHistoryPage>(
                        "catalog.messageHistory", buildJsonObject {
                            put("hierarchyId", hierarchyId)
                        })
                    val known = messages.mapTo(HashSet()) { it.id }
                    val arrivals = fresh.messages.filter { it.id !in known }
                    if (arrivals.isNotEmpty()) {
                        val wasAtLatest = nearBottom
                        val incoming = arrivals.filter {
                            it.kind == "contribution" && it.senderUsername != null &&
                                it.senderUsername != selfUsername
                        }.sortedWith(compareBy<HistoryMessage> { it.occurredAtMs ?: Long.MIN_VALUE }
                            .thenBy { it.id })
                        if (incoming.isNotEmpty() && !wasAtLatest && newMessagesDividerId == null) {
                            newMessagesDividerId = incoming.first().id
                        }
                        messages = (messages + arrivals).sortedWith(
                            compareBy<HistoryMessage> { it.occurredAtMs ?: Long.MIN_VALUE }
                                .thenBy { it.id })
                        if (wasAtLatest) autoScrollToLatest = true
                        if (incoming.isNotEmpty()) readEpoch++
                    }
                    // Member names, group title and participant list may also change.
                    page = fresh
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // A transient poll failure should not erase currently displayed history.
                }
            }
        }
    }
    // Scroll only after the newly merged rows have actually been measured.
    LaunchedEffect(autoScrollToLatest, visibleMessages.size) {
        if (autoScrollToLatest) {
            val count = visibleMessages.size + 1 // status/pagination row
            snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it >= count }
            scroll.animateScrollToItem((count - 1).coerceAtLeast(0))
            autoScrollToLatest = false
        }
    }
    // The marker is dismissed when it has actually scrolled above the viewport,
    // not merely because a new message became visible at the bottom of the list.
    LaunchedEffect(newMessagesDividerId, visibleMessages.size) {
        val markerId = newMessagesDividerId ?: return@LaunchedEffect
        val markerIndex = visibleMessages.indexOfFirst { it.id == markerId }
        if (markerIndex < 0) { newMessagesDividerId = null; return@LaunchedEffect }
        snapshotFlow { scroll.firstVisibleItemIndex }.first { it > markerIndex + 1 }
        newMessagesDividerId = null
    }

    // Spotify's APK: mark a hierarchy seen once the reader has reached the
    // latest messages. If the user is browsing older messages, hold the unread
    // state until they actually scroll to the bottom.
    LaunchedEffect(hierarchyId, subpage, initialScrollCompleted, nearBottom, readEpoch) {
        if (subpage != 0 || !initialScrollCompleted || !nearBottom || loading ||
            markingSeen || acknowledgedReadEpoch >= readEpoch) return@LaunchedEffect
        markingSeen = true
        try {
            val result = graph.rpc.call<SendTextResult>("catalog.markChatSeen", buildJsonObject {
                put("hierarchyId", hierarchyId)
            })
            if (result.acknowledged) {
                acknowledgedReadEpoch = readEpoch
                ChatUnreadStates.counts[hierarchyId] = 0
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Do not clear badges on a failed or uncertain acknowledgement.
        } finally { markingSeen = false }
    }

    // Shared reaction handler, used by the quick toolbar, expanded picker and
    // a tap on your own row in the Reactions details sheet.
    val setTapback: (HistoryMessage, String) -> Unit = { target, emoji ->
        if (!reacting && target.id.isNotBlank()) {
            val current = activitiesByParent[target.id].orEmpty().firstOrNull {
                it.activityType == "reaction" && it.activityInitiator == selfUsername
            }
            if (current != null && current.reactionEmoji != emoji) {
                navigator.showMessage("Remove your existing reaction before choosing another")
            } else {
                reacting = true
                selectedMessageId = null
                emojiPickerMessageId = null
                reactionDetailsId = null
                scope.launch {
                    try {
                        val receipt = graph.rpc.call<SendTextResult>(
                            "catalog.setMessageReaction", buildJsonObject {
                                put("hierarchyId", hierarchyId)
                                put("nodePathId", target.id)
                                put("emoji", emoji)
                                current?.let { put("removeActivityId", it.id) }
                            })
                        if (receipt.acknowledged) {
                            requestedToken = ""
                            refresh++
                        } else navigator.showMessage("Reaction not confirmed. Refresh before retrying.")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (e: Exception) {
                        navigator.showMessage((e.message?.take(140) ?: "Reaction status unknown") +
                            ". Refresh before retrying.")
                    } finally { reacting = false }
                }
            }
        }
    }

    val outsideTapSource = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxSize().imePadding().clickable(
        enabled = selectedMessageId != null,
        interactionSource = outsideTapSource,
        indication = null,
    ) { selectedMessageId = null }) {
        CenterAlignedTopAppBar(
            title = {
                if (subpage != 0) {
                    Text(if (subpage == 1) "Chat details" else "People",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                } else {
                    Column(Modifier.clickable { selectedMessageId = null; subpage = 1 },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isDirectChat && directPeer != null) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                ChatAvatar(directPeer.avatarUrl, directPeer.displayName, 32.dp)
                                Text(directPeer.displayName.removeSuffix(" (you)"),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Share the exact group collage used by Messages and New Message.
                                if (participants.isNotEmpty()) {
                                    MessageGroupAvatar(participants.map {
                                        ChatParticipant(it.username, it.displayName, it.avatarUrl)
                                    }, 30.dp)
                                }
                                Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            if ((page?.groupMemberCount ?: 0) > 2) {
                                val pending = page?.pendingMemberCount ?: 0
                                val active = 1 + (page?.activeOtherMemberCount ?: 0)
                                Text(if (pending > 0)
                                    "$active ${if (active == 1) "person" else "people"} ($pending pending)"
                                    else "${page?.groupMemberCount} members · Chat details",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = {
                    when (subpage) {
                        2 -> subpage = 1
                        1 -> subpage = 0
                        else -> navigator.back()
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                if (subpage == 0) {
                    val activePeerInvite = if (isDirectChat) peerJamUrl else null
                    Row(Modifier.clickable {
                        if (activePeerInvite != null) {
                            incomingJoinUrl = activePeerInvite
                            joinDiscoveryMethod = "listening_activity"
                            listenOnThisPhone = true
                            askToJoinFeedback = null
                        } else {
                            jamEntryMode = -1
                            showJam = true
                        }
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.Headphones,
                            contentDescription = if (activePeerInvite != null) "Join Jam" else "Jam",
                            tint = if (activePeerInvite != null) Color(0xFF1ED760)
                                else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp))
                        if (activePeerInvite != null) {
                            Text("Jam", color = Color(0xFF1ED760),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (subpage == 1) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Chat settings")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        when (subpage) {
            0 -> {
                if (currentlyListening.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clickable { showActivity = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "▮▮▮", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(currentlyListening.take(2).joinToString(", ") { it.name } +
                                (if (currentlyListening.size > 2) " +${currentlyListening.size - 2}" else "") +
                                " listening", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                            maxLines = 1)
                        Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (participants.size > 1) {
                    TextButton(
                        onClick = { showActivity = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Listening activity  ⌄") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().pointerInput(hierarchyId) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, dx ->
                                    totalDrag += dx
                                    if (totalDrag < -with(density) { 22.dp.toPx() }) revealMessageTimes =
                                        true
                                },
                                onDragEnd = { totalDrag = 0f; revealMessageTimes = false },
                                onDragCancel = { totalDrag = 0f; revealMessageTimes = false },
                            )
                        },
                        state = scroll,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item {
                            if (pendingInvite) {
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // The native service is Spotify Campfire. This notice describes
                                    // Spotify's review policy, not moderation performed by our app.
                                    Text(
                                        "To help keep your chats safe, Spotify may review messages " +
                                                "that are reported or violate its policies. See Spotify's " +
                                                "Platform Rules and Terms of Use.",
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                                    )
                                    Spacer(Modifier.height(15.dp))
                                    if (participants.isNotEmpty()) {
                                        MessageGroupAvatar(participants.map {
                                            ChatParticipant(it.username, it.displayName, it.avatarUrl)
                                        }, 66.dp)
                                    }
                                    Text(
                                        currentTitle,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val pendingCount = page?.pendingMemberCount ?: 0
                                    val activeCount = 1 + (page?.activeOtherMemberCount ?: 0)
                                    Text(
                                        "Group · $activeCount ${if (activeCount == 1) "person" else "people"} " +
                                                "($pendingCount pending)",
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                            if (nextToken != null) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    OutlinedButton(
                                        onClick = { nextToken?.let { requestedToken = it } },
                                        enabled = !loading && !loadingOlder
                                    ) {
                                        Text(if (loadingOlder) "Loading…" else "Load older messages")
                                    }
                                }
                            }
                            if (loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            error?.let {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "History unavailable: $it",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Button(onClick = { refresh++ }) { Text("Try again") }
                                }
                            }
                            if (!loading && error == null && messages.isEmpty()) {
                                Text(
                                    "No messages were returned for this conversation.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(visibleMessages.size, key = { i -> visibleMessages[i].id }) { index ->
                            val message = visibleMessages[index]
                            if (message.id == newMessagesDividerId) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    HorizontalDivider(
                                        Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "New Messages", color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    HorizontalDivider(
                                        Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            val previous = visibleMessages.subList(0, index)
                                .lastOrNull { it.kind != "activity" }
                            val day = messageDay(message.occurredAtMs)
                            if (message.kind != "activity" && day != null &&
                                (previous == null || messageDay(previous.occurredAtMs) != day)
                            ) {
                                Text(
                                    if (pendingInvite && previous == null && message.occurredAtMs != null) {
                                        val time = Instant.ofEpochMilli(message.occurredAtMs)
                                            .atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("h:mm a", locale))
                                        "${dayHeading(message.occurredAtMs)} $time"
                                    } else dayHeading(message.occurredAtMs),
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            MessageBubble(
                                message, sharedDetails[message.shareUri],
                                participants = participants,
                                isDirectChat = (page?.groupMemberCount ?: participants.size) == 2,
                                attachedActivities = activitiesByParent[message.id].orEmpty(),
                                revealTime = revealMessageTimes,
                                onOpenUri = navigator::openUri,
                                onJamInvitationTap = ::openJamInvitation,
                                onActions = { media ->
                                    shareOptionsTarget(media)?.let { navigator.showActions(it) }
                                        ?: navigator.showMessage("Details aren't available for this share yet")
                                },
                                onQueue = { media ->
                                    when (media.kind) {
                                        "track" -> runner.addToQueue(listOf(media.canonicalUri))
                                        "album" -> runner.addCollectionToQueue(media.canonicalUri) {
                                            graph.catalog.album(media.canonicalUri)
                                                .awaitData()?.tracks.orEmpty()
                                                .filter { it.playable }.map { it.uri }
                                        }

                                        "playlist" -> runner.addCollectionToQueue(media.canonicalUri) {
                                            graph.catalog.playlistItemUris(media.canonicalUri)
                                        }
                                    }
                                },
                                onAdd = { uri -> navigator.addToPlaylist(listOf(uri)) },
                                onShare = { uri ->
                                    // Externally shared Spotify URL; uses the standard Android chooser.
                                    val url = canonicalSharedMedia(uri)?.second?.let { canonical ->
                                        "https://open.spotify.com/" + canonical.substringAfter("spotify:")
                                            .replace(':', '/')
                                    } ?: uri
                                    runCatching {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, url)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                send,
                                                "Share Spotify link"
                                            )
                                        )
                                    }.onFailure { navigator.showMessage("No share target is available") }
                                },
                                optionsVisible = selectedMessageId == message.id,
                                onMessageTap = {
                                    val shouldOpen = selectedMessageId == null
                                    if (selectedMessageId != null && selectedMessageId != message.id) {
                                        selectedMessageId = null
                                    }
                                    shouldOpen
                                },
                                onLongPress = {
                                    selectedMessageId =
                                        if (selectedMessageId == message.id) null else message.id
                                },
                                onReactionAttempt = { emoji ->
                                    if (emoji == "more") {
                                        emojiPickerMessageId = message.id
                                        selectedMessageId = null
                                    } else setTapback(message, emoji)
                                },
                                onCopy = {
                                    message.text?.let { copiedText ->
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(
                                                    ClipData.newPlainText(
                                                        "Message",
                                                        copiedText
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    selectedMessageId = null
                                },
                                onReactionDetails = { reactionDetailsId = message.id },
                                onDelete = {
                                    if (!deleting && message.id.isNotBlank() && message.kind == "contribution") {
                                        deleteTarget = message
                                        selectedMessageId = null
                                    }
                                },
                            )
                        }
                    }
                    ChatJumpToLatest(
                        visible = messages.isNotEmpty() && !nearBottom,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                        onClick = {
                            scope.launch {
                                val last = (scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                scroll.animateScrollToItem(last)
                                newMessagesDividerId = null
                            }
                        },
                    )
                }
                HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        // The mini-player is drawn above the chat by MainScaffold, not inside it.
                        // With pending invitations the editor may disappear entirely, leaving only
                        // this explanatory footer. Reserve a minimum player-safe bottom area so
                        // that the footer cannot be covered by the floating now-playing card.
                        // Keep the normal composer position and the IME layout unchanged.
                        .padding(
                            bottom = when {
                                keyboardVisible -> 4.dp
                                pendingInvite -> maxOf(
                                    contentPadding.calculateBottomPadding() + 4.dp,
                                    112.dp
                                )

                                else -> contentPadding.calculateBottomPadding() + 4.dp
                            }
                        )
                ) {
                    if (pendingInvite) {
                        Column(
                            Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                "Message request sent",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                if (pendingSendLimitReached)
                                    "You'll be able to share and chat more with everyone once at least " +
                                            "one person accepts your message request."
                                else "You can send up to two messages while you wait for people to accept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 15.dp)
                            )
                        }
                    }
                    sendWarning?.let { warning ->
                        Text(
                            warning, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    if (!pendingSendLimitReached) {
                        Row(
                            Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    attachedMusic?.let { music ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(
                                                start = 10.dp, top = 8.dp,
                                                end = 4.dp, bottom = 2.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            ChatSharedArtwork(attachmentArtwork, 48.dp)
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    music.name, maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    "Spotify ${music.type.name.lowercase()}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = { attachedMusic = null },
                                                enabled = !sending, modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Close,
                                                    contentDescription = "Remove attached media"
                                                )
                                            }
                                        }
                                    }
                                    attachedSticker?.let { lyric ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            ChatLyricArtwork(lyric, Modifier.size(62.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(lyric.lyricsText.ifBlank { lyric.altText.ifBlank { "Lyric sticker" } },
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall)
                                                Text("Lyric sticker", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall)
                                            }
                                            IconButton(onClick = { attachedSticker = null },
                                                enabled = !sending) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Remove lyric sticker")
                                            }
                                        }
                                    }
                                    attachedGif?.let { gif ->
                                        // Show the GIF as the full-width composer attachment, not
                                        // a tiny thumbnail with a provider-label block.
                                        Box(
                                            Modifier.fillMaxWidth().padding(6.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                        ) {
                                            ChatAnimatedGif(
                                                id = gif.id,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(1.55f),
                                                description = "Selected animated GIF",
                                            )
                                            IconButton(
                                                onClick = { attachedGif = null },
                                                enabled = !sending,
                                                modifier = Modifier.align(Alignment.TopEnd)
                                                    .padding(5.dp).size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            ) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Remove GIF")
                                            }
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BasicTextField(
                                            value = draft, onValueChange = {
                                                if (it.toByteArray(Charsets.UTF_8).size <= 4096) {
                                                    draft = it
                                                    sendWarning = null
                                                }
                                            }, enabled = !sending, minLines = 1, maxLines = 6,
                                            modifier = Modifier.weight(1f).padding(
                                                start = 14.dp,
                                                top = 12.dp, bottom = 12.dp, end = 2.dp
                                            ),
                                            textStyle = TextStyle(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 15.sp, lineHeight = 20.sp
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            decorationBox = { innerField ->
                                                Box {
                                                    if (draft.isEmpty()) Text(
                                                        "Write a message…",
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 15.sp
                                                    )
                                                    innerField()
                                                }
                                            })
                                        IconButton(
                                            onClick = { showMusicPicker = true },
                                            enabled = !sending, modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Add,
                                                contentDescription = "Attach Spotify music"
                                            )
                                        }
                                        IconButton(
                                            onClick = { showStickers = true },
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.EmojiEmotions,
                                                contentDescription = "Stickers and GIFs"
                                            )
                                        }
                                    }
                                }
                            }
                            ChatSendButton(
                                visible = draft.isNotBlank() || attachedMusic != null || attachedGif != null || attachedSticker != null,
                                enabled = !sending,
                                sending = sending,
                                onClick = {
                                    val music = attachedMusic
                                    val gif = attachedGif
                                    val sticker = attachedSticker
                                    val submitted = draft.trim()
                                    if (music == null && gif == null && sticker == null && submitted.isBlank()) return@ChatSendButton
                                    if (pendingInvite && messagesSentWhilePending == 1 &&
                                        (music != null || gif != null || sticker != null) && submitted.isNotBlank()
                                    ) {
                                        sendWarning =
                                            "Only one message request remains. Remove the attachment or text to send one item."
                                        return@ChatSendButton
                                    }
                                    sending = true
                                    sendWarning = null
                                    scope.launch {
                                        // Attachment first, then text. Stop after the first unconfirmed
                                        // submission; never repeat a POST to resolve an uncertain outcome.
                                        var confirmedAttachment = false
                                        var confirmedText = false
                                        try {
                                            if (gif != null) {
                                                val result = graph.rpc.call<SendTextResult>(
                                                    "catalog.sendRichMediaMessage", buildJsonObject {
                                                        put("hierarchyId", hierarchyId)
                                                        put("kind", "gif")
                                                        put("mediaId", gif.id)
                                                        put("ageRating", gif.ageRating)
                                                    })
                                                if (!result.acknowledged) {
                                                    sendWarning =
                                                        "GIF delivery is uncertain. Check the chat before retrying."
                                                    return@launch
                                                }
                                                confirmedAttachment = true
                                                if (attachedGif == gif) attachedGif = null
                                            } else if (sticker != null) {
                                                val result = graph.rpc.call<SendTextResult>(
                                                    "catalog.sendRichMediaMessage", buildJsonObject {
                                                        put("hierarchyId", hierarchyId)
                                                        put("kind", "sticker")
                                                        put("mediaId", sticker.stickerId)
                                                    })
                                                if (!result.acknowledged) {
                                                    sendWarning = "Sticker delivery is uncertain. Check the chat before retrying."
                                                    return@launch
                                                }
                                                confirmedAttachment = true
                                                if (attachedSticker?.stickerId == sticker.stickerId) attachedSticker = null
                                            } else if (music != null) {
                                                val result = graph.rpc.call<SendTextResult>(
                                                    "catalog.sendMusicMessage", buildJsonObject {
                                                        put("hierarchyId", hierarchyId)
                                                        put("uri", music.uri)
                                                    })
                                                if (!result.acknowledged) {
                                                    sendWarning =
                                                        "Music delivery is uncertain. Check the chat before retrying."
                                                    return@launch
                                                }
                                                confirmedAttachment = true
                                                if (attachedMusic?.uri == music.uri) attachedMusic = null
                                            }
                                            if (submitted.isNotBlank()) {
                                                val result = graph.rpc.call<SendTextResult>(
                                                    "catalog.sendTextMessage", buildJsonObject {
                                                        put("hierarchyId", hierarchyId)
                                                        put("text", submitted)
                                                    })
                                                if (!result.acknowledged) {
                                                    sendWarning =
                                                        "${if (confirmedAttachment) "Attachment sent. " else ""}Text delivery is uncertain. Check the chat before retrying."
                                                    return@launch
                                                }
                                                confirmedText = true
                                                // Don't discard text modified since tapping Send.
                                                if (draft.trim() == submitted) draft = ""
                                            }
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (e: Exception) {
                                            val prefix =
                                                if (confirmedAttachment) "Attachment sent. " else ""
                                            sendWarning = prefix + (e.message?.take(170)
                                                ?: "Unable to confirm delivery") + ". Check the chat before retrying."
                                        } finally {
                                            if (confirmedAttachment || confirmedText) {
                                                requestedToken = ""
                                                initialScrollCompleted = false
                                                refresh++
                                            }
                                            sending = false
                                        }
                                    }
                                },
                            )
                        }
                    }

                }
            }
            1 -> {
                HorizontalDivider()
                ChatDetailsContent(
                    currentTitle, page?.groupMemberCount ?: participants.size,
                    page?.pendingMemberCount ?: 0, page?.activeOtherMemberCount ?: 0,
                    participants, selfUsername,
                    archiveShares.ifEmpty { messages.filter { it.shareUri != null } },
                    sharedDetails,
                    sharesLoading = sharesLoading, sharesError = sharesError,
                    sharesMore = sharesNextToken != null,
                    onMoreShares = { if (!sharesLoading) sharesNextToken?.let { shareToken = it } },
                    onRetryShares = { if (!sharesLoading) shareRefresh++ },
                    onPeople = { if ((page?.groupMemberCount ?: participants.size) > 2) subpage = 2 },
                    onCreateGroup = { showCreateGroup = true },
                    onManagement = { showManagement = true },
                    muted = currentMuteState == true,
                    onMute = {
                        if (currentMuteState == true) unmute()
                        else {
                            muteError = null; showMute = true
                        }
                    },
                    onShare = navigator::openUri,
                    onMediaActions = { media -> shareOptionsTarget(media)?.let(navigator::showActions) },
                    onEdit = { navigator.showMessage("Group renaming is not enabled yet") },
                    // Keep the final Shares row scrollable above the floating now-playing card.
                    bottomClearance = maxOf(contentPadding.calculateBottomPadding() + 24.dp, 136.dp),
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                HorizontalDivider()
                ChatPeopleContent(participants, selfUsername, onPerson = { person ->
                    if (person.username.isNotBlank()) navigator.navigate(Route.Profile(person.username))
                }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (showActivity) {
        ModalBottomSheet(onDismissRequest = { showActivity = false }) {
            ConversationActivitySheet(
                participants = page?.participants.orEmpty(),
                isDirectChat = isDirectChat,
                directPeerUsername = directPeer?.username,
                onPlay = { friend ->
                    // Play a verified track; use the existing media route for other kinds.
                    if (friend.track.type == MediaType.TRACK) {
                        graph.player.play(PlayRequest(trackUris = listOf(friend.track.uri)))
                    } else navigator.open(friend.track)
                },
                onProfile = { friend ->
                    showActivity = false
                    navigator.navigate(Route.Profile(friend.username))
                },
                onDraftChatMessage = { message ->
                    // This is a normal composer draft, not a Spotify tracked-share reply.
                    draft = if (draft.isBlank()) message else draft.trimEnd() + "\n" + message
                    sendWarning = null
                    showActivity = false
                },
                onQueue = { friend ->
                    if (friend.track.type == MediaType.TRACK) {
                        runner.addToQueue(listOf(friend.track.uri))
                    } else navigator.showMessage("Queue is available for songs")
                },
                onAddToPlaylist = { friend ->
                    if (friend.track.type == MediaType.TRACK) {
                        navigator.addToPlaylist(listOf(friend.track.uri))
                    } else navigator.showMessage("Add to playlist is available for songs")
                },
                onSongActions = { friend ->
                    if (friend.track.type != MediaType.TRACK) {
                        navigator.open(friend.track)
                    } else scope.launch {
                        try {
                            val track = graph.catalog.tracks(listOf(friend.track.uri)).firstOrNull()
                            if (track != null) navigator.showActions(MediaActionTarget.TrackTarget(track))
                            else navigator.showMessage("Song actions are unavailable")
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            navigator.showMessage("Unable to load song actions")
                        }
                    }
                },
                onGoToChat = {
                    showActivity = false
                },
            )
        }
    }
    incomingJoinUrl?.let { invitation ->
        AlertDialog(
            onDismissRequest = { if (!askToJoinBusy) incomingJoinUrl = null },
            title = {
                Text("Want to Jam with ${directPeer?.displayName?.takeIf { it.isNotBlank() }
                    ?: directPeer?.username ?: "them"}?",
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose where to listen", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().clickable(enabled = !askToJoinBusy) {
                        listenOnThisPhone = true
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = listenOnThisPhone,
                            onClick = { listenOnThisPhone = true }, enabled = !askToJoinBusy)
                        Text("Listen on this phone")
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !askToJoinBusy) {
                        listenOnThisPhone = false
                    }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !listenOnThisPhone,
                            onClick = { listenOnThisPhone = false }, enabled = !askToJoinBusy)
                        Text("Listen on their device")
                    }
                    askToJoinFeedback?.let { feedback ->
                        Text(feedback, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(enabled = !askToJoinBusy, onClick = {
                    if (!askToJoinBusy) {
                        askToJoinBusy = true
                        askToJoinFeedback = null
                        val choice = listenOnThisPhone
                        scope.launch {
                            try {
                                val result = graph.rpc.call<JamApprovalRequestResult>(
                                    "catalog.jamAskToJoin", buildJsonObject {
                                        put("invitationUrl", invitation)
                                        put("listenOnThisPhone", choice)
                                        put("discoveryMethod", joinDiscoveryMethod)
                                        put("joinAttemptId", UUID.randomUUID().toString())
                                    })
                                when (result.result) {
                                    "pending" -> {
                                        incomingJoinUrl = null
                                        navigator.showMessage("Ask to Join sent. Waiting for host approval.")
                                    }
                                    "ended" -> {
                                        incomingJoinUrl = null
                                        showEndedJamDialog = true
                                    }
                                    "server-acknowledged" ->
                                        askToJoinFeedback = "Spotify accepted the request, but its pending status isn't confirmed."
                                    "request-rejected" ->
                                        askToJoinFeedback = "Spotify rejected the join request (HTTP ${result.httpStatus ?: "unknown"})."
                                    else -> askToJoinFeedback = "Could not confirm the request. Check before trying again."
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                askToJoinFeedback = "Could not confirm the request. Check before trying again."
                            } finally {
                                askToJoinBusy = false
                            }
                        }
                    }
                }) {
                    if (askToJoinBusy) CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Ask to Join")
                }
            },
            dismissButton = {
                TextButton(enabled = !askToJoinBusy,
                    onClick = { incomingJoinUrl = null }) { Text("Cancel") }
            },
        )
    }
    if (showEndedJamDialog) {
        AlertDialog(onDismissRequest = { showEndedJamDialog = false },
            containerColor = Color.White,
            title = {
                Text("The host has ended the Jam", color = Color.Black,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Ask them to start a new Jam, or start one yourself!",
                    color = Color.Black, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Box(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Button(onClick = { showEndedJamDialog = false },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1ED760), contentColor = Color.Black)) {
                        Text("Got it", fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {},
        )
    }
    if (showJam) {
        ModalBottomSheet(onDismissRequest = { showJam = false },
            containerColor = JamUi.background) {
            val user = graph.engine.user.collectAsStateWithLifecycle().value
            ChatJamStatusSheet(
                onDismiss = { showJam = false },
                conversationTitle = if (isDirectChat) directPeer?.displayName?.takeIf { it.isNotBlank() }
                    ?: currentTitle else currentTitle,
                hostName = user?.displayName?.takeIf { it.isNotBlank() }
                    ?: user?.username.orEmpty(),
                hostAvatarUrl = user?.images?.best(96),
                conversationAvatarUrl = if (isDirectChat) directPeer?.avatarUrl else null,
                onSessionChanged = { activeChatJam = it; JamGlobalState.session = it },
                onOpenQueue = { showJam = false; navigator.openQueue() },
                onFindSongs = { showJam = false; navigator.navigate(Route.Search) },
                onOpenLikedSongs = { showJam = false; navigator.navigate(Route.LikedSongs) },
                initialView = jamEntryMode,
                conversationId = hierarchyId,
                onInviteSent = { refresh++ },
            )
        }
    }
    deleteTarget?.let { selected ->
        if (deleteForAllConfirmation == null) {
            ModalBottomSheet(onDismissRequest = { if (!deleting) deleteTarget = null }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delete message", style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !deleting, onClick = { deleteForAllConfirmation = false }) {
                        Text("Delete for you")
                    }
                    if (selected.isMine) {
                        TextButton(enabled = !deleting, onClick = { deleteForAllConfirmation = true }) {
                            Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            }
        } else {
            val forAll = deleteForAllConfirmation == true
            AlertDialog(
                onDismissRequest = {
                    if (!deleting) { deleteForAllConfirmation = null; deleteTarget = null }
                },
                title = { Text(if (forAll) "Delete for everyone?" else "Delete for you?") },
                text = { Text(if (forAll) "This requests removal for everyone in the chat."
                    else "This requests removal from your view of this chat.") },
                confirmButton = {
                    TextButton(enabled = !deleting, onClick = {
                        deleting = true
                        scope.launch {
                            try {
                                val response = graph.rpc.call<DeleteMessageResult>(
                                    "catalog.deleteChatMessage", buildJsonObject {
                                        put("hierarchyId", hierarchyId)
                                        put("nodePathId", selected.id)
                                        put("deleteForAll", forAll)
                                    })
                                if (response.submitted) {
                                    if (!forAll) {
                                        // Hide the message and its attached activity immediately
                                        // after successful submission, and persist that view.
                                        val key = hiddenKey
                                        if (key != null) {
                                            val updated = locallyHidden + selected.id
                                            locallyHidden = updated
                                            hiddenPrefs.edit { putStringSet(key, updated) }
                                        }
                                    }
                                    requestedToken = ""
                                    refresh++
                                    navigator.showMessage(if (forAll)
                                        "Deletion requested for everyone; waiting for server confirmation."
                                        else "Deleted from your chat view.")
                                } else navigator.showMessage("Deletion was not confirmed. Check the chat before retrying.")
                            } catch (e: Exception) {
                                navigator.showMessage((e.message?.take(150)
                                    ?: "Deletion outcome unknown") + ". Check the chat before retrying.")
                            } finally {
                                deleting = false
                                deleteForAllConfirmation = null
                                deleteTarget = null
                            }
                        }
                    }) { Text(if (deleting) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(enabled = !deleting, onClick = {
                        deleteForAllConfirmation = null
                        deleteTarget = null
                    }) { Text("Cancel") }
                },
            )
        }
    }
    reactionDetailsId?.let { messageId ->
        ModalBottomSheet(onDismissRequest = { reactionDetailsId = null }) {
            val reacted = activitiesByParent[messageId].orEmpty()
                .filter { it.activityType == "reaction" }
                .distinctBy { it.activityInitiator ?: it.id }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reactions", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                if (reacted.isEmpty()) {
                    Text("No reactions available")
                } else reacted.forEach { reaction ->
                    val actor = participants.firstOrNull { it.username == reaction.activityInitiator }
                    val mine = reaction.activityInitiator == selfUsername
                    val original = messages.firstOrNull { it.id == messageId }
                    Row(Modifier.fillMaxWidth()
                        .clickable(enabled = mine && !reacting && original != null) {
                            if (original != null) setTapback(original, reaction.reactionEmoji ?: "")
                        }
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatAvatar(actor?.avatarUrl ?: reaction.senderAvatarUrl,
                            actor?.displayName ?: reaction.activityInitiator ?: "Member", 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(actor?.displayName ?: reaction.activityInitiator ?: "Member")
                            if (mine) Text(if (reacting) "Removing…" else "Tap to remove",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(reaction.reactionEmoji ?: "?", fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    emojiPickerMessageId?.let { messageId ->
        ModalBottomSheet(onDismissRequest = { emojiPickerMessageId = null }) {
            ChatEmojiPicker(onSelect = { emoji ->
                messages.firstOrNull { it.id == messageId }?.let { target ->
                    setTapback(target, emoji)
                }
                emojiPickerMessageId = null
            })
        }
    }
    if (showMusicPicker) {
        ModalBottomSheet(onDismissRequest = { showMusicPicker = false }) {
            ChatMusicPicker(onSelect = { item ->
                attachedMusic = item
                attachedGif = null
                attachedSticker = null
                sendWarning = null
                showMusicPicker = false
            })
        }
    }
    if (showStickers) {
        ModalBottomSheet(onDismissRequest = { showStickers = false }) {
            ChatStickerPicker(
                onSelectSticker = { sticker ->
                    // Catalog IDs are the only accepted lyric-sticker attachment source.
                    attachedSticker = sticker
                    attachedGif = null
                    attachedMusic = null
                    sendWarning = null
                    showStickers = false
                },
                onSelectGif = { media ->
                    // The native rich-media sender takes a GIPHY ID and its verified rating.
                    // Selecting media only composes an attachment; it never sends automatically.
                    val selection = GiphyMessagePickerActivity.supportedSelection(media)
                    if (selection == null) {
                        navigator.showMessage("GIPHY didn't return a supported GIF ID and rating")
                    } else {
                        attachedGif = GifAttachment(selection.first, selection.second)
                        attachedMusic = null
                        attachedSticker = null
                        sendWarning = null
                        showStickers = false
                    }
                },
                onOpenGifPicker = {
                    showStickers = false
                    giphyLauncher.launch(Intent(context, GiphyMessagePickerActivity::class.java))
                },
            )
        }
    }
    if (showMute) {
        ModalBottomSheet(onDismissRequest = { if (!muteBusy) showMute = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("Mute notifications", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                HorizontalDivider()
                listOf("1 hour" to 3600L, "8 hours" to 28800L,
                    "1 day" to 86400L, "1 week" to 604800L).forEach { (label, seconds) ->
                    TextButton(enabled = !muteBusy, onClick = {
                        muteBusy = true
                        muteError = null
                        scope.launch {
                            try {
                                graph.rpc.call<Boolean>("catalog.muteChat", buildJsonObject {
                                    put("hierarchyId", hierarchyId)
                                    put("durationSeconds", seconds)
                                })
                                ChatMuteStates.statuses[hierarchyId] = true
                                showMute = false
                                navigator.showMessage("Chat muted for $label")
                            } catch (e: Exception) {
                                muteError = e.message?.take(140) ?: "Unable to mute chat"
                            } finally { muteBusy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                            textAlign = TextAlign.Start)
                    }
                }
                // An absent protobuf duration may mean a permanent mute or the
                // server default. Don't send it without mapping that behavior.
                Text("Always (requires protocol verification)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp))
                muteError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)) }
            }
        }
    }
    if (showManagement) {
        val other = participants.firstOrNull { it.username != selfUsername }
        val direct = (page?.groupMemberCount ?: participants.size) == 2
        ModalBottomSheet(onDismissRequest = { showManagement = false }) {
            ReadOnlyChatOptions(
                isDirectChat = direct,
                muted = currentMuteState == true,
                canOpenProfile = other?.username?.isNotBlank() == true,
                onMute = {
                    showManagement = false
                    if (currentMuteState == true) unmute()
                    else { muteError = null; showMute = true }
                },
                onProfile = {
                    showManagement = false
                    other?.username?.takeIf { it.isNotBlank() }?.let {
                        navigator.navigate(Route.Profile(it))
                    }
                },
                onDelete = {
                    showManagement = false
                    showDeleteChatConfirmation = true
                },
                onLeave = {
                    showManagement = false
                    pendingLeaveMode = false
                },
                onLeaveAndDelete = {
                    showManagement = false
                    pendingLeaveMode = true
                },
            )
        }
    }
    pendingLeaveMode?.let { deleteHistory ->
        AlertDialog(
            onDismissRequest = { if (!leavingGroup) pendingLeaveMode = null },
            title = { Text(if (deleteHistory) "Leave and delete this group?" else "Leave this group?") },
            text = { Text(if (deleteHistory)
                "You will leave the group and request deletion of its chat history from your account. Other members keep their messages."
                else "You will leave the group but keep its previous messages in read-only form.") },
            confirmButton = {
                TextButton(enabled = !leavingGroup, onClick = {
                    if (!leavingGroup) {
                        leavingGroup = true
                        scope.launch {
                            try {
                                val result = graph.rpc.call<ChatDeleteRequestResult>(
                                    "catalog.leaveGroupChat", buildJsonObject {
                                        put("hierarchyId", hierarchyId)
                                        put("deleteHistory", deleteHistory)
                                    })
                                if (result.submitted) {
                                    pendingLeaveMode = null
                                    ChatUnreadStates.counts.remove(hierarchyId)
                                    navigator.showMessage("Leave request accepted by Spotify")
                                    navigator.back()
                                } else navigator.showMessage("Leave request was not accepted")
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                navigator.showMessage((e.message ?: "Leave outcome unknown").take(190))
                            } finally { leavingGroup = false }
                        }
                    }
                }) { Text(if (leavingGroup) "Leaving…" else "Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !leavingGroup, onClick = { pendingLeaveMode = null }) { Text("Cancel") }
            },
        )
    }
    if (showDeleteChatConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!deletingChat) showDeleteChatConfirmation = false },
            title = { Text("Delete this chat?") },
            text = { Text("This requests removal of the conversation from your account. It will not delete messages for other members.") },
            confirmButton = {
                TextButton(enabled = !deletingChat, onClick = {
                    if (!deletingChat) {
                        deletingChat = true
                        scope.launch {
                            try {
                                val result = graph.rpc.call<ChatDeleteRequestResult>(
                                    "catalog.deleteChatForMe", buildJsonObject {
                                        put("hierarchyId", hierarchyId)
                                    })
                                if (result.submitted) {
                                    ChatUnreadStates.counts.remove(hierarchyId)
                                    showDeleteChatConfirmation = false
                                    navigator.showMessage("Delete chat request submitted")
                                    navigator.back()
                                } else navigator.showMessage("Chat deletion was not accepted")
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (e: Exception) {
                                navigator.showMessage((e.message ?: "Could not confirm deletion").take(160))
                            } finally { deletingChat = false }
                        }
                    }
                }) { Text(if (deletingChat) "Deleting…" else "Delete chat", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deletingChat,
                onClick = { showDeleteChatConfirmation = false }) { Text("Cancel") } },
        )
    }
    if (showCreateGroup) {
        val includedPeer = participants.firstOrNull { it.username != selfUsername }
        NewMessageSheet(
            initialRecipients = listOfNotNull(includedPeer?.let {
                ChatParticipant(it.username, it.displayName, it.avatarUrl)
            }),
            requireGroup = true,
            onDismiss = { showCreateGroup = false },
            onOpenChat = { id, newTitle ->
                showCreateGroup = false
                navigator.navigate(Route.MessageConversation(id, newTitle))
            },
        )
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReadOnlyChatSettings()
        }
    }
}

/** Standalone animation: the editor naturally grows/shrinks into the button's width. */
@Composable
private fun ChatSendButton(
    visible: Boolean,
    enabled: Boolean,
    sending: Boolean,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(animationSpec = tween(190), expandFrom = Alignment.End) +
            fadeIn(animationSpec = tween(160)),
        exit = shrinkHorizontally(animationSpec = tween(190), shrinkTowards = Alignment.End) +
            fadeOut(animationSpec = tween(140)),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(52.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send message",
                tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** A standalone composable avoids ColumnScope.AnimatedVisibility leaking into a nested BoxScope. */
@Composable
private fun ChatJumpToLatest(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(180)),
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(48.dp).alpha(0.82f),
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Jump to latest message")
        }
    }
}

@Composable
private fun MessageBubble(
    message: HistoryMessage,
    media: ChatSharedMedia?,
    participants: List<HistoryParticipant>,
    isDirectChat: Boolean,
    attachedActivities: List<HistoryMessage>,
    revealTime: Boolean,
    onOpenUri: (String) -> Boolean,
    onActions: (ChatSharedMedia) -> Unit,
    onQueue: (ChatSharedMedia) -> Unit,
    onAdd: (String) -> Unit,
    onShare: (String) -> Unit,
    onJamInvitationTap: (String) -> Unit,
    onLongPress: () -> Unit,
    optionsVisible: Boolean,
    onMessageTap: () -> Boolean,
    onReactionAttempt: (String) -> Unit,
    onCopy: () -> Unit,
    onReactionDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    if (message.kind == "activity") {
        val actor = participants.firstOrNull { it.username == message.activityInitiator }
        val avatar = actor?.avatarUrl ?: message.senderAvatarUrl
        val name = actor?.displayName ?: message.senderName ?: "A member"
        val description = when (message.activityType) {
            "reaction" -> message.reactionEmoji?.takeIf { it.isNotBlank() }
                ?.let { "reacted $it" } ?: "reacted"
            "receipt" -> when (message.receiptType) {
                "played" -> "played"; "saved" -> "saved"
                "seen" -> "seen"; else -> "activity receipt"
            }
            "ack" -> "activity acknowledged"
            else -> "activity"
        }
        // Orphaned activity has no verified parent; align by its initiator only.
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 1.dp, bottom = 6.dp),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically) {
            if (!isDirectChat) {
                ChatAvatar(avatar, name, 19.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(if (isDirectChat) description else "$name · $description",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    // Only use system-event labels when the verified ChatEventType oneof
    // identifies the operation. Invitation recipients are not exposed by this
    // outer schema, so don't claim "you" was specifically invited.
    if (message.systemEventType != null ||
        (message.contentLabel in setOf("Chat event", "Chat invitation") &&
            message.shareUri == null && message.text.isNullOrBlank())) {
        val actor = message.senderName?.takeIf { it.isNotBlank() }
            ?: message.senderUsername ?: "A member"
        val detail = when (message.systemEventType) {
            "created" -> "$actor created the chat"
            "invited" -> "$actor invited a member to the chat"
            "joined" -> if (message.isMine) "You joined the chat" else "$actor joined the chat"
            "left" -> if (message.isMine) "You left the chat" else "$actor left the chat"
            "renamed" -> "$actor renamed the chat"
            "admin" -> "Chat administrator changed"
            "photo" -> "Chat photo changed"
            "removed" -> "A member was removed from the chat"
            "invite_link" -> "Chat invite link used"
            "reported" -> "Chat was reported"
            else -> message.contentLabel ?: "Chat event"
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(detail, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        return
    }
    val isMine = message.isMine
    // WindowInfo is observable and uses physical pixels; convert before sizing bubbles.
    val maxBubbleWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() * 0.75f
    }
    val locale = LocalConfiguration.current.locales[0]
    val timestampInset by animateDpAsState(
        targetValue = if (revealTime) 67.dp else 0.dp,
        animationSpec = tween(durationMillis = 180), label = "message-timestamp-shift",
    )
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 7.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(end = timestampInset),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!isMine && !isDirectChat) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChatAvatar(message.senderAvatarUrl, message.senderName ?: "?", 26.dp)
                    Text(message.senderName ?: "Participant", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (optionsVisible) {
                ChatReactionToolbar(onChoose = onReactionAttempt)
            }
            val jamInviteUrl = jamLinkFromMessage(message.text, message.shareUri)
            val context = LocalContext.current
            val shareUri = message.shareUri
            // GIF and sticker contributions are separate native rich-media variants.
            // Only provider IDs returned by Spotify are rendered, never arbitrary URLs.
            val safeGifId = message.gifId?.takeIf { id ->
                id.length in 3..128 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
            }
            if (jamInviteUrl != null) {
                JamConversationInviteCard(
                    onClick = { if (onMessageTap()) onJamInvitationTap(jamInviteUrl) },
                    onLongClick = onLongPress,
                )
            } else if (safeGifId != null) {
                // Full-bleed animated rich-media bubble; no static thumbnail or caption.
                Surface(
                    modifier = Modifier.fillMaxWidth(0.75f)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    ChatAnimatedGif(
                        id = safeGifId,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
                        description = "Animated GIF message",
                    )
                }
            } else if (message.stickerId != null) {
                Surface(modifier = Modifier.widthIn(max = maxBubbleWidth)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    ChatReceivedSticker(message.stickerId,
                        modifier = Modifier.fillMaxWidth(0.72f).padding(8.dp))
                }
            } else if (shareUri != null) {
                val kind = media?.kind ?: canonicalSharedMedia(shareUri)?.first
                if (isDirectChat && message.shareOrigin == "offplatform") {
                    Text("Shared off Spotify", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(0.75f).combinedClickable(
                        onClick = { if (onMessageTap()) onOpenUri(shareUri) },
                        onLongClick = onLongPress),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChatSharedArtwork(media, 82.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(media?.title ?: (message.contentLabel ?: "Shared music"),
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(media?.subtitle ?: (when (kind) {
                                    "album" -> "Album"
                                    "playlist" -> "Playlist"
                                    else -> "Open in Spotify Good"
                                }), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (kind in setOf("track", "album", "playlist") && media != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onQueue(media) }) { Text("Queue") }
                                TextButton(onClick = {
                                    if (kind == "track") onAdd(media.canonicalUri)
                                    else onActions(media)
                                }) { Text("Add") }
                                TextButton(onClick = {
                                    if (kind == "track") onOpenUri(media.canonicalUri)
                                    else onShare(media.canonicalUri)
                                }) { Text(if (kind == "track") "Play" else "Share") }
                                IconButton(onClick = { onActions(media) }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More media actions")
                                }
                            }
                        } else {
                            Text("Tap to open · Long press for message options",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Surface(modifier = Modifier.widthIn(max = maxBubbleWidth)
                    .combinedClickable(onClick = { onMessageTap() }, onLongClick = onLongPress),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isMine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(message.text ?: message.contentLabel ?: "Conversation event",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (attachedActivities.isNotEmpty()) {
                ReceiptSummary(attachedActivities, participants,
                    isDirectChat = isDirectChat,
                    onReactionDetails = onReactionDetails,
                    modifier = Modifier.padding(top = 1.dp))
            }
            if (optionsVisible) {
                ChatMessageOptions(
                    isMine = message.isMine,
                    canCopy = message.text != null,
                    onCopy = onCopy, onDelete = onDelete,
                )
            }
        }
        // A leftward drag reveals every message's original time only while held.
        // This is intentionally not a persistent timestamp toggle.
        if (message.occurredAtMs != null) {
            val clockTime = runCatching {
                Instant.ofEpochMilli(message.occurredAtMs).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("h:mm a", locale))
            }.getOrNull()
            AnimatedVisibility(visible = revealTime && clockTime != null,
                enter = fadeIn(tween(140)), exit = fadeOut(tween(140)),
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(clockTime.orEmpty(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Reaction chips live immediately beneath the message, before play/save receipts. */
@Composable
private fun ReceiptSummary(
    receipts: List<HistoryMessage>,
    participants: List<HistoryParticipant>,
    isDirectChat: Boolean,
    onReactionDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val reactions = receipts.filter { it.activityType == "reaction" }
            .distinctBy { it.activityInitiator ?: it.id }
        if (reactions.isNotEmpty()) {
            Row(Modifier.clickable(onClick = onReactionDetails)
                .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                reactions.mapNotNull { it.reactionEmoji }.distinct().take(6).forEach { emoji ->
                    Text(emoji, fontSize = 23.sp,
                        modifier = Modifier.clip(RoundedCornerShape(15.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 6.dp, vertical = 3.dp))
                }
                if (reactions.size > 1) {
                    Text(reactions.size.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        for (type in listOf("played", "saved")) {
            val actors = receipts.filter { it.activityType == "receipt" && it.receiptType == type }
                .distinctBy { it.activityInitiator ?: it.id }
            if (actors.isEmpty()) continue
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!isDirectChat) {
                    actors.take(6).forEach { receipt ->
                        val person = participants.firstOrNull { it.username == receipt.activityInitiator }
                        ChatAvatar(person?.avatarUrl ?: receipt.senderAvatarUrl,
                            person?.displayName ?: receipt.activityInitiator ?: "Member", 17.dp)
                    }
                    Spacer(Modifier.width(2.dp))
                }
                Icon(if (type == "played") Icons.Rounded.AccessTime else Icons.Rounded.CheckCircle,
                    contentDescription = null, modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (isDirectChat && actors.size == 1) type else "${actors.size} $type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChatReactionToolbar(onChoose: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(30.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        listOf("❤️", "👍", "😂", "🔥", "🥺", "👎").forEach { emoji ->
            Text(emoji, fontSize = 23.sp,
                modifier = Modifier.clickable { onChoose(emoji) }
                    .padding(horizontal = 2.dp, vertical = 2.dp))
        }
        Text("⊕", fontSize = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onChoose("more") })
    }
}

@Composable
private fun ChatMessageOptions(
    isMine: Boolean,
    canCopy: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.widthIn(min = 210.dp, max = 265.dp)) {
            if (canCopy) {
                Row(Modifier.fillMaxWidth().clickable { onCopy() }
                    .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Copy", modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                }
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onDelete)
                .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isMine) "Delete…" else "Delete for you",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null,
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Browses Spotify music to attach as a native tracked-share contribution. */
@Composable
private fun ChatMusicPicker(onSelect: (MediaRef) -> Unit) {
    val graph = rememberAppGraph()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var results by remember { mutableStateOf<List<MediaRef>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val allowed = setOf(MediaType.TRACK, MediaType.ALBUM, MediaType.PLAYLIST)
    LaunchedEffect(query) {
        loading = true
        error = null
        try {
            delay(if (query.isBlank()) 0.milliseconds else 260.milliseconds)
            results = if (query.isBlank()) graph.catalog.recentlyPlayed(40)
                .filter { it.type in allowed }.distinctBy { it.uri }
            else {
                val found = graph.search.search(query,
                    setOf(SearchType.TRACK, SearchType.ALBUM, SearchType.PLAYLIST), limit = 20)
                buildList {
                    found.tracks.forEach { track -> add(MediaRef(MediaType.TRACK, track.uri,
                        track.name, track.artists.joinToString(", ") { it.name },
                        track.album?.images.orEmpty())) }
                    found.albums.forEach { album -> add(MediaRef(MediaType.ALBUM, album.uri,
                        album.name, album.artists.joinToString(", ") { it.name }, album.images)) }
                    found.playlists.forEach { playlist -> add(MediaRef(MediaType.PLAYLIST,
                        playlist.uri, playlist.name,
                        playlist.owner?.displayName ?: playlist.owner?.username, playlist.images)) }
                }
            }
        } catch (e: Exception) {
            error = e.message?.take(100) ?: "Music isn't available right now"
        } finally { loading = false }
    }
    val selected = when (filter) {
        "Songs" -> MediaType.TRACK
        "Albums" -> MediaType.ALBUM
        "Playlists" -> MediaType.PLAYLIST
        else -> null
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search music") }, shape = RoundedCornerShape(18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("All", "Songs", "Playlists", "Albums").forEach { name ->
                FilterChip(selected = filter == name, onClick = { filter = name },
                    label = { Text(name) })
            }
        }
        Text(if (query.isBlank()) "Recently played" else "Search results",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            else -> {
                val filtered = results.filter { selected == null || it.type == selected }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(filtered, key = { it.uri }) { item ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(item) }
                            .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val art = item.images.best(120)
                            if (art != null) AsyncImage(art, contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)),
                                contentScale = ContentScale.Crop)
                            else Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.type.name.lowercase().replaceFirstChar(Char::uppercase)} · ${item.subtitle.orEmpty()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Add", modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        Text("Add music to compose a native Spotify share card.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

/** Displays catalog-provided imagery, falling back to official alt/lyric text. */
@Composable
private fun ChatLyricArtwork(sticker: ChatLyricSticker, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        val image = sticker.imageUrl
        if (image?.startsWith("https://") == true) {
            AsyncImage(model = image, contentDescription = sticker.altText.ifBlank { "Lyric sticker" },
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(sticker.lyricsText.ifBlank { sticker.altText.ifBlank { "Lyric sticker" } },
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp))
        }
    }
}

/** Resolve the actual server sticker by its ID; never fabricate the lyrics. */
@Composable
private fun ChatReceivedSticker(stickerId: String, modifier: Modifier = Modifier) {
    val graph = rememberAppGraph()
    var sticker by remember(stickerId) { mutableStateOf<ChatLyricSticker?>(null) }
    var unavailable by remember(stickerId) { mutableStateOf(false) }
    LaunchedEffect(stickerId, graph.rpc) {
        try {
            sticker = graph.rpc.call<ChatLyricSticker?>("catalog.lyricStickerDetails", buildJsonObject {
                put("stickerId", stickerId)
            })
            if (sticker == null) unavailable = true
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { unavailable = true }
    }
    if (sticker != null) ChatLyricArtwork(sticker!!, modifier)
    else Box(modifier.heightIn(min = 105.dp), contentAlignment = Alignment.Center) {
        if (unavailable) Text("Sticker unavailable", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/** Spotify stickers/v1 catalog: all results are server-selected IDs. */
@Composable
private fun ChatLyricStickerResults(
    search: String,
    onSelect: (ChatLyricSticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = rememberAppGraph()
    var catalog by remember { mutableStateOf(ChatStickerCatalog()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(search, retry, graph.rpc) {
        loading = true
        error = null
        try {
            catalog = if (search.isBlank()) {
                graph.rpc.call<ChatStickerCatalog>("catalog.lyricStickerSurface", buildJsonObject {})
            } else {
                graph.rpc.call<ChatStickerCatalog>("catalog.searchLyricStickers", buildJsonObject {
                    put("query", search)
                    put("pageSize", 30)
                })
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            catalog = ChatStickerCatalog()
            error = e.message?.take(180) ?: "Spotify lyric stickers are unavailable"
        } finally { loading = false }
    }
    val results = catalog.packs.flatMap { pack -> pack.stickers }
        .distinctBy { it.stickerId }
    Column(modifier) {
        if (loading && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                TextButton(onClick = { retry++ }) { Text("Retry") }
            }
        } else if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (search.isBlank()) "No lyric stickers were returned by Spotify."
                    else "No lyric stickers found", textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(results.size, key = { i -> results[i].stickerId }) { index ->
                    val sticker = results[index]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(sticker) }.padding(3.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        ChatLyricArtwork(sticker, Modifier.fillMaxWidth().aspectRatio(1.1f))
                        Text(sticker.lyricsText.ifBlank { sticker.altText.ifBlank { "Lyric sticker" } },
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Coil's coil-gif artifact enables animated GIF frame decoding for this AsyncImage. */
@Composable
private fun ChatAnimatedGif(id: String, modifier: Modifier, description: String) {
    AsyncImage(
        model = "https://media.giphy.com/media/$id/giphy.gif",
        contentDescription = description,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/** GIPHY offers an embeddable two-column grid; Spotify lyric stickers use a separate catalog. */
@Composable
private fun ChatStickerPicker(
    onSelectSticker: (ChatLyricSticker) -> Unit,
    onSelectGif: (Media) -> Unit,
    onOpenGifPicker: () -> Unit,
) {
    var tab by remember { mutableStateOf("GIF") }
    var query by remember { mutableStateOf("") }
    var committedQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val uriHandler = LocalUriHandler.current
    val sdkKey = BuildConfig.GIPHY_SDK_KEY
    LaunchedEffect(query) {
        delay(280.milliseconds)
        committedQuery = query.trim()
    }

    // Bounded sheet: selector stays visible while the GIF grid scrolls beneath it.
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tabs are outside the scrolled content and always remain visible.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Equal-width targets place icon/label centers at 25% and 75% of sheet width.
        Row(Modifier.fillMaxWidth().height(70.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Lyric stickers", "GIF").forEach { destination ->
                val selected = tab == destination
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        focusManager.clearFocus()
                        tab = destination
                        query = ""
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (destination == "GIF") Icons.Rounded.Gif else Icons.Rounded.Mic,
                        contentDescription = null, modifier = Modifier.size(24.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(destination, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (tab == "GIF") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search GIPHY") },
                shape = RoundedCornerShape(9.dp),
            )
            if (sdkKey.isBlank()) {
                // This is a build-time key. End users cannot repair a distributed
                // keyless APK by editing Android settings; the builder must rebuild.
                Column(
                    Modifier.fillMaxWidth().weight(1f)
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("GIPHY API key required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("This APK was built without a GIPHY Android SDK key. " +
                            "GIF browsing is optional; other messages still work.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("To enable GIFs when building Spotify Good:\n" +
                            "1. Create a GIPHY Android SDK key.\n" +
                            "2. Add giphySdkKey=YOUR_KEY to project-root local.properties.\n" +
                            "3. Rebuild and reinstall the APK.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        uriHandler.openUri("https://developers.giphy.com/dashboard/")
                    }) { Text("GIPHY Developer Dashboard") }
                }
            } else {
                ChatGiphyInlineGrid(
                    apiKey = sdkKey,
                    query = committedQuery,
                    onSelect = onSelectGif,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            // Keep the previous full-screen SDK picker as a fallback, not a giant green button.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Powered by GIPHY", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    enabled = sdkKey.isNotBlank(),
                    onClick = {
                        focusManager.clearFocus()
                        onOpenGifPicker()
                    },
                ) { Text("Full GIF browser", style = MaterialTheme.typography.labelSmall) }
            }
        } else {
            OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search lyric stickers") },
                shape = RoundedCornerShape(9.dp))
            ChatLyricStickerResults(
                search = committedQuery,
                onSelect = onSelectSticker,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

    }
}

/**
 * Bind the SDK Grid-Only callback by its published two-method contract. The listener's
 * binary interface has varied across SDK versions, so inspect its Java setter at runtime;
 * this keeps the existing SDK Dialog picker available if the grid callback changes.
 */
private fun installGiphyGridCallback(
    grid: GiphyGridView,
    onSelect: (Media) -> Unit,
    onResultCount: (Int) -> Unit,
): Boolean = runCatching {
    val setter = grid.javaClass.methods.firstOrNull { method ->
        method.name == "setCallback" && method.parameterCount == 1 &&
            method.parameterTypes[0].isInterface
    } ?: return@runCatching false
    val callbackType = setter.parameterTypes[0]
    val callback = Proxy.newProxyInstance(
        callbackType.classLoader, arrayOf(callbackType),
    ) { proxy, method, args ->
        when (method.name) {
            "didSelectMedia" -> (args?.firstOrNull() as? Media)?.let(onSelect)
            "contentDidUpdate" -> (args?.firstOrNull() as? Number)?.toInt()?.let(onResultCount)
            "toString" -> "SpotifyGoodGiphyGridCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    }
    setter.invoke(grid, callback)
    true
}.getOrDefault(false)

/** Uses GIPHY's own loader/caching rather than a manually constructed REST URL. */
@Composable
private fun ChatGiphyInlineGrid(
    apiKey: String,
    query: String,
    onSelect: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection by rememberUpdatedState(onSelect)
    var callbackAvailable by remember { mutableStateOf(true) }
    var resultCount by remember { mutableStateOf<Int?>(null) }
    val lastAppliedQuery = remember { arrayOf<String?>(null) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // GPHContent requires Giphy.configure before creating the first query.
                Giphy.configure(context.applicationContext, apiKey)
                val grid = GiphyGridView(context).apply {
                    direction = GiphyGridView.VERTICAL
                    spanCount = 2
                    cellPadding = 3
                    showCheckeredBackground = false
                    isNestedScrollingEnabled = true
                    callbackAvailable = installGiphyGridCallback(this,
                        onSelect = { media -> post { selection(media) } },
                        onResultCount = { count -> post { resultCount = count } })
                }
                // ModalBottomSheet also handles vertical gestures. Intercept them
                // only in the grid area so a swipe scrolls GIFs, not the sheet.
                object : FrameLayout(context) {
                    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return super.dispatchTouchEvent(ev)
                    }
                }.apply {
                    addView(
                        grid,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            update = { host ->
                val grid = host.getChildAt(0) as GiphyGridView
                if (lastAppliedQuery[0] != query) {
                    lastAppliedQuery[0] = query
                    resultCount = null
                    grid.content = if (query.isBlank()) GPHContent.trendingGifs
                        else GPHContent.searchQuery(query)
                }
            },
        )
        if (!callbackAvailable || resultCount == -1 || (resultCount == 0 && query.isNotBlank())) {
            Text(
                when {
                    !callbackAvailable -> "Inline GIF selection unavailable. Use Full GIF browser."
                    resultCount == -1 -> "GIPHY couldn't load GIFs. Check your connection or SDK key."
                    else -> "No GIFs found"
                },
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Reuse the same real group member list and listening card from the drawer and View More. */
@Composable
internal fun GroupConversationActivitySheet(
    participants: List<ChatParticipant>,
    onPlay: (FriendActivity) -> Unit,
    onProfile: (FriendActivity) -> Unit,
    onDraftChatMessage: (String) -> Unit,
    onQueue: (FriendActivity) -> Unit,
    onAddToPlaylist: (FriendActivity) -> Unit,
    onSongActions: (FriendActivity) -> Unit,
    onGoToChat: () -> Unit,
) {
    ConversationActivitySheet(
        participants = participants.map {
            HistoryParticipant(it.username, it.displayName, it.avatarUrl)
        },
        isDirectChat = false,
        directPeerUsername = null,
        onPlay = onPlay,
        onProfile = onProfile,
        onDraftChatMessage = onDraftChatMessage,
        onQueue = onQueue,
        onAddToPlaylist = onAddToPlaylist,
        onSongActions = onSongActions,
        onGoToChat = onGoToChat,
    )
}

/** A single ephemeral reply draft, scoped to the signed-in account and conversation. */
internal object PendingListeningChatDraft {
    private val pending = mutableMapOf<Pair<String, String>, String>()

    @Synchronized fun stage(account: String?, hierarchyId: String, text: String) {
        if (account.isNullOrBlank() || hierarchyId.isBlank() || text.isBlank()) return
        pending.clear()
        pending[account to hierarchyId] = text.take(4096)
    }

    @Synchronized fun take(account: String?, hierarchyId: String): String? {
        if (account.isNullOrBlank()) return null
        return pending.remove(account to hierarchyId)
    }
}

@Composable
private fun ConversationActivitySheet(
    participants: List<HistoryParticipant>,
    isDirectChat: Boolean,
    directPeerUsername: String?,
    onPlay: (FriendActivity) -> Unit,
    onProfile: (FriendActivity) -> Unit,
    onDraftChatMessage: (String) -> Unit,
    onQueue: (FriendActivity) -> Unit,
    onAddToPlaylist: (FriendActivity) -> Unit,
    onSongActions: (FriendActivity) -> Unit,
    onGoToChat: () -> Unit,
) {
    val graph = rememberAppGraph()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var activity by remember { mutableStateOf<List<FriendActivity>>(emptyList()) }
    var selectedUsername by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = !isDirectChat && selectedUsername != null) {
        selectedUsername = null
    }
    val usernames = participants.map { it.username }.filter { it.isNotBlank() }.toSet()
    LaunchedEffect(usernames, isDirectChat, refresh) {
        loading = true
        failed = false
        try {
            activity = if (isDirectChat) {
                // Two-person chats retain their original standalone listener card.
                graph.catalog.friendActivity().filter { it.username in usernames }
            } else if (usernames.size in 3..40) {
                // Spotify's official user-list activity service returns per-user
                // visibility for this authenticated session. Only request the
                // opened conversation's participants; do not fall back to a
                // global FOLLOWING feed when group member activity is empty.
                val byUsername = participants.associateBy { it.username }
                graph.catalog.groupMemberActivity(usernames.sorted()).map { row ->
                    val participant = byUsername[row.username]
                    row.copy(
                        name = participant?.displayName?.takeIf { it.isNotBlank() } ?: row.name,
                        image = participant?.avatarUrl ?: row.image,
                    )
                }
            } else emptyList()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
            activity = emptyList()
        } finally {
            loading = false
        }
    }
    // A group member's own listening history must not appear in their group list.
    // Wait for the signed-in identity instead of briefly exposing the self row.
    val accountUsername = graph.engine.user.collectAsStateWithLifecycle().value?.username
    val sorted = activity.filter { isDirectChat ||
        (accountUsername != null && it.username != accountUsername)
    }.sortedWith(compareByDescending<FriendActivity> { it.isActive == true }
        .thenByDescending { it.timestampMs }
        .thenBy { it.name.lowercase(Locale.ROOT) })
    // Direct chats open the one person's card. Group chats open the member list,
    // then that exact same card after tapping a person.
    val selected = if (isDirectChat) sorted.firstOrNull { it.username == directPeerUsername }
        else sorted.firstOrNull { it.username == selectedUsername }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 700.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isDirectChat || selectedUsername != null) {
            if (!isDirectChat) {
                TextButton(onClick = { selectedUsername = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Listening activity")
                }
            }
            when {
                loading && selected == null -> CircularProgressIndicator()
                failed && selected == null -> Text("Listening activity is unavailable. Try refreshing.")
                selected == null -> {
                    Text("Listening activity", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("No listening activity is currently available for this person.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> ConversationListeningCard(selected, isDirectChat,
                    onPlay = { onPlay(selected) },
                    onProfile = { onProfile(selected) },
                    onQueue = { onQueue(selected) },
                    onAddToPlaylist = { onAddToPlaylist(selected) },
                    onSongActions = { onSongActions(selected) },
                    onDraftChatMessage = onDraftChatMessage,
                    onGoToChat = onGoToChat)
            }
        } else {
            // Group member list only: compact refresh at the upper-right.
            // The individual listening card deliberately has no refresh control.
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Listening activity", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { refresh++ },
                    enabled = !loading,
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Rounded.Refresh,
                            contentDescription = "Refresh listening activity",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Activity shared by members of this conversation, where available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                loading && sorted.isEmpty() -> CircularProgressIndicator()
                failed && sorted.isEmpty() -> Text("Listening activity is unavailable right now.")
                sorted.isEmpty() -> Text("No shared listening activity is available for these members.")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sorted, key = { it.username }) { friend ->
                        Row(Modifier.fillMaxWidth().clickable { selectedUsername = friend.username }
                            .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val cover = friend.track.images.best(160)
                            if (cover != null) AsyncImage(cover, null,
                                Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop)
                            else Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(friend.name, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    // Share the exact green animated listening bars used by
                                    // the drawer's individual activity tiles.
                                    if (friend.isActive == true) LiveBars()
                                    Text(friend.track.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (friend.isActive == true) Color(0xFF1DB954)
                                            else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                }
                                Text(friend.track.subtitle.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "View activity",
                                modifier = Modifier.size(18.dp).rotate(180f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Detail presentation used by both direct and group messaging activity. */
@Composable
private fun ConversationListeningCard(
    friend: FriendActivity,
    isDirectChat: Boolean,
    onPlay: () -> Unit,
    onProfile: () -> Unit,
    onQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSongActions: () -> Unit,
    onDraftChatMessage: (String) -> Unit,
    onGoToChat: () -> Unit,
) {
    var reply by remember(friend.username, friend.track.uri) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().clickable(onClick = onProfile)
        .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChatAvatar(friend.image, friend.name, 46.dp)
        Column(Modifier.weight(1f)) {
            Text(friend.name, fontWeight = FontWeight.SemiBold)
            Text("Go to profile", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Surface(shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val cover = friend.track.images.best(240)
                if (cover != null) AsyncImage(cover, "Album artwork",
                    Modifier.size(66.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop)
                else Box(Modifier.size(66.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                Column(Modifier.weight(1f)) {
                    Text(friend.track.name, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(friend.track.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (friend.isActive == true) {
                        Text("Listening now", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall)
                    } else if (friend.timestampMs > 0) {
                        Text(android.text.format.DateUtils.getRelativeTimeSpanString(
                            friend.timestampMs, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = onSongActions) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Song options")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                // Song previews need Spotify's preview protocol; don't mistake
                // full playback for an audio preview.
                Spacer(Modifier.weight(1f))
                if (friend.track.type == MediaType.TRACK) {
                    IconButton(onClick = onQueue) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "Add song to queue")
                    }
                    IconButton(onClick = onAddToPlaylist) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add song to playlist")
                    }
                }
                IconButton(onClick = onPlay) {
                    Icon(Icons.Rounded.PlayArrow,
                        contentDescription = if (friend.track.type == MediaType.TRACK)
                            "Play song" else "Open media")
                }
            }
        }
    }
    OutlinedTextField(
        value = reply, onValueChange = { reply = it.take(4096) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(if (isDirectChat) "Message ${friend.name}…" else "Message the group…") },
        maxLines = 4, shape = RoundedCornerShape(22.dp),
    )
    AnimatedVisibility(
        visible = reply.isNotBlank(),
        enter = expandVertically(animationSpec = tween(190)) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(170)) + fadeOut(animationSpec = tween(140)),
    ) {
        Button(onClick = { if (reply.isNotBlank()) onDraftChatMessage(reply.trim()) },
            modifier = Modifier.fillMaxWidth()) {
            Text("Continue in chat")
        }
    }
    TextButton(onClick = onGoToChat, modifier = Modifier.fillMaxWidth()) {
        Text("Go to chat")
    }
}


/** A playlist with no uploaded cover uses Spotify Good's existing first-tracks mosaic rule. */
@Composable
private fun ChatSharedArtwork(media: ChatSharedMedia?, size: androidx.compose.ui.unit.Dp) {
    val covers = media?.mosaicUrls.orEmpty()
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        when {
            media?.coverUrl != null -> AsyncImage(media.coverUrl, contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            covers.size == 4 -> Column(Modifier.matchParentSize()) {
                for (row in 0..1) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0..1) {
                            AsyncImage(covers[row * 2 + col], contentDescription = null,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                contentScale = ContentScale.Crop)
                        }
                    }
                }
            }
            covers.isNotEmpty() -> AsyncImage(covers.first(), contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else -> Icon(Icons.Rounded.Headphones, contentDescription = null)
        }
    }
}

@Composable
private fun ChatAvatar(url: String?, name: String, size: androidx.compose.ui.unit.Dp) {
    val locale = LocalConfiguration.current.locales[0]
    if (url?.startsWith("https://") == true) {
        AsyncImage(url, contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(locale),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ChatDetailsContent(
    title: String, memberCount: Int, pendingMemberCount: Int, activeOtherMemberCount: Int,
    participants: List<HistoryParticipant>,
    selfUsername: String?, shares: List<HistoryMessage>,
    mediaByUri: Map<String, ChatSharedMedia>,
    sharesLoading: Boolean, sharesError: String?, sharesMore: Boolean,
    onMoreShares: () -> Unit, onRetryShares: () -> Unit,
    onPeople: () -> Unit, onCreateGroup: () -> Unit, onManagement: () -> Unit,
    onMute: () -> Unit, muted: Boolean,
    onShare: (String) -> Boolean, onMediaActions: (ChatSharedMedia) -> Unit,
    onEdit: () -> Unit, bottomClearance: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val sortedShares = shares.filter { it.shareUri != null }
        .sortedByDescending { it.occurredAtMs ?: 0L }
    val people = remember(participants, selfUsername) {
        orderedChatParticipants(participants, selfUsername)
    }
    LazyColumn(modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp,
            bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (memberCount <= 2) {
                val peer = people.firstOrNull { it.username != selfUsername }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChatAvatar(peer?.avatarUrl, peer?.displayName ?: title, 64.dp)
                    Text(peer?.displayName?.removeSuffix(" (you)") ?: title,
                        modifier = Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Surface(onClick = onEdit, shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("Edit", modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold)
                    }
                }
                val memberDescription = if (pendingMemberCount > 0) {
                    val accepted = 1 + activeOtherMemberCount
                    "Group · $accepted ${if (accepted == 1) "person" else "people"} ($pendingMemberCount pending)"
                } else "Group · $memberCount people"
                Text(memberDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Same ordering as People: signed-in member first, then names A-Z.
                // The extra-count and add circles belong to the same overlapping stack.
                if (memberCount > 2) {
                val shown = people.take(2)
                val extra = (people.size - shown.size).coerceAtLeast(0)
                val circleCount = shown.size + (if (extra > 0) 1 else 0) + 1
                Box(
                    Modifier.width((36 + (circleCount - 1) * 23).dp)
                        .height(36.dp)
                        .clickable(onClick = onPeople),
                ) {
                    shown.forEachIndexed { index, person ->
                        Box(Modifier.offset(x = (index * 23).dp)
                            .size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center) {
                            ChatAvatar(person.avatarUrl, person.displayName, 34.dp)
                        }
                    }
                    if (extra > 0) {
                        Box(Modifier.offset(x = (shown.size * 23).dp)
                            .size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text("+$extra", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.offset(x = ((circleCount - 1) * 23).dp)
                        .size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.background, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, contentDescription = "View chat people",
                            modifier = Modifier.size(19.dp))
                    }
                }
                }
                if (memberCount <= 2) {
                    OutlinedButton(onClick = onCreateGroup,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                        Icon(Icons.Rounded.People, contentDescription = null,
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Create Group Chat", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(onClick = onMute,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.NotificationsOff, contentDescription = null,
                        modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (muted) "Unmute" else "Mute")
                }
                IconButton(onClick = onManagement, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More chat options")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Shares", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sortedShares.isEmpty() && !sharesLoading && sharesError == null) {
                Text("No shared items were returned by the Shares archive.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(sortedShares.size, key = { "detail-share-" + sortedShares[it].id }) { index ->
            val msg = sortedShares[index]
            val uri = msg.shareUri ?: return@items
            val media = mediaByUri[uri]
            val month = monthHeading(msg.occurredAtMs)
            val previousMonth = sortedShares.getOrNull(index - 1)?.let { monthHeading(it.occurredAtMs) }
            if (month.isNotBlank() && month != previousMonth) {
                Text(month, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            }
            Row(Modifier.fillMaxWidth().clickable { onShare(uri) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp)) {
                    ChatSharedArtwork(media, 48.dp)
                    if (msg.senderAvatarUrl != null) {
                        Box(Modifier.align(Alignment.BottomEnd)) {
                            ChatAvatar(msg.senderAvatarUrl, msg.senderName ?: "?", 18.dp)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(dayHeading(msg.occurredAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(media?.title ?: (msg.contentLabel ?: "Shared item"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    val detail = when (media?.kind) {
                        "track" -> "Song"
                        "album" -> "Album"
                        "playlist" -> "Playlist"
                        else -> "Share"
                    }
                    Text("$detail · ${media?.subtitle ?: msg.senderName ?: "Chat member"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (media != null) onMediaActions(media) else onShare(uri) }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Shared item options")
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            if (sharesLoading) CircularProgressIndicator(modifier = Modifier.padding(12.dp))
            sharesError?.let {
                Text("Shares archive unavailable: $it. Showing loaded-chat items if available.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = onRetryShares) { Text("Retry Shares") }
            }
            if (sharesMore && !sharesLoading) {
                OutlinedButton(onClick = onMoreShares) { Text("Load more shares") }
            }
        }
    }
}

/** One ordering rule for both the People page and the Chat Details avatar stack. */
private fun orderedChatParticipants(
    participants: List<HistoryParticipant>, selfUsername: String?,
): List<HistoryParticipant> = participants.sortedWith(
    compareBy<HistoryParticipant> {
        if (selfUsername != null && it.username == selfUsername) 0 else 1
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
)

@Composable
private fun ChatPeopleContent(
    participants: List<HistoryParticipant>,
    selfUsername: String?,
    onPerson: (HistoryParticipant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val people = remember(participants, selfUsername) {
        orderedChatParticipants(participants, selfUsername)
    }
    LazyColumn(modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(people, key = { it.username }) { person ->
            Row(Modifier.fillMaxWidth().clickable { onPerson(person) }
                    .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChatAvatar(person.avatarUrl, person.displayName, 44.dp)
                val visibleName = person.displayName.removeSuffix(" (you)")
                val suffix = if (selfUsername != null && person.username == selfUsername) " (you)" else ""
                Text(visibleName + suffix, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReadOnlyChatOptions(
    isDirectChat: Boolean, muted: Boolean, canOpenProfile: Boolean,
    onMute: () -> Unit, onProfile: () -> Unit, onDelete: () -> Unit,
    onLeave: () -> Unit, onLeaveAndDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Chat options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 14.dp))
        TextButton(onClick = onMute) { Text(if (muted) "Unmute Chat" else "Mute Chat") }
        if (isDirectChat) {
            TextButton(onClick = onProfile, enabled = canOpenProfile) { Text("Go to Profile") }
            // The trusted-circle hide endpoint is not an account-level Block User operation.
            TextButton(enabled = false, onClick = {}) { Text("Block User") }
        } else {
            TextButton(onClick = onLeave) { Text("Leave Chat") }
        }
        TextButton(onClick = onDelete) { Text("Delete Chat", color = MaterialTheme.colorScheme.error) }
        if (!isDirectChat) {
            TextButton(onClick = onLeaveAndDelete) {
                Text("Leave and Delete Chat", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ReadOnlyChatSettings() {
    Column(Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Chat settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Listening activity", fontWeight = FontWeight.SemiBold)
        Text("Choose who can see what music you're playing.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text("Played receipts", fontWeight = FontWeight.SemiBold)
        Text("Controls visibility when you play a shared item.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text("Saved receipts", fontWeight = FontWeight.SemiBold)
        Text("Controls visibility when you save a shared item.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text("Current setting values are not available through the history API. Controls will be enabled after their settings API is verified.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}
