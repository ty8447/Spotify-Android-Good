package com.taehagen.spotifygood.ui.screens.codes

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Permissive, diagnostic-only candidate tracker. Does not decode or authorize navigation. */
internal object SpotifyWaveformTracker {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val bars: Int)

    fun locate(proxy: ImageProxy): Box? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val w0 = proxy.width
        val h0 = proxy.height
        if (w0 < 120 || h0 < 80) return null
        val step = max(1, max(w0, h0) / 900)
        val w = w0 / step
        val h = h0 / step
        val bytes = ByteArray(w * h)
        val src = plane.buffer.duplicate()
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * step * plane.rowStride + x * step * plane.pixelStride
            if (p < src.limit()) bytes[y * w + x] = src.get(p)
        }
        val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360
        val rw = if (rotation == 90 || rotation == 270) h else w
        val rh = if (rotation == 90 || rotation == 270) w else h
        fun light(x: Int, y: Int): Int {
            val index = when (rotation) {
                90 -> (h - 1 - x) * w + y
                180 -> (h - 1 - y) * w + (w - 1 - x)
                270 -> x * w + (w - 1 - y)
                else -> y * w + x
            }
            return bytes[index].toInt() and 255
        }
        var best: Box? = null
        var bestScore = 0.0
        val bandH = max(12, min(48, rh / 8))
        for (cy in (bandH / 2 until rh - bandH / 2 step max(8, bandH / 2))) {
            val y0 = cy - bandH / 2
            val y1 = min(rh, y0 + bandH)
            // Estimate local contrast across strip; code bars can be light or dark.
            var minL = 255
            var maxL = 0
            for (y in y0 until y1 step 3) for (x in 0 until rw step 3) {
                val v = light(x, y)
                minL = min(minL, v)
                maxL = max(maxL, v)
            }
            if (maxL - minL < 42) continue
            val cut = (minL + maxL) / 2
            for (bright in listOf(true, false)) {
                val counts = IntArray(rw)
                for (x in 0 until rw) for (y in y0 until y1 step 2) {
                    val v = light(x, y)
                    if (if (bright) v > cut else v < cut) counts[x]++
                }
                val threshold = max(2, (y1 - y0) / 14)
                val runs = ArrayList<IntRange>()
                var start = -1
                for (x in 0..rw) {
                    val active = x < rw && counts[x] >= threshold
                    if (active && start < 0) start = x
                    if (!active && start >= 0) {
                        if (x - start in 2..18) runs.add(start until x)
                        start = -1
                    }
                }
                // Track smaller plausible groups even when we cannot decode all 23 bars.
                for (i in runs.indices) {
                    if (i + 7 >= runs.size) break
                    val group = ArrayList<IntRange>()
                    group.add(runs[i])
                    var avgGap = 0.0
                    for (j in i + 1 until min(runs.size, i + 23)) {
                        val gap = (runs[j].first + runs[j].last - runs[j - 1].first - runs[j - 1].last) / 2.0
                        if (gap !in 3.0..25.0) break
                        if (avgGap > 0 && (gap < avgGap * 0.67 || gap > avgGap * 1.42)) break
                        group.add(runs[j]); avgGap = if (avgGap == 0.0) gap else avgGap * 0.75 + gap * 0.25
                    }
                    if (group.size < 8) continue
                    val width = group.last().last - group.first().first + 1
                    if (width < 45) continue
                    val score = group.size.toDouble() + min(1.0, (maxL - minL) / 130.0)
                    if (score <= bestScore) continue
                    // Keep bounds within image; taller bar span than the sample band.
                    val padX = max(5, (avgGap * 2).toInt())
                    val padY = max(bandH, (width * 0.14).toInt())
                    bestScore = score
                    best = Box(
                        (group.first().first - padX).coerceAtLeast(0).toFloat() / rw,
                        (cy - padY).coerceAtLeast(0).toFloat() / rh,
                        (group.last().last + padX).coerceAtMost(rw - 1).toFloat() / rw,
                        (cy + padY).coerceAtMost(rh - 1).toFloat() / rh,
                        group.size,
                    )
                }
            }
        }
        return best
    }
}
