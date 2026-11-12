package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.serialization.Serializable

@Serializable
internal data class LibraryTag(
    val uri: String,
    val name: String = "",
    val items: List<String> = emptyList(),
    /** Each item's relevance to the tag (0: unscored). */
    val scores: List<Double> = emptyList(),
)

@Serializable
internal data class LibraryTagClass(val name: String = "", val tags: List<LibraryTag> = emptyList())

@Serializable
private data class LibraryTags(val classes: List<LibraryTagClass> = emptyList())

/**
 * Spotify's "Filter by" sheet: Genres, Moods and Activities with how many library items each has.
 * [onApply] gets the chosen tags (empty: no filter).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LibraryTagsSheet(selected: Set<LibraryTag>, onDismiss: () -> Unit, onApply: (Set<LibraryTag>) -> Unit) {
    val graph = rememberAppGraph()
    var classes by remember { mutableStateOf<List<LibraryTagClass>?>(null) }
    var chosen by remember { mutableStateOf(selected.map { it.uri }.toSet()) }
    LaunchedEffect(Unit) {
        classes = runCatching { graph.rpc.call<LibraryTags>("library.tags").classes }.getOrDefault(emptyList())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter by", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (chosen.isNotEmpty()) TextButton(onClick = { chosen = emptySet() }) { Text("Clear") }
            }
            val list = classes
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text("Spotify has no filters for your library yet.", Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { c ->
                        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            c.tags.forEach { t ->
                                FilterChip(
                                    selected = t.uri in chosen,
                                    onClick = { chosen = if (t.uri in chosen) chosen - t.uri else chosen + t.uri },
                                    label = { Text("${t.name} (${t.items.size})") },
                                )
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { onApply(classes.orEmpty().flatMap { it.tags }.filter { it.uri in chosen }.toSet()) },
                enabled = classes != null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) { Text("Apply", fontWeight = FontWeight.Bold) }
        }
    }
}
