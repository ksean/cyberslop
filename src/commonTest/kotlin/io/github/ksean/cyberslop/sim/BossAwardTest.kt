package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.loot.PowerupTier
import io.github.ksean.cyberslop.physics.InputFrame
import kotlin.test.Test
import kotlin.test.assertTrue

/** The main boss's award floors (`specs/combat.md`): weapon ≥ T3 and powerup ≥ T2, on every seed. */
class BossAwardTest {
    @Test
    fun `a main-boss award is never below the floors over many seeds`() {
        for (seed in 1uL..60uL) {
            val sim = TestLevels.simulation(seed = seed * 0x9E3779B97F4A7C15uL)
            sim.boss.fight.engage()
            sim.boss.fight.damage(sim.boss.spec.maxHealth)
            val before = sim.items.size
            sim.tick(InputFrame())

            val awards = sim.items.drop(before).filter { it.guaranteed }
            val weapon = awards.mapNotNull { it.weapon }.single()
            val powerup = awards.mapNotNull { it.powerup }.single()
            assertTrue(weapon.tier.ordinal >= Tier.Chromed.ordinal, "seed $seed: boss weapon was ${weapon.tier}")
            assertTrue(powerup.tier.ordinal >= PowerupTier.Scav.ordinal, "seed $seed: boss powerup was ${powerup.tier}")
        }
    }
}
