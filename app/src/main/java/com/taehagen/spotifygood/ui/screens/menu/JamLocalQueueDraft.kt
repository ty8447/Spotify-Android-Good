package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.runtime.mutableStateListOf
import com.taehagen.spotifygood.model.Track

/** Only a device-local draft. Never publishes a remote queue mutation until Play. */
internal object JamLocalQueueDraft {
    private var owner: String? = null
    private var seeded = false
    private var touched = false
    val tracks = mutableStateListOf<Track>()

    fun forAccount(username: String?) {
        if (owner == username) return
        owner = username
        tracks.clear()
        seeded = false
        touched = false
    }

    fun seed(username: String?, items: List<Track>) {
        if (username.isNullOrBlank()) return
        forAccount(username)
        if (seeded || touched) return
        tracks.addAll(items.filter { it.uri.startsWith("spotify:track:") && it.playable })
        seeded = true
    }

    fun add(username: String?, item: Track): Boolean {
        if (username.isNullOrBlank() || !item.uri.startsWith("spotify:track:") || !item.playable) return false
        forAccount(username)
        tracks.add(item) // Keep identical tracks as distinct queue occurrences.
        touched = true
        return true
    }

    fun remove(index: Int) {
        if (index in tracks.indices) { tracks.removeAt(index); touched = true }
    }

    fun move(from: Int, to: Int) {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        tracks.add(to, tracks.removeAt(from))
        touched = true
    }
}
