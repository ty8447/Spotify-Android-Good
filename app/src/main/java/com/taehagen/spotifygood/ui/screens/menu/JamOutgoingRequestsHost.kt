package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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

internal data class JamOutgoingPending(
    val account: String,
    val hostUsername: String,
    val hostName: String,
    val hostAvatar: String?,
    val createdAtMs: Long,
)

/** UI state only. No requests are sent unless the backend confirmed `pending`. */
internal object JamOutgoingRequestState {
    var pending by mutableStateOf<JamOutgoingPending?>(null)
    var showSheet by mutableStateOf(false)
    fun begin(account: String, host: String, name: String, avatar: String?) {
        pending = JamOutgoingPending(account, host, name.ifBlank { host }, avatar,
            android.os.SystemClock.elapsedRealtime())
        showSheet = true
    }
    fun clear() { pending = null; showSheet = false }
}

@Serializable
internal data class JamOutgoingStatus(val result: String = "unverified")

@Serializable
internal data class JamOutgoingCancel(
    val result: String = "unverified",
    val httpStatus: Int? = null,
)

/** Docked above the mini-player; tapping it opens the request status/cancel sheet. */
@Composable
internal fun JamWaitingBanner(request: JamOutgoingPending) {
    Surface(onClick = { JamOutgoingRequestState.showSheet = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 4.dp),
        color = JamUi.row) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp,
                color = JamUi.white)
            Text("Waiting for ${request.hostName} to accept…", color = JamUi.white,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            JamAvatar(request.hostAvatar, 30, "Host")
        }
    }
}

@Composable
internal fun JamOutgoingRequestsHost(
    account: String?, online: Boolean, onNotice: (String) -> Unit,
) {
    val graph = rememberAppGraph()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val request = JamOutgoingRequestState.pending
    var busy by remember(request?.hostUsername) { mutableStateOf(false) }
    var feedback by remember(request?.hostUsername) { mutableStateOf<String?>(null) }

    LaunchedEffect(account) {
        if (JamOutgoingRequestState.pending?.account != account) JamOutgoingRequestState.clear()
    }
    LaunchedEffect(account, online, request?.hostUsername, graph.rpc, lifecycle) {
        val item = request ?: return@LaunchedEffect
        val host = item.hostUsername
        if (!online || account != item.account) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val result = withTimeoutOrNull(8_000L) {
                        graph.rpc.call<JamOutgoingStatus>("catalog.jamOutgoingStatus",
                            buildJsonObject { put("hostUsername", host) })
                    }
                    if (JamOutgoingRequestState.pending?.hostUsername == host) {
                        when (result?.result) {
                            "pending" -> Unit
                            "not-pending" -> {
                                // Do not mistake a just-submitted request for a decline while
                                // its status propagates. A missing pending request does not
                                // prove *why* it ended.
                                if (android.os.SystemClock.elapsedRealtime() - item.createdAtMs > 12_000L) {
                                    val accepted = JamGlobalState.session?.active == true
                                    JamOutgoingRequestState.clear()
                                    onNotice(if (accepted) "Jam request accepted."
                                        else "Jam request is no longer pending. It may have been declined or expired.")
                                    break
                                }
                            }
                        }
                    } else break
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Retain last verified pending state. */ }
                delay(5_000L)
            }
        }
    }
    if (request == null || !JamOutgoingRequestState.showSheet) return
    AlertDialog(
        onDismissRequest = { if (!busy) JamOutgoingRequestState.showSheet = false },
        title = { Text("Waiting for ${request.hostName} to accept your Jam request",
            textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                JamAvatar(null, 56, "You", letter = request.account.take(1).uppercase())
                JamAvatar(request.hostAvatar, 56, "Jam host")
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp,
                    color = JamUi.green)
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            Button(enabled = !busy && online, onClick = {
                if (busy) return@Button
                busy = true
                feedback = null
                scope.launch {
                    try {
                        val result = graph.rpc.call<JamOutgoingCancel>(
                            "catalog.jamCancelOutgoingRequest", buildJsonObject {
                                put("hostUsername", request.hostUsername)
                            })
                        if (result.result == "cancelled") {
                            JamOutgoingRequestState.clear()
                            onNotice("Jam request canceled.")
                        } else feedback = "Cancellation could not be confirmed (${result.result})."
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { feedback = "Cancellation could not be confirmed." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Canceling…" else "Cancel request") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                JamOutgoingRequestState.showSheet = false
            }) { Text("Close") }
        },
    )
}
