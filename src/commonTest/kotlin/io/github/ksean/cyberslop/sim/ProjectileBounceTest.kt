package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-45, bounce (PROD-074): with Ricochet ROM a ranged projectile that would stop against terrain
 * reflects off it instead — the entered axis reversed, 85 % of its damage kept — as many times
 * as it has bounces, and is spent on the contact after its last.
 */
class ProjectileBounceTest {
    @Test
    fun `a projectile fired into the floor reflects with vy reversed and vx kept`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, stacks = 1, level = pocketBelow())
        val before = untilAirborneShot(sim).snapshot()
        val contact = untilContact(sim).snapshot()
        assertClose(before.velocity.x, contact.velocity.x, "vx")
        assertClose(-before.velocity.y, contact.velocity.y, "vy")
        assertClose(before.damage * GameSimulation.BOUNCE_DAMAGE, contact.damage, "damage")
        assertEquals(before.pierceLeft, contact.pierceLeft)
        assertTrue(contact.secondsLeft > 0.0 && contact.secondsLeft < before.secondsLeft, "lifetime did not carry on")
        assertEquals(0, contact.bouncesLeft)
    }

    @Test
    fun `the contact after the last bounce spends the projectile and leaves an impact`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, stacks = 1, level = pocketBelow(ceiling = true))
        untilAirborneShot(sim)
        untilContact(sim)
        var ticks = 0
        while (sim.projectiles.any { it.fromPlayer } && ticks < 200) { sim.tick(InputFrame()); ticks++ }
        assertTrue(ticks < 200, "the projectile never stopped")
        assertTrue(sim.impacts.any { (it.shape as? HitShape.Impact)?.fromPlayer == true }, "no impact tracer was left")
    }

    @Test
    fun `three stacks survive three contacts`() {
        val sim = simulation(WeaponId.ScraplineZipPistol, stacks = 3, level = pocketBelow(ceiling = true))
        val shot = untilAirborneShot(sim).snapshot()
        repeat(3) { bounce ->
            val contact = untilContact(sim).snapshot()
            assertEquals(2 - bounce, contact.bouncesLeft, "bounces left after contact ${bounce + 1}")
            assertClose(shot.damage * pow(GameSimulation.BOUNCE_DAMAGE, bounce + 1), contact.damage, "damage after ${bounce + 1}")
        }
        var ticks = 0
        while (sim.projectiles.any { it.fromPlayer } && ticks < 200) { sim.tick(InputFrame()); ticks++ }
        assertTrue(ticks < 200, "the projectile survived a fourth contact")
    }

    @Test
    fun `a wall reverses vx`() {
        val level = TestLevels.flat(wallColumn = 7, committedColumns = 1..5)
        val sim = simulation(WeaponId.ScraplineZipPistol, stacks = 1, level = level)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 10)
        val before = untilAirborneShot(sim).snapshot()
        val contact = untilContact(sim).snapshot()
        assertClose(-before.velocity.x, contact.velocity.x, "vx")
        assertClose(before.velocity.y, contact.velocity.y, "vy")
    }

    @Test
    fun `a bounced projectile still hits an enemy it meets`() {
        // A stunned Flyer hangs in the air on the return path (a turret would fall to the floor).
        val sim = simulation(WeaponId.ScraplineZipPistol, stacks = 1, level = pocketBelow())
        val above = TestLevels.enemyAt(sim, EnemyArchetype.Flyer, column = 5, row = TestLevels.FLOOR_ROW - 5)
        above.stun(2.0)
        val full = above.health
        untilAirborneShot(sim)
        val live = untilContact(sim)
        val contact = live.snapshot()
        var ticks = 0
        while (above.health == full && ticks < 120 && sim.projectiles.contains(live)) { sim.tick(InputFrame()); ticks++ }
        assertTrue(above.health < full, "the bounced projectile hit nothing on its way back up")
        assertClose(contact.damage, full - above.health, "damage dealt after the bounce")
    }

    @Test
    fun `a psychic projectile passes terrain and never bounces`() {
        val sim = simulation(WeaponId.NeuralSpike, stacks = 3, level = pocketBelow())
        val pocketed = sim.enemies.single()
        val full = pocketed.health
        val shot = untilAirborneShot(sim)
        var ticks = 0
        while (pocketed.health == full && ticks < 120) { sim.tick(InputFrame()); ticks++ }
        assertTrue(pocketed.health < full, "the orb never reached the turret under the floor")
        assertEquals(0, shot.bouncesLeft)
        assertTrue(shot.velocity.y > 0.0, "the orb turned round")
    }

    @Test
    fun `an enemy shot never bounces`() {
        val sim = simulation(WeaponId.BrokenBottle, stacks = 3, level = TestLevels.flat(committedColumns = 1..5))
        // A turret on a ledge above shoots down into the floor beside the player.
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 12, row = TestLevels.FLOOR_ROW - 6)
        var enemyShot: LiveProjectile? = null
        var ticks = 0
        while (enemyShot == null && ticks < 600) { sim.tick(InputFrame()); enemyShot = sim.projectiles.firstOrNull { !it.fromPlayer }; ticks++ }
        assertTrue(enemyShot != null, "fixture: the turret never fired")
        assertEquals(0, enemyShot.bouncesLeft)
    }

    /** A projectile's rule-bearing values as they were at one tick; the live object keeps changing. */
    private data class Snapshot(val velocity: Vec2, val damage: Double, val pierceLeft: Int, val secondsLeft: Double, val bouncesLeft: Int)

    private fun LiveProjectile.snapshot() = Snapshot(velocity, damage, pierceLeft, secondsLeft, bouncesLeft)

    /** Round-1 finding: a Railgun covers 23 px a tick against 16 px tiles, so the endpoint alone can miss a wall. */
    @Test
    fun `a fast projectile cannot cross a one-tile wall between two ticks`() {
        val wall = 7
        listOf(0, 1).forEach { bounces ->
            val sim = simulation(WeaponId.BrokenBottle, stacks = 0, level = TestLevels.flat(wallColumn = wall, committedColumns = 1..5))
            val wallLeft = io.github.ksean.cyberslop.world.TileMap.toWorld(wall)
            val y = io.github.ksean.cyberslop.world.TileMap.toWorld(TestLevels.FLOOR_ROW) + 8.0
            // 24 px a tick from 4 px short of the wall: the endpoint lies 4 px beyond its far face.
            val shot = LiveProjectile(Vec2(wallLeft - 4.0, y), Vec2(24.0 * 60.0, 0.0), 10.0, 0, 1.0, passesTerrain = false, fromPlayer = true, bouncesLeft = bounces)
            sim.projectiles.add(shot)
            sim.tick(InputFrame())
            if (bounces == 0) {
                assertTrue(shot !in sim.projectiles, "the shot tunnelled through the wall")
            } else {
                assertTrue(shot in sim.projectiles, "the bouncing shot was spent")
                assertTrue(shot.velocity.x < 0.0, "the shot did not reflect off the wall")
                assertTrue(shot.position.x < wallLeft, "the shot ended inside or beyond the wall at ${shot.position.x}")
            }
        }
    }

    private fun pow(base: Double, times: Int): Double = (1..times).fold(1.0) { acc, _ -> acc * base }

    /** A turret sealed in a pocket under the floor, so the shot at it enters the floor from above. */
    private fun pocketBelow(ceiling: Boolean = false): Level {
        val level = TestLevels.flat(committedColumns = 1..5)
        level.tiles[4, TestLevels.FLOOR_ROW + 3] = TileKind.Empty
        if (ceiling) for (x in 0 until TestLevels.WIDTH) level.tiles[x, TestLevels.FLOOR_ROW - 6] = TileKind.Solid
        return level
    }

    private fun simulation(weapon: WeaponId, stacks: Int, level: Level): GameSimulation {
        var slots = PowerupSlots.empty()
        repeat(stacks) { slots = slots.collect(PowerupId.RicochetRom).first }
        val run = RunState.begin(TestLevels.SEED).copy(loadout = Loadout(Weapons.of(weapon), slots))
        val sim = GameSimulation(level, run, TestLevels.SEED)
        if (level.tiles[4, TestLevels.FLOOR_ROW + 3] == TileKind.Empty) {
            TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 4, row = TestLevels.FLOOR_ROW + 3)
        }
        return sim
    }

    /** Ticks until the player's first projectile exists, returning it as it was that tick. */
    private fun untilAirborneShot(sim: GameSimulation): LiveProjectile {
        repeat(120) {
            sim.tick(InputFrame())
            sim.projectiles.firstOrNull { it.fromPlayer }?.let { return it }
        }
        error("fixture: the player never fired")
    }

    /** Ticks until the live player projectile's bounce count falls, returning it. */
    private fun untilContact(sim: GameSimulation): LiveProjectile {
        val shot = sim.projectiles.first { it.fromPlayer }
        val bounces = shot.bouncesLeft
        repeat(200) {
            sim.tick(InputFrame())
            if (shot.bouncesLeft < bounces) return shot
            assertTrue(sim.projectiles.contains(shot), "the projectile was spent without bouncing")
        }
        error("fixture: no terrain contact in 200 ticks")
    }

    private fun assertClose(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected, was $actual")
}
