package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P-46 (PROD-075): a machine gun fires its rounds one after another along the aim of the trigger
 * tick, each from the muzzle of its own tick; a spread weapon still fans its projectiles at once.
 */
class BurstFireTest {
    @Test
    fun `an SMG trigger fires one round and the rest follow at the burst interval`() {
        val sim = simulation(WeaponId.GanglordSmg)
        // Tick 1 triggers; 0.05 s is three ticks, so the rounds leave on ticks 4 and 7 exactly.
        val counts = (1..8).map { sim.tick(InputFrame()); shots(sim).size }
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3, 3), counts)
    }

    @Test
    fun `later rounds keep the trigger aim and leave the muzzle of their own tick`() {
        val sim = simulation(WeaponId.GanglordSmg)
        sim.tick(InputFrame())
        val first = shots(sim).single()
        val triggerMuzzle = origin(first)
        var seen = 1
        var secondOrigin: Vec2? = null
        var secondMuzzle: Vec2? = null
        repeat(8) {
            sim.tick(InputFrame(right = true))
            val now = shots(sim)
            if (now.size > seen && secondOrigin == null) {
                secondOrigin = origin(now.last())
                secondMuzzle = sim.player.centre(Physics.Default)
            }
            seen = now.size
        }
        val rounds = shots(sim)
        assertEquals(3, rounds.size)
        rounds.forEach { assertEquals(first.velocity, it.velocity, "a later round bent off the trigger aim") }
        val originAtSecondRound = assertNotNull(secondOrigin, "the second round never fired")
        val muzzleAtSecondRound = assertNotNull(secondMuzzle, "the second round had no muzzle")
        assertTrue(
            abs(originAtSecondRound.x - muzzleAtSecondRound.x) < 1e-6,
            "the second round left ${originAtSecondRound.x}, not the muzzle at ${muzzleAtSecondRound.x}",
        )
        assertTrue(originAtSecondRound.x > triggerMuzzle.x + 1.0, "the player moved but the second round left the trigger muzzle")
    }

    @Test
    fun `every round draws its own muzzle flash`() {
        val sim = simulation(WeaponId.GanglordSmg)
        sim.tick(InputFrame())
        var seen = 1
        repeat(8) {
            sim.tick(InputFrame())
            val now = shots(sim).size
            if (now > seen) assertEquals(GameSimulation.FLASH_VISIBLE_SECONDS, sim.lastShot?.secondsLeft, "round $now had no fresh flash")
            seen = now
        }
        assertEquals(3, seen)
    }

    @Test
    fun `a shotgun still fans all its projectiles at once`() {
        val sim = simulation(WeaponId.RiotbreakerShotgun)
        sim.tick(InputFrame())
        val pellets = shots(sim)
        assertEquals(5, pellets.size)
        assertTrue(pellets.map { it.velocity }.distinct().size == 5, "the pellets did not spread")
    }

    @Test
    fun `fork bomb extends the burst`() {
        val sim = simulation(WeaponId.GanglordSmg, PowerupId.ForkBomb)
        repeat(12) { sim.tick(InputFrame()) }
        assertEquals(4, shots(sim).size)
    }

    /** Round-2 finding: a trigger on the very tick a pending round is due still discards it. */
    @Test
    fun `a trigger on the tick a round is due discards that round too`() {
        val sim = simulation(WeaponId.GanglordSmg)
        repeat(3) { sim.tick(InputFrame()) }
        sim.autoFire.clearCooldown() // the next tick both triggers and has the second round due
        sim.tick(InputFrame())
        assertEquals(2, shots(sim).size, "the due round left beside the new trigger")
        repeat(8) { sim.tick(InputFrame()) }
        assertEquals(4, shots(sim).size)
    }

    @Test
    fun `a shotgun's outermost pellets sit on the edges of its spread`() {
        val sim = simulation(WeaponId.RiotbreakerShotgun)
        sim.tick(InputFrame())
        val pellets = shots(sim).map { it.velocity }
        val first = pellets.first()
        val last = pellets.last()
        val cos = (first.x * last.x + first.y * last.y) / (first.length * last.length)
        val degrees = kotlin.math.acos(cos.coerceIn(-1.0, 1.0)) * 180 / kotlin.math.PI
        assertTrue(abs(degrees - 30.0) < 0.5, "the fan spans $degrees°, not 30°")
    }

    @Test
    fun `a trigger while rounds are pending discards them`() {
        val sim = simulation(WeaponId.GanglordSmg)
        sim.tick(InputFrame())
        sim.autoFire.clearCooldown()
        sim.tick(InputFrame())
        assertEquals(2, shots(sim).size, "the second trigger did not fire at once")
        repeat(10) { sim.tick(InputFrame()) }
        assertEquals(4, shots(sim).size, "the first burst's pending rounds were not discarded")
    }

    private fun shots(sim: GameSimulation) = sim.projectiles.filter { it.fromPlayer }

    /** Where a projectile left this tick: it has already flown one tick from its origin. */
    private fun origin(p: LiveProjectile) = p.position - p.velocity * TICK_SECONDS

    /** The player over committed columns, a turret far enough that no round lands inside the burst. */
    private fun simulation(weapon: WeaponId, vararg build: PowerupId): GameSimulation {
        var slots = PowerupSlots.empty()
        build.forEach { slots = slots.collect(it).first }
        val run = RunState.begin(TestLevels.SEED).copy(loadout = Loadout(Weapons.of(weapon), slots))
        val sim = GameSimulation(TestLevels.flat(committedColumns = 1..5), run, TestLevels.SEED)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 16)
        return sim
    }
}
