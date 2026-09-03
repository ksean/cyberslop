package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PROD-110 / P-87: independent grounded ramen drops and capped healing. */
class RamenDropTest {
    @Test
    fun `every rank-and-file death consumes one independent one-in-eight ramen draw`() {
        for (mapIndex in 1..10) {
            val level = TestLevels.flat(mapIndex = mapIndex)
            val run = RunState.begin(SEED).copy(mapIndex = mapIndex)
            val sim = GameSimulation(level, run, SEED)
            val expected = Rng.derive(SEED, mapIndex, "ramen")
            var successes = 0

            repeat(KILLS) {
                if (expected.nextInt(8) == 0) successes++
                killOne(sim)
            }

            assertEquals(expected.state, sim.ramenRng.state, "map $mapIndex consumed the wrong draws")
            assertEquals(successes, sim.items.count { it.payload is GroundItem.Ramen }, "map $mapIndex did not keep each success")
        }
    }

    @Test
    fun `changing ramen outcomes does not change ordinary loot and both drops can coexist`() {
        val ordinary = simulation()
        val shifted = simulation()
        repeat(17) { shifted.ramenRng.nextInt(8) }

        repeat(KILLS) {
            killOne(ordinary)
            killOne(shifted)
        }

        fun ordinaryLoot(sim: GameSimulation) = sim.items
            .mapNotNull { it.payload as? GroundItem.Equipment }
            .map { it.weapon?.id to it.powerup?.id }
        assertEquals(ordinary.lootRng.state, shifted.lootRng.state)
        assertEquals(ordinaryLoot(ordinary), ordinaryLoot(shifted))
        assertTrue(
            sequenceOf(ordinary, shifted).any { sim ->
                sim.items.filter { it.payload is GroundItem.Ramen }.any { ramen ->
                    sim.items.filter { it.payload is GroundItem.Equipment }.any { it.position.x == ramen.position.x }
                }
            },
            "the fixture produced no death with both independent drops",
        )
    }

    @Test
    fun `boss deaths consume no ramen draw`() {
        val sim = simulation()
        val before = sim.ramenRng.state

        sim.miniboss.fight.engage()
        sim.miniboss.fight.damage(sim.miniboss.spec.maxHealth)
        sim.tick(InputFrame())
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())

        assertEquals(before, sim.ramenRng.state)
    }

    @Test
    fun `ramen rests on safe ground and rejects a hazardous death projection`() {
        val flat = TestLevels.flat(mapIndex = MAP_INDEX)
        val death = Vec2(TileMap.toWorld(DROP_COLUMN) + TILE_SIZE / 2.0, TileMap.toWorld(8))

        val grounded = DeathDropPlacement(flat).placeGrounded(death)

        assertEquals(death.x, grounded.x)
        assertEquals(TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - TILE_SIZE / 2.0, grounded.y)

        val glass = TestLevels.flat(
            mapIndex = MAP_INDEX,
            glassColumns = DROP_COLUMN..DROP_COLUMN,
        )
        val safe = DeathDropPlacement(glass).placeGrounded(death)
        assertEquals(TileMap.toWorld(DROP_COLUMN - 1) + TILE_SIZE / 2.0, safe.x)
        assertEquals(grounded.y, safe.y)
    }

    @Test
    fun `grounded contact heals five percent of maximum caps and still consumes at full health`() {
        listOf(0.40 to 0.45, 0.98 to 1.0, 1.0 to 1.0).forEach { (beforeFraction, afterFraction) ->
            val base = RunState.begin(SEED).copy(mapIndex = MAP_INDEX)
            val sim = GameSimulation(
                TestLevels.flat(mapIndex = MAP_INDEX),
                base.copy(health = base.maxHealth * beforeFraction),
                SEED,
                optionalLoot = false,
            )
            sim.autoFire.remaining = 100.0
            val loadout = sim.run.loadout
            val centre = sim.player.centre(Physics.Default)
            val grounded = Vec2(
                centre.x,
                TileMap.toWorld(TestLevels.FLOOR_ROW + 1) - TILE_SIZE / 2.0,
            )
            sim.items += GroundItem.ramen(grounded)

            val report = sim.tick(InputFrame())

            assertEquals(base.maxHealth * afterFraction, sim.run.health, absoluteTolerance = 1e-9)
            assertTrue(sim.items.none { it.payload is GroundItem.Ramen })
            assertEquals(loadout, sim.run.loadout)
            assertEquals(0, sim.run.scrap)
            assertEquals(emptyList(), report.collectedDiscoveries)
            assertEquals(listOf(AudioCue.PickupPulse), report.audioCues)
            assertEquals(GameSimulation.HEAL_FLASH_SECONDS, sim.playerHealSecondsLeft)
        }
    }

    @Test
    fun `ramen payload position and stream are digested but heal feedback is not`() {
        val baseline = simulation()
        val digest = baseline.digest()

        baseline.playerHealSecondsLeft = 0.1
        assertEquals(digest, baseline.digest())

        val item = simulation().also {
            it.items += GroundItem.ramen(Vec2.Zero)
        }
        assertNotEquals(digest, item.digest())

        val moved = simulation().also {
            it.items += GroundItem.ramen(Vec2.Right)
        }
        assertNotEquals(item.digest(), moved.digest())

        val advanced = simulation()
        advanced.ramenRng.nextInt(8)
        assertNotEquals(digest, advanced.digest())
    }

    private fun simulation(): GameSimulation {
        val run = RunState.begin(SEED).copy(mapIndex = MAP_INDEX)
        return GameSimulation(TestLevels.flat(mapIndex = MAP_INDEX), run, SEED)
    }

    private fun killOne(sim: GameSimulation) {
        val enemy = TestLevels.enemyAt(
            sim,
            EnemyArchetype.Swarm,
            column = DROP_COLUMN,
            health = 0.01,
        )
        enemy.burn.apply(seconds = 1.0, rate = 1.0)
        sim.tick(InputFrame())
        assertFalse(enemy.alive, "fixture enemy survived")
    }

    private companion object {
        val SEED = 0xA11CEuL
        const val MAP_INDEX = 2
        const val DROP_COLUMN = 20
        const val KILLS = 96
    }
}
