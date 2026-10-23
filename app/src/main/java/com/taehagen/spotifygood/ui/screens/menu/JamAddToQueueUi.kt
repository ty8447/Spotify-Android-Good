package com.taehagen.spotifygood.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Jam-specific entry surface. Recommendations require a Jam recommender, not generic catalog rows. */
@Composable
internal fun JamAddToQueuePage(onBack: () -> Unit, onSearch: () -> Unit, onOpenLikedSongs: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().height(675.dp).background(JamUi.black)
        .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to Jam", tint = JamUi.white) }
            Text("Add to Queue", color = JamUi.white, fontSize = 21.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(horizontal = 24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Group recommendations", "From your Liked Songs").forEachIndexed { i, label ->
                Surface(onClick = { tab = i }, shape = CircleShape,
                    color = if (tab == i) JamUi.green else JamUi.row,
                    modifier = Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold,
                        color = if (tab == i) JamUi.black else JamUi.white,
                        textAlign = TextAlign.Center, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 13.dp))
                }
            }
        }
        if (tab == 0) {
            Text("Based on all of your tastes and songs in this Jam", color = JamUi.white, fontSize = 17.sp)
            Text("Spotify Good doesn't have a Jam group-recommendations feed yet. " +
                "Search for tracks to add them to the current queue.", color = JamUi.muted, fontSize = 14.sp)
        } else {
            Text("Your Liked Songs", color = JamUi.white, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Choose a song from your library and use its Add to queue action.",
                color = JamUi.muted, fontSize = 14.sp)
            TextButton(onClick = onOpenLikedSongs) { Text("Open Liked Songs", color = JamUi.green) }
        }
        Spacer(Modifier.weight(1f))
        Surface(onClick = onSearch, color = JamUi.background, shape = CircleShape,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Search, null, tint = JamUi.white)
                Text("What would you like to add?", fontSize = 16.sp, color = JamUi.white)
            }
        }
    }
}
