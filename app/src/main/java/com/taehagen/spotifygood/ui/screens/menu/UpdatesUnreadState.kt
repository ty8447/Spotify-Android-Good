package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.taehagen.spotifygood.model.SpotifyNotification

/** Authoritative unread update count, shared by Home, Library, and the profile drawer.
 * No inference from chat badge counts, and no optimistic read acknowledgments. */
internal object UpdatesUnreadState {
    var unreadCount by mutableIntStateOf(0)
        private set

    fun update(notifications: List<SpotifyNotification>) {
        val fresh = notifications.count { it.isNew }
        if (unreadCount != fresh) unreadCount = fresh
    }

    fun clear() { unreadCount = 0 }
}
