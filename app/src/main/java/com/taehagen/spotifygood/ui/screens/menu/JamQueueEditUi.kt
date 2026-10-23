package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.TrackProvider
import com.taehagen.spotifygood.ui.screens.player.PlayerViewModel
import com.taehagen.spotifygood.ui.screens.player.QueueEntry
import com.taehagen.spotifygood.ui.screens.player.QueueSections
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Keep the Jam header above this editor and the selection actions below it.
 * Only the list between them scrolls. Queue mutations use real Connect UIDs.
 */
@Composable
internal fun JamInlineQueueEditor(
    snapshot: PlaybackSnapshot, sections: QueueSections, vm: PlayerViewModel,
    hostAvatarUrl: String? = null, showLocalAttribution: Boolean = false,
    movedTrackUris: SnapshotStateList<String>,
    onLocalMoved: (QueueEntry) -> Unit,
) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A promoted Connect track may receive a new UID/key after the server revision.
    // Retain locally-moved attribution by its stable track URI for the lifetime of this Jam sheet.
    var visualOrder by remember { mutableStateOf<List<String>?>(null) }
    var pendingResetOrder by remember { mutableStateOf<List<String>?>(null) }
    val entries = sections.queued + sections.upNext + sections.autoplay
    val queuedKeys = sections.queued.map { it.key }
    val canEditContext = snapshot.source == PlaybackSource.REMOTE
    val editableEntries = entries.filter { it.hasUid && (it.track.provider == TrackProvider.QUEUE || canEditContext) }
    val editableKeys = editableEntries.map { it.key }
    val valid = remember(entries) { entries.mapTo(HashSet()) { it.key } }
    LaunchedEffect(valid) {
        selected = selected.intersect(valid)
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val fallbackRowPx = with(density) { 66.dp.toPx() }
    val listGapPx = with(density) { 3.dp.toPx() }
    val marginPx = with(density) { 8.dp.toPx() }
    val orderedEntries = visualOrder?.let { order ->
        val byKey = entries.associateBy { it.key }
        order.mapNotNull(byKey::get) + entries.filterNot { it.key in order }
    } ?: entries
    LaunchedEffect(entries.map { it.key }) {
        val expected = visualOrder
        if (expected != null && (entries.map { it.key } == expected || entries.map { it.key }.toSet() != expected.toSet())) {
            visualOrder = null
            pendingResetOrder = null
        }
    }
    // Launch the cleanup from composition, never from an unscoped drag callback.
    // The timeout starts only after a move has been submitted to Connect.
    LaunchedEffect(pendingResetOrder) {
        val expected = pendingResetOrder ?: return@LaunchedEffect
        delay(4_500.milliseconds)
        if (visualOrder == expected) visualOrder = null
        if (pendingResetOrder == expected) pendingResetOrder = null
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            snapshot.track?.let { track ->
                item(key = "jam:current") {
                    JamEditTrackRow(track, isCurrent = true, playing = snapshot.isPlaying)
                }
            }
            if (entries.isEmpty()) {
                item(key = "jam:empty") {
                    Text("No upcoming tracks were provided by the active playback device.",
                        color = JamUi.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            items(orderedEntries, key = { it.key }) { entry ->
                val isQueued = entry.key in queuedKeys
                val editable = entry.key in editableKeys
                var dragDy by remember(entry.key) { mutableFloatStateOf(0f) }
                var dragging by remember(entry.key) { mutableStateOf(false) }
                var measuredRowPx by remember(entry.key) { mutableFloatStateOf(0f) }
                Row(Modifier.fillMaxWidth()
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .onSizeChanged { measuredRowPx = it.height.toFloat() }
                    .zIndex(if (dragging) 10f else 0f)
                    .graphicsLayer { translationY = dragDy }
                    .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(8.dp)) else Modifier)
                    .background(if (dragging) JamUi.row else JamUi.background),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = {
                        selected = if (entry.key in selected) selected - entry.key else selected + entry.key
                    }, enabled = editable, modifier = Modifier.size(32.dp)) {
                        Icon(if (entry.key in selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = if (entry.key in selected) "Deselect" else "Select",
                            tint = if (editable) JamUi.muted else JamUi.divider)
                    }
                    Box(Modifier.weight(1f)) {
                        JamEditTrackRow(entry.track, isCurrent = false,
                            prioritized = isQueued)
                    }
                    if (editable) {
                        if (showLocalAttribution && isQueued && entry.track.uri in movedTrackUris) {
                            JamAvatar(hostAvatarUrl, 23, "Moved by you")
                        }
                        val currentEntry by rememberUpdatedState(entry)
                        Box(Modifier.size(38.dp).pointerInput(entry.key) {
                            detectDragGestures(onDragStart = {
                                dragging = true
                                dragDy = 0f
                            }, onDragCancel = { dragging = false; dragDy = 0f; visualOrder = null; pendingResetOrder = null }, onDragEnd = {
                                val from = entries.indexOfFirst { it.key == currentEntry.key }
                                val expected = visualOrder
                                val destination = (expected ?: entries.map { it.key }).indexOf(currentEntry.key)
                                if (from >= 0 && destination >= 0 && destination != from) {
                                    vm.moveUpcoming(currentEntry, destination)
                                    onLocalMoved(currentEntry)
                                    pendingResetOrder = expected
                                } else visualOrder = null
                                dragging = false
                                dragDy = 0f
                            }, onDrag = { change, amount ->
                                change.consume()
                                dragDy += amount.y
                                // Do not displace a neighbor until the finger has traveled
                                // one *entire* row plus a margin. Only that neighbor animates.
                                val stepPx = (measuredRowPx.takeIf { it > 0f } ?: fallbackRowPx) + listGapPx
                                val direction = jamCrossedRow(dragDy, stepPx, marginPx)
                                if (direction != 0) {
                                    val active = (visualOrder ?: entries.map { it.key }).toMutableList()
                                    val at = active.indexOf(currentEntry.key)
                                    val dest = at + direction
                                    if (at >= 0 && dest in active.indices &&
                                        active[dest] in editableKeys) {
                                        active.add(dest, active.removeAt(at))
                                        visualOrder = active
                                        // The dragged row now occupies its neighbor's layout
                                        // slot; cancel that slot displacement to stay under touch.
                                        dragDy -= direction * stepPx
                                    }
                                }
                            })
                        }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DragHandle, "Hold and drag to reorder", tint = JamUi.white)
                        }
                    } else Spacer(Modifier.width(38.dp))
                }
            }
        }
        val selectedQueued = editableEntries.filter { it.key in selected }
        // This row is deliberately *outside* LazyColumn, anchored to the sheet bottom.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selectedQueued.isNotEmpty()) {
                IconButton(onClick = { selected = emptySet() }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Close, "Deselect all", tint = JamUi.white)
                }
            }
            JamEditorAction("Move up", enabled = selectedQueued.isNotEmpty(), onClick = {
                selectedQueued.forEach(onLocalMoved)
                vm.moveSelectedToFront(selectedQueued.mapTo(HashSet()) { it.key })
                selected = emptySet()
            })
            JamEditorAction("Remove", enabled = selectedQueued.isNotEmpty(), onClick = {
                selectedQueued.forEach(vm::remove)
                selected = emptySet()
            })
        }
    }
}

@Composable
private fun JamEditorAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = if (enabled) JamUi.row else JamUi.black,
        shape = RoundedCornerShape(22.dp)) {
        Text(label, color = if (enabled) JamUi.white else JamUi.muted.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}

@Composable
private fun JamEditTrackRow(
    track: PlaybackTrack, isCurrent: Boolean, playing: Boolean = false,
    prioritized: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(45.dp).clip(RoundedCornerShape(5.dp)).background(JamUi.row),
            contentAlignment = Alignment.Center) {
            if (track.imageUrl?.startsWith("https://") == true) {
                AsyncImage(model = track.imageUrl, contentDescription = null,
                    modifier = Modifier.size(45.dp).clip(RoundedCornerShape(5.dp)))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isCurrent) JamPlayingBars(playing)
                Text(track.name.orEmpty().ifBlank { "Unknown track" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                    color = if (isCurrent) JamUi.green else JamUi.white)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prioritized) Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "Manually queued",
                    modifier = Modifier.size(13.dp), tint = JamUi.green)
                Text(track.artistLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = JamUi.muted, fontSize = 13.sp)
            }
        }
        if (isCurrent) Icon(Icons.Rounded.PlayArrow, null, tint = JamUi.white)
    }
}

/** One complete row plus a margin is needed before a neighbor shifts. */
internal fun jamCrossedRow(offsetPx: Float, rowStepPx: Float, marginPx: Float): Int = when {
    rowStepPx <= 0f || marginPx < 0f -> 0
    offsetPx >= rowStepPx + marginPx -> 1
    offsetPx <= -rowStepPx - marginPx -> -1
    else -> 0
}
