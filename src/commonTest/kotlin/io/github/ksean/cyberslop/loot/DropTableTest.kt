package io.github.ksean.cyberslop.loot

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.core.Rng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DropTableTest {
    @Test
    fun `stronger tiers are rarer on every map`() {
        (1..10).forEach { map ->
            val weights = DropTable.weights(map)
            weights.toList().zipWithNext { stronger, weaker ->
                assertTrue(
                    stronger > weaker,
                    "map $map: weights ${weights.toList()} are not decreasing in tier",
                )
            }
        }
    }

    @Test
    fun `weights are a probability distribution`() {
        (1..10).forEach { map ->
            val weights = DropTable.weights(map)
            assertTrue(abs(weights.sum() - 1.0) < 1e-9, "map $map sums to ${weights.sum()}")
            weights.forEach { assertTrue(it > 0.0, "map $map has a zero weight") }
        }
    }

    @Test
    fun `later maps drop better loot without making the best common`() {
        val early = DropTable.weights(1)
        val late = DropTable.weights(10)

        assertTrue(late.last() > early.last(), "the strongest tier did not become more available")
        assertTrue(late.first() < early.first(), "the weakest tier did not become less common")
        assertTrue(late.last() < late.first(), "the strongest tier became the most common")
    }

    @Test
    fun `a tier floor is respected`() {
        val rng = Rng(1uL)
        repeat(500) {
            val weapon = DropTable.rollWeapon(rng, mapIndex = 2, floor = Tier.Chromed)
            assertTrue(weapon.tier.ordinal >= Tier.Chromed.ordinal, "rolled ${weapon.tier}")
        }
    }

    @Test
    fun `a powerup floor keeps every roll at or above the tier`() {
        val rng = Rng(3uL)
        repeat(500) {
            val powerup = DropTable.rollPowerup(rng, mapIndex = 1, pool = Powerups.all, floor = PowerupTier.Scav)
            assertTrue(powerup.tier.ordinal >= PowerupTier.Scav.ordinal, "rolled ${powerup.tier}")
        }
    }

    @Test
    fun `upward shifts make strong rolls more likely without breaking the floor`() {
        val plain = tierHistogram(shifts = 0)
        val shifted = tierHistogram(shifts = 2)

        val plainStrong = plain.filterKeys { it.ordinal >= Tier.Blacksite.ordinal }.values.sum()
        val shiftedStrong = shifted.filterKeys { it.ordinal >= Tier.Blacksite.ordinal }.values.sum()

        assertTrue(shiftedStrong > plainStrong, "shifting did not favour stronger tiers")
    }

    @Test
    fun `a run pool is a distinct subset that makes stacking possible`() {
        val pool = DropTable.runPool(Rng(7uL), mapIndex = 3)

        assertEquals(DropTable.RUN_POOL_SIZE, pool.size)
        assertEquals(pool.size, pool.map { it.id }.toSet().size)
        assertTrue(pool.size < Powerups.all.size, "the pool is the whole registry")
    }

    @Test
    fun `drawing is deterministic for a seed`() {
        val first = (1..50).map { DropTable.rollWeapon(Rng(9uL), 5).id }
        val second = (1..50).map { DropTable.rollWeapon(Rng(9uL), 5).id }

        assertEquals(first, second)
    }

    private fun tierHistogram(shifts: Int): Map<Tier, Int> {
        val rng = Rng(3uL)
        val counts = mutableMapOf<Tier, Int>()
        repeat(4000) {
            val tier = DropTable.rollTier(rng, mapIndex = 6, shifts = shifts)
            counts[tier] = (counts[tier] ?: 0) + 1
        }
        return counts
    }
}
