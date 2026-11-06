package com.taehagen.spotifygood.playback

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusStateTest {
    private class Fakes : AudioFocusController.Callbacks, AudioFocusState.Platform {
        var speech = false
        var playing = true
        var result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var time = 1_000L
        val events = mutableListOf<String>()
        var requests = 0
        val duck = mutableListOf<Boolean>()

        override fun pauseForFocus() { events += "pause" }
        override fun resumeAfterFocus() { events += "resume" }
        override fun setDuck(ducked: Boolean) { duck += ducked }
        override fun isSpeech() = speech
        override fun isPlayingLocally() = playing
        override fun requestFocus(): Int { requests++; return result }
        override fun abandonFocus() { events += "abandon" }
        override fun now() = time
    }

    private val f = Fakes()
    private val state = AudioFocusState(f, f)

    @Test
    fun duckDoesNotLoseFocusSoASinkRestartDoesNotStealItBack() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(listOf(true), f.duck)
        assertTrue(state.hasFocus)
        // Skip / pause+resume / watchdog restart during the navigation prompt.
        assertTrue(state.request())
        assertEquals("no second requestAudioFocus while ducked", 1, f.requests)
        // The prompt ends: the ducking app abandons and we get GAIN.
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(true, false), f.duck)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun escalationToTransientLossClearsDuckAndKeepsPendingFocus() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(state.hasFocus)
        assertFalse(state.isDucked)
        assertFalse("a sink restart must await focus gain", state.request())
        assertEquals("no second request until GAIN", 1, f.requests)
        assertEquals(listOf(true, false), f.duck)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, f.events.count { it == "resume" })
    }

    @Test
    fun speechPausesInsteadOfDucking() {
        f.speech = true
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(f.duck.isEmpty())
        assertEquals(listOf("pause"), f.events)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), f.events)
    }

    @Test
    fun transientLossResumesOnlyWithinTheWindowAndOnlyWhatWasPlaying() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.time += AudioFocusState.RESUME_WINDOW_MS + 1
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause"), f.events)

        f.events.clear()
        f.playing = false
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("a paused player is not resumed", f.events.isEmpty())
    }

    @Test
    fun lossPausesAndAbandonsAndClearsTheDuck() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(listOf("pause", "abandon"), f.events)
        assertEquals(listOf(true, false), f.duck)
        assertFalse(state.isRequested)
    }

    @Test
    fun failedAndDelayedRequestsPause() {
        f.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertFalse(state.request())
        assertEquals(listOf("pause"), f.events)
        f.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertFalse(state.request())
        assertTrue(state.isRequested)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "pause", "resume"), f.events)
    }

    @Test
    fun aNoisyEventDuringAFocusPauseCancelsTheResume() {
        // A call (or a spoken navigation prompt over a podcast) pauses with a resume pending...
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(state.isWaitingForGain)
        // ...the earbuds go into their case (or the car is turned off): becoming noisy.
        state.cancelPendingResume()
        assertFalse(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("no resume on the speaker", listOf("pause"), f.events)
    }

    @Test
    fun aNoisyEventWhileFocusIsDelayedCancelsTheResumeToo() {
        f.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertFalse(state.request())
        assertTrue(state.isWaitingForGain)
        state.cancelPendingResume()
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause"), f.events)
    }

    @Test
    fun aUserPauseDuringAFocusPauseIsNotUndoneByTheGain() {
        // "Hey Google, pause": the Assistant takes transient focus (the podcast pauses, a resume
        // is pending), then sends pause, which cancels the resume.
        f.speech = true
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(state.isWaitingForGain)
        state.cancelPendingResume()
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause"), f.events)
    }

    @Test
    fun phoneCallResumesEvenIfSinkStoppedJustBeforeFocusLossCallback() {
        assertTrue(state.request())
        // AudioTrack may be stopped by Telecom before AudioManager delivers LOSS_TRANSIENT.
        f.playing = false
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf("pause"), f.events)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), f.events)
    }

    @Test
    fun sinkRestartDuringTransientLossCannotErasePendingResume() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        f.playing = true // stale AudioTrack restart before the queued native pause is processed
        assertFalse(state.request())
        assertEquals("no focus stealing while awaiting GAIN", 1, f.requests)
        assertTrue(state.isWaitingForGain)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, f.events.count { it == "resume" })
    }

    @Test
    fun escalationFromDuckToPauseRestoresGainWithoutStealingFocus() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(state.isDucked)
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(state.isDucked)
        assertEquals(listOf(true, false), f.duck)
        f.playing = false
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse("duck while paused must not stick", state.isDucked)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, f.events.count { it == "resume" })
    }

    @Test
    fun stuckDuckOnlyRecoversAfterPlatformGrantsFocus() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        f.result = AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        assertEquals("delayed", state.recheckStaleDuck())
        assertFalse(state.isDucked)
        assertFalse(state.hasFocus)
        assertTrue(state.isWaitingForGain)
        assertEquals(listOf("pause"), f.events)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), f.events)
    }

    @Test
    fun successfulFocusRecheckUnducksWithoutResumingOrPausing() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals("granted", state.recheckStaleDuck())
        assertFalse(state.isDucked)
        assertEquals(listOf(true, false), f.duck)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun focusRecheckFailurePausesInsteadOfPlayingWithoutFocus() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        f.result = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        assertEquals("failed", state.recheckStaleDuck())
        assertFalse(state.isDucked)
        assertFalse(state.hasFocus)
        assertFalse(state.isRequested)
        assertEquals(listOf("pause"), f.events)
    }

    @Test
    fun deliberatePauseWhileSinkIsStoppedPreventsCallResume() {
        assertTrue(state.request())
        f.playing = false
        state.cancelPendingResume() // User tapped Pause while the call was active.
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun withoutAUserPauseTheGainStillResumes() {
        assertTrue(state.request())
        state.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        state.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf("pause", "resume"), f.events)
    }
}
