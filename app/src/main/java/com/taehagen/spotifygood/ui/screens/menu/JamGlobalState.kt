package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One app-wide presentation state for Spotify's server-verified active Jam. */
/** Only a verified HTTP 404 or an explicitly inactive decoded session ends a Jam.
 * A timeout, 401, 500, or undecodable 200 must never dismiss the user's sheet.
 */
internal fun jamConfirmedEnded(previouslyActive: Boolean, current: JamReadCheck): Boolean =
    previouslyActive && (current.result == "not-found" ||
        (current.result == "reply" && current.session?.active == false))

internal object JamGlobalState {
    var session by mutableStateOf<JamSessionSummary?>(null)
    var showSheet by mutableStateOf(false)
    fun open() { showSheet = true }
    fun close() { showSheet = false }

    /** Shared read-only heartbeat for the Jam bar and the globally opened Jam sheet. */
    fun acceptServerRead(current: JamReadCheck) {
        if (jamConfirmedEnded(session?.active == true, current)) {
            session = null
            showSheet = false
            android.util.Log.i("SpotifyGoodJam", "session-ended source=global result=${current.result}")
            return
        }
        when (current.result) {
            "not-found" -> session = null
            "reply" -> current.session?.let { session = it.takeIf(JamSessionSummary::active) }
            // Do not erase the last verified state on a transient read failure.
        }
    }
}
