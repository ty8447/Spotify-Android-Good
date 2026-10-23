package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class JamSessionSummary(
    val active: Boolean = false,
    val isSessionOwner: Boolean? = null,
    val isControlling: Boolean? = null,
    val isListening: Boolean? = null,
    val isPaused: Boolean? = null,
    val queueOnlyMode: Boolean? = null,
    val memberCount: Int? = null,
    val maxMemberCount: Int? = null,
)

@Serializable
internal data class JamReadCheck(
    val result: String = "not-checked",
    val httpStatus: Int? = null,
    val bytes: Int = 0,
    val format: String = "unknown",
    val fieldPaths: List<String> = emptyList(),
    val booleanFlags: Map<String, Boolean> = emptyMap(),
    val session: JamSessionSummary? = null,
)

@Serializable
internal data class JamServiceStatus(
    val current: JamReadCheck = JamReadCheck(),
    val device: JamReadCheck = JamReadCheck(),
)

@Serializable
internal data class JamStartResult(
    val result: String = "not-attempted",
    val httpStatus: Int? = null,
    val replyBytes: Int = 0,
    val replyFormat: String = "unknown",
    val current: JamReadCheck? = null,
)

@Serializable
internal data class JamInvitationLink(
    val result: String = "unavailable", val url: String? = null,
    val shortUrl: String? = null,
)

@Serializable
internal data class JamInvitationSendResult(val acknowledged: Boolean = false)

@Serializable
internal data class JamApprovalRequestResult(
    val result: String = "unverified",
    val httpStatus: Int? = null,
)

@Serializable
internal data class JamPeerLookupResult(
    val result: String = "unverified",
    val invitationUrl: String? = null,
)

@Serializable
internal data class JamGuestMutationResult(
    val result: String = "unverified",
    val httpStatus: Int? = null,
    val current: JamReadCheck? = null,
)

@Serializable
internal data class JamLeaveResult(
    val result: String = "not-attempted",
    val httpStatus: Int? = null,
    val current: JamReadCheck? = null,
)

private fun jamStartDescription(result: JamStartResult): String = when (result.result) {
    "active" -> "Your Jam is active. No invitation was sent from this conversation."
    "server-acknowledged" -> "Spotify accepted the creation request, but an active Jam could not be confirmed. " +
        "Check again before trying to start another."
    "request-rejected" -> "Spotify rejected the Jam creation request " +
        "(HTTP ${result.httpStatus ?: "unknown"}). No Jam was confirmed."
    "unverified" -> "Jam creation could not be verified. " +
        "Check the current session before trying again; the request may have reached Spotify."
    else -> "Jam was not started."
}

private fun jamLeaveDescription(result: JamLeaveResult): String = when (result.result) {
    "left" -> "Spotify accepted the leave request. No active session was returned afterward."
    "still-active" -> "Spotify accepted the leave request, but this device still reports an active Jam."
    "server-acknowledged" -> "Spotify accepted the leave request. Confirm the current session before trying again."
    "no-active-session" -> "No active Jam was found to leave."
    "request-rejected" -> "Spotify rejected the leave request (HTTP ${result.httpStatus ?: "unknown"})."
    else -> "Couldn't verify leaving this Jam. Check status before trying again."
}

private fun jamCheckDescription(result: JamReadCheck): String = when (result.result) {
    "reply" -> when (result.session?.active) {
        true -> "Active Jam"
        false -> "Session returned (not active)"
        null -> if (result.bytes > 0) "Service responded; session state not verified"
                else "Service returned an empty response"
    }
    "not-found" -> "No session returned (HTTP 404); possibly no active Jam"
    "request-error" -> "Request unsuccessful (HTTP ${result.httpStatus ?: "unavailable"})"
    "oversized" -> "Response too large to inspect"
    else -> "Not checked"
}

/** Real active-session ribbon shown above the app's existing floating mini-player.
 * No local fake session or message is created: it only renders server-verified state.
 */
@Composable
internal fun ChatJamConversationBar(
    session: JamSessionSummary,
    hostName: String,
    hostAvatarUrl: String?,
    onOpen: () -> Unit,
) {
    if (!session.active) return
    Surface(onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 5.dp),
        shape = RoundedCornerShape(8.dp), color = JamUi.row) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (session.isSessionOwner == false) "Active Jam"
                else if (hostName.isBlank()) "Your Jam" else "$hostName's Jam",
                style = MaterialTheme.typography.titleSmall,
                color = JamUi.white, modifier = Modifier.weight(1f), maxLines = 1)
            JamAvatar(if (session.isSessionOwner == true) hostAvatarUrl else null,
                30, "Jam participant")
            session.memberCount?.takeIf { it > 1 }?.let {
                Text("+${it - 1}", color = JamUi.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}


/** Verified Jam state with single-attempt start and confirmed leave actions. */
@Composable
internal fun ChatJamStatusSheet(
    onDismiss: () -> Unit,
    conversationTitle: String,
    hostName: String,
    hostAvatarUrl: String?,
    conversationAvatarUrl: String?,
    onSessionChanged: (JamSessionSummary?) -> Unit,
    onOpenQueue: () -> Unit,
    onFindSongs: () -> Unit,
    onOpenLikedSongs: () -> Unit,
    initialView: Int = 0,
    conversationId: String? = null,
    onInviteSent: () -> Unit = {},
) {
    val graph = rememberAppGraph()
    var view by remember { mutableIntStateOf(initialView) } // -1 invitation, 0 overview, 1 details, 2 guest settings, 3 add songs
    var snapshot by remember { mutableStateOf<JamServiceStatus?>(null) }
    // Distinguish an ended Jam from an invitation screen that never had a Jam.
    var verifiedActiveSeen by remember { mutableStateOf(JamGlobalState.session?.active == true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var startAttempt by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    var inviteAttempt by remember { mutableIntStateOf(0) }
    var sendingInvite by remember { mutableStateOf(false) }
    var inviteFeedback by remember { mutableStateOf<String?>(null) }
    var inviteAfterStart by remember { mutableStateOf(false) }
    var startResult by remember { mutableStateOf<JamStartResult?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var leaveAttempt by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    var leaveResult by remember { mutableStateOf<JamLeaveResult?>(null) }
    var leaveError by remember { mutableStateOf<String?>(null) }
    var guestChange by remember { mutableIntStateOf(0) }
    var pendingQueueOnlyMode by remember { mutableStateOf<Boolean?>(null) }
    var guestBusy by remember { mutableStateOf(false) }
    var guestFeedback by remember { mutableStateOf<String?>(null) }
    // The invitation sheet overlays Jam details, rather than replacing the session view.
    var showShareSheet by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var shareUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(graph.rpc, refresh) {
        loading = true
        error = null
        try {
            val latest = graph.rpc.call<JamServiceStatus>("catalog.jamServiceStatus", buildJsonObject {})
            val current = latest.current
            if (jamConfirmedEnded(verifiedActiveSeen, current)) {
                onSessionChanged(null)
                android.util.Log.i("SpotifyGoodJam", "session-ended source=sheet-refresh result=${current.result}")
                onDismiss()
                return@LaunchedEffect
            }
            if (current.result == "reply" && current.session?.active == true) {
                verifiedActiveSeen = true
                snapshot = latest
                onSessionChanged(current.session)
            } else if (current.result == "not-found" && !starting && !leaving) {
                snapshot = latest
                onSessionChanged(null)
            } else if (current.result == "reply" && current.session?.active == false) {
                snapshot = latest
                onSessionChanged(null)
            }
            // Transient errors do not invalidate the last confirmed session.
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            snapshot = null
            error = e.message?.take(160) ?: "Jam status could not be checked"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(startAttempt) {
        if (startAttempt == 0) return@LaunchedEffect
        starting = true
        startError = null
        try {
            startResult = graph.rpc.call<JamStartResult>(
                "catalog.jamStartSession", buildJsonObject {}
            )
            if (startResult?.result == "active" && inviteAfterStart) {
                inviteAfterStart = false
                inviteAttempt++
            }
            startResult?.current?.let { current ->
                snapshot = snapshot?.copy(current = current)
                if (current.result == "reply" && current.session?.active == true) {
                    verifiedActiveSeen = true
                    onSessionChanged(current.session)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            startError = e.message?.take(160) ?: "Jam creation could not be verified"
        } finally {
            starting = false
        }
    }

    // Only an explicit Send invite tap reaches this path. Fetch the *current*
    // bearer link immediately before sending; never retry the POST automatically.
    LaunchedEffect(inviteAttempt) {
        if (inviteAttempt == 0) return@LaunchedEffect
        val target = conversationId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sendingInvite = true
        inviteFeedback = null
        try {
            val link = graph.rpc.call<JamInvitationLink>(
                "catalog.jamInvitationLink", buildJsonObject {})
            val url = link.url?.takeIf { link.result == "ready" }
                ?.let(JamInviteLinks::canonical)
            if (url == null) {
                inviteFeedback = "This Jam hasn't returned a shareable invitation link yet."
            } else {
                JamInviteLinks.observeSpotifySessionLink(url, link.shortUrl)
                val sent = graph.rpc.call<JamInvitationSendResult>(
                    "catalog.sendTextMessage", buildJsonObject {
                        put("hierarchyId", target)
                        put("text", JamInviteLinks.messageBody(url) ?: url)
                    })
                if (sent.acknowledged) {
                    onInviteSent()
                    view = 0
                    inviteFeedback = "Jam invitation sent to this conversation."
                } else {
                    inviteFeedback = "Delivery couldn't be confirmed. Check chat history before trying again."
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            inviteFeedback = "Invitation status is uncertain. Check chat history before trying again."
        } finally {
            sendingInvite = false
        }
    }

    // Only an explicit Send invite tap reaches this path. Fetch the *current*
    // bearer link immediately before sending; never retry the POST automatically.
    LaunchedEffect(inviteAttempt) {
        if (inviteAttempt == 0) return@LaunchedEffect
        val target = conversationId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        sendingInvite = true
        inviteFeedback = null
        try {
            val link = graph.rpc.call<JamInvitationLink>(
                "catalog.jamInvitationLink", buildJsonObject {})
            val url = link.url?.takeIf {
                link.result == "ready" &&
                    it.startsWith("https://open.spotify.com/socialsession/") &&
                    it.substringAfterLast('/').length == 22 &&
                    it.substringAfterLast('/').all { ch -> ch.isLetterOrDigit() && ch.code < 128 }
            }
            if (url == null) {
                inviteFeedback = "This Jam hasn't returned a shareable invitation link yet."
            } else {
                val sent = graph.rpc.call<JamInvitationSendResult>(
                    "catalog.sendTextMessage", buildJsonObject {
                        put("hierarchyId", target)
                        put("text", url)
                    })
                if (sent.acknowledged) {
                    onInviteSent()
                    view = 0
                    inviteFeedback = "Jam invitation sent to this conversation."
                } else {
                    inviteFeedback = "Delivery couldn't be confirmed. Check chat history before trying again."
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            inviteFeedback = "Invitation status is uncertain. Check chat history before trying again."
        } finally {
            sendingInvite = false
        }
    }

    LaunchedEffect(leaveAttempt) {
        if (leaveAttempt == 0) return@LaunchedEffect
        leaving = true
        leaveError = null
        try {
            leaveResult = graph.rpc.call<JamLeaveResult>(
                "catalog.jamLeaveSession", buildJsonObject {}
            )
            leaveResult?.current?.let { current ->
                snapshot = snapshot?.copy(current = current)
                onSessionChanged(current.session?.takeIf { it.active })
            }
            refresh++
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            leaveError = e.message?.take(160) ?: "Couldn't verify the Jam leave request"
        } finally {
            leaving = false
        }
    }

    // Heartbeat while *any* Jam sheet page is open, not just guest settings.
    // A one-person Jam can expire server-side without anyone pressing Leave.
    // Read failures are not evidence of an ended session.
    LaunchedEffect(view, guestBusy, graph.rpc) {
        if (view == 2 && guestBusy) return@LaunchedEffect
        while (true) {
            delay(if (view == 2) 5_000L else 8_000L)
            try {
                val latest = graph.rpc.call<JamServiceStatus>(
                    "catalog.jamServiceStatus", buildJsonObject {})
                val current = latest.current
                if (jamConfirmedEnded(verifiedActiveSeen, current)) {
                    onSessionChanged(null)
                    android.util.Log.i("SpotifyGoodJam", "session-ended source=sheet-poll result=${current.result}")
                    onDismiss()
                    return@LaunchedEffect
                }
                if (current.result == "reply" && current.session?.active == true) {
                    verifiedActiveSeen = true
                    snapshot = latest
                    onSessionChanged(current.session)
                } else if (current.result == "not-found" && !starting && !leaving) {
                    snapshot = latest
                    onSessionChanged(null)
                } else if (current.result == "reply" && current.session?.active == false) {
                    snapshot = latest
                    onSessionChanged(null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the previous verified session if Spotify is temporarily unreachable.
            }
        }
    }

    LaunchedEffect(guestChange) {
        val target = pendingQueueOnlyMode ?: return@LaunchedEffect
        if (guestChange == 0) return@LaunchedEffect
        guestBusy = true
        guestFeedback = null
        try {
            val result = graph.rpc.call<JamGuestMutationResult>("catalog.jamSetQueueOnlyMode",
                kotlinx.serialization.json.buildJsonObject { put("enabled", target) })
            result.current?.let { current ->
                snapshot = snapshot?.copy(current = current)
                onSessionChanged(current.session?.takeIf { it.active })
            }
            guestFeedback = when (result.result) {
                "verified", "already-set" -> null
                "not-host" -> "Only the Jam host can change guest controls."
                "request-rejected" -> "Spotify rejected the change (HTTP ${result.httpStatus ?: "unknown"})."
                else -> "Spotify hasn't confirmed the new setting. Refresh Jam status before retrying."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            guestFeedback = "Could not confirm guest controls. Refresh the session before retrying."
        } finally {
            guestBusy = false
            pendingQueueOnlyMode = null
        }
    }

    // Request the real shareable link only while the invite sheet is open.
    LaunchedEffect(showShareSheet, graph.rpc) {
        shareUrl = null
        if (showShareSheet) {
            try {
                val link = graph.rpc.call<JamInvitationLink>(
                    "catalog.jamInvitationLink", buildJsonObject {})
                shareUrl = link.url?.takeIf { link.result == "ready" }
                    ?.let(JamInviteLinks::canonical)
                JamInviteLinks.observeSpotifySessionLink(shareUrl, link.shortUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { /* Keep the share option disabled. */ }
        }
    }

    // Request the real shareable link only while the invite sheet is open.
    LaunchedEffect(showShareSheet, graph.rpc) {
        shareUrl = null
        if (showShareSheet) {
            try {
                val link = graph.rpc.call<JamInvitationLink>(
                    "catalog.jamInvitationLink", buildJsonObject {})
                shareUrl = link.url?.takeIf { url ->
                    link.result == "ready" &&
                        url.startsWith("https://open.spotify.com/socialsession/") &&
                        url.substringAfterLast('/').length == 22 &&
                        url.substringAfterLast('/').all { ch -> ch.isLetterOrDigit() && ch.code < 128 }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { /* Keep the share option disabled. */ }
        }
    }

    if (showShareSheet && snapshot?.current?.session?.active == true) {
        JamShareInviteSheet(
            onDismiss = { showShareSheet = false },
            onOpenQr = { showQrDialog = true },
            shareUrl = shareUrl,
        )
    }
    if (showQrDialog && showShareSheet) {
        JamQrCodeDialog(shareUrl = JamInviteLinks.preferred(shareUrl),
            onDismiss = { showQrDialog = false })
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave this Jam?") },
            text = { Text("You'll leave the current shared session. If you're the host, " +
                "this may affect other participants. This action cannot be undone here.") },
            confirmButton = {
                Button(onClick = {
                    showLeaveConfirm = false
                    leaveAttempt++
                }) { Text("Leave Jam") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLeaveConfirm = false }) { Text("Cancel") }
            },
        )
    }

    val activeSession = snapshot?.current?.session?.takeIf { it.active }
    LaunchedEffect(activeSession?.active) {
        if (activeSession == null) {
            showShareSheet = false
            showQrDialog = false
        }
    }
    // Re-enter each Jam subpage at the top, even if the prior queue had been scrolled.
    val sheetScroll = remember(view) { ScrollState(0) }
    val isOverview = activeSession != null && view == 0
    Column(
        modifier = if (isOverview) {
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).background(JamUi.background)
        } else {
            Modifier.fillMaxWidth().background(JamUi.background).verticalScroll(sheetScroll)
        },
    ) {
        if (loading && snapshot == null) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = JamUi.green)
            }
        } else if (error != null && snapshot == null) {
            Column(Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(error.orEmpty(), color = JamUi.red)
                OutlinedButton(onClick = { refresh++ }) { Text("Try again") }
                TextButton(onClick = onDismiss) { Text("Close", color = JamUi.muted) }
            }
        } else if (activeSession != null && view == -1) {
            JamInvitePage(
                title = conversationTitle,
                hostAvatarUrl = hostAvatarUrl,
                peerAvatarUrl = conversationAvatarUrl,
                session = activeSession,
                canStart = false, startBusy = starting,
                onStart = {}, onViewJam = { view = 0 },
                onInvite = if (conversationId.isNullOrBlank() || inviteAttempt != 0) null else ({ inviteAttempt++ }),
                sendingInvite = sendingInvite,
            )
        } else if (activeSession != null && view == 1) {
            JamDetailsPage(
                session = activeSession, hostName = hostName,
                hostAvatarUrl = hostAvatarUrl,
                onBack = { view = 0 }, onInvite = { showShareSheet = true },
                onGuestSettings = { view = 2 },
                onLeave = { showLeaveConfirm = true },
            )
        } else if (activeSession != null && view == 2) {
            JamGuestSettingsPage(activeSession, onBack = { view = 0 },
                onQueueOnlyModeChange = { enabled ->
                    if (!guestBusy) { pendingQueueOnlyMode = enabled; guestChange++ }
                }, changing = guestBusy, feedback = guestFeedback)
        } else if (activeSession != null && view == 3) {
            JamAddToQueuePage(
                onBack = { view = 0 },
                onSearch = onFindSongs,
                onOpenLikedSongs = onOpenLikedSongs,
            )
        } else if (activeSession != null) {
            JamOverviewPage(
                session = activeSession,
                hostName = hostName,
                hostAvatarUrl = hostAvatarUrl,
                onInvite = { view = 1 },
                onDetails = { view = 1 },
                onGuestSettings = { view = 2 },
                onLeave = { showLeaveConfirm = true },
                onOpenQueue = onOpenQueue,
                onFindSongs = { view = 3 },
            )
        } else {
            JamInvitePage(
                title = conversationTitle,
                hostAvatarUrl = hostAvatarUrl,
                peerAvatarUrl = conversationAvatarUrl,
                session = null,
                canStart = snapshot?.current?.result == "not-found" &&
                    snapshot?.device?.result == "reply" && startResult == null &&
                    startAttempt == 0 && leaveAttempt == 0,
                startBusy = starting,
                onStart = {
                    inviteAfterStart = !conversationId.isNullOrBlank()
                    startAttempt++
                },
                onViewJam = { view = 0 },
                sendAfterStart = !conversationId.isNullOrBlank(),
            )
            if (snapshot?.current?.result != "not-found" && startResult == null) {
                Text("Current session: ${jamCheckDescription(snapshot?.current ?: JamReadCheck())}",
                    color = JamUi.muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (starting || leaving || startError != null || leaveError != null ||
            startResult?.result?.let { it != "active" } == true || leaveResult != null || error != null || inviteFeedback != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (starting) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = JamUi.green)
                    Spacer(Modifier.width(10.dp))
                    Text("Starting Jam…", color = JamUi.white)
                }
            }
            startResult?.let { result ->
                if (result.result != "active") Text(jamStartDescription(result), color = JamUi.muted)
            }
            startError?.let { Text(it, color = JamUi.red) }
            inviteFeedback?.let { Text(it, color = JamUi.muted) }
            if (leaving) Text("Leaving Jam…", color = JamUi.white,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            leaveResult?.let {
                Text(jamLeaveDescription(it), color = JamUi.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            leaveError?.let { Text(it, color = JamUi.red) }
            if (error != null || startError != null || leaveError != null) {
                TextButton(onClick = { refresh++ }, enabled = !loading && !starting && !leaving) {
                    Text("Try again", color = JamUi.muted)
                }
            }
        }
        }
    }

}
