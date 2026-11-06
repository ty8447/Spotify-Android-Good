package com.taehagen.spotifygood.ui.screens.player

import android.text.Html
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.max

/**
 * Below-the-player editorial cards. Every fact comes from the current track, its album or the
 * native catalog's cached artist response. Unsupported content types (concerts, SongDNA and
 * music-video/Canvas URLs) are deliberately omitted instead of showing fictitious data.
 *
 * Album and artist lookups are one per URI while this composition is active. CatalogRepository
 * performs its existing TTL caching and network deduplication; no per-position polling is added.
 */
@Composable
internal fun SongInformationCards(
    track: PlaybackTrack,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (track.isEpisode) return
    val graph = rememberAppGraph()
    val albumRef = track.album
    val albumUri = albumRef?.uri?.takeIf { it.isNotBlank() }
    val artistRef = track.artists.firstOrNull { it.uri.isNotBlank() }
    val artistUri = artistRef?.uri
    var album by remember(albumUri) { mutableStateOf<Album?>(null) }
    var artist by remember(artistUri) { mutableStateOf<Artist?>(null) }

    LaunchedEffect(albumUri) {
        if (albumUri == null) return@LaunchedEffect
        try {
            album = graph.catalog.album(albumUri)
                .first { it.dataOrNull != null || it is Resource.Error<*> }.dataOrNull
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // AlbumRef still allows an accurate basic card when the catalog is offline.
        }
    }
    LaunchedEffect(artistUri) {
        if (artistUri == null) return@LaunchedEffect
        try {
            artist = graph.catalog.artist(artistUri)
                .first { it.dataOrNull != null || it is Resource.Error<*> }.dataOrNull
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Never block playback or the remaining cards on an optional biography request.
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val actualAlbum = album?.takeIf { it.uri == albumUri }
        if (albumRef != null && albumRef.name.isNotBlank()) {
            InfoCard(title = stringResource(R.string.song_info_about_song)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(
                        url = albumRef.images.best(200),
                        contentDescription = albumRef.name,
                        modifier = Modifier.size(66.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(albumRef.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val date = actualAlbum?.releaseDate ?: albumRef.releaseDate
                        if (!date.isNullOrBlank()) {
                            Text(date, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!actualAlbum?.label.isNullOrBlank()) {
                            Text(actualAlbum?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onOpenUri(albumRef.uri) }) {
                            Text(stringResource(R.string.song_info_open_album))
                        }
                    }
                }
            }
        }

        if (artistRef != null) {
            val about = artist?.biography.orEmpty().let { raw ->
                // Biography may contain Spotify's HTML markup; show readable text, not tags.
                if (raw.isBlank()) "" else remember(raw) {
                    Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                }
            }
            InfoCard(title = stringResource(R.string.song_info_about_artist)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(
                        url = artist?.images?.best(200) ?: artistRef.images.best(200),
                        contentDescription = artistRef.name,
                        modifier = Modifier.size(66.dp),
                        shape = RoundedCornerShape(50),
                        avatarName = artistRef.name,
                    )
                    Column {
                        Text(artistRef.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onOpenUri(artistRef.uri) }) {
                            Text(stringResource(R.string.song_info_explore_artist))
                        }
                    }
                }
                if (about.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(about, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        val releases = artist?.albums.orEmpty().take(5).ifEmpty { artist?.singles.orEmpty().take(5) }
        if (releases.isNotEmpty()) {
            InfoCard(title = stringResource(R.string.song_info_more_by_artist)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    releases.forEach { release ->
                        InfoMediaTile(release, onOpenUri)
                    }
                }
            }
        }

        val related = artist?.related.orEmpty().take(6)
        if (related.isNotEmpty()) {
            InfoCard(title = stringResource(R.string.song_info_discover_artists)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    related.forEach { relatedArtist ->
                        InfoArtistTile(relatedArtist, onOpenUri)
                    }
                }
            }
        }

        // These are album-level and performer credits, not songwriter/producer credits; the
        // current catalog contract does not expose track-specific credits yet.
        if (track.artists.isNotEmpty() || actualAlbum?.label?.isNotBlank() == true || !actualAlbum?.copyrights.isNullOrEmpty()) {
            InfoCard(title = stringResource(R.string.song_info_credits)) {
                if (track.artists.isNotEmpty()) {
                    Text(stringResource(R.string.song_info_performers), style = MaterialTheme.typography.labelSmall)
                    Text(track.artists.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium)
                }
                if (!actualAlbum?.label.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.song_info_label), style = MaterialTheme.typography.labelSmall)
                    Text(actualAlbum?.label.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                actualAlbum?.copyrights?.filter { it.isNotBlank() }?.take(2)?.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.09f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun InfoMediaTile(release: AlbumRef, onOpenUri: (String) -> Unit) {
    Column(Modifier.size(width = 110.dp, height = 165.dp)) {
        Artwork(url = release.images.best(160), contentDescription = release.name, modifier = Modifier.size(110.dp))
        TextButton(onClick = { onOpenUri(release.uri) }, modifier = Modifier.fillMaxWidth()) {
            Text(release.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoArtistTile(artist: ArtistRef, onOpenUri: (String) -> Unit) {
    Column(Modifier.size(width = 110.dp, height = 165.dp)) {
        Artwork(
            url = artist.images.best(160),
            contentDescription = artist.name,
            modifier = Modifier.size(110.dp),
            shape = RoundedCornerShape(50),
            avatarName = artist.name,
        )
        TextButton(onClick = { onOpenUri(artist.uri) }, modifier = Modifier.fillMaxWidth()) {
            Text(artist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Subtle original artwork-colored movement, not Spotify's licensed Canvas video. */
@Composable
internal fun AmbientAlbumBackdrop(artColor: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "album ambient motion")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(28000), repeatMode = RepeatMode.Reverse),
        label = "album ambient drift",
    )
    Canvas(modifier) {
        val radius = max(size.width, size.height).coerceAtLeast(1f) * .90f
        val color = artColor.copy(alpha = .15f)
        drawRect(brush = Brush.radialGradient(
            listOf(color, Color.Transparent),
            center = Offset(size.width * (.24f + progress * .48f), size.height * .27f),
            radius = radius,
        ))
        drawRect(brush = Brush.radialGradient(
            listOf(color.copy(alpha = .65f), Color.Transparent),
            center = Offset(size.width * (.72f - progress * .40f), size.height * .78f),
            radius = radius,
        ))
    }
}
