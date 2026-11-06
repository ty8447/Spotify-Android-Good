package com.taehagen.spotifygood.playback

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem

/**
 * A voice "play X" request as both of its entries receive it (docs/ARCHITECTURE.md §9.4): the
 * media session (Assistant, Android Auto: a set item with a search query, `PlaybackService`) and
 * the activity (`MEDIA_PLAY_FROM_SEARCH` through `LinkActivity`, `ShellViewModel.playFromSearch`).
 * Both hand it to the one resolver, [LibraryTree.resolveVoice]: [query] is what was said, [focus]
 * `MediaStore.EXTRA_MEDIA_FOCUS`, [names] the names the assistant split out per kind
 * (`EXTRA_MEDIA_PLAYLIST`, `_ALBUM`, `_ARTIST`, `_TITLE`).
 */
internal data class VoiceRequest(
    val query: String,
    val focus: String? = null,
    val names: Map<VoiceMatch.Kind, String> = emptyMap(),
) {
    /** "Play something": nothing named. */
    val isBlank: Boolean get() = query.isBlank() && names.isEmpty()

    /** The kinds asked for ([VoiceMatch.kindsFor]). */
    val kinds: Set<VoiceMatch.Kind>
        get() = if (focus != null) VoiceMatch.kindsFor(focus) else when {
            VoiceMatch.Kind.PLAYLIST in names -> setOf(VoiceMatch.Kind.PLAYLIST)
            VoiceMatch.Kind.ALBUM in names -> setOf(VoiceMatch.Kind.ALBUM)
            VoiceMatch.Kind.SONG in names -> setOf(VoiceMatch.Kind.SONG)
            VoiceMatch.Kind.ARTIST in names -> setOf(VoiceMatch.Kind.ARTIST)
            else -> VoiceMatch.kindsFor(null)
        }

    /** A structured voice hint can identify the target even when Gemini sends no query text. */
    val catalogFocus: String?
        get() = focus ?: when {
            VoiceMatch.Kind.PLAYLIST in names -> VoiceMatch.PLAYLIST_FOCUS
            VoiceMatch.Kind.ALBUM in names -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
            VoiceMatch.Kind.SONG in names -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
            VoiceMatch.Kind.ARTIST in names -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            else -> null
        }

    /** Prefer the extracted media name to the entire spoken sentence when both are provided. */
    val text: String
        get() = when {
            VoiceMatch.Kind.PLAYLIST in names -> names.getValue(VoiceMatch.Kind.PLAYLIST)
            VoiceMatch.Kind.ALBUM in names -> names.getValue(VoiceMatch.Kind.ALBUM)
            VoiceMatch.Kind.SONG in names -> listOfNotNull(
                names[VoiceMatch.Kind.SONG], names[VoiceMatch.Kind.ARTIST],
            ).joinToString(" ")
            VoiceMatch.Kind.ARTIST in names -> names.getValue(VoiceMatch.Kind.ARTIST)
            else -> query.trim()
        }

    companion object {
        fun of(query: String?, focus: String?, artist: String?, album: String?, title: String?, playlist: String?): VoiceRequest =
            VoiceRequest(
                query = query.orEmpty(),
                focus = focus,
                names = buildMap {
                    playlist?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.PLAYLIST, it) }
                    album?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.ALBUM, it) }
                    artist?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.ARTIST, it) }
                    title?.takeIf { it.isNotBlank() }?.let { put(VoiceMatch.Kind.SONG, it) }
                },
            )

        /** A media session's request: the item's search query and its request extras. */
        fun of(query: String, extras: Bundle?): VoiceRequest = of(
            query,
            extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
        )
    }
}

/** What a [VoiceRequest] comes to, on either entry. */
internal sealed interface VoiceOutcome {
    /** Play these (a collection as its context, or songs / episodes as a list). */
    data class Play(val items: List<MediaItem>) : VoiceOutcome

    /** "Play something": the session's stored session, or playback resumed. */
    data object PlaySomething : VoiceOutcome

    /** Nothing matches: Not found, or offline Not available offline. */
    data class NoMatch(val kind: PlaybackErrorKind) : VoiceOutcome

    companion object {
        /** The outcome of a resolved request: its [items], or no match ([offline]: not downloaded). */
        fun of(items: List<MediaItem>, offline: Boolean): VoiceOutcome = when {
            items.isNotEmpty() -> Play(items)
            offline -> NoMatch(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE)
            else -> NoMatch(PlaybackErrorKind.NOT_FOUND)
        }
    }
}

/**
 * How both entries resolve a [VoiceRequest]: the catalog's search needs the session, so unless the
 * request is blank ("play something") or the phone is offline ([isOffline]: offline mode, or no
 * network as of now, also read while the engine is stopped) it waits for the session first
 * ([awaitSession], bounded). The caller holds the engine meanwhile: an idle-stopped session only
 * starts for a holder (the activity's MEDIA_PLAY_FROM_SEARCH arrives before its own UI holder).
 */
internal object VoiceEntry {
    suspend fun <T> resolve(
        request: VoiceRequest,
        isOffline: () -> Boolean,
        awaitSession: suspend () -> Unit,
        resolve: suspend (VoiceRequest) -> T,
    ): T {
        if (!request.isBlank && !isOffline()) awaitSession()
        return resolve(request)
    }
}
