package com.taehagen.spotifygood.ui.screens.profile

import android.os.SystemClock
import com.taehagen.spotifygood.AppGraph
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Null means unknown: 429s must never be misrepresented as "not following". */
internal data class ProfileRelationship(val following: Boolean? = null, val followsYou: Boolean? = null)

/** Shared one-account cache. The lists are our own, never the target's private followers. */
internal object ProfileRelationships {
    private val mutex = Mutex()
    private var account: String? = null
    private var refreshedAt: Long = 0
    private var followingNames: Set<String>? = null
    private var followerNames: Set<String>? = null
    private const val CACHE_MS = 10 * 60 * 1000L
    private const val ERROR_COOLDOWN_MS = 2 * 60 * 1000L
    private var errorUntil: Long = 0

    suspend fun check(graph: AppGraph, me: String, target: String): ProfileRelationship = mutex.withLock {
        if (!me.equals(account, ignoreCase = true)) {
            account = me
            refreshedAt = 0
            followingNames = null
            followerNames = null
            errorUntil = 0
        }
        val now = SystemClock.elapsedRealtime()
        if (now >= errorUntil && (refreshedAt == 0L || now - refreshedAt > CACHE_MS)) {
            // Preserve previously verified data on an intermittent error.
            var failed = false
            try {
                followingNames = graph.catalog.socialList("following").map { it.username.lowercase() }.toSet()
            } catch (_: Exception) { failed = true }
            if (!failed) {
                try {
                    followerNames = graph.catalog.socialList("followers").map { it.username.lowercase() }.toSet()
                } catch (_: Exception) { failed = true }
            }
            if (failed) errorUntil = now + ERROR_COOLDOWN_MS else refreshedAt = now
        }
        val normalized = target.lowercase()
        ProfileRelationship(followingNames?.contains(normalized), followerNames?.contains(normalized))
    }

    suspend fun updatedFollowing(me: String, target: String, following: Boolean) = mutex.withLock {
        if (me.equals(account, ignoreCase = true)) {
            followingNames = followingNames?.let { names ->
                if (following) names + target.lowercase() else names - target.lowercase()
            }
        }
    }
}
