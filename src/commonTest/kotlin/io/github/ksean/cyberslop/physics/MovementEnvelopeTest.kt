package io.github.ksean.cyberslop.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MovementEnvelopeTest {
    @Test
    fun `the envelope is measured from the integrator, not from the closed forms`() {
        val weaker = Physics.Default.copy(gravity = Physics.Default.gravity * 2.0)

        val standard = measureEnvelope(Physics.Default)
        val heavy = measureEnvelope(weaker)

        assertTrue(
            heavy.maxGapTiles(0) < standard.maxGapTiles(0),
            "doubling gravity did not shorten the measured gap budget",
        )
    }

    @Test
    fun `the measured maxima and the budgets derived from them are the published values`() {
        val envelope = measureEnvelope(Physics.Default)

        // What the player can actually do.
        assertEquals(8, envelope.maxGapTiles(0), "widest crossable level gap")
        assertEquals(4, envelope.maxStepUpTiles, "tallest climbable step")

        // What generation is allowed to use, after the safety scales.
        assertEquals(5, envelope.gapMaxTiles(0), "gap budget")
        assertEquals(3, envelope.stepUpMaxTiles, "step-up budget")
    }

    @Test
    fun `every scaled bound sits at least five percent clear of its floor boundary`() {
        val envelope = measureEnvelope(Physics.Default)

        assertClearOfFloor(envelope.scaledGapTiles(0), "gap")
        assertClearOfFloor(envelope.scaledStepUpTiles, "step up")
    }

    @Test
    fun `a run-up clears a wider gap than a standing jump`() {
        val envelope = measureEnvelope(Physics.Default)

        assertTrue(envelope.runwayTiles >= 1, "runway must be at least one tile")
        assertTrue(
            envelope.maxGapTiles(0) > envelope.maxGapTilesFromStandstill(0),
            "a run-up should cross more than a standing jump",
        )
    }

    @Test
    fun `a longer drop lets the player cross a wider gap`() {
        val envelope = measureEnvelope(Physics.Default)

        assertTrue(
            envelope.maxGapTiles(4) > envelope.maxGapTiles(0),
            "extra airtime from a drop did not widen the crossable gap",
        )
    }

    @Test
    fun `an unmeasured drop is refused rather than silently answered with flat ground`() {
        val envelope = measureEnvelope(Physics.Default)

        assertFailsWith<IllegalStateException> { envelope.maxGapTiles(99) }
    }

    @Test
    fun `measurement terminates and narrows when the player cannot build speed on the ground`() {
        // Every loop in the measurement is bounded, so physics a caller experiments with produces a
        // small envelope rather than a hang. Air acceleration still applies, so this player is not
        // immobile — just unable to take a run-up.
        val noRunUp = Physics.Default.copy(groundAccel = 0.0)

        val envelope = measureEnvelope(noRunUp)

        assertEquals(0.0, envelope.runwayPx, "a player who cannot accelerate has no runway")
        assertTrue(
            envelope.maxGapTiles(0) < measureEnvelope(Physics.Default).maxGapTiles(0),
            "losing the run-up should narrow the crossable gap",
        )
    }

    @Test
    fun `measurement terminates when the player cannot move at all`() {
        val frozen = Physics.Default.copy(groundAccel = 0.0, airAccel = 0.0)

        val envelope = measureEnvelope(frozen)

        assertTrue(envelope.maxGapTiles(0) <= 0, "an immobile player crossed a gap")
    }

    private fun assertClearOfFloor(value: Double, what: String) {
        val floor = value.toInt().toDouble()
        val margin = value - floor
        assertTrue(
            margin >= 0.05,
            "$what bound $value is only ${margin * 100}% above its floor $floor; " +
                "a small physics change would silently drop a whole move kind",
        )
    }
}
