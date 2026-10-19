package com.taehagen.spotifygood.ui.screens.codes

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bounded, image-only perspective fallback for the live Spotify Code camera.
 *
 * No Spotify-logo or banner/edge assumptions. A sparse slanted scanline finds
 * regularly ordered strokes; the strokes define a quadrilateral. Only this small
 * quadrilateral is projectively rectified. At most three distinct hypotheses are
 * decoded/CRC-checked so one bad 23-bar grouping does not mask a good grouping.
 * It is intentionally invoked only after the existing fast detector fails.
 */
internal object SpotifyPerspectiveProbe {
    private data class Row(
        val center: DoubleArray, // centers of 23 bars in deskewed scanline coordinates
        val y: Int,
        val angle: Int,
        val cutoff: Int,
        val dark: Boolean,
        val spacing: Double,
        val quality: Double,
    )

    // Raw grayscale image is already display-rotated by the caller.
    fun find(luma: ByteArray, width: Int, height: Int): SpotifyWaveformProbe.Candidate? {
        if (width < 100 || height < 50 || luma.size < width * height) return null
        val centerX = (width - 1) * 0.5
        val centerY = (height - 1) * 0.5
        val rows = ArrayList<Row>(4)
        val samples = IntArray(width)
        // Fine search near level + a larger set for angled camera views.
        val angles = intArrayOf(0, -6, 6, -12, 12, -18, 18, -24, 24, -3, 3, -9, 9, -15, 15, -21, 21)
        val stepY = max(4, height / 50)
        val firstY = max(6, height / 7)
        val lastY = min(height - 7, height * 6 / 7)
        for (angle in angles) {
            val rad = Math.toRadians(angle.toDouble())
            val co = cos(rad)
            val si = sin(rad)
            for (y in firstY..lastY step stepY) {
                val offsetY = y - centerY
                var count = 0
                val hist = IntArray(32)
                for (x in 0 until width) {
                    val dx = x - centerX
                    val sx = (centerX + dx * co - offsetY * si).roundToInt()
                    val sy = (centerY + dx * si + offsetY * co).roundToInt()
                    val v = if (sx in 0 until width && sy in 0 until height) {
                        luma[sy * width + sx].toInt() and 255
                    } else -1
                    samples[x] = v
                    if (v >= 0) { hist[v ushr 3]++; count++ }
                }
                if (count < width * 0.65) continue
                var running = 0
                var low = 0
                var high = 31
                for (i in hist.indices) {
                    running += hist[i]
                    if (running >= count / 10) { low = i; break }
                }
                running = 0
                for (i in hist.indices) {
                    running += hist[i]
                    if (running >= count * 9 / 10) { high = i; break }
                }
                if (high - low < 6) continue
                val threshold = (low + high + 1) * 4
                for (dark in booleanArrayOf(true, false)) {
                    val runs = ArrayList<IntRange>(32)
                    var start = -1
                    for (x in 0..width) {
                        val v = if (x == width) -1 else samples[x]
                        val fg = v >= 0 && (if (dark) v < threshold else v > threshold)
                        if (fg && start < 0) start = x
                        if (!fg && start >= 0) {
                            if (x - start >= 2) runs.add(start until x)
                            start = -1
                        }
                    }
                    if (runs.size < 23) continue
                    for (i in 0..runs.size - 23) {
                        val bars = runs.subList(i, i + 23)
                        val centers = DoubleArray(23) { n -> (bars[n].first + bars[n].last) * 0.5 }
                        val gap = (centers[22] - centers[0]) / 22.0
                        if (gap < 2.8 || gap > width / 10.0) continue
                        var good = 0
                        var thin = 0
                        // The middle bar is allowed to be slightly off-center in projection.
                        // Quadratic term approximates varying horizontal scale before warp.
                        val bow = centers[11] - (centers[0] + centers[22]) * 0.5
                        if (abs(bow) > gap * 4.0) continue
                        var residual = 0.0
                        for (n in 0 until 23) {
                            val t = n / 22.0
                            val expected = centers[0] * (1.0 - t) + centers[22] * t +
                                bow * 4.0 * t * (1.0 - t)
                            val difference = abs(centers[n] - expected)
                            if (difference <= gap * 0.38) good++
                            residual += difference
                            if (bars[n].last - bars[n].first + 1 <= gap * 0.76) thin++
                        }
                        if (good < 19 || thin < 19 || residual > gap * 8.5) continue
                        val quality = good * 2.0 + thin - residual / gap - abs(angle) * 0.045
                        // Skip duplicates of the same region from nearby scanlines.
                        val same = rows.indexOfFirst { previous ->
                            abs(previous.center[11] - centers[11]) < gap * 2.2 &&
                                abs(previous.y - y) < max(9, stepY * 2) &&
                                abs(previous.angle - angle) <= 8
                        }
                        val row = Row(centers, y, angle, threshold, dark, gap, quality)
                        if (same >= 0) {
                            if (quality > rows[same].quality) rows[same] = row
                        } else {
                            rows.add(row)
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) return null
        rows.sortByDescending { it.quality }
        var best: SpotifyWaveformProbe.Candidate? = null
        var bestErrors = Int.MAX_VALUE
        var tested = 0
        // Alternative bar groups (possibly different nearby codes) are compared
        // by the actual error-correcting decoder, not geometric contrast alone.
        for (row in rows) {
            if (tested >= 3) break
            if (rows.takeWhile { it !== row }.any {
                    abs(it.center[11] - row.center[11]) < row.spacing * 3.0 &&
                        abs(it.y - row.y) < max(14, stepY * 3)
                }) continue
            tested++
            val candidate = rectifyAndRead(luma, width, height, row) ?: continue
            val decoded = SpotifyCodeDecoder.decode(candidate.levels) ?: continue
            if (decoded.bitErrors < bestErrors) {
                best = candidate
                bestErrors = decoded.bitErrors
                if (bestErrors == 0) break
            }
        }
        if (best != null) {
            android.util.Log.i("SpotifyWaveform",
                "Camera perspective candidate angle=${best.orientation} tested=$tested bitErrors=$bestErrors")
        }
        return best
    }

    /** Construct a quadrilateral from the bar centerline and the outer stroke paths. */
    private fun rectifyAndRead(
        luma: ByteArray, w: Int, h: Int, row: Row,
    ): SpotifyWaveformProbe.Candidate? {
        val radians = Math.toRadians(row.angle.toDouble())
        val co = cos(radians)
        val si = sin(radians)
        val cx = (w - 1) * 0.5
        val cy = (h - 1) * 0.5
        fun rawX(x: Double, y: Double) = cx + (x - cx) * co - (y - cy) * si
        fun rawY(x: Double, y: Double) = cy + (x - cx) * si + (y - cy) * co
        fun sample(x: Double, y: Double): Int {
            val ix = rawX(x, y).roundToInt()
            val iy = rawY(x, y).roundToInt()
            return if (ix in 0 until w && iy in 0 until h) luma[iy * w + ix].toInt() and 255 else -1
        }
        fun foreground(x: Double, y: Double): Boolean {
            val v = sample(x, y)
            return v >= 0 && if (row.dark) v < row.cutoff else v > row.cutoff
        }
        val top = IntArray(23)
        val bottom = IntArray(23)
        val topX = DoubleArray(23)
        val bottomX = DoubleArray(23)
        val reach = min(max(18, (row.spacing * 5.5).roundToInt()), min(h / 3, 120))
        for (i in 0 until 23) {
            val x = row.center[i]
            if (!foreground(x, row.y.toDouble())) return null
            // Track each stroke along the direction normal to its 23-bar baseline.
            // Allow a small lateral drift to account for camera perspective.
            var upX = x
            var downX = x
            var up = 0
            var down = 0
            for (direction in intArrayOf(-1, 1)) {
                var misses = 0
                var currentX = x
                var lastValid = 0
                for (distance in 1..reach) {
                    val y = row.y + direction * distance.toDouble()
                    val drift = max(1, min(3, (row.spacing * 0.19).roundToInt()))
                    var foundX = Double.NaN
                    // Prefer continuing the previous path rather than jumping to an adjacent bar.
                    for (d in 0..drift) {
                        if (foreground(currentX + d, y)) { foundX = currentX + d; break }
                        if (d != 0 && foreground(currentX - d, y)) { foundX = currentX - d; break }
                    }
                    if (foundX.isNaN()) {
                        misses++
                        if (misses >= 2) break
                    } else {
                        currentX = foundX
                        misses = 0
                        lastValid = distance
                    }
                }
                if (direction < 0) { up = lastValid; upX = currentX }
                else { down = lastValid; downX = currentX }
            }
            top[i] = row.y - up
            bottom[i] = row.y + down
            topX[i] = upX
            bottomX[i] = downX
        }
        val lengths = DoubleArray(23) { i -> (bottom[i] - top[i] + 1).toDouble() }
        val minLen = lengths.minOrNull() ?: return null
        val maxLen = lengths.maxOrNull() ?: return null
        if (minLen < 3 || maxLen < 9 || maxLen < minLen * 1.8) return null
        // Followed stroke extents are often more reliable than a resampled bitmap
        // when one edge is aliased. The end locator bars are level 1 and the
        // center locator is level 8: use those three anchors to normalize the
        // projective change in stroke scale across the row.
        decodeHeightHypotheses(lengths, row.angle)?.let { return it }

        // Find the enclosing quad from stroke extrema rather than banner edges.
        // Tracks near the outer thirds are more reliable than short end locators.
        val reachTop = lengths.maxOrNull()!! * 0.63 + row.spacing
        val halfHeight = min(reach.toDouble(), reachTop).coerceAtLeast(row.spacing * 2.0)
        fun xAt(i: Int, y: Double): Double {
            val length = (bottom[i] - top[i]).coerceAtLeast(1)
            val tilt = (bottomX[i] - topX[i]) / length
            return (row.center[i] + tilt * (y - row.y)).coerceIn(0.0, (w - 1).toDouble())
        }
        fun edgeX(indices: IntRange, y: Double): Double {
            // Linear extrapolation from the observed samples to the outermost bar.
            val arr = indices.map { i -> i.toDouble() to xAt(i, y) }
            val i0 = arr.first().first
            val i1 = arr.last().first
            val x0 = arr.first().second
            val x1 = arr.last().second
            val wanted = if (indices.first < 11) 0.0 else 22.0
            return x0 + (x1 - x0) * ((wanted - i0) / (i1 - i0))
        }
        val y0 = row.y - halfHeight
        val y1 = row.y + halfHeight
        val sidePad = row.spacing * 0.65
        val lx0 = edgeX(1..5, y0) - sidePad
        val rx0 = edgeX(17..21, y0) + sidePad
        val lx1 = edgeX(1..5, y1) - sidePad
        val rx1 = edgeX(17..21, y1) + sidePad
        if (min(rx0 - lx0, rx1 - lx1) < row.spacing * 17.0) return null
        // Clockwise corners (TL, TR, BR, BL) in camera pixel coordinates.
        val corners = doubleArrayOf(
            rawX(lx0, y0), rawY(lx0, y0), rawX(rx0, y0), rawY(rx0, y0),
            rawX(rx1, y1), rawY(rx1, y1), rawX(lx1, y1), rawY(lx1, y1),
        )
        val warp = Homography.fromSquare(corners) ?: return null
        val dstW = 240
        val dstH = 96
        val rectified = ByteArray(dstW * dstH)
        for (y in 0 until dstH) {
            val v = y.toDouble() / (dstH - 1)
            for (x in 0 until dstW) {
                val u = x.toDouble() / (dstW - 1)
                val sx = warp.x(u, v).roundToInt()
                val sy = warp.y(u, v).roundToInt()
                rectified[y * dstW + x] = if (sx in 0 until w && sy in 0 until h)
                    luma[sy * w + sx] else 127.toByte()
            }
        }
        // Measure each stroke in the rectified image. This is a *projective* warp:
        // both vertical-line convergence and unequal near/far bar spacing are corrected.
        val rMid = dstH / 2
        val positions = DoubleArray(23) { i -> (0.65 + i) / (23.0 + 0.30) * (dstW - 1) }
        // Use the detected source centers, projectively mapped to rectified x via
        // the inverse homography (find the centerline intersection numerically).
        for (i in 0 until 23) {
            val targetX = rawX(row.center[i], row.y.toDouble())
            val targetY = rawY(row.center[i], row.y.toDouble())
            var closest = Double.MAX_VALUE
            for (x in 0 until dstW step 1) {
                val u = x.toDouble() / (dstW - 1)
                val err = abs(warp.x(u, 0.5) - targetX) + abs(warp.y(u, 0.5) - targetY)
                if (err < closest) { closest = err; positions[i] = x.toDouble() }
            }
        }
        val observed = DoubleArray(23)
        for (i in 0 until 23) {
            val x = positions[i].roundToInt().coerceIn(1, dstW - 2)
            var bestX = x
            var bestContrast = -1
            for (xx in max(1, x - 2)..min(dstW - 2, x + 2)) {
                val v = rectified[rMid * dstW + xx].toInt() and 255
                val contrast = if (row.dark) 255 - v else v
                if (contrast > bestContrast) { bestContrast = contrast; bestX = xx }
            }
            fun isStroke(xx: Int, yy: Int): Boolean {
                val v = rectified[yy * dstW + xx].toInt() and 255
                return if (row.dark) v < row.cutoff else v > row.cutoff
            }
            var anchorY = -1
            var anchorX = bestX
            // Perspective and the short end locators can shift the barcode
            // baseline several pixels away from rectified image center.
            for (delta in 0..7) {
                for (direction in intArrayOf(-1, 1)) {
                    val yy = rMid + delta * direction
                    if (yy !in 1 until dstH - 1) continue
                    for (xx in max(1, bestX - 2)..min(dstW - 2, bestX + 2)) {
                        if (isStroke(xx, yy)) { anchorY = yy; anchorX = xx; break }
                    }
                    if (anchorY >= 0) break
                }
                if (anchorY >= 0) break
            }
            if (anchorY < 0) return null
            var a = anchorY
            var b = anchorY
            while (a > 0 && isStroke(anchorX, a - 1)) a--
            while (b < dstH - 1 && isStroke(anchorX, b + 1)) b++
            observed[i] = (b - a + 1).toDouble()
        }
        val minimum = observed.minOrNull() ?: return null
        val maximum = observed.maxOrNull() ?: return null
        if (minimum < 2 || maximum < minimum * 1.8) return null
        val levels = observed.map { (1.0 + 7.0 * (it - minimum) / (maximum - minimum)).roundToInt().coerceIn(1, 8) }
        if (levels[0] != 1 || levels[11] != 8 || levels[22] != 1) return null
        return SpotifyWaveformProbe.Candidate(levels, (maximum - minimum).roundToInt(), row.angle)
    }

    /** Perspective scale variation: infer 1-level baseline from two end locators,
     * then fit a slowly varying height unit using the central level-8 locator.
     * Test several nearby quantizations through the actual CRC decoder rather
     * than assuming every stroke was measured without occlusion or aliasing.
     */
    private fun decodeHeightHypotheses(
        height: DoubleArray, angle: Int,
    ): SpotifyWaveformProbe.Candidate? {
        val middleBase = (height[0] + height[22]) / 2.0
        val unit = (height[11] - middleBase) / 7.0
        if (unit < 1.0) return null
        data class Hypothesis(val levels: List<Int>, val residual: Double)
        val hypotheses = ArrayList<Hypothesis>(60)
        val seen = HashSet<String>()
        for (slopeStep in -9..9) {
            val slope = slopeStep * 0.075
            for (gainStep in -3..3) {
                val gain = 1.0 + gainStep * 0.045
                var residual = 0.0
                val levels = ArrayList<Int>(23)
                for (i in 0 until 23) {
                    val t = i / 22.0
                    val offset = height[0] * (1.0 - t) + height[22] * t
                    val scale = unit * gain * (1.0 + slope * (t - 0.5))
                    val floating = 1.0 + (height[i] - offset) / scale
                    val rounded = floating.roundToInt().coerceIn(1, 8)
                    residual += (floating - rounded) * (floating - rounded)
                    levels.add(rounded)
                }
                if (levels[0] != 1 || levels[11] != 8 || levels[22] != 1) continue
                val key = levels.joinToString("")
                if (seen.add(key)) hypotheses.add(Hypothesis(levels, residual))
            }
        }
        hypotheses.sortBy { it.residual }
        var best: SpotifyWaveformProbe.Candidate? = null
        var bestErrors = Int.MAX_VALUE
        for (hypothesis in hypotheses.take(16)) {
            for (levels in listOf(hypothesis.levels, hypothesis.levels.reversed())) {
                val decoded = SpotifyCodeDecoder.decode(levels) ?: continue
                if (decoded.bitErrors < bestErrors) {
                    bestErrors = decoded.bitErrors
                    best = SpotifyWaveformProbe.Candidate(
                        levels, (height.maxOrNull()!! - height.minOrNull()!!).roundToInt(), angle
                    )
                    if (bestErrors == 0) break
                }
            }
            if (bestErrors == 0) break
        }
        return best
    }

    private class Homography(private val a: Double, private val b: Double, private val c: Double,
                             private val d: Double, private val e: Double, private val f: Double,
                             private val g: Double, private val h: Double) {
        fun x(u: Double, v: Double): Double = (a * u + b * v + c) / (g * u + h * v + 1.0)
        fun y(u: Double, v: Double): Double = (d * u + e * v + f) / (g * u + h * v + 1.0)
        companion object {
            fun fromSquare(q: DoubleArray): Homography? {
                val x0 = q[0]; val y0 = q[1]
                val x1 = q[2]; val y1 = q[3]
                val x2 = q[4]; val y2 = q[5]
                val x3 = q[6]; val y3 = q[7]
                val dx1 = x1 - x2; val dx2 = x3 - x2
                val dy1 = y1 - y2; val dy2 = y3 - y2
                val dx3 = x0 - x1 + x2 - x3
                val dy3 = y0 - y1 + y2 - y3
                val determinant = dx1 * dy2 - dx2 * dy1
                val g: Double; val h: Double
                if (abs(dx3) + abs(dy3) < 1e-6) { g = 0.0; h = 0.0 }
                else {
                    if (abs(determinant) < 1e-6) return null
                    g = (dx3 * dy2 - dx2 * dy3) / determinant
                    h = (dx1 * dy3 - dx3 * dy1) / determinant
                }
                if (abs(g) > 3.0 || abs(h) > 3.0) return null
                return Homography(
                    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                    y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h)
            }
        }
    }
}
