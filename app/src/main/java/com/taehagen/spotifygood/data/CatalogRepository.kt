package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.FriendActivity
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.SpotifyNotification
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * Read-only catalog pages (native `catalog.*`). Flows are cached (stale-while-revalidate): album and
 * artist 24 h, playlist first page 10 min, show 1 h. They reload by themselves when their cache entry
 * is invalidated (playlist edits, follow/unfollow) and therefore never complete.
 *
 * A page the engine marks `partial` (some item metadata failed, docs §6.3) is shown but not cached as
 * fresh, and is refetched while on screen ([ResponseCache.resourceOf]).
 */
/**
 * [progress]: learns Spotify's podcast played state from every fresh answer that carries it (show
 * pages, episodes), once, where the answer arrives (docs §6.5).
 */
class CatalogRepository(
    private val rpc: NativeRpc,
    private val cache: ResponseCache,
    private val progress: EpisodeProgressStore? = null,
) {
    fun album(uri: String): Flow<Resource<Album>> =
        cache.liveOf(CacheKeys.album(uri), Album.serializer(), CacheKeys.TTL_ALBUM) {
            rpc.callOffMain<Album>("catalog.album", rpcArgs { put("uri", uri) }).let { CacheFill(it, it.partial) }
        }

    fun artist(uri: String): Flow<Resource<Artist>> =
        cache.liveOf(CacheKeys.artist(uri), Artist.serializer(), CacheKeys.TTL_ARTIST) {
            rpc.callOffMain<Artist>("catalog.artist", rpcArgs { put("uri", uri) }).let { CacheFill(it, it.partial) }
        }

    /** First page of a playlist (items 0..[pageSize]). */
    fun playlist(uri: String, pageSize: Int = 100): Flow<Resource<Playlist>> =
        cache.liveOf(CacheKeys.playlist(uri, pageSize), Playlist.serializer(), CacheKeys.TTL_PLAYLIST) {
            playlistPage(uri, 0, pageSize).let { CacheFill(it, it.partial) }
        }

    /** Further playlist pages (not cached). */
    suspend fun playlistPage(uri: String, offset: Int, limit: Int = 100): Playlist =
        rpc.callOffMain("catalog.playlist", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    /**
     * A window of a playlist's item URIs (and uids) without their metadata
     * (`catalog.playlistUris`): what an add needs, a small fraction of a [playlistPage].
     */
    suspend fun playlistUrisPage(uri: String, offset: Int, limit: Int = PLAYLIST_MAX_ITEMS): PlaylistUris =
        rpc.callOffMain("catalog.playlistUris", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    /**
     * The playlist's current revision, from the smallest request that tells it: one item's URI, no
     * metadata (the open page's check for changes made elsewhere, docs §9.9).
     */
    suspend fun playlistRevision(uri: String): String? = playlistUrisPage(uri, 0, 1).revision

    /**
     * The cached pages of playlist [uri] go stale without reloading what shows them (it changed
     * elsewhere; the page on screen refreshes itself): a reopened page revalidates. No request.
     */
    suspend fun markPlaylistStale(uri: String) {
        cache.markStalePrefix(CacheKeys.playlistPrefix(uri))
        val canonical = SpotifyUris.playlistKey(uri)
        if (canonical != uri) cache.markStalePrefix(CacheKeys.playlistPrefix(canonical))
    }

    /**
     * A playlist's tracks and episodes in order (local files and other entries left out, as the
     * queue and other playlists can't take them), for a whole-playlist action: "Add to queue",
     * "Add to other playlist". At most [PLAYLIST_MAX_ITEMS] (no playlist holds more), URIs only
     * (no item metadata), in a bounded number of small requests ([pagePlaylist]): usually one.
     */
    suspend fun playlistItemUris(uri: String): List<String> = playableItemUris(uri, ::playlistUrisPage)

    /**
     * The show's first page. Spotify's played state (resume points) only comes with a fresh answer:
     * cached copies (fresh within the TTL, or shown while revalidating / offline) carry none, so
     * an old state can never stand for the current one (docs §6.5, [EpisodeProgressStore.observe]).
     */
    fun show(uri: String): Flow<Resource<Show>> =
        cache.liveOf(CacheKeys.show(uri), Show.serializer(), CacheKeys.TTL_SHOW) { showPage(uri, 0).let { CacheFill(it, it.partial) } }
            .map { it.withoutCachedPlayedState() }

    suspend fun showPage(uri: String, offset: Int, limit: Int = 50): Show {
        val requestedAt = System.currentTimeMillis()
        val page = rpc.callOffMain<Show>("catalog.show", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })
        progress?.observe(page.episodes, requestedAt)
        return page
    }

    suspend fun tracks(uris: List<String>): List<Track> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            rpc.callOffMain<TracksResult>("catalog.tracks", rpcArgs { putStrings("uris", chunk) }).tracks
        }
    }

    suspend fun episodes(uris: List<String>): List<Episode> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            val requestedAt = System.currentTimeMillis()
            rpc.callOffMain<EpisodesResult>("catalog.episodes", rpcArgs { putStrings("uris", chunk) }).episodes
                .also { progress?.observe(it, requestedAt) }
        }
    }

    suspend fun episode(uri: String): Episode? = episodes(listOf(uri)).firstOrNull()

    suspend fun recentlyPlayed(limit: Int = 50): List<MediaRef> =
        rpc.callOffMain<MediaRefs>("catalog.recentlyPlayed", rpcArgs { put("limit", limit) }).items

    /** Timestamped playback contexts; no guessed song counts. */
    suspend fun recentHistory(limit: Int = 50): List<RecentHistoryEntry> =
        rpc.callOffMain<RecentHistoryResult>("catalog.recentHistory", rpcArgs { put("limit", limit) }).items

    /** The last (up to 50) songs played, newest first, each with when and where it played. */
    suspend fun playHistory(): List<PlayedTrack> = rpc.callOffMain<PlayHistoryResult>("catalog.playHistory").items

    /** New releases of followed artists and new episodes of followed shows ("What's new"). */
    suspend fun whatsNew(): List<MediaRef> = rpc.callOffMain<MediaRefs>("catalog.whatsNew").items

    /** Concerts, live events, save-the-dates and other account notifications. */
    suspend fun notifications(): List<SpotifyNotification> = rpc.callOffMain<NotificationsResult>("catalog.notifications").items

    /** Cursor-based read operation. Also marks notifications older than this one read. */
    suspend fun markNotificationRead(cursorMs: String) {
        rpc.callOffMain<kotlinx.serialization.json.JsonObject>("catalog.markNotificationRead", rpcArgs { put("cursor", cursorMs) })
    }

    /** Send repeated Gander storage IDs in one atomic request. */
    suspend fun deleteNotifications(storageIds: List<String>) {
        rpc.callOffMain<kotlinx.serialization.json.JsonObject>("catalog.deleteNotifications", rpcArgs {
            put("storageIds", kotlinx.serialization.json.JsonArray(storageIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
    }

    /** Server-side Gander notification deletion; never local-only. */
    suspend fun deleteNotification(storageId: String) {
        rpc.callOffMain<kotlinx.serialization.json.JsonObject>("catalog.deleteNotification", rpcArgs { put("storageId", storageId) })
    }

    /** What friends listen to, newest first. */
    suspend fun friendActivity(): List<FriendActivity> = rpc.callOffMain<FriendActivities>("catalog.friendActivity").items

    suspend fun user(username: String? = null): User =
        rpc.callOffMain("catalog.user", rpcArgs { if (username != null) put("username", username) })

    /** Writes the Spotify account profile and requires a fresh server read to confirm. */
    suspend fun updateProfilePhoto(jpeg: ByteArray): User =
        rpc.callOffMain("catalog.updateProfilePhoto", rpcArgs {
            put("jpegBase64", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
        })

    suspend fun removeProfilePhoto(): User =
        rpc.callOffMain("catalog.removeProfilePhoto", rpcArgs { })

    suspend fun updateProfileName(name: String): User =
        rpc.callOffMain("catalog.updateProfileName", rpcArgs { put("name", name) })

    /** Human users, not followed artists. */
    suspend fun socialList(kind: String, username: String? = null): List<SocialPerson> =
        rpc.callOffMain<SocialPeople>("catalog.socialList", rpcArgs {
            put("kind", kind)
            if (username != null) put("username", username)
        }).items

    /** Read Spotify's session ProductState publish-activity value. Null = not available. */
    suspend fun listeningGlobal(): Boolean? =
        rpc.callOffMain<ListeningGlobalResult>("catalog.listeningGlobal", rpcArgs { }).enabled

    /** Write Spotify's global "all followers" listening-activity flag; returns the stored value. */
    suspend fun setListeningGlobal(enabled: Boolean): Boolean =
        rpc.callOffMain<ListeningGlobalResult>("catalog.listeningSetGlobal", rpcArgs { put("enabled", enabled) }).enabled ?: enabled

    /** Server-authoritative audience for listening activity sharing. */
    suspend fun listeningAudience(): List<ListeningAudienceUser> =
        rpc.callOffMain<ListeningAudienceResult>("catalog.listeningAudience", rpcArgs { }).users

    /** Restrict or allow a username using Spotify's listening activity audience service. */
    suspend fun restrictListeningAudience(username: String, restricted: Boolean) {
        rpc.callOffMain<ListeningAudienceMutation>("catalog.restrictListeningAudience", rpcArgs {
            put("username", username)
            put("restrict", restricted)
        })
    }

    /** Updates the Spotify account only after the service accepts the mutation. */
    suspend fun followUser(username: String, following: Boolean) {
        rpc.callOffMain<SocialFollowResult>("catalog.socialFollow", rpcArgs {
            put("username", username)
            put("following", following)
        })
    }

    internal companion object {
        /** `catalog.tracks` accepts at most 200 URIs per call (docs §6.3). */
        const val METADATA_BATCH = 200
    }
}

@Serializable
internal data class TracksResult(val tracks: List<Track> = emptyList())

/** [Resource] of a show page with Spotify's played state only where it is a fresh answer. */
internal fun Resource<Show>.withoutCachedPlayedState(): Resource<Show> = when (this) {
    is Resource.Success -> if (fromCache) copy(data = data.withoutPlayedState()) else this
    is Resource.Loading -> copy(cached = cached?.withoutPlayedState())
    is Resource.Error -> copy(cached = cached?.withoutPlayedState())
}

@Serializable
internal data class EpisodesResult(val episodes: List<Episode> = emptyList())

@Serializable
internal data class MediaRefs(val items: List<MediaRef> = emptyList())

@Serializable
internal data class FriendActivities(val items: List<FriendActivity> = emptyList())

@Serializable
internal data class NotificationsResult(val items: List<SpotifyNotification> = emptyList(), val latestCursor: String? = null)

@Serializable
data class PlayedTrack(val track: MediaRef, val playedAt: String = "", val contextUri: String? = null) {
    val playedAtMs: Long get() = runCatching { java.time.Instant.parse(playedAt).toEpochMilli() }.getOrDefault(0L)
}

@Serializable
internal data class PlayHistoryResult(val items: List<PlayedTrack> = emptyList())

@Serializable
data class RecentHistoryEntry(val context: MediaRef, val timestampMs: Long = 0, val lastTrackUri: String? = null)

@Serializable
internal data class RecentHistoryResult(val items: List<RecentHistoryEntry> = emptyList())

@Serializable
data class SocialPerson(
    val username: String,
    val displayName: String = "",
    val imageUrl: String? = null,
    val followersCount: Int? = null,
    val isFollowing: Boolean = false,
)

@Serializable
internal data class SocialPeople(val items: List<SocialPerson> = emptyList())

@Serializable
internal data class SocialFollowResult(val success: Boolean = false)

@Serializable
data class ListeningAudienceUser(
    val username: String,
    val restricted: Boolean = false,
    val hidden: Boolean = false,
)

@Serializable
private data class ListeningAudienceResult(val users: List<ListeningAudienceUser> = emptyList())

@Serializable
private data class ListeningAudienceMutation(val success: Boolean = false)

@Serializable
data class ListeningGlobalResult(val enabled: Boolean? = null)
