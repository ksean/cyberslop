package io.github.ksean.cyberslop.audio

import io.github.ksean.cyberslop.sim.AudioCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-77 at the browser boundary, with Web Audio replaced by an observable sink. */
class BrowserSoundEffectsTest {
    @Test
    fun `arming and rearming forward only new cues once and in order`() {
        val engine = RecordingEngine()
        val sounds = BrowserSoundEffects(engine)

        sounds.play(listOf(AudioCue.MeleeSwing))
        sounds.arm()
        sounds.play(
            listOf(AudioCue.RangedFire, AudioCue.PickupPulse, AudioCue.MeleeSwing),
        )
        sounds.arm()
        sounds.play(listOf(AudioCue.PickupPulse))

        assertEquals(2, engine.armCount)
        assertEquals(
            listOf(
                AudioCue.RangedFire,
                AudioCue.PickupPulse,
                AudioCue.MeleeSwing,
                AudioCue.PickupPulse,
            ),
            engine.played,
            "a pre-arm cue was replayed or a live cue was reordered",
        )
    }

    @Test
    fun `default browser engine tolerates the current audio context state`() {
        val sounds = BrowserSoundEffects()

        sounds.arm()
        sounds.play(AudioCue.entries)
    }

    @Test
    fun `audio setup and playback failures degrade to silence`() {
        val sounds = BrowserSoundEffects(FailingEngine)

        sounds.arm()
        sounds.play(AudioCue.entries)
    }

    @Test
    fun `every synthesized patch is short quiet and distinct`() {
        val patches = AudioCue.entries.associateWith(SoundPatches::of)

        assertEquals(AudioCue.entries.size, patches.values.distinct().size)
        patches.forEach { (cue, patch) ->
            val durationLimit = when (cue) {
                AudioCue.MeleeSwing -> 140
                AudioCue.RangedFire -> 100
                AudioCue.PickupPulse -> 120
            }
            assertTrue(patch.durationMillis in 1..durationLimit, "$cue lasts ${patch.durationMillis} ms")
            assertTrue(patch.peakGain in 0.0..0.12, "$cue peaks at ${patch.peakGain}")
            assertTrue(patch.startFrequency > 0.0 && patch.endFrequency > 0.0)
        }
    }

    private class RecordingEngine : AudioEngine {
        var armCount = 0
        val played = mutableListOf<AudioCue>()

        override fun arm() {
            armCount++
        }

        override fun play(cue: AudioCue) {
            played += cue
        }
    }

    private object FailingEngine : AudioEngine {
        override fun arm(): Unit = error("browser rejected AudioContext")

        override fun play(cue: AudioCue): Unit = error("browser suspended AudioContext")
    }
}
