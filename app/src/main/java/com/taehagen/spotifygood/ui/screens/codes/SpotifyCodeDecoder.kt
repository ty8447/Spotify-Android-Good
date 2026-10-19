package com.taehagen.spotifygood.ui.screens.codes

/** Independent Spotify Code decoder. Input: 23 measured bar levels, either 1..8 or 0..7. */
internal object SpotifyCodeDecoder {
    data class Decoded(val reference: String, val bitErrors: Int)

    fun decode(levels: List<Int>): Decoded? {
        if (levels.size != 23) return null
        val heights = when {
            levels[0] == 1 && levels[11] == 8 && levels[22] == 1 && levels.all { it in 1..8 } ->
                levels.map { it - 1 }
            levels[0] == 0 && levels[11] == 7 && levels[22] == 0 && levels.all { it in 0..7 } -> levels
            else -> return null
        }
        val dataBars = heights.subList(1, 11) + heights.subList(12, 22)
        val coded = IntArray(60)
        for ((barIndex, height) in dataBars.withIndex()) {
            val gray = height xor (height shr 1)
            for (bitIndex in 0..2) {
                val shuffledIndex = barIndex * 3 + bitIndex
                coded[(shuffledIndex * 7) % 60] = (gray shr (2 - bitIndex)) and 1
            }
        }
        val observed = IntArray(90) { -1 }
        var k = 0
        for (i in 0 until 90) if (i % 3 != 2) observed[i] = coded[k++]

        var bestReference: Long? = null
        var bestErrors = Int.MAX_VALUE
        val inf = 1_000_000
        for (initial in 0 until 64) {
            var costs = IntArray(64) { inf }
            costs[initial] = 0
            val previous = Array(45) { IntArray(64) { -1 } }
            val inputBits = Array(45) { IntArray(64) }
            for (time in 0 until 45) {
                val next = IntArray(64) { inf }
                for (state in 0 until 64) {
                    val cost = costs[state]
                    if (cost == inf) continue
                    for (bit in 0..1) {
                        val window = (state shl 1) or bit
                        val nextState = window and 63
                        val first = Integer.bitCount(window and 0b1101101) and 1
                        val second = Integer.bitCount(window and 0b1001111) and 1
                        var mismatch = 0
                        val a = observed[time * 2]
                        val b = observed[time * 2 + 1]
                        if (a >= 0 && a != first) mismatch++
                        if (b >= 0 && b != second) mismatch++
                        val newCost = cost + mismatch
                        if (newCost < next[nextState]) {
                            next[nextState] = newCost
                            previous[time][nextState] = state
                            inputBits[time][nextState] = bit
                        }
                    }
                }
                costs = next
            }
            val errors = costs[initial]
            if (errors > 4 || errors >= bestErrors) continue
            val payload = IntArray(45)
            var state = initial
            var complete = true
            for (time in 44 downTo 0) {
                if (previous[time][state] == -1) { complete = false; break }
                payload[time] = inputBits[time][state]
                state = previous[time][state]
            }
            if (!complete || !crcValid(payload)) continue
            var reference = 0L
            for (i in 0 until 37) reference = reference or (payload[i].toLong() shl i)
            bestReference = reference
            bestErrors = errors
            if (errors == 0) break
        }
        return bestReference?.let { Decoded(it.toString(), bestErrors) }
    }

    private fun crcValid(payload: IntArray): Boolean {
        val source = IntArray(40)
        for (i in 0 until 37) source[i] = payload[i]
        val polynomial = intArrayOf(1, 0, 0, 0, 0, 0, 1, 1, 1)
        val division = IntArray(48)
        for (i in 0 until 40) division[i] = source[(i / 8) * 8 + 7 - i % 8]
        for (i in 0 until 40) if (division[i] == 1) {
            for (j in polynomial.indices) division[i + j] = division[i + j] xor polynomial[j]
        }
        for (i in 0 until 8) {
            val expected = 1 xor division[47 - i]
            if (payload[37 + i] != expected) return false
        }
        return true
    }
}
