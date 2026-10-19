package com.taehagen.spotifygood.ui.screens.codes

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Experimental image-only Spotify bar extractor. NOT an identifier decoder.
 * A detection is reported for diagnostics, never opened as a Spotify URI.
 */
internal object SpotifyWaveformProbe {
    data class Candidate(
        val levels: List<Int>,
        val contrast: Int,
        val orientation: Int,
        // Nonzero for live YUV frames, allowing confirmation across distinct frames.
        val observedAtMs: Long = 0L,
    ) {
        val signature: String get() = levels.joinToString("")
    }

    fun fromBitmap(bitmap: Bitmap): Candidate? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 70 || height < 25) return null
        // Read one pixel buffer instead of repeatedly creating/copying/recycling
        // full-size Bitmap crops. Caller owns the Bitmap and still recycles it.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val pixel: (Int, Int) -> Int = { x, y -> pixels[y * width + x] and 0xFFFFFF }

        // Fast path for the common black-on-yellow Spotify Code banner, including
        // banners printed below artwork. The logo is deliberately excluded by
        // requiring exactly 23 narrow, evenly spaced strokes to its right.
        detectFlatBanner(width, height, pixels)?.let {
            android.util.Log.i("SpotifyWaveform", "Gallery flat-banner candidate 23 bars")
            return it
        }

        fun inspect(left: Int, top: Int, w: Int, h: Int, bounded: Boolean): Candidate? {
            if (w < 70 || h < 25) return null
            return detect(w, h, boundedBars = bounded) { x, y -> pixel(left + x, top + y) }
        }

        // Keep the original full-image behavior as the first attempt so clean
        // Spotify Codes that already worked are unaffected.
        inspect(0, 0, width, height, bounded = false)?.let { return it }

        // For codes surrounded by UI, print, or artwork, estimate background
        // inside overlapping local windows rather than from the photo edges.
        // A second pass uses the bounded vertical-stroke measurement proven on
        // camera frames. Windows only change coordinates; no bitmap allocations.
        val windows = ArrayList<IntArray>(15)
        for (fraction in listOf(0.60, 0.38)) {
            val w = (width * fraction).roundToInt().coerceAtLeast(70).coerceAtMost(width)
            val h = (height * fraction).roundToInt().coerceAtLeast(25).coerceAtMost(height)
            val xs = if (fraction > 0.5) listOf(0, (width - w) / 2, width - w)
                else listOf(0, (width - w) / 2, width - w)
            val ys = if (fraction > 0.5) listOf(0, (height - h) / 2, height - h)
                else listOf(0, (height - h) / 2, height - h)
            // Prioritize center and the broad 60% windows before smaller windows.
            val positions = xs.flatMap { x -> ys.map { y -> x to y } }
                .sortedBy { (x, y) -> abs(x - (width - w) / 2) + abs(y - (height - h) / 2) }
            for ((x, y) in positions) {
                if (windows.none { it[0] == x && it[1] == y && it[2] == w && it[3] == h }) {
                    windows.add(intArrayOf(x, y, w, h))
                }
            }
        }
        for ((index, window) in windows.withIndex()) {
            val (x, y, w, h) = window
            inspect(x, y, w, h, bounded = true)?.let {
                android.util.Log.i("SpotifyWaveform", "Gallery local candidate window=$index size=${w}x${h} bounded=true")
                return it
            }
        }
        android.util.Log.i("SpotifyWaveform", "Gallery localization exhausted windows=${windows.size} source=${width}x${height}")
        return null
    }

    /**
     * Read the camera's Y plane once, prioritizing a tracked region. Tracker coordinates
     * are normalized in the display-rotated frame; the crop is rotated during copying.
     * This avoids 18 expensive full-resolution sliding-window passes per frame.
     */
    fun fromFrame(proxy: ImageProxy, target: SpotifyWaveformTracker.Box? = null): Candidate? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val w0 = proxy.width
        val h0 = proxy.height
        if (w0 < 120 || h0 < 80) return null
        val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val rotated = rotation == 90 || rotation == 270
        val frameW = if (rotated) h0 else w0
        val frameH = if (rotated) w0 else h0
        val source = plane.buffer.duplicate()
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride

        fun sampleRegion(left: Int, top: Int, width: Int, height: Int): Candidate? {
            if (width < 70 || height < 30) return null
            val step = max(1, max(width, height) / 620)
            val w = width / step
            val h = height / step
            if (w < 70 || h < 25) return null
            val luminance = ByteArray(w * h)
            for (y in 0 until h) {
                val sy = top + y * step
                for (x in 0 until w) {
                    val sx = left + x * step
                    val rawX: Int
                    val rawY: Int
                    when (rotation) {
                        90 -> { rawX = sy; rawY = h0 - 1 - sx }
                        180 -> { rawX = w0 - 1 - sx; rawY = h0 - 1 - sy }
                        270 -> { rawX = w0 - 1 - sy; rawY = sx }
                        else -> { rawX = sx; rawY = sy }
                    }
                    val index = rawY * stride + rawX * pixelStride
                    if (index in 0 until source.limit()) luminance[y * w + x] = source.get(index)
                }
            }
            val straight = detect(w, h, rotations = listOf(0, 2), boundedBars = true) { x, y ->
                val v = luminance[y * w + x].toInt() and 255
                (v shl 16) or (v shl 8) or v
            }
            // Existing fast detector remains first. When its bar measurements
            // fail CRC, try other 23-bar groups with a bar-derived projective
            // transform before falling back to the v14 in-plane deskew.
            if (straight != null && SpotifyCodeDecoder.decode(straight.levels) != null) return straight
            SpotifyPerspectiveProbe.find(luminance, w, h)?.let { return it }
            if (straight != null) return straight
            // Fast estimate from the 23 regularly spaced strokes along slanted rows.
            // Only when the ordinary detector fails do we sample a rotated ROI.
            val angle = estimateBarRowAngle(luminance, w, h) ?: return null
            val radians = Math.toRadians(angle.toDouble())
            val cosAngle = kotlin.math.cos(radians)
            val sinAngle = kotlin.math.sin(radians)
            val cx = (w - 1) * 0.5
            val cy = (h - 1) * 0.5
            val corrected = detect(w, h, rotations = listOf(0, 2), boundedBars = true) { x, y ->
                val dx = x - cx
                val dy = y - cy
                val sx = (cx + dx * cosAngle - dy * sinAngle).roundToInt()
                val sy = (cy + dx * sinAngle + dy * cosAngle).roundToInt()
                val v = if (sx in 0 until w && sy in 0 until h) {
                    luminance[sy * w + sx].toInt() and 255
                } else 128
                (v shl 16) or (v shl 8) or v
            }
            if (corrected != null) {
                android.util.Log.i("SpotifyWaveform", "Camera deskew candidate angle=${angle}deg")
            }
            return corrected
        }

        if (target != null) {
            val boxWidth = ((target.right - target.left) * frameW).coerceAtLeast(1f)
            val boxHeight = ((target.bottom - target.top) * frameH).coerceAtLeast(1f)
            // Tracker may only lock onto a subset of bars: expand horizontally.
            val padX = max(35f, boxWidth * 0.45f)
            val padY = max(28f, boxHeight * 0.60f)
            val left = (target.left * frameW - padX).toInt().coerceIn(0, frameW - 1)
            val top = (target.top * frameH - padY).toInt().coerceIn(0, frameH - 1)
            val right = (target.right * frameW + padX).toInt().coerceIn(left + 1, frameW)
            val bottom = (target.bottom * frameH + padY).toInt().coerceIn(top + 1, frameH)
            sampleRegion(left, top, right - left, bottom - top)?.let { return it }
        }
        // A bounded center-frame search is considerably cheaper than sliding windows
        // across the entire camera image. Gallery decoding still uses its full search.
        val width = (frameW * 0.74f).toInt()
        val height = (frameH * 0.54f).toInt()
        return sampleRegion((frameW - width) / 2, (frameH - height) / 2, width, height)
    }

    /**
     * Estimate camera roll from the *code bars*, without assuming a specific
     * background color or a visible rectangular border. A true projective warp
     * requires reliable four-corner geometry; this inexpensive affine deskew
     * addresses the more common tilted-phone case first.
     *
     * This runs only if normal detection misses and examines sparse scanlines.
     */
    private fun estimateBarRowAngle(luma: ByteArray, w: Int, h: Int): Int? {
        val cx = (w - 1) * 0.5
        val cy = (h - 1) * 0.5
        var bestScore = -1
        var bestAngle = 0
        val line = IntArray(w)
        val angles = intArrayOf(-21, -18, -15, -12, -9, -6, 6, 9, 12, 15, 18, 21)
        val stepY = max(5, h / 32)
        val startY = max(5, h / 8)
        val endY = min(h - 6, h * 7 / 8)
        for (angle in angles) {
            val radians = Math.toRadians(angle.toDouble())
            val sine = kotlin.math.sin(radians)
            val cosine = kotlin.math.cos(radians)
            var angleScore = 0
            for (y in startY..endY step stepY) {
                // Sample the row that would become horizontal after rotation.
                val dy = y - cy
                var valid = 0
                for (x in 0 until w) {
                    val dx = x - cx
                    val sx = (cx + dx * cosine - dy * sine).roundToInt()
                    val sy = (cy + dx * sine + dy * cosine).roundToInt()
                    line[x] = if (sx in 0 until w && sy in 0 until h) {
                        valid++
                        luma[sy * w + sx].toInt() and 255
                    } else -1
                }
                if (valid < w * 0.7) continue
                // Histogram-based foreground selection handles dark or light bars
                // on backgrounds of arbitrary color. Skip low-contrast rows.
                val histogram = IntArray(16)
                for (v in line) if (v >= 0) histogram[v ushr 4]++
                var total = 0
                var low = 0
                var high = 15
                for (bucket in histogram) total += bucket
                var running = 0
                for (i in 0..15) {
                    running += histogram[i]
                    if (running >= total / 10) { low = i; break }
                }
                running = 0
                for (i in 0..15) {
                    running += histogram[i]
                    if (running >= total * 9 / 10) { high = i; break }
                }
                if (high - low < 3) continue
                val cutoff = (low + high + 1) * 8
                var darkCount = 0
                for (v in line) if (v in 0..<cutoff) darkCount++
                val darkForeground = darkCount < valid / 2
                // Extract narrow foreground runs and look for the 23-bar rhythm.
                val runs = ArrayList<IntRange>(32)
                var runStart = -1
                for (x in 0..w) {
                    val v = if (x < w) line[x] else -1
                    val foreground = v >= 0 && if (darkForeground) v < cutoff else v >= cutoff
                    if (foreground && runStart < 0) runStart = x
                    if (!foreground && runStart >= 0) {
                        if (x - runStart >= 2) runs.add(runStart until x)
                        runStart = -1
                    }
                }
                if (runs.size < 23) continue
                for (first in 0..runs.size - 23) {
                    val bars = runs.subList(first, first + 23)
                    val avgGap = (bars.last().first + bars.last().last - bars.first().first - bars.first().last) / 44.0
                    if (avgGap < 3.0 || avgGap > w / 10.0) continue
                    var correctGaps = 0
                    var thinBars = 0
                    for (i in 0..22) {
                        val width = bars[i].last - bars[i].first + 1
                        if (width <= avgGap * 0.72) thinBars++
                        if (i < 22) {
                            val gap = ((bars[i + 1].first + bars[i + 1].last) -
                                (bars[i].first + bars[i].last)) / 2.0
                            if (gap in avgGap * 0.76..avgGap * 1.24) correctGaps++
                        }
                    }
                    if (correctGaps < 19 || thinBars < 20) continue
                    val score = correctGaps * 2 + thinBars
                    if (score > angleScore) angleScore = score
                }
            }
            if (angleScore > bestScore) {
                bestScore = angleScore
                bestAngle = angle
            }
        }
        // 19/22 gaps and 20/23 narrow strokes required at minimum (58 points).
        return if (bestScore >= 58) bestAngle else null
    }

    private fun detect(
        w: Int, h: Int, rotations: List<Int> = listOf(0, 1, 2, 3),
        boundedBars: Boolean = false, pixel: (Int, Int) -> Int,
    ): Candidate? {
        if (w < 70 || h < 25) return null
        var best: Candidate? = null
        // Frames are often landscape YUV even when the preview is portrait.
        for (rotation in rotations) {
            val rw = if (rotation % 2 == 0) w else h
            val rh = if (rotation % 2 == 0) h else w
            if (rw < 70) continue
            val at: (Int, Int) -> Int = { x, y -> when (rotation) {
                0 -> pixel(x, y)
                1 -> pixel(y, h - 1 - x)
                2 -> pixel(w - 1 - x, h - 1 - y)
                else -> pixel(w - 1 - y, x)
            } }
            // Multiple windows provide tolerance for off-center scans and uncropped gallery photos.
            val fractions = listOf(0.15, 0.25, 0.35, 0.45, 0.5, 0.55, 0.65, 0.75, 0.85)
            for (fraction in fractions) {
                val cy = (rh * fraction).toInt().coerceIn(0, rh - 1)
                val band = max(10, min(rh / 5, 42))
                val y0 = max(0, cy - band / 2)
                val y1 = min(rh, cy + band / 2)
                if (y1 - y0 < 6) continue
                // Background estimate: borders of the candidate horizontal strip.
                val a = at(0, cy)
                val b = at(rw - 1, cy)
                val bg = if (distance(a, b) < 90) a else at(rw / 2, max(0, y0 - 1))
                val threshold = 75
                val count = IntArray(rw)
                val minY = IntArray(rw) { rh }
                val maxY = IntArray(rw) { -1 }
                for (x in 0 until rw) {
                    for (y in y0 until y1) {
                        if (distance(at(x, y), bg) >= threshold) {
                            count[x]++
                            minY[x] = min(minY[x], y)
                            maxY[x] = max(maxY[x], y)
                        }
                    }
                }
                val minCount = max(2, (y1 - y0) / 8)
                val runs = ArrayList<IntRange>()
                var start = -1
                for (x in 0..rw) {
                    val active = x < rw && count[x] >= minCount
                    if (active && start < 0) start = x
                    if (!active && start >= 0) {
                        if (x - start >= 2) runs += start until x
                        start = -1
                    }
                }
                // Find exactly 23 adjacent, similarly spaced, narrow columns.
                for (startRun in 0..runs.size - 23) {
                    val bars = runs.subList(startRun, startRun + 23)
                    val widths = bars.map { it.last - it.first + 1 }
                    val centers = bars.map { (it.first + it.last) / 2.0 }
                    val gaps = centers.zipWithNext { x, y -> y - x }
                    val avg = gaps.average()
                    if (avg < 3 || avg > rw / 12.0) continue
                    if (gaps.any { it < avg * 0.65 || it > avg * 1.4 }) continue
                    if (widths.any { it > avg * 0.8 || it < 2 }) continue
                    val heights = bars.map { range ->
                        val x = (range.first + range.last) / 2
                        if (!boundedBars) {
                            // Preserve the proven gallery behavior until camera results are validated.
                            var lo = rh
                            var hi = -1
                            for (y in 0 until rh) {
                                if (distance(at(x, y), bg) >= threshold) {
                                    lo = min(lo, y)
                                    hi = max(hi, y)
                                }
                            }
                            if (hi < 0) 0 else hi - lo + 1
                        } else {
                            // Camera: measure only the connected stroke through the detection band.
                            // Full-column extrema include nearby artwork/text and turn most bars into 8s.
                            // A Spotify bar is much shorter than the width of the 23-bar code.
                            val radius = (avg * 5.5).roundToInt().coerceAtLeast(14)
                            val loLimit = max(0, y0 - radius)
                            val hiLimit = min(rh - 1, y1 + radius)
                            // Locate the strongest contiguous vertical run intersecting the scan band.
                            // Bridging one missing pixel tolerates anti-aliasing and screen moire.
                            var bestHeight = 0
                            var bestDistance = Int.MAX_VALUE
                            var y = loLimit
                            while (y <= hiLimit) {
                                if (distance(at(x, y), bg) < threshold) { y++; continue }
                                val begin = y
                                var end = y
                                var gaps = 0
                                y++
                                while (y <= hiLimit && gaps < 2) {
                                    if (distance(at(x, y), bg) >= threshold) {
                                        end = y
                                        gaps = 0
                                    } else gaps++
                                    y++
                                }
                                // Require intersection with central search strip, but don't attach
                                // unrelated regions above/below the code to the stroke.
                                if (end >= y0 && begin < y1) {
                                    val height = end - begin + 1
                                    val distanceToCenter = abs((begin + end) / 2 - cy)
                                    if (height > bestHeight ||
                                        (height == bestHeight && distanceToCenter < bestDistance)) {
                                        bestHeight = height
                                        bestDistance = distanceToCenter
                                    }
                                }
                            }
                            bestHeight
                        }
                    }
                    val maxHeight = heights.maxOrNull() ?: continue
                    val minHeight = heights.minOrNull() ?: continue
                    if (maxHeight < 10 || minHeight < 3 || maxHeight.toDouble() / minHeight < 2.0) continue
                    // For diagnostics only: quantize between smallest/largest measured heights.
                    val levels = heights.map { (1 + 7.0 * (it - minHeight) / (maxHeight - minHeight)).roundToInt().coerceIn(1, 8) }
                    if (boundedBars) {
                        // Bad camera segmentation can saturate most bars at the same extreme.
                        if (levels.count { it == 8 } >= 15 || levels.distinct().size < 4) continue
                        // First/last bars should be short and the middle locator bar tall.
                        if (levels.first() >= 6 || levels.last() >= 6 || levels[11] <= 5) continue
                    }
                    val candidate = Candidate(levels, maxHeight - minHeight, rotation * 90)
                    if (best == null || candidate.contrast > best.contrast) best = candidate
                }
            }
        }
        return best
    }

    /** A logo plus 23 dark strokes on a solid-color strip (not only yellow). */
    private fun detectFlatBanner(w: Int, h: Int, pixels: IntArray): Candidate? {
        fun rgbDist(a: Int, b: Int): Int = max(
            abs((a ushr 16 and 255) - (b ushr 16 and 255)),
            max(abs((a ushr 8 and 255) - (b ushr 8 and 255)), abs((a and 255) - (b and 255)))
        )
        fun luminance(c: Int): Int = ((c ushr 16 and 255) * 77 +
            (c ushr 8 and 255) * 150 + (c and 255) * 29) ushr 8
        val xStep = max(1, w / 220)
        val yStep = max(1, h / 360)
        // Find a row band whose background is uniform across most of its width.
        // Sample the outermost columns for background color; ignore the dark logo
        // and bars when counting similar pixels. Prefer the bottom band on artwork.
        var bestTop = -1
        var bestBottom = -1
        var runStart = -1
        var runLast = -1
        val maxBandGap = max(90, h / 8)
        fun finishRun() {
            if (runStart >= 0 && runLast - runStart > bestBottom - bestTop &&
                runLast - runStart >= 30) {
                bestTop = runStart
                bestBottom = runLast
            }
            runStart = -1
        }
        for (y in 0 until h step yStep) {
            val bg = pixels[y * w + min(w - 1, max(0, w / 100))]
            val bgLum = luminance(bg)
            var matches = 0
            var total = 0
            for (x in 0 until w step xStep) {
                total++
                if (rgbDist(pixels[y * w + x], bg) <= 28) matches++
            }
            // Reject near-black artwork regions as background candidates.
            val uniform = bgLum >= 105 && matches * 100 >= total * 66
            if (uniform) {
                if (runStart < 0) runStart = y
                runLast = min(h - 1, y + yStep)
            } else if (runStart >= 0 && y - runLast > maxBandGap) finishRun()
        }
        finishRun()
        if (bestTop < 0) return null
        val top = bestTop
        val bottom = bestBottom
        val cy = (top + bottom) / 2
        val bg = pixels[cy * w + min(w - 1, max(0, w / 100))]
        val bgLum = luminance(bg)
        fun dark(x: Int, y: Int): Boolean {
            val c = pixels[y * w + x]
            return bgLum - luminance(c) >= max(48, bgLum / 3)
        }
        val runs = ArrayList<IntRange>(28)
        var start = -1
        for (x in 0..w) {
            val active = x < w && dark(x, cy)
            if (active && start == -1) start = x
            if (!active && start >= 0) {
                if (x - start >= 2) runs.add(start until x)
                start = -1
            }
        }
        if (runs.size < 23) return null
        for (i in 0..runs.size - 23) {
            val bars = runs.subList(i, i + 23)
            val centers = bars.map { (it.first + it.last) / 2.0 }
            val gaps = centers.zipWithNext { a, b -> b - a }
            val spacing = gaps.average()
            if (spacing < 3 || gaps.any { it < spacing * 0.82 || it > spacing * 1.18 }) continue
            if (bars.any { it.last - it.first + 1 > spacing * 0.7 }) continue
            val heights = bars.map { range ->
                val x = (range.first + range.last) / 2
                if (!dark(x, cy)) return@map 0
                var lo = cy
                var hi = cy
                while (lo > top && dark(x, lo - 1)) lo--
                while (hi < bottom && dark(x, hi + 1)) hi++
                hi - lo + 1
            }
            val minHeight = heights.minOrNull() ?: continue
            val maxHeight = heights.maxOrNull() ?: continue
            if (minHeight < 3 || maxHeight < minHeight * 2) continue
            val levels = heights.map {
                (1 + 7.0 * (it - minHeight) / (maxHeight - minHeight)).roundToInt().coerceIn(1, 8)
            }
            if (levels.first() > 3 || levels.last() > 3 || levels[11] < 7) continue
            return Candidate(levels, maxHeight - minHeight, 0)
        }
        return null
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = abs((a shr 16 and 255) - (b shr 16 and 255))
        val dg = abs((a shr 8 and 255) - (b shr 8 and 255))
        val db = abs((a and 255) - (b and 255))
        return max(dr, max(dg, db))
    }
}
