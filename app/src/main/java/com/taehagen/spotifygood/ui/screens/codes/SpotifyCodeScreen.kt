package com.taehagen.spotifygood.ui.screens.codes

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImagePainter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.taehagen.spotifygood.ui.components.Artwork
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator

/** Renders real Spotify scannables; the app never fabricates the encoded bars. */
@Composable
fun SpotifyCodeScreen(uri: String, artworkUrl: String?) {
    val navigator = LocalAppNavigator.current
    // Only expected Spotify URIs go to the external renderer; never include credentials.
    val supported = listOf("spotify:track:", "spotify:album:", "spotify:playlist:",
        "spotify:artist:", "spotify:show:", "spotify:episode:", "spotify:user:")
    val valid = supported.any(uri::startsWith) && uri.all { it.isLetterOrDigit() || it in ":_-" }
    // Request black bars on an opaque white panel for guaranteed contrast.
    val codeUrl = if (valid) "https://scannables.scdn.co/uri/plain/png/FFFFFF/black/640/$uri" else null
    var codeState by remember(codeUrl) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        IconButton(
            onClick = navigator::back,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(14.dp),
        ) { Icon(Icons.Rounded.Close, contentDescription = "Close Spotify Code", tint = Color.White) }
        if (codeUrl != null) {
            val coverSize = 200.dp
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(artworkUrl, null, Modifier.size(coverSize))
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.width(coverSize).height(92.dp)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = codeUrl,
                        contentDescription = "Spotify Code",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            codeState = state
                            if (state is AsyncImagePainter.State.Error) {
                                Log.e("SpotifyCodeScreen", "Code image load failed: $codeUrl", state.result.throwable)
                            }
                        },
                    )
                    when (codeState) {
                        is AsyncImagePainter.State.Error -> {
                            val cause = (codeState as AsyncImagePainter.State.Error).result.throwable
                            Text(
                                "Unable to load Spotify Code: ${cause.javaClass.simpleName}",
                                color = Color.Black,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        is AsyncImagePainter.State.Success -> Unit
                        else -> CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                    }
                }
            }
        } else {
            Text("Unsupported Spotify link", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
    }
}
