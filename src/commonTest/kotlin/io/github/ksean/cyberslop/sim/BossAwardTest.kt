package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.Tier
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupTier
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            // One item carries both (PROD-070): the weapon resolves first whichever side the
            // player walks in from, so the award cannot wipe its own powerup.
            val award = sim.items.drop(before).filter { it.isGuaranteedEquipment }.single().requireEquipment()
            val weapon = award.weapon ?: error("seed $seed: the award holds no weapon")
            val powerup = award.powerup ?: error("seed $seed: the award holds no powerup")
            assertTrue(weapon.tier.ordinal >= Tier.Chromed.ordinal, "seed $seed: boss weapon was ${weapon.tier}")
            assertTrue(powerup.tier.ordinal >= PowerupTier.Scav.ordinal, "seed $seed: boss powerup was ${powerup.tier}")
        }
    }

    @Test
    fun `mini and main paired awards stay overhead until a real jump collects them weapon-first`() {
        listOf(false, true).forEach { main ->
            val sim = simulation()
            val live = if (main) sim.boss else sim.miniboss
            sim.awardOverride = { _, _ ->
                Weapons.of(WeaponId.VultureRailCarbine) to Powerups.of(PowerupId.SpikeDriver)
            }
            live.placeAt(
                Vec2(
                    TileMap.toWorld(DROP_COLUMN) + TILE_SIZE / 2.0,
                    TileMap.toWorld(TestLevels.FLOOR_ROW + 1),
                ),
            )
            live.fight.engage()
            live.fight.damage(live.spec.maxHealth)

            sim.tick(InputFrame())

            val award = sim.items.filter { it.isGuaranteedEquipment }.single()
            assertEquals(
                TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - DeathDropPlacement.DEATH_DROP_RISE,
                award.position.y,
                "${if (main) "main" else "mini"} award was not raised",
            )
            assertEquals(award.position.y, award.powerupPosition.y)
            approachOnGroundThenJump(sim, award)
            assertEquals(WeaponId.VultureRailCarbine, sim.run.loadout.weapon.id)
            assertEquals(mapOf(PowerupId.SpikeDriver to 1), sim.run.loadout.slots.held)
        }
    }

    @Test
    fun `a main boss killed while airborne over a pit falls back to safe raised ground`() {
        val gap = 15..17
        val sim = simulation(TestLevels.flat(gapColumns = gap, mapIndex = MAP_INDEX))
        val live = sim.boss
        live.placeAt(Vec2(TileMap.toWorld(24), TileMap.toWorld(TestLevels.FLOOR_ROW + 1)))
        live.fight.engage()

        var ticks = 0
        while (
            ticks < MAX_LEAP_TICKS &&
            !(live.leap != null && TileMap.toTile(live.position.x) in gap)
        ) {
            sim.tick(InputFrame())
            ticks++
        }
        assertTrue(live.leap != null, "fixture: the boss never became airborne")
        assertTrue(TileMap.toTile(live.position.x) in gap, "fixture: the boss was not over the pit")
        live.fight.damage(live.spec.maxHealth)

        sim.tick(InputFrame())

        val award = sim.items.filter { it.isGuaranteedEquipment }.single()
        assertFalse(TileMap.toTile(award.position.x) in gap, "the award remained over the pit")
        assertEquals(
            TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - DeathDropPlacement.DEATH_DROP_RISE,
            award.position.y,
        )
    }

    private fun approachOnGroundThenJump(sim: GameSimulation, item: GroundItem) {
        repeat(MAX_ALIGN_TICKS) {
            val centreX = sim.player.centre(Physics.Default).x
            val delta = item.position.x - centreX
            val braking = sim.player.vx * sim.player.vx / (2.0 * Physics.Default.groundFriction)
            val press = abs(delta) > braking + ALIGN_TOLERANCE
            sim.tick(
                InputFrame(
                    left = press && delta < 0.0,
                    right = press && delta > 0.0,
                ),
            )
        }
        assertTrue(item in sim.items, "a grounded approach collected the award")

        repeat(MAX_JUMP_TICKS) { tick ->
            sim.tick(InputFrame(jump = true, jumpStart = tick == 0))
        }
        assertFalse(item in sim.items, "a normal held jump did not collect the award")
    }

    private fun simulation(
        level: io.github.ksean.cyberslop.world.Level = TestLevels.flat(mapIndex = MAP_INDEX),
    ): GameSimulation {
        val run = RunState.begin(SEED).copy(mapIndex = MAP_INDEX)
        return GameSimulation(level, run, SEED)
    }

    private companion object {
        const val MAP_INDEX = 4
        const val DROP_COLUMN = 20
        const val MAX_ALIGN_TICKS = 240
        const val MAX_JUMP_TICKS = 240
        const val MAX_LEAP_TICKS = 1_800
        const val ALIGN_TOLERANCE = 1.0
        const val SEED = 0xB055uL
    }
}
