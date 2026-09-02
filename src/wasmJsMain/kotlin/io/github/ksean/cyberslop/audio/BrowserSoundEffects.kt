package io.github.ksean.cyberslop.audio

import io.github.ksean.cyberslop.sim.AudioCue
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

/** Browser lifecycle boundary for presentation-only sound cues. */
interface SoundEffects {
    /** Called directly from the player gesture that starts or continues a game. */
    fun arm()

    /** Cues are consumed immediately and are never retained for replay. */
    fun play(cues: Iterable<AudioCue>)
}

internal interface AudioEngine {
    fun arm()
    fun play(cue: AudioCue)
}

internal class BrowserSoundEffects(
    private val engine: AudioEngine = WebAudioEngine(),
) : SoundEffects {
    private var armed = false

    override fun arm() {
        armed = true
        runCatching(engine::arm)
    }

    override fun play(cues: Iterable<AudioCue>) {
        if (!armed) return
        cues.forEach { cue -> runCatching { engine.play(cue) } }
    }
}

internal data class SoundPatch(
    val waveform: String,
    val startFrequency: Double,
    val endFrequency: Double,
    val durationMillis: Int,
    val peakGain: Double,
)

internal object SoundPatches {
    private val melee = SoundPatch("sawtooth", 260.0, 80.0, 120, 0.07)
    private val ranged = SoundPatch("square", 180.0, 55.0, 75, 0.10)
    private val pickup = SoundPatch("sine", 480.0, 820.0, 110, 0.06)

    fun of(cue: AudioCue): SoundPatch = when (cue) {
        AudioCue.MeleeSwing -> melee
        AudioCue.RangedFire -> ranged
        AudioCue.PickupPulse -> pickup
    }
}

private class WebAudioEngine : AudioEngine {
    private var context: JsAny? = null

    override fun arm() {
        val active = context ?: createAudioContext()?.also { context = it } ?: return
        resumeAudioContext(active)
    }

    override fun play(cue: AudioCue) {
        val active = context ?: return
        val patch = SoundPatches.of(cue)
        playTone(
            context = active,
            waveform = patch.waveform.toJsString(),
            startFrequency = patch.startFrequency,
            endFrequency = patch.endFrequency,
            durationMillis = patch.durationMillis,
            peakGain = patch.peakGain,
        )
    }
}

@JsFun(
    """() => {
        const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext;
        return AudioContextClass ? new AudioContextClass() : null;
    }""",
)
private external fun createAudioContext(): JsAny?

@JsFun(
    """(context) => {
        if (context.state !== "suspended") return;
        const resumed = context.resume();
        if (resumed && resumed.catch) resumed.catch(() => {});
    }""",
)
private external fun resumeAudioContext(context: JsAny)

@JsFun(
    """(context, waveform, startFrequency, endFrequency, durationMillis, peakGain) => {
        if (!context || context.state !== "running") return;
        const now = context.currentTime;
        const duration = durationMillis / 1000;
        const attack = Math.min(0.008, duration * 0.2);
        const oscillator = context.createOscillator();
        const gain = context.createGain();
        oscillator.type = waveform;
        oscillator.frequency.setValueAtTime(startFrequency, now);
        oscillator.frequency.exponentialRampToValueAtTime(Math.max(1, endFrequency), now + duration);
        gain.gain.setValueAtTime(0.0001, now);
        gain.gain.exponentialRampToValueAtTime(Math.max(0.0001, peakGain), now + attack);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + duration);
        oscillator.connect(gain);
        gain.connect(context.destination);
        oscillator.onended = () => {
            oscillator.disconnect();
            gain.disconnect();
        };
        oscillator.start(now);
        oscillator.stop(now + duration);
    }""",
)
private external fun playTone(
    context: JsAny,
    waveform: JsString,
    startFrequency: Double,
    endFrequency: Double,
    durationMillis: Int,
    peakGain: Double,
)
