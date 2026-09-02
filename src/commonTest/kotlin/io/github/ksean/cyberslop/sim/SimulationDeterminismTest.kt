package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.BossModule
import io.github.ksean.cyberslop.entity.BossRoster
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * P-40: the whole rule-bearing simulation state, digested, matches a committed golden on every
 * target — the only way the JVM and Wasm results can be compared, since neither test run can see
 * the other. A mutation in each state family changes the digest, so the digest is known to read
 * every family rather than merely to be stable.
 */
class SimulationDeterminismTest {
    @Test
    fun `a fixed tape on a fixed seed produces the committed digest on every target`() {
        assertEquals(GOLDEN, simulate().digest())
    }

    @Test
    fun `the tape actually exercises the population`() {
        val sim = simulate()
        assertNotEquals(sim.run.maxHealth, sim.run.health, "nothing hurt the player, so the tape proves little")
    }

    @Test
    fun `a mutation in every state family changes the digest`() {
        val baseline = simulate().digest()

        fun mutated(family: String, mutate: (GameSimulation) -> Unit) {
            val sim = simulate()
            mutate(sim)
            assertNotEquals(baseline, sim.digest(), "$family is not read by the digest")
        }

        mutated("player and run") { it.tick(InputFrame(jumpStart = true, jump = true)) }
        mutated("auto-fire accumulator") { it.autoFire.remaining += 0.01 }
        mutated("loot rng") { it.lootRng.nextULong() }
        mutated("enemies") { it.enemies.first().health -= 1.0 }
        mutated("enemy engagement") { it.enemies.first().engaged = !it.enemies.first().engaged }
        mutated("enemy aiming velocity") { it.enemies.first().aimingVelocity = Vec2.Right }
        mutated("projectiles") {
            it.projectiles.add(LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = true))
        }
        mutated("projectile payload") {
            it.projectiles.add(LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = true, weapon = it.autoFire.weapon))
        }
        mutated("items") { it.items.add(GroundItem(Vec2.Zero, null, null)) }
        mutated("bosses") { it.boss.fight.engage() }
        mutated("boss aiming velocity") { it.boss.aimingVelocity = Vec2.Right }
        mutated("boss rest") { it.miniboss.restSecondsLeft += 0.1 }
        mutated("boss melee index") { it.boss.meleeIndex++ }
        mutated("boss ranged index") { it.boss.rangedIndex++ }
        mutated("boss choice rng") { it.boss.rng.nextULong() }
        mutated("boss charge rng") { it.boss.chargeRng.nextULong() }
        mutated("boss charge selection") { it.boss.meleeChargeSelected = !it.boss.meleeChargeSelected }
        mutated("boss stopped charge") { it.boss.meleeChargeStopped = !it.boss.meleeChargeStopped }
        mutated("boss consumed charge event") { it.boss.consumedChargeEvents += 2 }
        mutated("enemy leap") { it.enemies.first().leap = EnemyLeap(1, 64.0, 4..5) }
        mutated("boss locked aim") { it.boss.lockAim(Vec2(123.0, 45.0)) }
        mutated("boss beam") {
            it.bossBeams += LiveBossBeam(Vec2.Zero, Vec2.Right, 3.0, 0.2, 0.3)
        }
        mutated("lifesteal budget") { it.lifestealBudget -= 1.0 }
        mutated("pending burst") { it.pendingBurst = PendingBurst(2, 0.05, Vec2.Right, it.autoFire.weapon) }
        // Round-1 finding: a payload is every field spawning or landing reads, not a marker that one exists.
        val damper = io.github.ksean.cyberslop.combat.DamagePipeline.resolve(
            it_weapon(), io.github.ksean.cyberslop.loot.PowerupSlots.empty().collect(io.github.ksean.cyberslop.loot.PowerupId.KineticDamper).first,
        )
        val ricochet = io.github.ksean.cyberslop.combat.DamagePipeline.resolve(
            it_weapon(), io.github.ksean.cyberslop.loot.PowerupSlots.empty().collect(io.github.ksean.cyberslop.loot.PowerupId.RicochetRom).first,
        )
        fun payloadDiffers(family: String, a: (GameSimulation) -> Unit, b: (GameSimulation) -> Unit) {
            val left = simulate().also(a).digest()
            val right = simulate().also(b).digest()
            assertNotEquals(left, right, "$family is not read by the digest")
        }
        payloadDiffers(
            "projectile knockback payload",
            { it.projectiles.add(LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = true, weapon = it.autoFire.weapon)) },
            { it.projectiles.add(LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = true, weapon = damper)) },
        )
        payloadDiffers(
            "burst bounce payload",
            { it.pendingBurst = PendingBurst(2, 0.05, Vec2.Right, it.autoFire.weapon) },
            { it.pendingBurst = PendingBurst(2, 0.05, Vec2.Right, ricochet) },
        )
        mutated("projectile bounces") {
            it.projectiles.add(LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = true, bouncesLeft = 1))
        }
        payloadDiffers(
            "boss projectile module",
            { it.projectiles += LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, false, false, true, BossModule.Bolt) },
            { it.projectiles += LiveProjectile(Vec2.Zero, Vec2.Right, 1.0, 0, 1.0, false, false, true, BossModule.Scatter) },
        )
        payloadDiffers(
            "ground-item position",
            { it.items += GroundItem(Vec2.Zero, it_weapon(), null) },
            { it.items += GroundItem(Vec2.Right, it_weapon(), null) },
        )
    }

    @Test
    fun `active swing geometry progress build and hit identities are in the digest`() {
        val sim = TestLevels.simulation()
        sim.tick(InputFrame())
        val active = sim.activeSwing!!
        val baseline = sim.digest()

        fun changed(label: String, mutate: (ActiveMeleeSwing) -> ActiveMeleeSwing) {
            sim.activeSwing = mutate(active)
            assertNotEquals(baseline, sim.digest(), "$label is not read by the digest")
            sim.activeSwing = active
        }

        changed("origin") { it.copy(origin = it.origin + Vec2.Right) }
        changed("locked direction") { it.copy(direction = Vec2(0.0, 1.0)) }
        changed("arc") { it.copy(arcDegrees = it.arcDegrees + 1.0) }
        changed("reach") { it.copy(reachPx = it.reachPx + 1.0) }
        changed("progress") { it.copy(elapsedSeconds = it.elapsedSeconds + 0.001) }
        changed("triggering build") {
            val changedWeapon = io.github.ksean.cyberslop.combat.DamagePipeline.resolve(
                it.weapon.spec,
                io.github.ksean.cyberslop.loot.PowerupSlots.empty()
                    .collect(io.github.ksean.cyberslop.loot.PowerupId.RangerOptics).first,
            )
            it.copy(weapon = changedWeapon)
        }
        changed("already-hit identity") {
            it.copy(hitTargets = setOf(CombatTargetId(CombatTargetKind.Enemy, 3)))
        }
    }

    @Test
    fun `terminal phase and age are digested while the visual cause is not`() {
        val sim = TestLevels.simulation()
        val playing = sim.digest()

        sim.deathSequence = DeathSequence(PlayerDamageSource.Acid, elapsedTicks = 1)
        val terminal = sim.digest()
        assertNotEquals(playing, terminal)

        sim.deathSequence = DeathSequence(PlayerDamageSource.Acid, elapsedTicks = 2)
        assertNotEquals(terminal, sim.digest(), "terminal age is not read by the digest")

        sim.deathSequence = DeathSequence(PlayerDamageSource.Fire, elapsedTicks = 1)
        assertEquals(terminal, sim.digest(), "presentation-only cause entered the digest")
    }

    @Test
    fun `safe-site geometry changes positions without changing seeded loot or its rng state`() {
        data class Snapshot(
            val contents: List<Pair<Int, Int>>,
            val positions: List<Vec2>,
            val rngState: ULong,
        )

        fun snapshot(raised: Boolean): Snapshot {
            val level = TestLevels.flat(mapIndex = LOOT_MAP)
            if (raised) level.tiles[LOOT_COLUMN, TestLevels.FLOOR_ROW] =
                io.github.ksean.cyberslop.world.TileKind.Solid
            val run = RunState.begin(LOOT_SEED).copy(mapIndex = LOOT_MAP)
            val sim = GameSimulation(level, run, LOOT_SEED)

            repeat(LOOT_KILLS) {
                val enemy = TestLevels.enemyAt(
                    sim,
                    EnemyArchetype.Swarm,
                    column = LOOT_COLUMN,
                    health = 0.01,
                )
                enemy.burn.apply(seconds = 1.0, rate = 1.0)
                sim.tick(InputFrame())
            }
            return Snapshot(
                contents = sim.items.map {
                    (it.weapon?.id?.ordinal ?: -1) to (it.powerup?.id?.ordinal ?: -1)
                },
                positions = sim.items.map { it.position },
                rngState = sim.lootRng.state,
            )
        }

        val flat = snapshot(raised = false)
        val raised = snapshot(raised = true)
        assertEquals(flat.contents, raised.contents)
        assertEquals(flat.rngState, raised.rngState)
        assertNotEquals(flat.positions, raised.positions)
    }

    @Test
    fun `the assigned boss roster is part of the future-state digest`() {
        val first = BossRoster.forRun(1uL)
        val second = (2uL..2_000uL).map(BossRoster::forRun).first {
            it.miniboss(1) != first.miniboss(1) || it.boss(1) != first.boss(1)
        }
        val run = RunState.begin(SEED)

        val a = GameSimulation(TestLevels.flat(), run, SEED, bossRoster = first)
        val b = GameSimulation(TestLevels.flat(), run, SEED, bossRoster = second)

        assertNotEquals(a.digest(), b.digest())
    }

    /** A hit indicator is presentation only (PROD-071): it never touches the digest. */
    @Test
    fun `a hit indicator does not change the digest`() {
        val sim = simulate()
        val before = sim.digest()
        sim.lastHit = HitIndicator(HitShape.Ring(Vec2.Zero, 10.0), 0.1, 0.1)
        assertEquals(before, sim.digest())
    }

    /** Round-3 finding: the exit state is the gate's tiles, which `openGate` mutates — not a flag. */
    @Test
    fun `the exit geometry is part of the digest`() {
        val sim = TestLevels.simulation()
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())
        val opened = sim.digest()
        sim.level.tiles[sim.level.gateColumn, sim.level.boss.floorRow - 1] = io.github.ksean.cyberslop.world.TileKind.Solid
        assertNotEquals(opened, sim.digest(), "a gate tile written back to solid left the digest unchanged")
    }

    private fun it_weapon() = io.github.ksean.cyberslop.combat.Weapons.startingWeapon

    private fun simulate(): GameSimulation {
        val sim = GameSimulation(level, RunState.begin(SEED), SEED)
        repeat(TICKS) { tick ->
            val running = tick < RUN_TICKS
            val jump = running && tick % 45 == 0
            sim.tick(InputFrame(right = running && tick % 90 < 70, jump = jump, jumpStart = jump, crouch = running && tick % 90 in 75..80))
        }
        return sim
    }

    private companion object {
        val SEED = 0xD1CE5uL
        const val TICKS = 720
        const val RUN_TICKS = 600
        const val GOLDEN = 15581592848669027342uL
        const val LOOT_MAP = 2
        const val LOOT_COLUMN = 20
        const val LOOT_KILLS = 80
        const val LOOT_SEED = 0xD09uL

        /** Generated once: nothing the tape does writes to the tiles, and generation is the slow part. */
        val level by lazy { LevelGenerator.generate(SEED, 1).level }
    }
}
