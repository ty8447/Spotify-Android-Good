package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class InitialChatReply(val acknowledged: Boolean = false, val hierarchyId: String = "")

@Serializable
private data class InitialTextReply(val acknowledged: Boolean = false)

/** Local-only composer until the user actually presses Send.
 * No group-creation or invitation RPC occurs while opening this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PendingDirectMessageScreen(username: String, title: String, contentPadding: PaddingValues) {
    val graph = rememberAppGraph()
    val navigator = LocalAppNavigator.current
    val self = graph.engine.user.collectAsStateWithLifecycle().value?.username.orEmpty()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(username) { mutableStateOf("") }
    var sending by remember(username) { mutableStateOf(false) }
    var error by remember(username) { mutableStateOf<String?>(null) }
    // Retain a confirmed hierarchy across failures; never create it twice.
    var confirmedHierarchy by remember(username) { mutableStateOf<String?>(null) }
    var outcomeUncertain by remember(username) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = { navigator.back() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        })
        Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Start a conversation", style = MaterialTheme.typography.titleMedium)
            Text("Your message invitation will only be sent when you press Send.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, onValueChange = { draft = it.take(4096) },
                modifier = Modifier.weight(1f), maxLines = 5, label = { Text("Message") },
                enabled = !sending && !outcomeUncertain)
            Button(enabled = !sending && !outcomeUncertain && draft.isNotBlank() && self.isNotBlank(),
                onClick = {
                    val submitted = draft.trim()
                    if (submitted.isEmpty()) return@Button
                    sending = true
                    error = null
                    scope.launch {
                        try {
                            // The directory is authoritative, even if the suggestion list
                            // failed to load earlier. Abort safely if it is unavailable.
                            var target = confirmedHierarchy
                            if (target == null) {
                                val overview = graph.rpc.call<MessageOverviewResult>("catalog.messagesOverview")
                                val existing = existingDirectConversation(overview.conversations, self, username)
                                target = existing?.hierarchyId
                            }
                            if (target == null) {
                                val created = graph.rpc.call<InitialChatReply>(
                                    "catalog.createGroupChat", buildJsonObject {
                                        put("memberUsernames", JsonArray(listOf(JsonPrimitive(username))))
                                    })
                                if (!created.acknowledged || created.hierarchyId.isBlank()) {
                                    outcomeUncertain = true
                                    error = "Chat creation could not be confirmed. Check Messages before trying again."
                                    return@launch
                                }
                                target = created.hierarchyId
                            }
                            val targetId = target ?: return@launch
                            confirmedHierarchy = targetId
                            // One submission only. Do not retry ambiguous writes automatically.
                            val sent = graph.rpc.call<InitialTextReply>(
                                "catalog.sendTextMessage", buildJsonObject {
                                    put("hierarchyId", targetId)
                                    put("text", submitted)
                                })
                            if (!sent.acknowledged) {
                                outcomeUncertain = true
                                error = "Message delivery is uncertain. Check the conversation before retrying."
                                return@launch
                            }
                            navigator.navigate(Route.MessageConversation(targetId, title))
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (failure: Exception) {
                            error = failure.message?.take(180) ?: "Unable to send message"
                            // If a chat exists, the user can inspect it before another send.
                            if (confirmedHierarchy != null) outcomeUncertain = true
                        } finally {
                            sending = false
                        }
                    }
                }) {
                if (sending) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("Send")
            }
        }
        if (outcomeUncertain) {
            Button(onClick = {
                val id = confirmedHierarchy
                if (id != null) navigator.navigate(Route.MessageConversation(id, title))
                else navigator.navigate(Route.Messages)
            }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (confirmedHierarchy == null) "Check Messages" else "Open conversation")
            }
        }
    }
}
