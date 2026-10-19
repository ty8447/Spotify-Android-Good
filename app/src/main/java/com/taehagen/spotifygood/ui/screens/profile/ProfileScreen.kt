package com.taehagen.spotifygood.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.appViewModel
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.components.ErrorState
import com.taehagen.spotifygood.ui.components.LoadingState
import com.taehagen.spotifygood.ui.components.MediaRow
import com.taehagen.spotifygood.ui.components.SectionHeader
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import com.taehagen.spotifygood.ui.screens.library.ConfirmDialog
import com.taehagen.spotifygood.ui.screens.library.StateBox
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.ui.screens.library.messageRes
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** [username] null = the logged-in user. */
@Composable
fun ProfileScreen(username: String?, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val viewModel = appViewModel(key = "profile:${username.orEmpty()}") { graph -> ProfileViewModel(graph, username) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var showProfileOptions by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draftName by rememberSaveable { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<Uri?>(null) }
    var photoSheet by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { selectedPhoto = uri; photoSheet = false }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) { selectedPhoto = cameraUri; photoSheet = false }
    }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val graph = rememberAppGraph()
    var profileVisibility by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var visibilityLoading by remember { mutableStateOf(false) }
    var visibilitySaving by remember { mutableStateOf<String?>(null) }
    var visibilityError by remember { mutableStateOf<String?>(null) }
    var artistsStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showProfileOptions, state.user?.username) {
        if (!showProfileOptions || !state.isMe) return@LaunchedEffect
        visibilityLoading = true
        visibilityError = null
        profileVisibility = emptyMap()
        artistsStatus = null
        try {
            val reply = graph.rpc.call<JsonObject>("catalog.profileVisibility")
            profileVisibility = listOf("followers", "playlists", "artists").associateWith { key ->
                reply[key]?.jsonPrimitive?.booleanOrNull
            }
            artistsStatus = reply["artistsStatus"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            visibilityError = "Couldn't load profile visibility: ${e.message ?: "Network unavailable"}"
        } finally {
            visibilityLoading = false
        }
    }
    fun saveVisibility(key: String, enabled: Boolean) {
        if (visibilitySaving != null || profileVisibility[key] == null) return
        visibilitySaving = key
        visibilityError = null
        scope.launch {
            try {
                val reply = graph.rpc.call<JsonObject>("catalog.setProfileVisibility", buildJsonObject {
                    put("key", key)
                    put("enabled", enabled)
                })
                val saved = reply["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: error("Spotify did not acknowledge the change")
                val verified = reply["verified"]?.jsonPrimitive?.booleanOrNull == true
                profileVisibility = profileVisibility + (key to saved)
                if (!verified) visibilityError = "Saved. Spotify hasn't exposed a fresh verification read for recently played artists yet."
            } catch (e: Exception) {
                visibilityError = "Couldn't save setting: ${e.message ?: "Network unavailable"}"
            } finally {
                visibilitySaving = null
            }
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val user = state.user
        val error = state.error
        when {
            user == null && error != null -> StateBox {
                ErrorState(message = stringResource(error.messageRes()), onRetry = viewModel::retry)
            }
            user == null -> LoadingState()
            editing && state.isMe && selectedPhoto != null -> ProfilePhotoCropScreen(
                uri = selectedPhoto!!,
                onCancel = { selectedPhoto = null },
                onUsePhoto = { jpeg -> photo = jpeg; removePhoto = false; selectedPhoto = null },
            )
            editing && state.isMe && user != null -> ProfileEditScreen(
                user = user,
                photo = photo,
                removePhoto = removePhoto,
                onPhotoClick = { photoSheet = true },
                draftName = draftName,
                onNameChanged = { draftName = it; saveError = null },
                saving = saving,
                error = saveError,
                contentPadding = contentPadding,
                onCancel = { if (!saving) { editing = false; photo = null; removePhoto = false; saveError = null } },
                onSave = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                if (draftName != (user.displayName ?: user.username)) viewModel.saveName(draftName)
                                if (removePhoto) viewModel.removePhoto()
                                else if (photo != null) viewModel.savePhoto(photo!!)
                                photo = null
                                removePhoto = false
                                editing = false
                                saveError = null
                            } catch (e: Exception) {
                                saveError = e.message ?: "Spotify did not confirm this change"
                            } finally { saving = false }
                        }
                    }
                },
            )
            else -> LazyColumn(contentPadding = contentPaddingWith(contentPadding), modifier = Modifier.fillMaxSize()) {
                item(key = "header", contentType = "header") {
                    ProfileHeader(
                        user = user,
                        playlistCount = if (state.isMe) state.playlists.size else null,
                        onFollowers = { navigator.navigate(Route.SocialPeople("followers", if (state.isMe) null else user.username)) },
                        onFollowing = { navigator.navigate(Route.SocialPeople("following", if (state.isMe) null else user.username)) },
                    )
                }
                if (state.isMe) {
                    item(key = "actions", contentType = "actions") {
                        ProfileActions(
                            onSettings = { navigator.navigate(Route.Settings) },
                            onEdit = {
                                photo = null
                                removePhoto = false
                                draftName = user.displayName?.takeIf { it.isNotBlank() } ?: user.username
                                saveError = null
                                editing = true
                            },
                            onOptions = { showProfileOptions = true },
                        )
                    }
                }
                if (state.isMe || state.playlists.isNotEmpty()) {
                    item(key = "playlistsHeader", contentType = "sectionHeader") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 24.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                SectionHeader(title = stringResource(R.string.browse_profile_playlists))
                            }
                            if (state.isMe) TextButton(onClick = { navigator.navigate(Route.ProfilePlaylists) }) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Manage")
                            }
                        }
                    }
                    items(state.playlists, key = { "playlist:${it.uri}" }, contentType = { "playlist" }) { playlist ->
                        val ref = remember(playlist) { playlist.toMediaRef() }
                        MediaRow(
                            ref = ref,
                            onClick = { navigator.open(ref) },
                            // Edit / delete only for my own playlists, never for another user's.
                            onLongClick = { navigator.showActions(profilePlaylistTarget(playlist, state.myUsername)) },
                        )
                    }
                }
            }
        }
        // Back button floating over the header gradient.
        if (!editing) {
            IconButton(onClick = navigator::back, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.browse_back))
            }
        }
    }


    if (photoSheet && editing && !saving) {
        ModalBottomSheet(onDismissRequest = { photoSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text("Add a profile picture", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { gallery.launch("image/*") }) { Text("Upload from your photos") }
                HorizontalDivider()
                TextButton(onClick = {
                    val file = File.createTempFile("profile-camera-", ".jpg", context.cacheDir)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.profilephotos", file)
                    cameraUri = uri
                    camera.launch(uri)
                }) { Text("Take a photo") }
                HorizontalDivider()
                TextButton(onClick = { photo = null; removePhoto = true; photoSheet = false }) { Text("Remove current photo") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showProfileOptions && state.isMe && state.user != null) {
        val profile = state.user!!
        val profileUrl = "https://open.spotify.com/user/${Uri.encode(profile.username)}"
        ModalBottomSheet(onDismissRequest = { showProfileOptions = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Profile options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(profile.images.best(96), null, Modifier.size(48.dp), CircleShape,
                        Icons.Rounded.Person, avatarName = profile.displayName ?: profile.username)
                    Spacer(Modifier.size(12.dp))
                    Text(profile.displayName ?: profile.username, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
                ProfileOption(Icons.Rounded.Visibility, "Preview public profile") {
                    showProfileOptions = false
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl)))
                }
                ProfileOption(Icons.Rounded.Share, "Share profile") {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, profileUrl)
                    }
                    context.startActivity(Intent.createChooser(send, "Share Spotify profile"))
                }
                ProfileOption(Icons.Rounded.QrCode2, "Create Spotify Code") {
                    // Spotify Codes are server-generated; open the official generator.
                    navigator.navigate(Route.SpotifyCode("spotify:user:${profile.username}", profile.images.best(480)))
                }
                HorizontalDivider()
                Text("What others can see", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (visibilityLoading) {
                    Text("Loading your Spotify account settings…", style = MaterialTheme.typography.bodySmall)
                }
                visibilityError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
                ProfileVisibilityToggle(
                    "Followers and following",
                    "On your profile, people can see who's following you and who you're following.",
                    profileVisibility["followers"], visibilitySaving != null, { saveVisibility("followers", it) },
                )
                ProfileVisibilityToggle(
                    "Playlists",
                    "People can see the playlists you've added to your profile.",
                    profileVisibility["playlists"], visibilitySaving != null, { saveVisibility("playlists", it) },
                )
                ProfileVisibilityToggle(
                    "Recently played artists",
                    "People can see who you recently listened to on your profile.",
                    profileVisibility["artists"], visibilitySaving != null, { saveVisibility("artists", it) },
                )
                if (!visibilityLoading && profileVisibility["artists"] == null) {
                    Text(
                        when (artistsStatus) {
                            "missing_from_session" -> "Recently played artists cannot be read from the current Spotify session. No setting has been changed."
                            "unrecognized_session_value" -> "Spotify returned an unsupported Recently played artists value. No setting has been changed."
                            else -> "Recently played artists is unavailable until Spotify confirms its saved value."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                TextButton(onClick = {
                    showProfileOptions = false
                    navigator.navigate(Route.Settings)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("View all privacy and social settings")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ProfileHeader(user: User, playlistCount: Int?, onFollowers: () -> Unit, onFollowing: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), MaterialTheme.colorScheme.background),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                url = user.images.best(480),
                contentDescription = null,
                shape = CircleShape,
                placeholderIcon = Icons.Rounded.Person,
                modifier = Modifier.size(144.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = user.displayName?.takeIf { it.isNotBlank() } ?: user.username,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            user.product?.let { product ->
                Spacer(Modifier.height(8.dp))
                ProductBadge(product, user.planType)
            }
            if (playlistCount != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.browse_profile_playlist_count, playlistCount, playlistCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(user.followersCount?.let { "%,d".format(it) } ?: "—")
                        pop()
                        append(" followers")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onFollowers),
                )
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(user.followingCount?.let { "%,d".format(it) } ?: "—")
                        pop()
                        append(" following")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onFollowing),
                )
            }
        }
    }
}

@Composable
private fun ProductBadge(product: String, planType: String?) {
    val premium = product.equals("premium", ignoreCase = true)
    val planLabel = if (premium) when (planType?.lowercase()) {
        "family", "premium_family" -> "Family"
        "duo", "premium_duo" -> "Duo"
        "student", "premium_student" -> "Student"
        "individual", "premium_individual" -> "Individual"
        else -> null
    } else null
    Surface(
        shape = CircleShape,
        color = if (premium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (premium) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (premium) Icon(Icons.Rounded.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = if (premium) stringResource(R.string.browse_profile_premium) + (planLabel?.let { " · $it" } ?: "") else product.replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = if (premium) 4.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun ProfileActions(onSettings: () -> Unit, onEdit: () -> Unit, onOptions: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onEdit) { Text("Edit") }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
        IconButton(onClick = onOptions) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Profile options")
        }
    }
}

@Composable
private fun ProfileOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Editing is staged locally until Spotify confirms Save. */
@Composable
private fun ProfileEditScreen(
    user: User,
    photo: ByteArray?,
    removePhoto: Boolean,
    onPhotoClick: () -> Unit,
    draftName: String,
    onNameChanged: (String) -> Unit,
    saving: Boolean,
    error: String?,
    contentPadding: PaddingValues,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(contentPadding).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onCancel, enabled = !saving) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel")
            }
            Text("Edit profile", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onSave, enabled = !saving && draftName.isNotBlank() && (draftName != (user.displayName ?: user.username) || photo != null || removePhoto)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }
        Spacer(Modifier.height(32.dp))
        Box(modifier = Modifier.clickable(onClick = onPhotoClick)) {
            if (photo != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(photo) { android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size).asImageBitmap() },
                    contentDescription = "Profile picture",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(144.dp).clip(CircleShape),
                )
            } else if (!removePhoto) {
                Artwork(url = user.images.best(480), contentDescription = "Profile picture",
                    shape = CircleShape, placeholderIcon = Icons.Rounded.Person, modifier = Modifier.size(144.dp))
            } else {
                Icon(Icons.Rounded.Person, contentDescription = "No picture", modifier = Modifier.size(144.dp))
            }
            Surface(modifier = Modifier.align(Alignment.BottomEnd).size(38.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurface) {
                Icon(Icons.Rounded.Edit, contentDescription = "Change profile picture",
                    tint = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(9.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Change photo", modifier = Modifier.clickable(onClick = onPhotoClick),
            style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = draftName,
            onValueChange = onNameChanged,
            label = { Text("Name") },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Switch state comes from the account. Unknown values remain disabled, never guessed false. */
@Composable
private fun ProfileVisibilityToggle(
    title: String, description: String, checked: Boolean?, saving: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked == true, onCheckedChange = onChange, enabled = checked != null && !saving)
    }
}
