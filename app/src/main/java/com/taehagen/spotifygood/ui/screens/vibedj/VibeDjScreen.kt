package com.taehagen.spotifygood.ui.screens.vibedj

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.screens.library.contentPaddingWith
import com.taehagen.spotifygood.vibedj.DjPlan
import com.taehagen.spotifygood.vibedj.DjPoolTrack
import com.taehagen.spotifygood.vibedj.DjPrefs
import com.taehagen.spotifygood.vibedj.DjSource
import com.taehagen.spotifygood.vibedj.DjState
import com.taehagen.spotifygood.vibedj.DjStyle
import com.taehagen.spotifygood.vibedj.DjTrack
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeDjScreen(contentPadding: PaddingValues) {
    val graph = rememberAppGraph()
    val repo = graph.vibeDj
    val navigator = LocalAppNavigator.current
    val state by repo.state.collectAsStateWithLifecycle()
    val plan by repo.plan.collectAsStateWithLifecycle()
    val prefs by repo.prefs.collectAsStateWithLifecycle()
    val source by repo.source.collectAsStateWithLifecycle()
    val starting by repo.starting.collectAsStateWithLifecycle()
    val playback by graph.events.playback.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    // A smooth playhead: the snapshot's position moved on by the clock.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = System.currentTimeMillis() } }
    val position = when {
        state.phase == "mix" -> state.aPosMs.toDouble()
        playback.status == PlaybackStatus.PLAYING ->
            playback.positionMs + (now - playback.positionTimestampMs).coerceAtLeast(0) * playback.playbackSpeed
        else -> playback.positionMs.toDouble()
    }
    val remote = playback.source == PlaybackSource.REMOTE

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Vibe DJ", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                if (state.active) TextButton(onClick = repo::stop) { Text("Stop") }
            },
        )
        LazyColumn(contentPadding = contentPaddingWith(contentPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (remote) item { Hint("Vibe DJ mixes on this phone. Play here (or on Bluetooth) to use it; other devices still see what's playing.") }
            if (state.active) {
                item { MixCard(plan, state, position) }
                item { NextCard(plan, state, repo::lockNext, repo::swapNext, repo::mixNow) }
                item { TargetCard(state, plan) { picking = true } }
                item { StatusLine(state) }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Mix like a club DJ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Vibe DJ listens to every song (tempo, beats, key, energy) right on your phone, picks what fits next and blends it in on the beat.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Mix from", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DjSource.entries.forEach { s ->
                                val label = if (s == DjSource.CONTEXT && playback.context?.type == com.taehagen.spotifygood.model.ContextType.ALBUM) "This album" else s.label
                                FilterChip(selected = s == source, onClick = { repo.setSource(s) }, label = { Text(label) })
                            }
                        }
                        Button(
                            onClick = { scope.launch { runCatching { repo.start() } } },
                            enabled = !starting && !remote && playback.track?.isEpisode == false,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (starting) "Gathering songs…" else "Start Vibe DJ")
                        }
                        if (playback.track == null) Hint("Start a song first: Vibe DJ takes over from what's playing.")
                        else if (playback.track?.isEpisode == true) Hint("Vibe DJ mixes music: play a song (a podcast has nothing to mix).")
                    }
                }
            }
            item { HorizontalDivider() }
            item { PrefsSection(prefs, repo::setPrefs) }
            item { BackgroundSection() }
        }
    }
    if (picking) TargetSheet(onDismiss = { picking = false })
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusLine(state: DjState) {
    val analysing = if (state.pending > 0 || state.analyzing != null) " · checking ${state.pending + if (state.analyzing != null) 1 else 0} songs" else ""
    Hint("${state.status.ifEmpty { "Listening…" }}$analysing · ${state.poolSize} songs in the pool")
}

private fun styleLabel(id: String) = DjStyle.of(id).label

/** Gains of the current (A) and next (B) song at progress [p] of a mix, as the native mixer. */
private fun gains(style: String, p: Float): Pair<Float, Float> {
    val x = p.coerceIn(0f, 1f)
    val out = { v: Float -> cos(v.coerceIn(0f, 1f) * PI.toFloat() / 2) }
    val inn = { v: Float -> sin(v.coerceIn(0f, 1f) * PI.toFloat() / 2) }
    return when (style) {
        "bassSwap" -> (if (x < 0.6f) 1f else out((x - 0.6f) / 0.4f)) to inn(x / 0.4f)
        "filterSweep" -> (if (x < 0.75f) 1f - x * 0.3f else out((x - 0.75f) / 0.25f) * 0.75f) to inn(x / 0.3f)
        "echoOut" -> (if (x < 0.15f) 1f - x / 0.15f else 0.35f * (1f - x)) to 1f
        "cut" -> (if (x < 0.5f) 1f else 0f) to (if (x < 0.5f) 0f else 1f)
        else -> out(x) to inn(x)
    }
}

private fun fmt(ms: Double): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** The transition view: both songs' energy over time, where they overlap, and how they blend. */
@Composable
private fun MixCard(plan: DjPlan?, state: DjState, position: Double) {
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val text = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    val pulse by rememberInfiniteTransition(label = "beat").animateFloat(
        0f, 1f, infiniteRepeatable(tween(((plan?.transition?.beatMs ?: 500.0).toInt()).coerceIn(250, 1500), easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val t = plan?.transition
            val headline = when {
                plan == null -> "Picking the next song…"
                state.phase == "mix" -> "${styleLabel(t!!.style)} · ${(state.progress * 100).toInt()}%"
                state.phase == "armed" -> "Matching tempo · ${styleLabel(t!!.style)} in ${fmt(t.mixStartMs - position)}"
                state.phase in setOf("settle", "handoff", "join", "glide") -> "Landing ${plan.next.name.ifEmpty { "the next song" }}"
                else -> "${styleLabel(t!!.style)} in ${fmt(t.mixStartMs - position)}"
            }
            Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (t != null) {
                val tempoA = (t.aSpeed - 1) * 100
                val tempoB = (t.speed - 1) * 100
                val detail = buildList {
                    if (t.beats > 0) add("${t.beats} beats")
                    add(fmt(t.lengthMs))
                    if (abs(tempoA) >= 0.1 || abs(tempoB) >= 0.1) add("tempo %+.1f%% / %+.1f%%".format(tempoA, tempoB))
                    if (abs(t.gainDb) >= 0.5) add("level %+.0f dB".format(t.gainDb))
                }.joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                if (plan == null || t == null) {
                    // Idle: a soft equaliser pulse.
                    val n = 24
                    val w = size.width / n
                    for (i in 0 until n) {
                        val h = size.height * (0.15f + 0.6f * abs(sin(i * 0.7f + pulse * 2 * PI.toFloat())))
                        drawRoundRect(lerp(accent, second, i / n.toFloat()).copy(alpha = 0.6f), Offset(i * w + 2, size.height - h), Size(w - 4, h), CornerRadius(6f))
                    }
                    return@Canvas
                }
                val mixStart = t.mixStartMs
                val mixEnd = mixStart + t.lengthMs
                val from = min(position - 8_000, mixStart - 20_000)
                val to = max(mixEnd + 25_000, from + 40_000)
                val xOf = { ms: Double -> ((ms - from) / (to - from) * size.width).toFloat() }
                val laneH = size.height * 0.36f
                val aTop = size.height * 0.08f
                val bTop = aTop + laneH + size.height * 0.12f
                // The overlap.
                val mx0 = xOf(mixStart)
                val mx1 = max(xOf(mixEnd), mx0 + 3f)
                drawRoundRect(
                    Brush.horizontalGradient(listOf(accent.copy(alpha = 0.10f), second.copy(alpha = 0.22f)), mx0, mx1),
                    Offset(mx0, 0f), Size(mx1 - mx0, size.height), CornerRadius(16f),
                )
                lane(plan.current, { it.toDouble() }, aTop, laneH, xOf, accent, second, dim, until = mixEnd, from = null)
                // B's time in A's: during the mix both play stretched, B's beats on A's.
                val bAt = { ms: Long -> mixStart + (ms - t.entryMs) * t.aSpeed / t.speed }
                lane(plan.next, { bAt(it) }, bTop, laneH, xOf, second, accent, dim, until = null, from = mixStart)
                // Gain curves through the overlap.
                val pathA = Path()
                val pathB = Path()
                for (i in 0..40) {
                    val p = i / 40f
                    val x = mx0 + (mx1 - mx0) * p
                    val (ga, gb) = gains(t.style, p)
                    if (i == 0) pathA.moveTo(x, aTop + laneH * (1 - ga)) else pathA.lineTo(x, aTop + laneH * (1 - ga))
                    if (i == 0) pathB.moveTo(x, bTop + laneH * (1 - gb)) else pathB.lineTo(x, bTop + laneH * (1 - gb))
                }
                drawPath(pathA, Color.White.copy(alpha = 0.85f), style = Stroke(3f, cap = StrokeCap.Round))
                drawPath(pathB, Color.White.copy(alpha = 0.85f), style = Stroke(3f, cap = StrokeCap.Round))
                if (t.style == "bassSwap") {
                    val sx = mx0 + (mx1 - mx0) * 0.5f
                    drawLine(Color.White.copy(alpha = 0.5f), Offset(sx, aTop), Offset(sx, bTop + laneH), 2f)
                    drawText(text, "BASS SWAP", Offset(sx + 4, aTop + laneH + 4), label)
                }
                // Phrase labels.
                plan.current.phrases.filter { it.kind != "body" }.forEach { ph ->
                    val x = xOf(ph.startMs.toDouble())
                    if (x in 0f..size.width - 40) drawText(text, ph.kind.uppercase(), Offset(x + 2, 0f), label)
                }
                plan.next.phrases.filter { it.kind != "body" }.forEach { ph ->
                    val x = xOf(bAt(ph.startMs))
                    if (x in 0f..size.width - 40) drawText(text, ph.kind.uppercase(), Offset(x + 2, bTop + laneH + 2), label)
                }
                // Playhead with a beat glow.
                val px = xOf(position)
                drawLine(accent.copy(alpha = 0.25f * (1 - pulse)), Offset(px, 0f), Offset(px, size.height), 14f * (1 - pulse) + 4f)
                drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 3f)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TrackBadge(plan?.current, "Now")
                if (plan?.current?.camelot != null && plan.next.camelot != null) {
                    CamelotWheel(plan.current.camelot, plan.next.camelot, Modifier.size(64.dp))
                }
                TrackBadge(plan?.next, "Next")
            }
        }
    }
}

/** One song's lane: a bar per musical bar, its height the bar's energy. */
private fun DrawScope.lane(
    track: DjTrack,
    timeOf: (Long) -> Double,
    top: Float,
    height: Float,
    xOf: (Double) -> Float,
    low: Color,
    high: Color,
    dim: Color,
    until: Double?,
    from: Double?,
) {
    drawRoundRect(dim, Offset(0f, top), Size(size.width, height), CornerRadius(10f))
    val bars = track.barsMs
    for (i in bars.indices) {
        val t0 = timeOf(bars[i])
        val t1 = if (i + 1 < bars.size) timeOf(bars[i + 1]) else t0 + 2_000
        val x0 = xOf(t0)
        val x1 = xOf(t1)
        if (x1 < 0 || x0 > size.width) continue
        val e = (track.energy.getOrNull(i) ?: 0) / 255f
        val faded = (until != null && t0 >= until) || (from != null && t1 <= from)
        val c = lerp(low, high, e).copy(alpha = if (faded) 0.18f else 0.9f)
        val h = height * (0.12f + 0.88f * e)
        drawRoundRect(c, Offset(x0 + 1, top + height - h), Size(max(x1 - x0 - 2, 1f), h), CornerRadius(3f))
    }
}

@Composable
private fun TrackBadge(track: DjTrack?, caption: String) {
    Column(Modifier.width(120.dp)) {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(track?.name?.ifEmpty { "—" } ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
        val facts = listOfNotNull(track?.bpm?.let { "%.0f BPM".format(it) }, track?.camelot, track?.key).joinToString(" · ")
        Text(facts.ifEmpty { "analysing…" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The Camelot wheel: outer ring major (B), inner minor (A); the playing key and the next one lit. */
@Composable
private fun CamelotWheel(a: String, b: String, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        for (ring in listOf('B', 'A')) {
            val radius = if (ring == 'B') r * 0.82f else r * 0.5f
            for (n in 1..12) {
                val key = "$n$ring"
                val color = when (key) {
                    a -> accent
                    b -> second
                    else -> base
                }
                val start = -90f + (n - 1) * 30f - 14f
                drawArc(color, start, 28f, false, Offset(c.x - radius, c.y - radius), Size(radius * 2, radius * 2), style = Stroke(r * 0.26f))
            }
        }
    }
}

@Composable
private fun NextCard(planOrNull: DjPlan?, state: DjState, onLock: (String?) -> Unit, onSwap: () -> Unit, onMixNow: () -> Unit) {
    val plan = planOrNull ?: return
    val next = plan.next
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("UP NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(next.name.ifEmpty { next.uri }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (next.artist.isNotEmpty()) Text(next.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plan.reasons.isNotEmpty()) Text("Why: " + plan.reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            val locked = state.locked == next.uri
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onLock(if (locked) null else next.uri) }) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (locked) "Locked" else "Lock")
                }
                OutlinedButton(onClick = onSwap, enabled = state.phase == "pass") {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Swap")
                }
                OutlinedButton(onClick = onMixNow, enabled = state.phase == "pass") {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mix now")
                }
            }
        }
    }
}

@Composable
private fun TargetCard(state: DjState, plan: DjPlan?, onPick: () -> Unit) {
    val repo = rememberAppGraph().vibeDj
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("GET TO A SONG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            val target = state.target
            if (target == null) {
                Text("Pick a song and Vibe DJ finds a path of songs to get there.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onPick) { Text("Choose a song") }
            } else {
                Text(if (target.asap) "As soon as possible" else "Seamless", fontWeight = FontWeight.Bold)
                if (target.path.size > 1) {
                    Text("${target.path.size - 1} song${if (target.path.size > 2) "s" else ""} away", style = MaterialTheme.typography.bodySmall)
                    RouteDots(target.path.size, plan)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPick) { Text("Change") }
                    TextButton(onClick = { repo.setTarget(null, false) }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun RouteDots(count: Int, plan: DjPlan?) {
    val accent = MaterialTheme.colorScheme.primary
    val second = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val n = count.coerceAtLeast(2)
        val step = size.width / (n - 1)
        drawLine(accent.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f)
        for (i in 0 until n) {
            val c = lerp(accent, second, i / (n - 1f))
            drawCircle(c, if (i == 0 || i == n - 1) 10f else 7f, Offset(i * step, size.height / 2))
        }
        if (plan == null) return@Canvas
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(onDismiss: () -> Unit) {
    val repo = rememberAppGraph().vibeDj
    var tracks by remember { mutableStateOf<List<DjPoolTrack>?>(null) }
    var query by remember { mutableStateOf("") }
    var asap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tracks = runCatching { repo.poolTracks() }.getOrDefault(emptyList()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Get to…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !asap, onClick = { asap = false }, label = { Text("Seamless") })
                FilterChip(selected = asap, onClick = { asap = true }, label = { Text("As soon as possible") })
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search the pool") }, singleLine = true)
            val shown = tracks.orEmpty().filter {
                query.isBlank() || it.name.contains(query, true) || it.artist.contains(query, true)
            }
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                if (tracks == null) item { Text("Loading…", Modifier.padding(16.dp)) }
                items(shown, key = { it.uri }) { t ->
                    Column(
                        Modifier.fillMaxWidth().clickable { repo.setTarget(t, asap); onDismiss() }.padding(vertical = 10.dp),
                    ) {
                        Text(t.name.ifEmpty { t.uri }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.artist.isNotEmpty()) Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefsSection(prefs: DjPrefs, onChange: (DjPrefs) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How it mixes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Song length", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("full" to "Full songs", "club" to "Club", "quick" to "Quick").forEach { (k, l) ->
                FilterChip(selected = prefs.length.kind == k, onClick = { onChange(prefs.copy(length = prefs.length.copy(kind = k))) }, label = { Text(l) })
            }
        }
        if (prefs.length.kind == "club") {
            Text("Play ${(prefs.length.min * 100).toInt()}–${(prefs.length.max * 100).toInt()}% of each song", style = MaterialTheme.typography.bodySmall)
            RangeSlider(
                value = prefs.length.min..prefs.length.max,
                onValueChange = { onChange(prefs.copy(length = prefs.length.copy(min = it.start, max = it.endInclusive))) },
                valueRange = 0.1f..0.9f,
            )
        }
        Text("Energy", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("steady" to "Keep the vibe", "build" to "Build up", "journey" to "Warm up → peak → cool down", "peak" to "Peak time").forEach { (k, l) ->
                FilterChip(selected = prefs.energyArc == k, onClick = { onChange(prefs.copy(energyArc = k)) }, label = { Text(l) })
            }
        }
        if (prefs.energyArc == "journey" || prefs.energyArc == "build") {
            Text("Session: ${prefs.sessionMinutes} min", style = MaterialTheme.typography.bodySmall)
            Slider(prefs.sessionMinutes.toFloat(), { onChange(prefs.copy(sessionMinutes = it.toInt())) }, valueRange = 15f..180f, steps = 10)
        }
        Text("Transitions", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DjStyle.entries.forEach { s ->
                val on = s.id in prefs.styles
                FilterChip(selected = on, onClick = {
                    val next = if (on) prefs.styles - s.id else prefs.styles + s.id
                    if (next.isNotEmpty()) onChange(prefs.copy(styles = next))
                }, label = { Text(s.label) })
            }
        }
        Toggle("Harmonic mixing", "Prefer songs in compatible keys (Camelot wheel)", prefs.harmonic) { onChange(prefs.copy(harmonic = it)) }
        Toggle(
            "Key lock",
            if (prefs.keyLock) "Keeps each song's pitch while tempos change" else "Pitch follows tempo like a turntable: the songs glide to meet, smoothest",
            prefs.keyLock,
        ) { onChange(prefs.copy(keyLock = it)) }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun BackgroundSection() {
    val repo = rememberAppGraph().vibeDj
    val on by repo.analyzeDownloads.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val c = repo.cacheInfo()
        info = "${c.tracks} songs analysed · ${c.bytes / 1024} KB"
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Toggle("Analyse downloads while charging", "So Vibe DJ can mix them offline", on, repo::setAnalyzeDownloads)
        Box(Modifier.fillMaxWidth()) {
            Text(info ?: "", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { repo.clearCache(); info = "0 songs analysed" } }, Modifier.align(Alignment.CenterEnd)) { Text("Clear") }
        }
    }
}
