package com.taehagen.spotifygood.ui.screens.menu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Read-only chat/activity requests: one initial call plus three bounded retries.
 * Cancellation (screen closed, account changed, or request superseded) is never swallowed.
 * Callers show Retry after this function throws on the final failure.
 */
internal suspend fun <T> retryChatRead(
    attempts: Int = 4,
    request: suspend () -> T,
): T {
    require(attempts in 1..5)
    var backoffMs = 400L
    repeat(attempts) { index ->
        try {
            return request()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (index == attempts - 1) throw failure
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(2_000L)
        }
    }
    error("Unreachable: retry loop always returns or throws")
}
