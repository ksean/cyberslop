package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.BossAttackKind
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossPhase
import io.github.ksean.cyberslop.entity.BossProfile
import io.github.ksean.cyberslop.entity.BossSpec
import io.github.ksean.cyberslop.entity.Bosses
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Telegraphed swings, close-range cadence, shots and committed-span fairness (P-34/P-80). */
class EnemyAttackTest {
    @Test
    fun `melee wind-ups advance twice as quickly while the player is in reach`() {
        meleeArchetypes.forEach { archetype ->
            val sim = TestLevels.simulation()
            val enemy = TestLevels.enemyAt(sim, archetype, column = TestLevels.SPAWN_COLUMN + 1)
            val swing = EnemyAttacks.swing(archetype)

            sim.tick(InputFrame())
            assertEquals(swing.windUpSeconds, enemy.windUpLeft, 1e-9, "$archetype did not begin its wind-up")

            val healthBeforeWindUp = sim.run.health
            sim.tick(InputFrame())

            assertEquals(
                swing.windUpSeconds - EXPECTED_MELEE_RATE * TICK_SECONDS,
                enemy.windUpLeft,
                1e-9,
                "$archetype did not use the in-reach wind-up rate",
            )
            assertEquals(healthBeforeWindUp, sim.run.health, 1e-9, "$archetype dealt damage during its wind-up")
        }
    }

    @Test
    fun `melee cooldowns recover twice as quickly while the player is in reach`() {
        meleeArchetypes.forEach { archetype ->
            val sim = TestLevels.simulation()
            val enemy = TestLevels.enemyAt(sim, archetype, column = TestLevels.SPAWN_COLUMN + 1)
            enemy.cooldownLeft = 1.0

            sim.tick(InputFrame())

            assertEquals(
                1.0 - EXPECTED_MELEE_RATE * TICK_SECONDS,
                enemy.cooldownLeft,
                1e-9,
                "$archetype did not use the in-reach cooldown rate",
            )
        }
    }

    @Test
    fun `melee timer rates follow the inclusive reach boundary without resetting progress`() {
        val sim = TestLevels.simulation()
        val flyer = TestLevels.enemyAt(sim, EnemyArchetype.Flyer, column = TestLevels.SPAWN_COLUMN)
        val reach = EnemyAttacks.swing(EnemyArchetype.Flyer).reachPx
        flyer.cooldownLeft = 1.0

        placeAtHorizontalDistance(sim, flyer, reach)
        sim.tick(InputFrame())
        assertEquals(1.0 - 2.0 * TICK_SECONDS, flyer.cooldownLeft, 1e-9, "reach itself was not in range")

        placeAtHorizontalDistance(sim, flyer, reach + 0.25)
        sim.tick(InputFrame())
        assertEquals(1.0 - 3.0 * TICK_SECONDS, flyer.cooldownLeft, 1e-9, "outside reach did not use normal time")

        placeAtHorizontalDistance(sim, flyer, reach / 2.0)
        sim.tick(InputFrame())
        assertEquals(1.0 - 5.0 * TICK_SECONDS, flyer.cooldownLeft, 1e-9, "crossing reach reset or lost progress")
    }

    @Test
    fun `close range does not accelerate ranged enemy timers`() {
        listOf(EnemyArchetype.Shooter, EnemyArchetype.Turret).forEach { archetype ->
            val windUpSim = TestLevels.simulation()
            val winding = TestLevels.enemyAt(windUpSim, archetype, column = TestLevels.SPAWN_COLUMN + 1)
            winding.windUpLeft = EnemyAttacks.SHOT.windUpSeconds
            winding.windUpTotal = EnemyAttacks.SHOT.windUpSeconds
            windUpSim.tick(InputFrame())
            assertEquals(
                EnemyAttacks.SHOT.windUpSeconds - TICK_SECONDS,
                winding.windUpLeft,
                1e-9,
                "$archetype wind-up was accelerated",
            )

            val cooldownSim = TestLevels.simulation()
            val cooling = TestLevels.enemyAt(cooldownSim, archetype, column = TestLevels.SPAWN_COLUMN + 1)
            cooling.cooldownLeft = 1.0
            cooldownSim.tick(InputFrame())
            assertEquals(1.0 - TICK_SECONDS, cooling.cooldownLeft, 1e-9, "$archetype cooldown was accelerated")
        }
    }

    @Test
    fun `close range does not accelerate a boss melee telegraph`() {
        val attack = Bosses.attack(BossModule.Slam, mapIndex = 1, mainBoss = true)
        val profile = BossProfile(BossModule.Slam, BossModule.Bolt)
        val spec = BossSpec(
            name = "cadence-fixture",
            maxHealth = 1_000.0,
            contactDamage = 0.0,
            phases = listOf(BossPhase(1.0, listOf(attack))),
            profile = profile,
            mapIndex = 1,
        )
        val sim = TestLevels.simulation()
        val boss = LiveBoss(spec, sim.level.boss, sim.level.tiles)
        val target = BossTarget(
            centre = boss.centre.copy(x = boss.centre.x - 16.0),
            onGround = true,
            crouched = false,
        )
        boss.fight.engage()
        boss.restSecondsLeft = 0.0

        boss.tick(TICK_SECONDS, target)
        assertEquals(BossAttackKind.Melee, boss.currentAttack?.kind, "fixture did not select melee")
        boss.tick(TICK_SECONDS, target)

        assertEquals(TICK_SECONDS, boss.attackElapsed, 1e-9, "boss telegraph was accelerated")
    }

    /** Round-2 finding (gate 4): the scene's projectile cap bounds enemy shots as well as the player's. */
    @Test
    fun `an enemy shot is withheld at the scene's projectile cap`() {
        val sim = TestLevels.simulation()
        val turret = TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = 8)
        repeat(GameSimulation.MAX_PROJECTILES) {
            sim.projectiles.add(LiveProjectile(Vec2(-1000.0, -1000.0), Vec2.Zero, 0.0, 0, 100.0, passesTerrain = true, fromPlayer = true))
        }
        repeat(120) { sim.tick(InputFrame()) }
        assertTrue(turret.cooldownLeft > 0.0 || turret.windingUp, "fixture: the turret never tried to fire")
        assertEquals(GameSimulation.MAX_PROJECTILES, sim.projectiles.size, "an enemy shot passed the cap")
    }

    @Test
    fun `an enemy overlapping the player outside a strike deals exactly its contact drain`() {
        val sim = TestLevels.simulation()
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN)
        swarm.stun(seconds = 5.0)

        repeat(120) { sim.tick(InputFrame()) }

        val drain = 120 * EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(1) * TICK_SECONDS
        assertEquals(sim.run.maxHealth - drain, sim.run.health, 1e-6, "a stunned enemy standing in the player did more or less than drain")
    }

    /**
     * Ticks [count] times and returns the damage of every tick that hurt more than the contact
     * drain could: the strikes, separated from the body drain of an enemy standing in the player.
     */
    private fun strikesOver(sim: GameSimulation, count: Int): List<Double> {
        val drainCeiling = 2 * EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(1) * TICK_SECONDS
        val strikes = mutableListOf<Double>()
        repeat(count) {
            val before = sim.run.health
            sim.tick(InputFrame())
            val taken = before - sim.run.health
            if (taken > drainCeiling) strikes.add(taken)
        }
        return strikes
    }

    @Test
    fun `a swing deals nothing during its wind-up and its damage exactly once per cooldown`() {
        val sim = TestLevels.simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)
        val swing = EnemyAttacks.swing(EnemyArchetype.Swarm)
        val windUpTicks = (swing.windUpSeconds / (EXPECTED_MELEE_RATE * TICK_SECONDS)).roundToInt()
        val drain = EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(1) * TICK_SECONDS
        val expected = Balance.contactDamage(1) * swing.damageShare

        assertEquals(emptyList(), strikesOver(sim, windUpTicks - 1), "damage landed during the wind-up")

        val first = strikesOver(sim, 3)
        assertEquals(1, first.size, "the swing did not land once")
        assertEquals(expected, first.single(), drain + 1e-9, "the swing's damage is not its share")

        val cooldownTicks = (swing.cooldownSeconds / (EXPECTED_MELEE_RATE * TICK_SECONDS)).roundToInt()
        assertEquals(emptyList(), strikesOver(sim, cooldownTicks - 2), "a second strike landed inside the cooldown")

        assertEquals(1, strikesOver(sim, windUpTicks + 4).size, "the second swing did not land")
    }

    private fun placeAtHorizontalDistance(sim: GameSimulation, enemy: LiveEnemy, distance: Double) {
        val playerCentre = sim.player.centre(Physics.Default)
        enemy.position = Vec2(
            playerCentre.x + distance - LiveEnemy.BODY_HALF,
            playerCentre.y - LiveEnemy.BODY_HALF,
        )
    }

    private companion object {
        const val EXPECTED_MELEE_RATE = 2.0
        val meleeArchetypes = listOf(EnemyArchetype.Swarm, EnemyArchetype.Flyer, EnemyArchetype.Brute)
    }

    @Test
    fun `a stun cancels a wind-up`() {
        val sim = TestLevels.simulation()
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)

        repeat(5) { sim.tick(InputFrame()) }
        assertTrue(swarm.windingUp, "the swarm had not started a swing")
        swarm.stun(seconds = 2.0)
        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(sim.run.maxHealth, sim.run.health, "a cancelled swing still landed")
    }

    @Test
    fun `a shot leaves after its wind-up at the specified speed and cadence`() {
        val sim = TestLevels.simulation()
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 8)
        val shot = EnemyAttacks.SHOT
        val windUpTicks = (shot.windUpSeconds / TICK_SECONDS).roundToInt()

        repeat(windUpTicks - 1) { sim.tick(InputFrame()) }
        assertTrue(sim.projectiles.isEmpty(), "a shot left before its wind-up ended")

        repeat(2) { sim.tick(InputFrame()) }
        assertEquals(1, sim.projectiles.size, "the shot did not leave after its wind-up")
        assertEquals(shot.speedPx, sim.projectiles.single().velocity.length, 1e-6)

        var fired = 1
        var previous = sim.projectiles.size
        repeat((5.0 / TICK_SECONDS).roundToInt()) {
            sim.tick(InputFrame())
            if (sim.projectiles.size > previous) fired++
            previous = sim.projectiles.size
        }
        val perShot = shot.windUpSeconds + shot.cooldownSeconds
        assertTrue(fired >= (5.0 / perShot).toInt(), "only $fired shots in five seconds at a $perShot s cadence")
    }

    @Test
    fun `a swing misses a player who got behind it during the wind-up`() {
        val sim = TestLevels.simulation()
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN + 1)
        val swing = EnemyAttacks.swing(EnemyArchetype.Brute)
        val windUpTicks = (swing.windUpSeconds / (EXPECTED_MELEE_RATE * TICK_SECONDS)).roundToInt()

        // The brute winds up facing left, at the player; the player runs through it and stops behind.
        repeat(10) { sim.tick(InputFrame(right = true)) }
        val strikes = strikesOver(sim, windUpTicks + 2 - 10)

        val offset = sim.player.centre(Physics.Default).x - brute.centre.x
        assertTrue(offset > 0.0 && offset < swing.reachPx, "fixture: player at offset $offset is not behind and in reach")
        assertTrue(brute.lastSwing != null, "fixture: the brute never struck")
        assertEquals(emptyList(), strikes, "a swing aimed left hit a player standing to the right")
    }

    @Test
    fun `enemy damage waits out the landing grace after a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        val damage = 5.0

        // Walk clear of the committed column, then stand still.
        repeat(12) { sim.tick(InputFrame(right = true)) }
        assertTrue(!sim.level.isCommitted(TileMap.toTile(sim.player.x)), "fixture: still over the committed column")
        repeat(4) { sim.tick(InputFrame()) }

        shootThePlayer(sim, damage)
        sim.tick(InputFrame())
        assertEquals(sim.run.maxHealth, sim.run.health, "a hit landed inside the landing grace")

        repeat((GameSimulation.LANDING_GRACE / TICK_SECONDS).roundToInt() + 2) { sim.tick(InputFrame()) }
        shootThePlayer(sim, damage)
        sim.tick(InputFrame())
        assertEquals(sim.run.maxHealth - damage, sim.run.health, 1e-9, "a hit after the grace did not land")
    }

    /** Gate-2 finding: projectiles resolved before exposure was updated, so the entry tick could hurt. */
    @Test
    fun `a projectile arriving on the tick the player enters a committed column deals nothing`() {
        val column = TestLevels.SPAWN_COLUMN + 2
        val level = TestLevels.flat(committedColumns = column..column)
        val sim = TestLevels.simulation(level)
        val boundary = TileMap.toWorld(column)
        val width = Physics.Default.width

        var entered = false
        repeat(40) {
            val before = sim.run.health
            shootThePlayer(sim, damage = 1.0)
            sim.tick(InputFrame(right = true))
            val overlapping = sim.player.x + width > boundary && sim.player.x < boundary + 16.0
            if (overlapping) {
                entered = true
                assertEquals(before, sim.run.health, "a hit landed with the player's box over the committed column at x=${sim.player.x}")
            }
        }
        assertTrue(entered, "fixture: the player never reached the committed column")
    }

    /**
     * The boss's ground (`specs/enemies.md`): a Shooter held at an arena's edge is still within its
     * range of a player inside, so the ground has to be fair as well as unenterable — no enemy
     * swing or projectile lands on a player standing on it.
     */
    @Test
    fun `no enemy damage lands on a player standing on the boss's ground`() {
        val arena = Arena(2, 12, TestLevels.FLOOR_ROW + 1)
        val level = TestLevels.flat(bossArena = arena)
        val sim = TestLevels.simulation(level)
        // The ground, not the boss: the boss's own attacks are not bound by the rule.
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        val shooter = TestLevels.enemyAt(sim, EnemyArchetype.Shooter, column = arena.rightTile + 2)
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = arena.rightTile + 1)
        shooter.engaged = true
        swarm.engaged = true

        // The player walks to the arena's right edge and stands there, in reach of both.
        val edge = TileMap.toWorld(arena.rightTile + 1) - 12.0 - 20.0
        var fired = false
        repeat(300) {
            sim.tick(InputFrame(right = sim.player.x < edge))
            if (shooter.lastShot != null) fired = true
        }

        assertTrue(TileMap.toTile(sim.player.x + 12.0) <= arena.rightTile, "fixture: the player left the arena at ${sim.player.x}")
        assertTrue(fired, "fixture: the shooter never fired")
        assertEquals(sim.run.maxHealth, sim.run.health, "enemy damage landed on the boss's ground")
    }

    /** An enemy projectile that will overlap the player's centre on the next tick. */
    private fun shootThePlayer(sim: GameSimulation, damage: Double) {
        val centre = sim.player.centre(Physics.Default)
        sim.projectiles.add(
            LiveProjectile(
                position = centre - Vec2(4.0, 0.0),
                velocity = Vec2(4.0 / TICK_SECONDS, 0.0),
                damage = damage,
                pierceLeft = 0,
                secondsLeft = 1.0,
                passesTerrain = false,
                fromPlayer = false,
            ),
        )
    }

    @Test
    fun `no enemy damage lands while the player is over a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN + 1)
        TestLevels.enemyAt(sim, EnemyArchetype.Turret, column = TestLevels.SPAWN_COLUMN + 6)

        repeat(240) { sim.tick(InputFrame()) }

        assertTrue(sim.level.isCommitted(TestLevels.SPAWN_COLUMN))
        assertEquals(sim.run.maxHealth, sim.run.health, "enemy damage landed on a committed span")
    }
}
