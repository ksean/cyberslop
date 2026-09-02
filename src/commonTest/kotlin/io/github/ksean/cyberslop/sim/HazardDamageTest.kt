package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Damaging hazards drain at their rate, never kill in one tick and never displace (P-36). */
class HazardDamageTest {
    @Test
    fun `standing in broken glass drains health at half the contact rate`() {
        val sim = TestLevels.simulation(
            TestLevels.flat(glassColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN + 1),
        )

        repeat(60) { sim.tick(InputFrame()) }

        val expected = Hazards.GLASS_RATE * Balance.contactDamage(1) * 60 * TICK_SECONDS
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-6, "glass did not drain at its rate")
    }

    @Test
    fun `one maximal glass patch counts once and distinct patches stack`() {
        val level = TestLevels.flat().also {
            it.tiles[3, TestLevels.FLOOR_ROW] = TileKind.BrokenGlass
            it.tiles[4, TestLevels.FLOOR_ROW] = TileKind.BrokenGlass
            it.tiles[6, TestLevels.FLOOR_ROW] = TileKind.BrokenGlass
        }
        val samePatch = Hazards.glassRatePerSecond(
            level, x = 3 * 16.0, y = TestLevels.FLOOR_ROW * 16.0, width = 32.0, height = 16.0,
        )
        val distinctPatches = Hazards.glassRatePerSecond(
            level, x = 3 * 16.0, y = TestLevels.FLOOR_ROW * 16.0, width = 64.0, height = 16.0,
        )

        assertEquals(Hazards.GLASS_RATE, samePatch)
        assertEquals(Hazards.GLASS_RATE * 2.0, distinctPatches)
    }

    @Test
    fun `standing in a spike strip drains health at the spike rate`() {
        val sim = TestLevels.simulation(TestLevels.flat(spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN + 1))

        repeat(60) { sim.tick(InputFrame()) }

        val expected = Hazards.SPIKE_RATE * Balance.contactDamage(1) * 60 * TICK_SECONDS
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-6, "spikes did not drain at their rate")
    }

    @Test
    fun `overlapping a barrel's body drains at the barrel rate`() {
        val sim = TestLevels.simulation(TestLevels.flat(barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW))))

        repeat(60) { sim.tick(InputFrame()) }

        val expected = Hazards.BARREL_RATE * Balance.contactDamage(1) * 60 * TICK_SECONDS
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-6, "a barrel's body did not drain at its rate")
    }

    @Test
    fun `overlapping only a barrel's flame drains at the barrel rate`() {
        // The body sits in the floor tile the player stands on, so only the flame above it is overlapped.
        val sim = TestLevels.simulation(TestLevels.flat(barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW + 1))))

        repeat(60) { sim.tick(InputFrame()) }

        val expected = Hazards.BARREL_RATE * Balance.contactDamage(1) * 60 * TICK_SECONDS
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-6, "a barrel's flame did not drain at its rate")
    }

    @Test
    fun `one tick of contact does not kill`() {
        val sim = TestLevels.simulation(TestLevels.flat(spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN))

        sim.tick(InputFrame())

        assertTrue(sim.run.health > sim.run.maxHealth * 0.9, "one tick of spikes took ${sim.run.maxHealth - sim.run.health}")
    }

    @Test
    fun `two overlapping hazards drain both`() {
        val level = TestLevels.flat(
            spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN,
            barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)),
        )
        val sim = TestLevels.simulation(level)

        repeat(60) { sim.tick(InputFrame()) }

        val expected = (Hazards.SPIKE_RATE + Hazards.BARREL_RATE) * Balance.contactDamage(1) * 60 * TICK_SECONDS
        assertEquals(sim.run.maxHealth - expected, sim.run.health, 1e-6, "two hazards did not both drain")
    }

    @Test
    fun `a hazard never displaces the player`() {
        val level = TestLevels.flat(
            spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN,
            barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)),
        )
        val sim = TestLevels.simulation(level)
        val start = sim.player

        repeat(30) { sim.tick(InputFrame()) }

        assertEquals(start.x, sim.player.x, "a hazard moved the player horizontally")
        assertEquals(start.y, sim.player.y, "a hazard moved the player vertically")
    }
}
