package com.taehagen.spotifygood.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.MediaThumbnail
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put

/** Account-backed profile membership: changes the rootlist item's public flag, not playlist privacy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePlaylistManagementScreen(contentPadding: PaddingValues) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val user by graph.engine.user.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<RootlistEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(graph, user?.username) {
        graph.library.playlists().collectLatest { result ->
            if (user != null) {
                entries = result.dataOrNull?.flatPlaylists()?.filter {
                    it.uri != null && it.isPublic != null
                }.orEmpty().distinctBy { it.uri }
                loading = result.dataOrNull == null
                selected = selected.intersect(entries.filter { it.isPublic == true }.mapNotNull { it.uri }.toSet())
            }
        }
    }
    val featured = entries.filter { it.isPublic == true }
    val available = entries.filter { it.isPublic == false }

    fun change(uris: List<String>, show: Boolean) {
        if (saving || uris.isEmpty()) return
        saving = true
        error = null
        scope.launch {
            try {
                // Sequential changes avoid conflicting rootlist revisions.
                for (uri in uris) {
                    graph.rpc.call<JsonElement>("playlist.setPublic", rpcArgs {
                        put("uri", uri)
                        put("public", show)
                    })
                }
                selected = emptySet()
            } catch (e: Exception) {
                error = e.message ?: "Spotify could not save the playlist changes"
            } finally {
                // A fresh server read is the authority for the section membership.
                try { graph.library.refresh() } finally { saving = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Playlists on your profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp, top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(onClick = { change(selected.toList(), false) }, enabled = !saving) {
                        Text(if (saving) "Saving…" else "Remove from profile")
                    }
                }
            }
        },
    ) { inner ->
        val sectionColor = MaterialTheme.colorScheme.surfaceContainerHigh
        LazyColumn(
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 12.dp,
            ),
        ) {
            item {
                Text("Select which playlists to remove from your profile. New playlists are shown by default.")
                TextButton(onClick = {
                    val all = featured.mapNotNull { it.uri }.toSet()
                    selected = if (selected.size == all.size) emptySet() else all
                }, enabled = !saving && featured.isNotEmpty()) {
                    Text(if (featured.isNotEmpty() && selected.size == featured.size) "Deselect all" else "Select all")
                }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(featured, key = { _, it -> "shown:${it.uri}" }) { _, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !saving) {
                            val uri = entry.uri ?: return@clickable
                            selected = if (uri in selected) selected - uri else selected + uri
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = entry.uri in selected, onCheckedChange = { checked ->
                        val uri = entry.uri ?: return@Checkbox
                        selected = if (checked) selected + uri else selected - uri
                    }, enabled = !saving)
                    MediaThumbnail(
                        ref = com.taehagen.spotifygood.model.PlaylistRef(
                            uri = requireNotNull(entry.uri),
                            name = entry.name,
                            images = entry.images,
                            owner = entry.owner,
                        ).toMediaRef(),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Public · ${entry.owner?.displayName ?: entry.owner?.username.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(
                        onClick = {
                            val playlist = entry.uri?.let { uri ->
                                com.taehagen.spotifygood.model.PlaylistRef(
                                    uri = uri,
                                    name = entry.name,
                                    images = entry.images,
                                    owner = entry.owner,
                                )
                            } ?: return@IconButton
                            navigator.showActions(profilePlaylistTarget(playlist, user?.username))
                        },
                        enabled = !saving,
                    ) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options")
                    }
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(sectionColor)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Add playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("They'll appear on your profile", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { change(available.mapNotNull { it.uri }, true) },
                        enabled = !saving && available.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.55f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
                    ) { Text("Add all") }
                }
            }
            itemsIndexed(available, key = { _, it -> "available:${it.uri}" }) { index, entry ->
                val shape = when {
                    available.size == 1 -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    index == available.lastIndex -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(sectionColor)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaThumbnail(
                        ref = com.taehagen.spotifygood.model.PlaylistRef(
                            uri = requireNotNull(entry.uri),
                            name = entry.name,
                            images = entry.images,
                            owner = entry.owner,
                        ).toMediaRef(),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Public · ${entry.owner?.displayName ?: entry.owner?.username.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { entry.uri?.let { change(listOf(it), true) } }, enabled = !saving) { Text("Add") }
                }
            }
        }
    }
}
