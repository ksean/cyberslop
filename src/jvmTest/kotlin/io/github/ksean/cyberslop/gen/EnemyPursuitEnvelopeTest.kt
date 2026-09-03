package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P-61: generation never accepts an obstacle the real enemy boxes cannot pursue across. */
class EnemyPursuitEnvelopeTest {
    @Test
    fun `a fault-injected span beyond the measured enemy leap is rejected`() {
        val level = TestLevels.flat(gapColumns = 18..60)

        val violations = EnemyPursuitEnvelope.audit(level)

        assertTrue(violations.isNotEmpty(), "the audit accepted a 43-tile void")
        assertFalse(EnemyPursuitEnvelope.accepts(level))
    }

    @Test
    fun `a generated-width gap is accepted for both real boxes in both directions`() {
        val level = TestLevels.flat(gapColumns = 18..20)

        assertTrue(EnemyPursuitEnvelope.audit(level).isEmpty())
    }

    @Test
    fun `a generated-width broken glass patch is accepted for both real boxes`() {
        val level = TestLevels.flat(glassColumns = 18..19)

        assertTrue(EnemyPursuitEnvelope.audit(level).isEmpty())
    }

    @Test
    fun `duplicate body violations do not remove the next safe hazard`() {
        val level = TestLevels.flat(spikeColumns = 18..27)
        level.tiles[40, TestLevels.FLOOR_ROW] = TileKind.Spikes

        HazardPlacer.confirmPursuit(level, emptyList())

        assertTrue((18..27).none { level.tiles[it, TestLevels.FLOOR_ROW] == TileKind.Spikes })
        assertTrue(level.tiles[40, TestLevels.FLOOR_ROW] == TileKind.Spikes)
        assertTrue(EnemyPursuitEnvelope.audit(level).isEmpty())
    }

    @Test
    fun `the generated seed cohort stays inside both real-box envelopes`() {
        for (seed in 1uL..COHORT) {
            for (map in 1..10) {
                val level = GeneratedLevels.level(seed * SPREAD, map)
                val violations = EnemyPursuitEnvelope.audit(level)
                assertTrue(violations.isEmpty(), "seed $seed map $map: $violations")
            }
        }
    }

    private companion object {
        const val COHORT = 8uL
        const val SPREAD = 0x9E37uL
    }
}
