package com.taehagen.spotifygood.vibedj

import kotlinx.serialization.Serializable

/** `dj.state`: what Vibe DJ is doing (sent 4×/s while mixing, 1×/s otherwise). */
@Serializable
data class DjState(
    val mode: String = "off",
    val status: String = "",
    /** pass | armed | mix | settle | handoff | join | glide */
    val phase: String = "pass",
    val progress: Float = 0f,
    val aPosMs: Long = 0,
    val bPosMs: Long = 0,
    val current: String? = null,
    val next: String? = null,
    val analyzing: String? = null,
    val pending: Int = 0,
    val poolSize: Int = 0,
    val target: DjTarget? = null,
    val locked: String? = null,
    val elapsedMs: Long = 0,
) {
    val active get() = mode == "dj"
    val mixing get() = phase == "mix"
}

@Serializable
data class DjTarget(val uri: String, val asap: Boolean = false, val path: List<String> = emptyList())

@Serializable
data class DjPhrase(val startMs: Long = 0, val bars: Int = 8, val kind: String = "body")

@Serializable
data class DjTrack(
    val uri: String = "",
    val name: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val bpm: Float? = null,
    val key: String? = null,
    val camelot: String? = null,
    val barsMs: List<Long> = emptyList(),
    /** Per bar, 0–255. */
    val energy: List<Int> = emptyList(),
    val phrases: List<DjPhrase> = emptyList(),
)

@Serializable
data class DjTransition(
    /** crossfade | bassSwap | filterSweep | echoOut | cut */
    val style: String = "crossfade",
    val mixStartMs: Double = 0.0,
    val lengthMs: Double = 0.0,
    val beats: Int = 0,
    val entryMs: Double = 0.0,
    val speed: Double = 1.0,
    /** The current song's speed when the mix starts (it eases there over [preGlideMs]). */
    val aSpeed: Double = 1.0,
    val preGlideMs: Double = 0.0,
    val settleMs: Double = 0.0,
    val gainDb: Float = 0f,
    val beatMs: Double = 500.0,
)

/** `dj.plan`: the planned mix (null when none). */
@Serializable
data class DjPlan(
    val current: DjTrack = DjTrack(),
    val next: DjTrack = DjTrack(),
    val transition: DjTransition = DjTransition(),
    val reasons: List<String> = emptyList(),
    val score: Float = 0f,
)

@Serializable
data class DjLength(val kind: String = "club", val min: Float = 0.25f, val max: Float = 0.5f)

@Serializable
data class DjPrefs(
    val length: DjLength = DjLength(),
    val harmonic: Boolean = true,
    /** Off: pitch follows tempo like a turntable (smoother glides); on: WSOLA keeps the key. */
    val keyLock: Boolean = false,
    val styles: List<String> = DjStyle.entries.filter { it != DjStyle.CUT }.map { it.id },
    val energyArc: String = "journey",
    val sessionMinutes: Int = 60,
    val noRepeat: Int = 50,
    val maxTempoShift: Float = 0.08f,
)

@Serializable
data class DjPoolTrack(val uri: String, val name: String = "", val artist: String = "", val local: Boolean = false)

enum class DjStyle(val id: String, val label: String) {
    BASS_SWAP("bassSwap", "Bass swap"),
    CROSSFADE("crossfade", "Blend"),
    FILTER_SWEEP("filterSweep", "Filter sweep"),
    ECHO_OUT("echoOut", "Echo out"),
    CUT("cut", "Cut on the drop");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: CROSSFADE
    }
}

enum class DjSource(val id: String, val label: String) {
    CONTEXT("context", "This playlist"),
    QUEUE("queue", "Queue"),
    DOWNLOADS("downloads", "Downloads"),
    RADIO("radio", "Spotify radio"),
}

@Serializable
data class DjCacheInfo(val tracks: Int = 0, val bytes: Long = 0)
