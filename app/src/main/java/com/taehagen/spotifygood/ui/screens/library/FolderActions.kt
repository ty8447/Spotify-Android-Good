package com.taehagen.spotifygood.ui.screens.library

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.CreateKind
import com.taehagen.spotifygood.ui.components.CreateSheet
import com.taehagen.spotifygood.ui.components.NameScreen
import com.taehagen.spotifygood.ui.components.friendlyErrorMessage
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.launch

private enum class FolderSheet { Menu, Create, AddTo, MoveTo, Rename, Delete }

/** One row of a folder picker; [id] "" is the top of the library. */
private data class PickEntry(val id: String, val name: String, val depth: Int, val folder: Boolean, val image: String?)

private fun LibraryItem.contains(id: String): Boolean = children.any { it.id == id || it.contains(id) }

/**
 * [tree] flattened for a picker. [hide]: left out with everything inside it; [open]: left out
 * itself, its contents still listed (the folders around the current one).
 */
private fun pickEntries(tree: List<LibraryItem>, foldersOnly: Boolean, hide: String, open: (LibraryItem) -> Boolean, depth: Int = 0): List<PickEntry> =
    tree.flatMap { item ->
        val isFolder = item.kind == LibraryItemKind.FOLDER
        when {
            item.id == hide -> emptyList()
            isFolder && open(item) -> pickEntries(item.children, foldersOnly, hide, open, depth)
            isFolder -> listOf(PickEntry(item.id, item.name, depth, true, null)) + pickEntries(item.children, foldersOnly, hide, open, depth + 1)
            foldersOnly -> emptyList()
            else -> listOf(PickEntry(item.id, item.name, depth, false, item.images.best(64)))
        }
    }

/** The folder page's ⋮ and + (Spotify's folder menu, and creating inside the folder). */
@Composable
internal fun FolderHeaderActions(folder: LibraryItem, tree: List<LibraryItem>, onGone: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    val graph = rememberAppGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<FolderSheet?>(null) }
    val perform: (Boolean, suspend () -> Unit) -> Unit = { leave, block ->
        sheet = null
        scope.launch {
            runCatching { block() }
                .onSuccess { if (leave) onGone() }
                .onFailure { Toast.makeText(context, friendlyErrorMessage(context, it), Toast.LENGTH_LONG).show() }
        }
    }
    IconButton({ sheet = FolderSheet.Menu }) { Icon(Icons.Rounded.MoreVert, "Folder options") }
    IconButton({ sheet = FolderSheet.Create }) { Icon(Icons.Rounded.Add, "Create in this folder") }
    val dismiss = { sheet = null }
    when (sheet) {
        FolderSheet.Menu -> FolderMenu(folder, dismiss) { sheet = it }
        FolderSheet.Create -> CreateSheet(dismiss, { dismiss(); onOpenPlaylist(it) }, listOf(CreateKind.Playlist, CreateKind.Folder), folder.id)
        FolderSheet.AddTo -> FolderPicker(
            "Add to ${folder.name}", pickEntries(tree, false, folder.id, { it.contains(folder.id) }), multi = true, dismiss,
        ) { picked -> perform(false) { graph.playlists.moveToFolder(picked, folder.id) } }
        FolderSheet.MoveTo -> FolderPicker(
            "Move to folder", listOf(PickEntry("", "Your Library", 0, true, null)) + pickEntries(tree, true, folder.id, { false }, 1), multi = false, dismiss,
        ) { picked -> perform(true) { graph.playlists.moveToFolder(listOf(folder.id), picked.first().ifEmpty { null }) } }
        FolderSheet.Rename -> NameScreen("Rename your folder", folder.name, "Save", dismiss, { graph.playlists.renameFolder(folder.id, it); it }) { dismiss() }
        FolderSheet.Delete -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete folder") },
            text = { Text("\"${folder.name}\" will be deleted. All playlists and folders inside will be kept in Your Library.") },
            confirmButton = {
                Row {
                    TextButton(dismiss) { Text("Cancel") }
                    TextButton({ perform(true) { graph.playlists.deleteFolder(folder.id, everything = true) } }) {
                        Text("Delete everything", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton({ perform(true) { graph.playlists.deleteFolder(folder.id, everything = false) } }) { Text("OK") }
                }
            },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderMenu(folder: LibraryItem, onDismiss: () -> Unit, onPick: (FolderSheet) -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)), Alignment.Center) {
                Icon(Icons.Rounded.Folder, null)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = folder.playlistCount()
                Text("$n playlist${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        listOf(
            Triple(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to folder", FolderSheet.AddTo),
            Triple(Icons.AutoMirrored.Rounded.DriveFileMove, "Move to folder", FolderSheet.MoveTo),
            Triple(Icons.Rounded.Edit, "Rename folder", FolderSheet.Rename),
            Triple(Icons.Rounded.Delete, "Delete folder", FolderSheet.Delete),
        ).forEach { (icon, label, target) -> MenuRow(icon, label) { onPick(target) } }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Picks library playlists / folders (checkboxes when [multi], else one), confirmed with a Move button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPicker(title: String, entries: List<PickEntry>, multi: Boolean, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(entries) { e ->
                val on = e.id in picked
                Row(
                    Modifier.fillMaxWidth().clickable(role = if (multi) Role.Checkbox else Role.RadioButton) {
                        if (multi) { if (on) picked.remove(e.id) else picked.add(e.id) } else { picked.clear(); picked.add(e.id) }
                    }.padding(start = 16.dp + 20.dp * e.depth, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        e.id.isEmpty() -> LeadingIcon(Icons.Rounded.LibraryMusic)
                        e.folder -> LeadingIcon(Icons.Rounded.Folder)
                        else -> Artwork(e.image, null, Modifier.size(48.dp), RoundedCornerShape(4.dp), Icons.Rounded.MusicNote)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (multi) Checkbox(on, null) else RadioButton(on, null)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760), contentColor = Color.Black),
            ) { Text(if (multi && picked.size > 1) "Move ${picked.size} items" else "Move", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun LeadingIcon(icon: ImageVector) {
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)), Alignment.Center) { Icon(icon, null) }
}
