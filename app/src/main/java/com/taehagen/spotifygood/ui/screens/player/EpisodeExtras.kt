package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.screens.video.VideoModeRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

@Serializable
internal data class Chapter(
    val title: String = "",
    val subtitle: String = "",
    val startMs: Long = 0,
    val stopMs: Long = 0,
    val durationMs: Long = 0,
    /** Its thumbnail (a video episode's frame). */
    val image: String = "",
)

@Serializable
internal data class Chapters(val items: List<Chapter> = emptyList())

@Serializable
internal data class EpisodeComment(
    val uri: String = "",
    val text: String = "",
    val author: String = "",
    val image: String? = null,
    val createdAt: Long = 0,
    val reactions: Int = 0,
    val replies: Int = 0,
    val pinned: Boolean = false,
)

@Serializable
internal data class Comments(val total: Int = 0, val comments: List<EpisodeComment> = emptyList(), val next: String? = null)

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** An episode's chapters (empty for songs and episodes without). */
@Composable
internal fun rememberChapters(uri: String, isEpisode: Boolean): List<Chapter> {
    val graph = rememberAppGraph()
    return produceState(emptyList<Chapter>(), uri, isEpisode) {
        if (isEpisode) value = runCatching { graph.rpc.callOffMain<Chapters>("catalog.chapters", buildJsonObject { put("uri", uri) }).items }.getOrDefault(emptyList())
    }.value
}

/** The chapter playing at [ms]. */
internal fun List<Chapter>.at(ms: Long): Chapter? = lastOrNull { it.startMs <= ms }

/**
 * Spotify's chips under Now Playing's art: Switch to audio / video, and for episodes Chapters and
 * Skip ahead (to the next chapter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingExtras(chapters: List<Chapter>, positionMs: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var sheet by remember(chapters) { mutableStateOf(false) }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VideoModeRow()
        if (chapters.isNotEmpty()) {
            AssistChip(onClick = { sheet = true }, label = { Text("Chapters") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(18.dp)) })
            AssistChip(
                onClick = { chapters.firstOrNull { it.startMs > positionMs() + 1_000 }?.let { onSeek(it.startMs) } },
                label = { Text("Skip ahead") },
                leadingIcon = { Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(chapters) { ch -> ChapterRow(ch, current = chapters.at(positionMs()) == ch) { onSeek(ch.startMs); sheet = false } }
        }
    }
}

/**
 * One chapter, as Spotify lists them: its thumbnail, title, and under it the explicit mark and how
 * long it runs; where it starts at the end.
 */
@Composable
internal fun ChapterRow(ch: Chapter, current: Boolean, explicit: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ch.image.isNotBlank()) {
            Artwork(ch.image, null, Modifier.width(80.dp).aspectRatio(16f / 9f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ch.title, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    com.taehagen.spotifygood.ui.components.ExplicitBadge()
                    Spacer(Modifier.width(4.dp))
                }
                val length = ch.durationMs.takeIf { it > 0 } ?: (ch.stopMs - ch.startMs).coerceAtLeast(0)
                Text(if (length > 0) clock(length) else ch.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(clock(ch.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * An episode's comments inline under Now Playing (loaded when scrolled to), with Spotify's
 * "Add a comment" box: newest pages load with "Show more".
 */
@Composable
internal fun EpisodeComments(uri: String, modifier: Modifier = Modifier) {
    val graph = rememberAppGraph()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pages by remember(uri) { mutableStateOf<Comments?>(null) }
    var reload by remember(uri) { mutableStateOf(0) }
    val load: suspend (String?) -> Comments? = { token ->
        runCatching {
            graph.rpc.callOffMain<Comments>("catalog.comments", buildJsonObject { put("uri", uri); token?.let { put("pageToken", it) } })
        }.getOrNull()
    }
    androidx.compose.runtime.LaunchedEffect(uri, reload) { pages = load(null) }
    var draft by remember(uri) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val c = pages
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            if (c != null && c.total > 0) "Comments (${c.total})" else "Comments",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                draft, { draft = it }, placeholder = { Text("Add a comment…") }, modifier = Modifier.weight(1f), maxLines = 4,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            )
            androidx.compose.material3.IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    scope.launch {
                        val ok = runCatching {
                            graph.rpc.callOffMain<kotlinx.serialization.json.JsonObject>("catalog.postComment", buildJsonObject { put("uri", uri); put("text", draft) })
                        }.isSuccess
                        sending = false
                        if (ok) { draft = ""; reload++ }
                    }
                },
            ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send comment") }
        }
        when {
            c == null -> {}
            c.comments.isEmpty() -> Text("Be the first to comment.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            else -> c.comments.forEach { CommentRow(it) }
        }
        val next = c?.next?.takeIf { it.isNotEmpty() }
        if (next != null && c != null) {
            androidx.compose.material3.TextButton(onClick = {
                scope.launch { load(next)?.let { more -> pages = more.copy(comments = c.comments + more.comments, total = c.total) } }
            }) { Text("Show more") }
        }
    }
}

@Composable
private fun CommentRow(c: EpisodeComment) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Artwork(c.image, null, Modifier.size(36.dp), CircleShape, Icons.Rounded.Person, avatarName = c.author)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.author + if (c.pinned) "  · Pinned" else "", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
            val meta = listOfNotNull(c.reactions.takeIf { it > 0 }?.let { "$it reactions" }, c.replies.takeIf { it > 0 }?.let { "$it replies" })
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
