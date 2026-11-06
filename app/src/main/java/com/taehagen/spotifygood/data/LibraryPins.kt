package com.taehagen.spotifygood.data

import android.content.Context
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.ui.screens.library.LibraryItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Account pins with a local offline cache. Pins never imply a library save/follow. */
@Serializable
data class LibraryPin(
    val kind: String,
    val id: String,
    val name: String,
    val creator: String? = null,
    // Defaults preserve pins saved by older versions of Spotify Good.
    val images: List<Image> = emptyList(),
    val ownerUsername: String? = null,
) {
    fun ref(): MediaRef? = when (kind) {
        "PLAYLIST" -> MediaRef(MediaType.PLAYLIST, id, name, creator, images)
        "ALBUM" -> MediaRef(MediaType.ALBUM, id, name, creator, images)
        "SHOW" -> MediaRef(MediaType.SHOW, id, name, creator, images)
        "ARTIST" -> MediaRef(MediaType.ARTIST, id, name, creator, images)
        "LIKED_SONGS" -> MediaRef(MediaType.COLLECTION, id, name, creator, images)
        else -> null
    }
}

/** Spotify's account pin collection stores item URIs, not their names/artwork. */
fun libraryPinFromUri(uri: String, cached: List<LibraryPin> = emptyList()): LibraryPin {
    cached.firstOrNull { it.id == uri }?.let { return it }
    val kind = when {
        uri.startsWith("spotify:playlist:") || uri.contains(":playlist:") -> "PLAYLIST"
        uri.startsWith("spotify:album:") -> "ALBUM"
        uri.startsWith("spotify:artist:") -> "ARTIST"
        uri.startsWith("spotify:show:") -> "SHOW"
        uri.startsWith("spotify:folder:") || uri.contains(":folder:") -> "FOLDER"
        uri.contains(":collection") || uri.startsWith("spotify:collection:") -> "LIKED_SONGS"
        else -> "UNKNOWN"
    }
    return LibraryPin(kind, uri, if (kind == "LIKED_SONGS") "Liked Songs" else uri)
}

/** A pending edit survives process death and reconnects; a write is not presumed successful. */
@Serializable
data class PinPendingWrite(val uri: String, val pinned: Boolean)

/** These URI aliases all navigate to the single built-in Liked Songs collection. */
fun LibraryPin.isLikedSongsPin(): Boolean = kind == "LIKED_SONGS" ||
    id.endsWith(":collection") || id == "spotify:collection:tracks" || id == "spotify:collection"

fun LibraryItem.libraryPin() = LibraryPin(kind.name, id, name, creator, images, ownerUsername)

class LibraryPins(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("library_shortcuts_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = ListSerializer(LibraryPin.serializer())
    private val orderCodec = ListSerializer(String.serializer())

    private fun decodeOrder(raw: String?): List<String>? = raw?.let {
        runCatching { json.decodeFromString(orderCodec, it) }.getOrNull()
    }

    /** Anonymous state is not saved; shortcuts must never leak across Spotify accounts. */
    @Synchronized fun read(username: String?): List<LibraryPin> {
        if (username.isNullOrBlank()) return emptyList()
        val raw = prefs.getString(key(username), null) ?: return emptyList()
        return runCatching { json.decodeFromString(codec, raw) }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.kind to it.id }.take(MAX_PINS)
    }

    @Synchronized fun toggle(username: String?, item: LibraryPin): List<LibraryPin> {
        if (username.isNullOrBlank()) return emptyList()
        val old = read(username)
        val next = if (old.any { it.kind == item.kind && it.id == item.id })
            old.filterNot { it.kind == item.kind && it.id == item.id }
        else (old + item).takeLast(MAX_PINS)
        prefs.edit().putString(key(username), json.encodeToString(codec, next)).apply()
        updates.tryEmit(key(username))
        return next
    }

    /** Move a local pin immediately; AccountPinsRepository persists its order remotely. */
    @Synchronized fun move(username: String?, from: Int, to: Int): List<LibraryPin> {
        if (username.isNullOrBlank()) return emptyList()
        val old = read(username)
        val next = reorderLibraryPins(old, from, to)
        if (next == old) return old
        prefs.edit().putString(key(username), json.encodeToString(codec, next)).apply()
        updates.tryEmit(key(username))
        return next
    }

    @Synchronized fun hasSynced(username: String): Boolean = prefs.getBoolean("synced:" + key(username), false)

    @Synchronized fun pending(username: String): List<PinPendingWrite> {
        val raw = prefs.getString("pending:" + key(username), null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PinPendingWrite.serializer()), raw) }.getOrDefault(emptyList())
    }

    @Synchronized fun queue(username: String, uri: String, pinned: Boolean) {
        val old = pending(username).filterNot { it.uri == uri }
        val next = old + PinPendingWrite(uri, pinned)
        prefs.edit().putString("pending:" + key(username), json.encodeToString(ListSerializer(PinPendingWrite.serializer()), next)).apply()
    }

    @Synchronized fun reorderQueued(username: String): Boolean = prefs.getBoolean("reorder:" + key(username), false)

    /** Order observed on the server *before* the user's first unsent reorder. */
    @Synchronized fun reorderBase(username: String): List<String>? =
        decodeOrder(prefs.getString("reorderBase:" + key(username), null))

    @Synchronized fun markReorder(username: String) {
        val k = key(username)
        val edit = prefs.edit().putBoolean("reorder:" + k, true)
        if (!prefs.contains("reorderBase:" + k)) {
            prefs.getString("serverOrder:" + k, null)?.let { edit.putString("reorderBase:" + k, it) }
        }
        edit.apply()
    }

    /** Called only after both the writes and the full account re-read succeeded. */
    @Synchronized fun acceptServer(username: String, pins: List<LibraryPin>) {
        val k = key(username)
        val editor = prefs.edit()
        if (!prefs.contains("backup:" + k)) {
            editor.putString("backup:" + k, prefs.getString(k, "[]") ?: "[]")
        }
        editor.putString(k, json.encodeToString(codec, pins.distinctBy { it.id }.take(MAX_PINS)))
        editor.putString("serverOrder:" + k, json.encodeToString(orderCodec,
            pins.filter { it.id.startsWith("spotify:") }.map { it.id }))
        editor.putBoolean("synced:" + k, true)
        editor.remove("pending:" + k)
        editor.remove("reorder:" + k)
        editor.remove("reorderBase:" + k)
        editor.apply()
        updates.tryEmit(k)
    }

    private fun key(username: String) = "pins:" + username.lowercase(java.util.Locale.ROOT)
    companion object {
        const val MAX_PINS = 200
        private val updates = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val changes = updates.asSharedFlow()
        fun accountKey(username: String) = "pins:" + username.lowercase(java.util.Locale.ROOT)
    }
}

/** Pure reorder helper: invalid moves leave pins untouched; moves preserve all identities. */
fun reorderLibraryPins(pins: List<LibraryPin>, from: Int, to: Int): List<LibraryPin> {
    if (from !in pins.indices || to !in pins.indices || from == to) return pins
    return pins.toMutableList().apply { add(to, removeAt(from)) }
}
