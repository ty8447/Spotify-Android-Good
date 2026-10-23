@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.playback.JamQueueMoveOrigin
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.NowPlayingBars
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Visual language of Spotify's Jam sheets; all state comes from the existing native services. */
internal object JamUi {
    val background = Color(0xFF222222)
    val black = Color(0xFF111111)
    val row = Color(0xFF333333)
    val white = Color(0xFFFFFFFF)
    val muted = Color(0xFFB3B3B3)
    val green = Color(0xFF1ED760)
    val divider = Color(0xFF3A3A3A)
    val red = Color(0xFFF26B85)
}

@Composable
internal fun JamAvatar(url: String?, size: Int, contentDescription: String, letter: String = "♪") {
    Box(Modifier.size(size.dp).clip(CircleShape).background(JamUi.row), contentAlignment = Alignment.Center) {
        if (url?.startsWith("https://") == true) {
            AsyncImage(model = url, contentDescription = contentDescription,
                modifier = Modifier.size(size.dp).clip(CircleShape))
        } else {
            Text(letter.take(1).uppercase(), fontSize = (size / 2.5).sp,
                fontWeight = FontWeight.Bold, color = JamUi.white)
        }
    }
}

@Composable
private fun JamDarkButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                          textColor: Color = JamUi.white) {
    Surface(onClick = onClick, color = JamUi.row, shape = CircleShape, modifier = modifier) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(title, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun JamCircleAction(onClick: () -> Unit,
                            content: @Composable () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = JamUi.row,
        modifier = Modifier.size(46.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun JamLine() {
    HorizontalDivider(color = JamUi.divider, thickness = 1.dp)
}

/** Spotify-style invitation surface. The invite button stays disabled until a native message exists. */
@Composable
internal fun JamInvitePage(
    title: String, hostAvatarUrl: String?, peerAvatarUrl: String?,
    session: JamSessionSummary?, canStart: Boolean, startBusy: Boolean,
    onStart: () -> Unit, onViewJam: () -> Unit,
    onInvite: (() -> Unit)? = null, sendingInvite: Boolean = false,
    sendAfterStart: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().background(JamUi.background).heightIn(min = 390.dp)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((-10).dp),
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)) {
            JamAvatar(peerAvatarUrl, 58, "Conversation avatar")
            JamAvatar(hostAvatarUrl, 58, "Jam host avatar")
        }
        Text(if (session?.active == true) {
            if (title.isBlank()) "Invite friends to your Jam" else "Invite $title to your Jam"
        } else if (title.isBlank()) "Start a Jam" else "Start a Jam with $title",
            fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 29.sp,
            textAlign = TextAlign.Center, color = JamUi.white)
        Text(if (session?.active == true) {
            if (session.isSessionOwner == true) "You're already hosting a Jam. Want this group to join?"
            else "You're in a Jam. Inviting this group isn't available yet."
        } else "Listen together in real time.",
            color = JamUi.muted, fontSize = 16.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        if (session?.active == true) {
            Button(onClick = { onInvite?.invoke() }, enabled = onInvite != null && !sendingInvite, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = JamUi.green, contentColor = JamUi.black,
                    disabledContainerColor = JamUi.green.copy(alpha = .58f),
                    disabledContentColor = JamUi.black), modifier = Modifier.height(56.dp)) {
                Icon(Icons.Rounded.People, null)
                Spacer(Modifier.width(10.dp))
                Text(if (sendingInvite) "Sending…" else "Send invite", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(if (onInvite == null) "Open a conversation to send a Jam invitation." else "Sends your current Jam link directly to this chat.", color = JamUi.muted,
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            TextButton(onClick = onViewJam) { Text("View your Jam", color = JamUi.white) }
        } else {
            Button(onClick = onStart, enabled = canStart && !startBusy,
                shape = CircleShape, modifier = Modifier.height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JamUi.green, contentColor = JamUi.black)) {
                Icon(Icons.Rounded.Headphones, null)
                Spacer(Modifier.width(10.dp))
                Text(if (startBusy) "Starting…" else if (sendAfterStart) "Start Jam and invite" else "Start a Jam", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(if (sendAfterStart) "Starts a Jam, then sends an invitation link to this chat." else "Starting opens a Jam on your device; it does not send an invite.",
                color = JamUi.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The current track keeps its green bars when paused, but no longer animates. */
@Composable
internal fun JamPlayingBars(isPlaying: Boolean) {
    if (isPlaying) {
        NowPlayingBars(true, color = JamUi.green)
    } else {
        Canvas(Modifier.size(16.dp)) {
            val width = size.width * 0.20f
            for (i in 0..2) {
                drawRect(JamUi.green, topLeft = Offset(i * size.width * .34f, size.height - 2.dp.toPx()),
                    size = Size(width, 2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun JamQueueRow(track: PlaybackTrack, nowPlaying: Boolean, isPlaying: Boolean,
                        queued: Boolean, onOpenQueue: () -> Unit,
                        onDragShift: ((Int) -> Boolean)? = null,
                        onDragCommit: (() -> Unit)? = null,
                        onDragCancel: (() -> Unit)? = null,
                        onDragActive: ((Boolean) -> Unit)? = null,
                        movedByLocal: Boolean = false, hostAvatarUrl: String? = null) {
    var offset by remember(track.uid) { mutableFloatStateOf(0f) }
    var dragging by remember(track.uid) { mutableStateOf(false) }
    var rowHeight by remember(track.uid) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val defaultStep = with(density) { 66.dp.toPx() }
    val gap = with(density) { 4.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    // Pointer input survives recomposition while the other rows are reordered.
    val currentShift by rememberUpdatedState(onDragShift)
    val currentCommit by rememberUpdatedState(onDragCommit)
    val currentCancel by rememberUpdatedState(onDragCancel)
    val currentActive by rememberUpdatedState(onDragActive)
    Row(Modifier.fillMaxWidth().zIndex(if (dragging) 12f else 0f)
        .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(8.dp))
            .background(JamUi.row, RoundedCornerShape(8.dp)) else Modifier)
        .onSizeChanged { rowHeight = it.height.toFloat() }
        .graphicsLayer { translationY = offset }
        .clickable(onClick = onOpenQueue).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)).background(JamUi.row),
            contentAlignment = Alignment.Center) {
            if (track.imageUrl?.startsWith("https://") == true) {
                AsyncImage(model = track.imageUrl, contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(5.dp)))
            } else Icon(Icons.Rounded.Headphones, null, tint = JamUi.muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (nowPlaying) JamPlayingBars(isPlaying)
                Text(track.name.orEmpty().ifBlank { "Unknown track" },
                    color = if (nowPlaying) JamUi.green else JamUi.white,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (queued) Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Manually queued",
                    modifier = Modifier.size(14.dp), tint = JamUi.green)
                Text(track.artistLine, color = JamUi.muted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (nowPlaying) {
            Icon(Icons.Rounded.PlayArrow, null, tint = JamUi.white, modifier = Modifier.size(24.dp))
        } else if (onDragShift != null) {
            if (queued && movedByLocal) JamAvatar(hostAvatarUrl, 23, "Moved by you")
            Box(Modifier.size(40.dp).pointerInput(track.uid) {
                detectDragGestures(
                    onDragStart = { dragging = true; offset = 0f; currentActive?.invoke(true) },
                    onDragEnd = {
                        currentCommit?.invoke()
                        dragging = false; offset = 0f; currentActive?.invoke(false)
                    },
                    onDragCancel = {
                        currentCancel?.invoke()
                        dragging = false; offset = 0f; currentActive?.invoke(false)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        offset += amount.y
                        val step = (rowHeight.takeIf { it > 0f } ?: defaultStep) + gap
                        val direction = jamCrossedRow(offset, step, margin)
                        if (direction != 0 && currentShift?.invoke(direction) == true) {
                            // The new layout position compensates for the crossed row.
                            offset -= direction * step
                        }
                    },
                )
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, "Hold and drag to reorder", tint = JamUi.white)
            }
        }
    }
}

/** Only the queue rows scroll; title, Edit/Close, and the selection actions stay fixed.
 * A new Jam may not expose remote nextTracks until Connect publishes a queue snapshot.
 * We do not synthesize queued songs from a playlist or unrelated recommendations.
 */
@Composable
internal fun JamQueuePreview(
    onOpenQueue: () -> Unit, modifier: Modifier = Modifier,
    hostAvatarUrl: String? = null, isLocalHost: Boolean = false,
) {
    val vm = appViewModel { graph -> PlayerViewModel(graph) }
    val graph = rememberAppGraph()
    val playback by vm.snapshot.collectAsStateWithLifecycle()
    val sections by vm.queue.collectAsStateWithLifecycle()
    // Before a Connect source becomes active, display this device's last context only.
    // Never turn a preview into a shared queue mutation.
    if (playback.source == PlaybackSource.NONE) {
        JamLocalQueuePreview(modifier)
        return
    }
    var editing by remember { mutableStateOf(false) }
    val movedTrackUris = remember { mutableStateListOf<String>() }
    val pendingTrackUris = remember { mutableStateListOf<String>() }
    // Only moves initiated in this sheet have a verified position to restore.
    val restoreOrigins = remember { mutableStateListOf<JamQueueMoveOrigin>() }
    var clearBusy by remember { mutableStateOf(false) }
    var clearFeedback by remember { mutableStateOf<String?>(null) }
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var activeDragKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun queuedOrigin(origin: JamQueueMoveOrigin): com.taehagen.spotifygood.ui.screens.player.QueueEntry? =
        sections.queued.firstOrNull { it.track.uid == origin.uid }
            ?: sections.queued.singleOrNull { it.track.uri == origin.uri }
    fun recordLocalMove(entry: com.taehagen.spotifygood.ui.screens.player.QueueEntry) {
        // Capture context neighbors *before* issuing the remote move; once promoted,
        // Spotify's queue no longer exposes the track's previous context position.
        if (entry.hasUid && entry.track.provider != TrackProvider.QUEUE &&
            restoreOrigins.none { it.uid == entry.track.uid } && playback.source == PlaybackSource.REMOTE) {
            val context = if (entry.track.provider == TrackProvider.AUTOPLAY) sections.autoplay else sections.upNext
            val at = context.indexOfFirst { it.key == entry.key }
            if (at >= 0 && (entry.track.provider == TrackProvider.CONTEXT || entry.track.provider == TrackProvider.AUTOPLAY)) {
                restoreOrigins.add(JamQueueMoveOrigin(entry.track.uid, entry.track.uri, at,
                    if (entry.track.provider == TrackProvider.AUTOPLAY) "autoplay" else "context",
                    context.getOrNull(at - 1)?.track?.uid?.takeIf { it.isNotBlank() },
                    context.getOrNull(at + 1)?.track?.uid?.takeIf { it.isNotBlank() }))
            }
        }
        val uri = entry.track.uri
        if (entry.track.provider == com.taehagen.spotifygood.model.TrackProvider.QUEUE) {
            if (uri !in movedTrackUris) movedTrackUris.add(uri)
        } else if (uri !in pendingTrackUris) {
            pendingTrackUris.add(uri)
            scope.launch { delay(10_000.milliseconds); pendingTrackUris.remove(uri) }
        }
    }
    val allEntries = sections.queued + sections.upNext + sections.autoplay
    val liveOrder = allEntries.map { it.key }
    val orderedEntries = previewOrder?.let { order ->
        val lookup = allEntries.associateBy { it.key }
        order.mapNotNull(lookup::get) + allEntries.filterNot { it.key in order }
    } ?: allEntries
    // An attribution is local to a still-protected, manually queued occurrence.
    // When Spotify restores a track to its playlist/context, remove the badge.
    LaunchedEffect(sections.queued.map { it.track.uri }, sections.upNext.map { it.track.uri },
        sections.autoplay.map { it.track.uri }, pendingTrackUris.toList()) {
        val protectedUris = sections.queued.mapTo(HashSet()) { it.track.uri }
        pendingTrackUris.toList().filter { it in protectedUris }.forEach { uri ->
            if (uri !in movedTrackUris) movedTrackUris.add(uri)
            pendingTrackUris.remove(uri)
        }
        movedTrackUris.retainAll(protectedUris)
        // An origin is no longer pending once its occurrence has left the manual queue.
        // If Spotify resets a moved song by another device, Clear must not offer to move it again.
        restoreOrigins.removeAll { origin ->
            queuedOrigin(origin) == null && origin.uri !in pendingTrackUris
        }
    }
    LaunchedEffect(liveOrder) {
        val expected = previewOrder
        if (expected != null && (liveOrder == expected || liveOrder.toSet() != expected.toSet())) {
            previewOrder = null
        }
    }
    LaunchedEffect(playback.source, playback.nextTracks.size,
        sections.queued.size, sections.upNext.size, sections.autoplay.size) {
        Log.i("SpotifyGoodJam", "queue-preview source=${playback.source} " +
            "connectNext=${playback.nextTracks.size} queued=${sections.queued.size} " +
            "context=${sections.upNext.size} autoplay=${sections.autoplay.size} " +
            "queuedWithUid=${sections.queued.count { it.hasUid }} " +
            "contextWithUid=${sections.upNext.count { it.hasUid }}")
    }
    val canClear = playback.source == PlaybackSource.REMOTE && restoreOrigins.any { queuedOrigin(it) != null }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (editing) "Edit Queue" else "Queue", fontSize = 24.sp, color = JamUi.white,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (editing) {
                IconButton(onClick = { editing = false }) {
                    Icon(Icons.Rounded.Close, "Finish editing queue", tint = JamUi.white)
                }
            } else {
                if (canClear) {
                    JamDarkButton(if (clearBusy) "Clearing…" else "Clear", {
                        if (!clearBusy) {
                            // Spotify may assign a new UID when promoting a context track.
                            // Only match by URI when unique; never reset an ambiguous duplicate.
                            val restoring = restoreOrigins.mapNotNull { origin ->
                                queuedOrigin(origin)?.let { origin.copy(uid = it.track.uid) }
                            }
                            if (restoring.isNotEmpty()) {
                                clearBusy = true
                                clearFeedback = null
                                scope.launch {
                                    try {
                                        val ok = graph.player.restoreJamMovedAsync(restoring).await()
                                        if (ok) {
                                            // The server is authoritative. The next Connect snapshot
                                            // will remove these occurrences from the protected queue.
                                            clearFeedback = null
                                        } else {
                                            clearFeedback = "Couldn't restore the original queue positions."
                                        }
                                    } catch (_: Exception) {
                                        clearFeedback = "Couldn't restore the original queue positions."
                                    } finally { clearBusy = false }
                                }
                            }
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                }
                JamDarkButton("Edit", { editing = true })
            }
        }
        clearFeedback?.let { Text(it, color = JamUi.muted, fontSize = 12.sp) }
        if (editing) {
            // The editor uses a LazyColumn and its own fixed selection-action footer.
            Box(Modifier.weight(1f)) {
                JamInlineQueueEditor(playback, sections, vm, hostAvatarUrl, isLocalHost, movedTrackUris,
                    onLocalMoved = { entry -> recordLocalMove(entry) })
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "jam:context") {
                    Text(playback.context?.name?.takeIf { it.isNotBlank() }?.let { "Playing $it" }
                        ?: "Current playback", fontSize = 14.sp, color = JamUi.muted)
                }
                playback.track?.let { track ->
                    item(key = "jam:playing") {
                        JamQueueRow(track, nowPlaying = true, isPlaying = playback.isPlaying,
                            queued = false, onOpenQueue = onOpenQueue)
                    }
                }
                items(orderedEntries, key = { "jam:next:${it.key}" }) { entry ->
                    val index = orderedEntries.indexOfFirst { it.key == entry.key }
                    val previous = orderedEntries.getOrNull(index - 1)?.track?.provider
                    val provider = entry.track.provider
                    val queued = provider == com.taehagen.spotifygood.model.TrackProvider.QUEUE
                    val isAutoplay = provider == com.taehagen.spotifygood.model.TrackProvider.AUTOPLAY
                    val canDrag = entry.hasUid &&
                        (queued || playback.source == PlaybackSource.REMOTE)
                    Column(Modifier
                        .zIndex(if (activeDragKey == entry.key) 12f else 0f)
                        .then(if (activeDragKey == entry.key) {
                            Modifier.shadow(12.dp, RoundedCornerShape(8.dp))
                                .background(JamUi.row, RoundedCornerShape(8.dp))
                        } else Modifier.animateItem())) {
                        if (index == 0 || (queued && previous != provider) ||
                            (isAutoplay && previous != provider) ||
                            (!queued && !isAutoplay && previous == com.taehagen.spotifygood.model.TrackProvider.QUEUE)) {
                            val label = when {
                                queued -> "Next in queue"
                                isAutoplay -> "Autoplay"
                                else -> playback.context?.name?.takeIf { it.isNotBlank() }
                                    ?.let { "Next from $it" } ?: "Up next"
                            }
                            Text(label, color = JamUi.muted, fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        }
                        Box {
                            JamQueueRow(entry.track, false, false, queued, onOpenQueue,
                                onDragShift = if (canDrag) { direction ->
                                    val next = (previewOrder ?: liveOrder).toMutableList()
                                    val at = next.indexOf(entry.key)
                                    val dest = at + direction
                                    if (at >= 0 && dest in next.indices) {
                                        next.add(dest, next.removeAt(at))
                                        previewOrder = next
                                        true
                                    } else false
                                } else null,
                                onDragCommit = {
                                    val expected = previewOrder
                                    val to = expected?.indexOf(entry.key) ?: -1
                                    val from = liveOrder.indexOf(entry.key)
                                    if (from >= 0 && to >= 0 && from != to) {
                                        vm.moveUpcoming(entry, to)
                                        // Await confirmation in sections.queued before showing
                                        // this local attribution in either queue mode.
                                        recordLocalMove(entry)
                                        Log.i("SpotifyGoodJam", "queue-drag mode=normal from=$from to=$to")
                                        scope.launch {
                                            delay(4_500.milliseconds)
                                            if (previewOrder == expected) previewOrder = null
                                        }
                                    } else previewOrder = null
                                },
                                onDragCancel = { previewOrder = null },
                                onDragActive = { isActive -> activeDragKey = if (isActive) entry.key else null },
                                movedByLocal = queued && entry.track.uri in movedTrackUris && isLocalHost,
                                hostAvatarUrl = hostAvatarUrl)
                        }
                    }
                }
                if (sections.isEmpty) item(key = "jam:empty") {
                    Text(if (playback.track == null) "Start playing music to see your queue."
                        else "Upcoming songs haven't been supplied by the active playback device yet.",
                        color = JamUi.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun JamActionTile(icon: @Composable () -> Unit, title: String,
                          onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(onClick = { onClick?.invoke() }, enabled = onClick != null,
        shape = RoundedCornerShape(9.dp), color = JamUi.row, modifier = modifier.height(73.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            icon()
            Text(title, color = if (onClick == null) JamUi.muted else JamUi.white,
                fontSize = 13.sp)
        }
    }
}

@Composable
internal fun JamOverviewPage(
    session: JamSessionSummary, hostName: String, hostAvatarUrl: String?,
    onInvite: () -> Unit, onDetails: () -> Unit, onGuestSettings: () -> Unit,
    onLeave: () -> Unit, onOpenQueue: () -> Unit, onFindSongs: () -> Unit,
) {
    val vm = appViewModel { graph -> PlayerViewModel(graph) }
    val graph = rememberAppGraph()
    val playback by vm.snapshot.collectAsStateWithLifecycle()
    val canShuffle = playback.track?.isEpisode == false && playback.restrictions.canToggleShuffle
    val canRepeat = playback.track?.isEpisode == false && playback.restrictions.canToggleRepeat
    val shuffleOn = playback.shuffle || playback.smartShuffle
    var modeFeedback by remember { mutableStateOf<String?>(null) }
    var modeBusy by remember { mutableStateOf(false) }
    val modeScope = rememberCoroutineScope()
    LaunchedEffect(playback.source, playback.shuffle, playback.repeat,
        playback.restrictions.canToggleShuffle, playback.restrictions.canToggleRepeat) {
        Log.i("SpotifyGoodJam", "modes source=${playback.source} " +
            "canShuffle=${playback.restrictions.canToggleShuffle} " +
            "canRepeat=${playback.restrictions.canToggleRepeat} " +
            "shuffle=${playback.shuffle} repeat=${playback.repeat}")
    }
    Column(
        Modifier.fillMaxSize().background(JamUi.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Text(if (session.isSessionOwner == false) "Active Jam"
             else if (hostName.isBlank()) "Your Jam" else "$hostName's Jam",
            color = JamUi.white, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JamCircleAction(onInvite) {
                Icon(Icons.Rounded.Add, "Invite", tint = JamUi.white, modifier = Modifier.size(30.dp))
            }
            Surface(onClick = onDetails, shape = CircleShape,
                color = JamUi.row, modifier = Modifier.size(42.dp)) {
                JamAvatar(if (session.isSessionOwner == true) hostAvatarUrl else null,
                    42, "View Jam participants")
            }
            session.memberCount?.takeIf { it > 1 }?.let { count ->
                Text("$count listening", color = JamUi.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(onClick = onGuestSettings, shape = CircleShape,
                color = JamUi.background, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Jam settings", tint = JamUi.white)
                }
            }
            JamDarkButton(if (session.isSessionOwner == true) "End" else "Leave", onLeave,
                textColor = JamUi.red)
        }
        JamLine()
        JamQueuePreview(onOpenQueue, modifier = Modifier.weight(1f),
            hostAvatarUrl = hostAvatarUrl, isLocalHost = session.isSessionOwner == true)
        if (shuffleOn && playback.source == PlaybackSource.REMOTE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                JamDarkButton("Reshuffle", onClick = {
                    if (!modeBusy) {
                        modeBusy = true
                        modeFeedback = null
                        modeScope.launch {
                            try {
                                val accepted = graph.player.reshuffleJamQueueAsync().await()
                                modeFeedback = if (accepted) null
                                    else "The device couldn't reshuffle the upcoming tracks."
                                Log.i("SpotifyGoodJam", "reshuffle-command accepted=$accepted")
                            } catch (e: Exception) {
                                modeFeedback = "Couldn't reshuffle the upcoming tracks."
                                Log.w("SpotifyGoodJam", "reshuffle-command failed: ${e.javaClass.simpleName}")
                            } finally {
                                modeBusy = false
                            }
                        }
                    }
                })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JamActionTile({ Icon(Icons.Rounded.Headphones, null, tint = JamUi.muted) }, "Mix", null,
                Modifier.weight(1f))
            JamActionTile({ Icon(Icons.Rounded.Shuffle, "Shuffle",
                tint = if (shuffleOn) JamUi.green else JamUi.white) }, "Shuffle", {
                if (!canShuffle) {
                    modeFeedback = "Shuffle isn't available on this playback device or track."
                } else if (!modeBusy) {
                    val target = !shuffleOn
                    modeBusy = true
                    modeFeedback = null
                    modeScope.launch {
                        try {
                            val ok = if (playback.smartShuffle) {
                                val disabled = graph.player.setSmartShuffleAsync(false).await()
                                disabled && graph.player.setShuffleAsync(false).await()
                            } else graph.player.setShuffleAsync(target).await()
                            Log.i("SpotifyGoodJam", "mode-command shuffle target=$target accepted=$ok")
                            modeFeedback = when {
                                !ok -> "The playback device rejected the Shuffle command."
                                withTimeoutOrNull(3_500.milliseconds) { vm.snapshot.first {
                                    (it.shuffle || it.smartShuffle) == target
                                } } == null -> {
                                    val observed = vm.snapshot.value.shuffle || vm.snapshot.value.smartShuffle
                                    Log.w("SpotifyGoodJam", "shuffle-command not-applied target=$target observed=$observed source=${vm.snapshot.value.source}")
                                    "The remote device acknowledged Shuffle but did not apply the change."
                                }
                                else -> null
                            }
                        } finally { modeBusy = false }
                    }
                }
            }, Modifier.weight(1f))
            JamActionTile({ Icon(
                if (playback.repeat == RepeatMode.TRACK) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Repeat",
                tint = if (playback.repeat == RepeatMode.OFF) JamUi.white else JamUi.green,
            ) }, "Repeat", {
                if (!canRepeat) {
                    modeFeedback = "Repeat isn't available on this playback device or track."
                } else if (!modeBusy) {
                    val target = when (playback.repeat) {
                        RepeatMode.OFF -> RepeatMode.CONTEXT
                        RepeatMode.CONTEXT -> RepeatMode.TRACK
                        RepeatMode.TRACK -> RepeatMode.OFF
                    }
                    modeBusy = true
                    modeFeedback = null
                    modeScope.launch {
                        try {
                            val ok = graph.player.setRepeatAsync(target).await()
                            Log.i("SpotifyGoodJam", "mode-command repeat target=$target accepted=$ok")
                            modeFeedback = when {
                                !ok -> "The playback device rejected the Repeat command."
                                withTimeoutOrNull(3_500.milliseconds) { vm.snapshot.first { it.repeat == target } } == null ->
                                    "Repeat was sent, but the device hasn't reported the new state."
                                else -> null
                            }
                        } finally { modeBusy = false }
                    }
                }
            }, Modifier.weight(1f))
            JamActionTile({ Icon(Icons.Rounded.Add, null, tint = JamUi.white) }, "Add songs",
                onFindSongs, Modifier.weight(1f))
        }
        modeFeedback?.let { Text(it, color = JamUi.muted, fontSize = 11.sp) }
    }
}

@Composable
internal fun JamDetailsPage(session: JamSessionSummary, hostName: String, hostAvatarUrl: String?,
                             onBack: () -> Unit, onInvite: () -> Unit, onGuestSettings: () -> Unit,
                             onLeave: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(JamUi.background).heightIn(min = 520.dp)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = JamUi.white)
            }
            Text("Jam details", fontSize = 21.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, color = JamUi.white, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        JamLine()
        Row(Modifier.fillMaxWidth().clickable(onClick = onInvite).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            JamCircleAction(onInvite) {
                Icon(Icons.Rounded.Add, "Invite others", tint = JamUi.white,
                    modifier = Modifier.size(28.dp))
            }
            Text("Invite others", color = JamUi.white, fontSize = 17.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            JamAvatar(if (session.isSessionOwner == true) hostAvatarUrl else null,
                46, "Jam host")
            Column {
                Text(if (session.isSessionOwner == true) hostName.ifBlank { "You" } else "Jam host",
                    color = JamUi.white, fontSize = 17.sp)
                Text(if (session.isSessionOwner == true) "Host" else "Host details unavailable",
                    color = JamUi.muted, fontSize = 13.sp)
            }
        }
        session.memberCount?.takeIf { it > 1 }?.let { count ->
            Text("$count participants", color = JamUi.muted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        JamLine()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.People, null, tint = JamUi.green)
            Column(Modifier.weight(1f)) {
                Text("Auto-invite people nearby", color = JamUi.white, fontSize = 16.sp)
                Text("Nearby discovery isn't available in Spotify Good yet",
                    color = JamUi.muted, fontSize = 12.sp)
            }
            Switch(checked = false, enabled = false, onCheckedChange = null)
        }
        Text("People on your WiFi or nearby with Bluetooth enabled can be invited in Spotify.",
            color = JamUi.muted, fontSize = 13.sp)
        Spacer(Modifier.weight(1f, fill = false))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JamActionTile({ Icon(Icons.Rounded.Close, null, tint = JamUi.white) },
                if (session.isSessionOwner == true) "End Jam" else "Leave Jam", onLeave, Modifier.weight(1f))
            JamActionTile({ Icon(Icons.Rounded.Settings, null, tint = JamUi.white) },
                "Guest settings", onGuestSettings, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun JamGuestSettingsPage(
    session: JamSessionSummary,
    onBack: () -> Unit,
    onQueueOnlyModeChange: (Boolean) -> Unit,
    changing: Boolean,
    feedback: String?,
) {
    Column(Modifier.fillMaxWidth().background(JamUi.background).heightIn(min = 320.dp)
        .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = JamUi.white) }
            Text("Guest controls", fontWeight = FontWeight.Bold, fontSize = 21.sp,
                color = JamUi.white, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
        JamLine()
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.PlayArrow, null, tint = JamUi.white,
                modifier = Modifier.size(28.dp))
            Text("Play, pause, and skip tracks", color = JamUi.white,
                fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = session.queueOnlyMode == false,
                onCheckedChange = { allowGuests -> onQueueOnlyModeChange(!allowGuests) },
                enabled = session.active && session.isSessionOwner == true &&
                    session.queueOnlyMode != null && !changing)
        }
        Text("When turned off, guests can still add songs but can't control playback.",
            color = JamUi.muted, fontSize = 14.sp)
        feedback?.let { Text(it, color = JamUi.muted, fontSize = 13.sp) }
        if (changing) Text("Updating guest controls…", color = JamUi.muted, fontSize = 13.sp)
        if (session.isSessionOwner != true) {
            Text("Only the host can change guest controls.", color = JamUi.muted, fontSize = 13.sp)
        }
        Text("Guest volume permissions are device-dependent and aren't editable here yet.",
            color = JamUi.muted, fontSize = 13.sp)
    }
}
