package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class JamIncomingJoinRequest(
    val username: String = "",
    val playbackControl: String? = null,
    val requestType: String? = null,
)

@Serializable
internal data class JamIncomingJoinInbox(
    val result: String = "unavailable",
    val requests: List<JamIncomingJoinRequest> = emptyList(),
)

@Serializable
internal data class JamHostApprovalResult(
    val result: String = "unverified",
    val httpStatus: Int? = null,
    val current: JamReadCheck? = null,
)

/**
 * The official client reads incoming approvals from the device's JamStatus
 * request_queue.pending_requests. Show this independently of JamGlobalState:
 * a listener can receive Ask to Join *before* they have created a Jam.
 * The authenticated server alone decides whether approving creates a Jam.
 */
@Composable
internal fun JamIncomingRequestsHost(
    account: String?,
    online: Boolean,
    onServerRead: (JamReadCheck) -> Unit,
    onJamActivated: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val graph = rememberAppGraph()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var pending by remember(account) { mutableStateOf<JamIncomingJoinRequest?>(null) }
    var suppressed by remember(account) { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember(account) { mutableStateOf(false) }
    var feedback by remember(account) { mutableStateOf<String?>(null) }

    LaunchedEffect(graph.rpc, account, online, lifecycle) {
        pending = null
        suppressed = emptySet()
        if (account.isNullOrBlank() || !online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val inbox = withTimeoutOrNull(9_000L) {
                        graph.rpc.call<JamIncomingJoinInbox>("catalog.jamPendingRequests", buildJsonObject {})
                    }
                    if (inbox?.result == "ready") {
                        val usernames = inbox.requests.map { it.username }.toSet()
                        suppressed = suppressed.intersect(usernames)
                        if (!busy) {
                            // Keep the displayed request until a user acts or it leaves the queue.
                            pending = pending?.takeIf { it.username in usernames }
                                ?: inbox.requests.firstOrNull { it.username !in suppressed }
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Network and schema failures must not impersonate an empty queue.
                }
                delay(8_000L)
            }
        }
    }

    val request = pending ?: return
    AlertDialog(
        onDismissRequest = {
            if (!busy) {
                suppressed = suppressed + request.username
                pending = null
                feedback = null
            }
        },
        title = { Text("${request.username} wants to join") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("They've asked to join your listening session. Approving lets Spotify create or update your Jam.")
                if (request.playbackControl?.equals("LISTEN_AND_CONTROL", ignoreCase = true) == true) {
                    Text("They requested to listen and control playback.",
                        style = MaterialTheme.typography.bodySmall)
                }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                if (busy) return@Button
                busy = true
                feedback = null
                scope.launch {
                    try {
                        val result = graph.rpc.call<JamHostApprovalResult>(
                            "catalog.jamRespondToJoin", buildJsonObject {
                                put("username", request.username)
                                put("accept", true)
                            })
                        when (result.result) {
                            "active" -> {
                                result.current?.let(onServerRead)
                                suppressed = suppressed + request.username
                                pending = null
                                onNotice("Join request approved. Your Jam is active.")
                                onJamActivated()
                            }
                            "accepted-unverified-session" -> {
                                suppressed = suppressed + request.username
                                pending = null
                                onNotice("Spotify accepted the request. Jam activation is not yet verified.")
                            }
                            "no-longer-pending" -> {
                                suppressed = suppressed + request.username
                                pending = null
                                onNotice("This join request is no longer pending.")
                            }
                            else -> feedback = "Could not confirm approval (${result.result}). Check the request before retrying."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        feedback = "Could not confirm approval. Check before retrying."
                    } finally {
                        busy = false
                    }
                }
            }) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Accept")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = {
                    if (busy) return@TextButton
                    busy = true
                    feedback = null
                    scope.launch {
                        try {
                            val result = graph.rpc.call<JamHostApprovalResult>(
                                "catalog.jamRespondToJoin", buildJsonObject {
                                    put("username", request.username)
                                    put("accept", false)
                                })
                            when (result.result) {
                                "declined", "no-longer-pending" -> {
                                    suppressed = suppressed + request.username
                                    pending = null
                                    onNotice("Join request declined.")
                                }
                                else -> feedback = "Could not confirm declining the request (${result.result})."
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            feedback = "Could not confirm declining the request."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Decline") }
                TextButton(enabled = !busy, onClick = {
                    suppressed = suppressed + request.username
                    pending = null
                    feedback = null
                }) { Text("Later") }
            }
        },
    )
}
