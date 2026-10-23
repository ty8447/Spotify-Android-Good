package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Use a Spotify-provided, verified shortcode when the active Jam status actually
 * includes one. Spotify's Branch shortcodes cannot be derived from a Jam token.
 * Never cache an unrelated user-pasted URL or invent a shortened redirect.
 */
internal object JamInviteLinks {
    private const val CARD_PREFIX = "Join my Jam: "
    private const val CANONICAL_PREFIX = "https://open.spotify.com/socialsession/"
    private const val SHORT_PREFIX = "https://spotify.link/"

    private var observedSessionLink by mutableStateOf<String?>(null)
    private var observedShortLink by mutableStateOf<String?>(null)

    fun canonical(url: String?): String? {
        val value = url?.trim() ?: return null
        if (!value.startsWith(CANONICAL_PREFIX)) return null
        val token = value.removePrefix(CANONICAL_PREFIX)
        return value.takeIf { token.length == 22 && token.all { it.isAsciiLetterOrDigit() } }
    }

    fun short(url: String?): String? {
        val value = url?.trim() ?: return null
        if (!value.startsWith(SHORT_PREFIX)) return null
        val code = value.removePrefix(SHORT_PREFIX)
        // Current fixed-version QR supports at most 84 ASCII bytes.
        return value.takeIf { value.length <= 84 && code.length in 6..55 &&
            code.all { it.isAsciiLetterOrDigit() } }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** Called only with a fresh authenticated active-session reply from Spotify. */
    fun observeSpotifySessionLink(canonicalUrl: String?, spotifyShortUrl: String?) {
        val base = canonical(canonicalUrl) ?: return
        val verifiedFormat = short(spotifyShortUrl)
        if (observedSessionLink != base) {
            observedSessionLink = base
            observedShortLink = verifiedFormat
        } else if (verifiedFormat != null) {
            observedShortLink = verifiedFormat
        }
    }

    fun preferred(canonicalUrl: String?): String? {
        val base = canonical(canonicalUrl) ?: return null
        return if (base == observedSessionLink) observedShortLink ?: base else base
    }

    /** Only an invitation confirmed for this device's Jam may open its own Jam sheet. */
    fun isCurrentSessionInvitation(url: String?): Boolean {
        val value = canonical(url) ?: short(url) ?: return false
        return value == observedSessionLink || (observedShortLink != null && value == observedShortLink)
    }

    fun canonicalForObservedInvite(url: String?): String? =
        observedSessionLink.takeIf { isCurrentSessionInvitation(url) }

    /** A tracked Spotify share can carry the Jam URI instead of a text contribution. */
    fun fromMessage(text: String?, shareUri: String?): String? {
        fromMessage(text)?.let { return it }
        val value = shareUri?.trim() ?: return null
        if (value.startsWith("spotify:socialsession:")) {
            val token = value.removePrefix("spotify:socialsession:")
            return canonical(CANONICAL_PREFIX + token)
        }
        // A generic spotify.link share may instead be a song/album/playlist.
        // Never classify those links as Jams without explicit Jam text.
        return canonical(value) ?: if (text?.trim()?.startsWith(CARD_PREFIX) == true) short(value) else null
    }

    /** Prefix is intentional: an unrelated spotify.link music URL is not a Jam card. */
    fun messageBody(canonicalUrl: String?): String? =
        preferred(canonicalUrl)?.let { CARD_PREFIX + it }

    fun fromMessage(text: String?): String? {
        val value = text?.trim() ?: return null
        canonical(value)?.let { return it } // Existing canonical messages.
        if (!value.startsWith(CARD_PREFIX)) return null
        val url = value.removePrefix(CARD_PREFIX).trim()
        return short(url) ?: canonical(url)
    }
}
