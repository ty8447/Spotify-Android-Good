package com.taehagen.spotifygood.ui.components

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.JoinInner
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.screens.player.shareText
import kotlinx.serialization.Serializable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** What the Create sheet makes; [label] doubles as the naming screen's noun. */
enum class CreateKind(val title: String, val subtitle: String, val icon: ImageVector, val label: String, val beta: Boolean = false) {
    Playlist("Playlist", "Create a playlist with songs or episodes", Icons.Rounded.MusicNote, "playlist"),
    Collaborative("Collaborative playlist", "Create a playlist together with friends", Icons.Rounded.Groups, "playlist"),
    Mixed("Mixed playlist", "Mix songs with smooth transitions", Icons.Rounded.Tune, "playlist", beta = true),
    Blend("Blend", "Combine your friends' tastes into a playlist", Icons.Rounded.JoinInner, ""),
    Folder("Folder", "Organize your playlists", Icons.Rounded.Folder, "folder"),
    Jam("Jam", "Listen together from anywhere", Icons.Rounded.Headphones, ""),
}

private val SpotifyGreen = Color(0xFF1ED760)

/**
 * Spotify's Create sheet. Playlists open the naming screen and then [onPlaylistCreated]; a folder is
 * added to the library; Blend opens its invite page; Jam is not available yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(
    onDismiss: () -> Unit,
    onPlaylistCreated: (String) -> Unit,
    kinds: List<CreateKind> = CreateKind.entries,
    /** Library folder URI new playlists and folders go into (the top of the library when null). */
    parent: String? = null,
) {
    val context = LocalContext.current
    val graph = rememberAppGraph()
    var naming by rememberSaveable { mutableStateOf<CreateKind?>(null) }
    if (naming == null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            kinds.forEach { kind ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (kind == CreateKind.Jam) {
                            Toast.makeText(context, "Jams are coming soon", Toast.LENGTH_SHORT).show()
                        } else naming = kind
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), Alignment.Center) {
                        Icon(kind.icon, null, Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(kind.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (kind.beta) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Beta", Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Text(kind.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    when (val kind = naming) {
        null -> Unit
        CreateKind.Blend -> BlendInviteScreen(onBack = { naming = null })
        else -> NameScreen(
            "Give your ${kind.label} a name", if (kind == CreateKind.Folder) "New folder" else "My playlist", "Create",
            onCancel = { naming = null },
            submit = { name ->
                when (kind) {
                    CreateKind.Folder -> graph.playlists.createFolder(name, parent)
                    CreateKind.Collaborative -> graph.playlists.create(name).also { graph.playlists.setCollaborative(it, true) }
                    else -> graph.playlists.create(name, public = true)
                }.also { if (kind != CreateKind.Folder && parent != null) graph.playlists.moveToFolder(listOf(it), parent) }
            },
        ) { uri -> if (kind == CreateKind.Folder) onDismiss() else onPlaylistCreated(uri) }
    }
}

/** Slowly drifting color glows behind the naming and Blend screens. */
@Composable
private fun AnimatedGlow(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    val colors = listOf(SpotifyGreen, MaterialTheme.colorScheme.tertiary, Color(0xFF4A3AFF))
    Canvas(modifier.fillMaxSize().background(Color(0xFF121212))) {
        colors.forEachIndexed { i, c ->
            val a = t + i * 2.1f
            // Whole-number frequencies only, so the loop joins up seamlessly.
            val center = Offset(size.width * (0.5f + 0.35f * cos(a)), size.height * (0.35f + 0.25f * sin(2 * a + i)))
            val r = size.minDimension * 0.75f
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent), center, r), r, center)
        }
    }
}

/** Full-screen naming ("Give your playlist a name", renames) with Cancel / [actionLabel], as in Spotify. */
@Composable
internal fun NameScreen(
    title: String,
    initial: String,
    actionLabel: String,
    onCancel: () -> Unit,
    submit: suspend (String) -> String,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val create: () -> Unit = create@{
        if (busy) return@create
        val name = value.text.trim().ifEmpty { initial }
        busy = true
        scope.launch {
            val result = withContext(NonCancellable) { runCatching { submit(name) } }
            busy = false
            result.onSuccess(onDone).onFailure { Toast.makeText(context, friendlyErrorMessage(context, it), Toast.LENGTH_LONG).show() }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            AnimatedGlow()
            Column(
                Modifier.fillMaxSize().imePadding().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(40.dp))
                BasicTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), enabled = !busy, singleLine = true,
                    textStyle = MaterialTheme.typography.displaySmall.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 36.sp),
                    cursorBrush = SolidColor(SpotifyGreen),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                )
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(
                        onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.14f)),
                    ) { Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold) }
                    Button(
                        create, enabled = !busy, modifier = Modifier.heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                        else Text(actionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Serializable
private data class BlendLink(val link: String)

/** Spotify's "Invite friends to Blend" page: the "+" circle and Invite share a new invite link. */
@Composable
private fun BlendInviteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberAppGraph()
    val me by graph.engine.user.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val invite: () -> Unit = invite@{
        if (busy) return@invite
        busy = true
        scope.launch {
            runCatching { graph.rpc.callOffMain<BlendLink>("blend.invite") }
                .onSuccess {
                    val name = me?.displayName?.takeIf(String::isNotBlank) ?: me?.username ?: "A friend"
                    shareText(context, "$name has invited you to join a Blend\n${it.link}", "Blend", "Invite to Blend")
                }
                .onFailure { Toast.makeText(context, friendlyErrorMessage(context, it), Toast.LENGTH_LONG).show() }
            busy = false
        }
    }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            AnimatedGlow()
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Text("Create a Blend", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.width(48.dp))
                }
                Spacer(Modifier.height(32.dp))
                Box(Modifier.width(240.dp), Alignment.Center) {
                    Surface(invite, Modifier.size(140.dp).offset(x = 50.dp), shape = CircleShape, color = Color(0xFF535353)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Invite", Modifier.size(64.dp), tint = Color.Black) }
                    }
                    Artwork(
                        me?.images?.best(160), null, Modifier.size(140.dp).offset(x = (-50).dp).border(3.dp, Color(0xFF121212), CircleShape),
                        CircleShape, Icons.Rounded.Person,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("Invite friends to Blend", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Invite up to 10 friends to a Blend, a shared playlist that gives you social recommendations based on all of your music tastes.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    invite, enabled = !busy,
                    modifier = Modifier.heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("Invite", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
        }
    }
}
