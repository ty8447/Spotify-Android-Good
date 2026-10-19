package com.taehagen.spotifygood.ui.screens.player

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import com.taehagen.spotifygood.model.PlaybackTrack
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min

/** Local, metadata-based easter eggs. No network calls or modifications to playback. */
internal enum class CinematicTheme { STAR_WARS, STRANGER_THINGS, HARRY_POTTER, JURASSIC_PARK, INDIANA_JONES, JAWS }

internal fun cinematicTheme(track: PlaybackTrack): CinematicTheme? {
    if (track.isEpisode) return null
    val album = track.album?.name.orEmpty().lowercase()
    val title = track.name.orEmpty().lowercase()
    val artist = track.artistLine.lowercase()
    // Prefer soundtrack identification; avoid false positives from unrelated song titles.
    return when {
        "star wars" in album || ("john williams" in artist &&
            listOf("imperial march", "binary sunset", "duel of the fates", "across the stars", "main title and the attack").any { it in title }) -> CinematicTheme.STAR_WARS
        "stranger things" in album || ("kyle dixon" in artist && "michael stein" in artist && "stranger things" in title) -> CinematicTheme.STRANGER_THINGS
        "harry potter" in album || ("john williams" in artist && "hedwig's theme" in title) -> CinematicTheme.HARRY_POTTER
        "jurassic park" in album || "jurassic world" in album || ("john williams" in artist && "theme from jurassic park" in title) -> CinematicTheme.JURASSIC_PARK
        "indiana jones" in album || ("john williams" in artist && "raiders march" in title) -> CinematicTheme.INDIANA_JONES
        "jaws" in album && ("soundtrack" in album || "original score" in album || "john williams" in artist) -> CinematicTheme.JAWS
        else -> null
    }
}

/**
 * Cinematic layers are decorative and draw behind the controls. All motion is deterministic,
 * non-interactive, asset-free and tied to the composition lifetime of the player.
 */
@Composable
internal fun CinematicAtmosphere(theme: CinematicTheme?, modifier: Modifier = Modifier) {
    if (theme == null) return
    val transition = rememberInfiniteTransition(label = "cinematic atmosphere")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(120000, easing = LinearEasing)),
        label = "cinematic loop",
    ).value
    Canvas(modifier.fillMaxSize()) {
        when (theme) {
            CinematicTheme.STAR_WARS -> starfield(phase)
            CinematicTheme.STRANGER_THINGS -> upsideDown(phase)
            CinematicTheme.HARRY_POTTER -> magic(phase)
            CinematicTheme.JURASSIC_PARK -> jungle(phase)
            CinematicTheme.INDIANA_JONES -> expedition(phase)
            CinematicTheme.JAWS -> underwater(phase)
        }
    }
}

private const val TAU = 6.2831853f
private fun fract(value: Float) = value - floor(value)
private fun seed(i: Int, salt: Float = 0f) = fract(sin(i * 127.1f + salt * 311.7f) * 43758.5453f)
private fun pulse(x: Float) = (sin(x * TAU) * .5f + .5f)
private fun life(t: Float): Float = min(1f, min(t * 7f, (1f - t) * 7f)).coerceAtLeast(0f)

private fun DrawScope.starfield(phase: Float) {
    val blue = Color(0xFF69B6FF)
    // Slow-moving stars and a faint nebula; the lightsaber itself lives in the seek bar.
    drawCircle(brush = Brush.radialGradient(listOf(blue.copy(alpha = .13f), Color.Transparent),
        center = Offset(size.width * .72f, size.height * .58f), radius = size.width * .8f),
        radius = size.width * .8f, center = Offset(size.width * .72f, size.height * .58f))
    repeat(35) { i ->
        val x = seed(i, 1f) * size.width
        val y = fract(seed(i, 2f) + phase * (.03f + seed(i, 3f) * .05f)) * size.height
        val bright = .14f + .38f * pulse(phase * (1f + seed(i, 4f)) + seed(i, 5f))
        drawCircle(Color.White.copy(alpha = bright), radius = (.45f + seed(i, 6f)) * density,
            center = Offset(x, y))
    }
}

private fun DrawScope.upsideDown(phase: Float) {
    // Each spore has an independent, whole-number cycle count. Its invisible respawn
    // occurs at a different time; the 120-second master clock has no visible reset.
    drawRect(brush = Brush.verticalGradient(listOf(
        Color(0xFF391018).copy(alpha = .21f), Color.Transparent,
        Color(0xFF18090E).copy(alpha = .29f))))
    repeat(48) { i ->
        val cycles = 2 + i % 5
        val age = fract(phase * cycles + seed(i, 13f))
        val directionX = if (i % 3 == 0) -1f else 1f
        val directionY = if (i % 2 == 0) -1f else 1f
        val sway = sin(age * TAU * (1f + i % 3) + seed(i, 14f) * TAU) * .035f
        // Respawn is hidden by the fade and happens independently per spore.
        val x = .25f + seed(i, 15f) * .5f + directionX * age * (.11f + .11f * seed(i, 16f)) + sway
        val y = .27f + seed(i, 17f) * .46f + directionY * age * (.12f + .16f * seed(i, 20f))
        val alpha = life(age) * (.32f + .48f * seed(i, 18f))
        if (alpha < .003f) return@repeat
        val r = (1.2f + 2.2f * seed(i, 19f)) * density
        val at = Offset(x * size.width, y * size.height)
        // Broad diffuse halo and a cold inner aura; neither has a hard circular edge.
        drawCircle(brush = Brush.radialGradient(
            colors = listOf(Color(0xFFDAE6F0).copy(alpha = alpha * .23f), Color.Transparent),
            center = at, radius = r * 7f), radius = r * 7f, center = at)
        drawCircle(brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = alpha * .42f), Color.Transparent),
            center = at, radius = r * 3f), radius = r * 3f, center = at)
        // Irregular, asymmetric ash flakes: no round dots. Individual rotations
        // drift over time without rotating the entire screen in lockstep.
        val rotation = phase * TAU * (if (i % 2 == 0) 1f else -1f) + seed(i, 21f) * TAU
        val jagged = Path()
        repeat(9) { point ->
            val theta = rotation + TAU * point / 9f
            val reach = r * (.53f + 1.12f * seed(i * 17 + point, 25f))
            val px = at.x + cos(theta) * reach
            val py = at.y + sin(theta) * reach
            if (point == 0) jagged.moveTo(px, py) else jagged.lineTo(px, py)
        }
        jagged.close()
        drawPath(jagged, Color(0xFFF2F0EE).copy(alpha = alpha))
    }
    val glow = .035f + .045f * pulse(phase * 3f)
    drawCircle(brush = Brush.radialGradient(
        listOf(Color(0xFFAD1829).copy(alpha = glow), Color.Transparent),
        center = Offset(0f, size.height * .35f), radius = size.width * .7f),
        radius = size.width * .7f, center = Offset(0f, size.height * .35f))
}

private fun DrawScope.magic(phase: Float) {
    val gold = Color(0xFFFFCF77)
    drawCircle(brush = Brush.radialGradient(listOf(gold.copy(alpha = .11f), Color.Transparent),
        center = Offset(size.width * .5f, size.height * .55f), radius = size.width * .75f),
        radius = size.width * .75f, center = Offset(size.width * .5f, size.height * .55f))
    // Orbiting spell glyphs and comet trails, rather than uniform particles.
    repeat(12) { i ->
        val age = fract(phase * (.55f + seed(i, 3f) * .5f) + seed(i, 1f))
        val angle = (age * (if (i % 2 == 0) 1f else -1f) + seed(i, 4f)) * TAU
        val cx = size.width * (.5f + (.27f + .13f * seed(i, 5f)) * cos(angle))
        val cy = size.height * (.52f + (.23f + .09f * seed(i, 6f)) * sin(angle))
        val at = Offset(cx, cy)
        val alpha = life(age) * .60f
        val r = (2.4f + seed(i, 7f) * 2f) * density
        drawCircle(gold.copy(alpha = alpha * .14f), r * 5f, at)
        drawLine(gold.copy(alpha = alpha), Offset(cx - r * 2f, cy), Offset(cx + r * 2f, cy), 1.1f * density)
        drawLine(Color.White.copy(alpha = alpha), Offset(cx, cy - r * 2f), Offset(cx, cy + r * 2f), 1.1f * density)
        drawCircle(Color.White.copy(alpha = alpha), r * .48f, at)
        val trail = Offset(cx - cos(angle + .8f) * r * 10f, cy - sin(angle + .8f) * r * 10f)
        drawLine(gold.copy(alpha = alpha * .28f), trail, at, 1.7f * density, cap = StrokeCap.Round)
    }
    // One wandering wand arc.
    val center = Offset(size.width * .5f, size.height * .77f)
    drawArc(gold.copy(alpha = .18f + .12f * pulse(phase * 2f)), 202f + phase * 180f, 115f,
        useCenter = false, topLeft = Offset(center.x - size.width * .3f, center.y - size.width * .3f),
        size = Size(size.width * .6f, size.width * .6f), style = Stroke(1.6f * density, cap = StrokeCap.Round))
}

private fun DrawScope.jungle(phase: Float) {
    val fern = Color(0xFF8AB865)
    drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF133A1F).copy(alpha = .28f), Color.Transparent)))
    // Fern fronds anchored to corners cast shifting jungle silhouettes.
    repeat(7) { i ->
        val fromLeft = i % 2 == 0
        val x = if (fromLeft) -size.width * .03f else size.width * 1.03f
        val y = size.height * (.05f + i * .145f)
        val length = size.width * (.13f + seed(i, 2f) * .17f)
        val sway = sin(phase * TAU + i) * size.width * .014f
        val end = Offset(x + (if (fromLeft) length else -length) + sway, y - length * .25f)
        val start = Offset(x, y)
        drawLine(fern.copy(alpha = .19f), start, end, 2.2f * density, cap = StrokeCap.Round)
        repeat(6) { j ->
            val t = (j + 1) / 7f
            val stem = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
            val bladeLength = length * (.21f * (1f - t * .6f))
            for (side in listOf(-1f, 1f)) {
                val path = Path().apply {
                    moveTo(stem.x, stem.y)
                    quadraticTo(stem.x + side * length * .06f, stem.y + side * bladeLength * .42f,
                        stem.x + (if (fromLeft) 1f else -1f) * bladeLength * .2f, stem.y + side * bladeLength)
                    quadraticTo(stem.x, stem.y + side * bladeLength * .4f, stem.x, stem.y)
                    close()
                }
                drawPath(path, fern.copy(alpha = .12f + .09f * seed(i + j, 1f)))
            }
        }
    }
    // Ground fog bands and drifting shadows.
    repeat(4) { i ->
        val x = fract(seed(i, 8f) + phase * (.07f + i * .014f)) * size.width
        val y = size.height * (.58f + i * .11f)
        drawOval(brush = Brush.radialGradient(listOf(Color(0xFFA3C8A5).copy(alpha = .065f), Color.Transparent),
            center = Offset(x, y), radius = size.width * .55f),
            topLeft = Offset(x - size.width * .55f, y - size.height * .07f),
            size = Size(size.width * 1.1f, size.height * .14f))
    }
}

private fun DrawScope.expedition(phase: Float) {
    val bronze = Color(0xFFDEB875)
    drawRect(brush = Brush.verticalGradient(listOf(bronze.copy(alpha = .12f), Color.Transparent,
        Color(0xFF49280E).copy(alpha = .2f))))
    // Faint moving map contours, compass rose and sweeping torchlight.
    repeat(7) { i ->
        val y = size.height * (.2f + i * .095f)
        val path = Path().apply {
            moveTo(-20f, y)
            cubicTo(size.width * .25f, y - size.height * .055f,
                size.width * .63f, y + size.height * .06f, size.width + 20f, y)
        }
        drawPath(path, bronze.copy(alpha = .08f), style = Stroke(.9f * density))
    }
    val center = Offset(size.width * .81f, size.height * .75f)
    val r = size.width * .095f
    drawCircle(bronze.copy(alpha = .15f), r, center, style = Stroke(1.3f * density))
    drawCircle(bronze.copy(alpha = .08f), r * .76f, center, style = Stroke(.8f * density))
    val angle = phase * TAU * .38f - .6f
    val tip = Offset(center.x + sin(angle) * r * .82f, center.y - cos(angle) * r * .82f)
    val tail = Offset(center.x - sin(angle) * r * .47f, center.y + cos(angle) * r * .47f)
    drawLine(bronze.copy(alpha = .5f), tail, tip, 2f * density, cap = StrokeCap.Round)
    drawCircle(bronze.copy(alpha = .5f), 2f * density, center)
    val lightX = size.width * (.2f + .6f * pulse(phase))
    drawCircle(brush = Brush.radialGradient(listOf(bronze.copy(alpha = .11f), Color.Transparent),
        center = Offset(lightX, size.height * .4f), radius = size.width * .65f),
        radius = size.width * .65f, center = Offset(lightX, size.height * .4f))
}

private fun DrawScope.underwater(phase: Float) {
    val aqua = Color(0xFF63CBDD)
    drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF146881).copy(alpha = .22f),
        Color.Transparent, Color(0xFF031522).copy(alpha = .36f))))
    // Refracted light streaks drift over the water, slow bubbles rise from the bottom.
    repeat(9) { i ->
        val x = size.width * (i / 8f) + sin(phase * TAU + i * .9f) * size.width * .035f
        val path = Path().apply {
            moveTo(x, 0f)
            quadraticTo(x + size.width * .08f, size.height * .33f,
                x - size.width * .09f, size.height * .63f)
        }
        drawPath(path, aqua.copy(alpha = .045f), style = Stroke((2f + i % 3) * density))
    }
    repeat(18) { i ->
        val age = fract(phase * (.6f + seed(i, 22f) * .7f) + seed(i, 23f))
        val x = fract(seed(i, 24f) + sin(age * TAU + i) * .018f) * size.width
        val y = (1f - age) * size.height
        val r = (1.5f + seed(i, 25f) * 5f) * density
        drawCircle(aqua.copy(alpha = life(age) * .40f), r, Offset(x, y), style = Stroke(.95f * density))
    }
    // Occasional submerged shark shadow, intentionally faint and away from controls.
    val sharkAge = fract(phase * .65f + .15f)
    val sx = (sharkAge * 1.5f - .25f) * size.width
    val sy = size.height * .82f
    val scale = size.width * .14f
    val shark = Path().apply {
        moveTo(sx - scale, sy)
        quadraticTo(sx, sy - scale * .14f, sx + scale, sy)
        quadraticTo(sx, sy + scale * .20f, sx - scale, sy)
        moveTo(sx - scale * .12f, sy)
        lineTo(sx + scale * .02f, sy - scale * .45f)
        lineTo(sx + scale * .22f, sy)
        close()
        moveTo(sx - scale, sy)
        lineTo(sx - scale * 1.27f, sy - scale * .29f)
        lineTo(sx - scale * 1.26f, sy + scale * .29f)
        close()
    }
    drawPath(shark, Color(0xFF010A12).copy(alpha = life(sharkAge) * .25f))
}
