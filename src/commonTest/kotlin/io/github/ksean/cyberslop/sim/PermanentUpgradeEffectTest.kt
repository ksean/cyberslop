package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.DamagePipeline
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.progression.UpgradeRanks
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.run.SaveCodec
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P-56: purchased effects enter rules once, at their explicit boundaries. */
class PermanentUpgradeEffectTest {
    @Test
    fun `chassis raises maximum health while map advance and profile refresh preserve current health`() {
        val ranks = UpgradeRanks(reinforcedChassis = 5)
        val fresh = RunState.begin(SEED, ranks)

        assertEquals(Balance.playerMaxHealth(1) * 1.5, fresh.maxHealth)
        assertEquals(fresh.maxHealth, fresh.health)

        val damaged = fresh.copy(health = 47.0)
        val advanced = damaged.advanced()
        assertEquals(2, advanced.mapIndex)
        assertEquals(Balance.playerMaxHealth(2) * 1.5, advanced.maxHealth)
        assertEquals(47.0, advanced.health)
        val restored = SaveCodec.decodeRun(SaveCodec.encodeRun(advanced)).getOrThrow().run
        assertEquals(advanced.mapIndex, restored.mapIndex)
        assertEquals(advanced.health, restored.health)

        val active = RunState.begin(SEED).copy(health = 47.0)
        val refreshed = active.copy(upgrades = ranks)
        assertEquals(47.0, refreshed.health)
        assertEquals(Balance.playerMaxHealth(1) * 1.5, refreshed.maxHealth)
    }

    @Test
    fun `firmware scales every registered weapon before hit paths and preserves baseline scoring by default`() {
        Weapons.all.forEach { weapon ->
            val baseline = DamagePipeline.resolve(weapon, PowerupSlots.empty())
            val upgraded = DamagePipeline.resolve(
                weapon,
                PowerupSlots.empty(),
                permanentDamageMultiplier = 1.25,
            )
            assertEquals(baseline.damagePerProjectile * 1.25, upgraded.damagePerProjectile, 1e-9, weapon.id.name)
            assertEquals(baseline.cooldown, upgraded.cooldown, 1e-9)
            assertEquals(1.25, upgraded.permanentDamageMultiplier)
        }
    }

    @Test
    fun `firmware reaches the simulation and scales fixed bleed derived from a hit`() {
        fun strike(ranks: UpgradeRanks): Pair<Double, Double> {
            val weapon = Weapons.of(WeaponId.RustlineMachete)
            val run = RunState.begin(SEED, ranks).copy(loadout = Loadout(weapon, PowerupSlots.empty()))
            val sim = GameSimulation(TestLevels.flat(), run, SEED)
            sim.enemies.clear()
            val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Brute, TestLevels.SPAWN_COLUMN + 1)
            val health = enemy.health
            sim.tick(InputFrame())
            return health - enemy.health to enemy.bleed.perSecond
        }

        val baseline = strike(UpgradeRanks())
        val upgraded = strike(UpgradeRanks(blackMarketFirmware = 5))
        assertEquals(baseline.first * 1.25, upgraded.first, 1e-9)
        assertEquals(baseline.second * 1.25, upgraded.second, 1e-9)
    }

    @Test
    fun `weave reduces enemy projectiles swings contact spikes glass barrels and boss damage`() {
        assertReduced(::enemyProjectileDamage, "enemy projectile")
        assertReduced(::enemySwingDamage, "enemy swing")
        assertReduced(::enemyContactDamage, "enemy contact")
        assertReduced(::hazardDamage, "spikes, glass and barrel")
        assertReduced(::bossAttackDamage, "boss attack")
        assertReduced(::bossContactDamage, "boss contact")
    }

    @Test
    fun `weave cannot prevent acid void or fire jet death`() {
        val ranks = UpgradeRanks(reactiveDermalWeave = 5)

        val acidLevel = TestLevels.flat().also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = TileKind.Acid
        }
        assertTrue(simulation(acidLevel, ranks).tick(InputFrame()).playerDied, "acid was mitigated")

        val void = simulation(
            TestLevels.flat(gapColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN + 1),
            ranks,
        )
        var voidDeath = false
        repeat(300) { if (void.tick(InputFrame()).playerDied) voidDeath = true }
        assertTrue(voidDeath, "the void was mitigated")

        val jetBase = TestLevels.flat()
        val jet = FireJet(
            TestLevels.SPAWN_COLUMN,
            TestLevels.FLOOR_ROW - 2,
            TestLevels.FLOOR_ROW,
            periodSeconds = 1.0,
            onSeconds = 1.0,
            phaseSeconds = 0.0,
        )
        assertTrue(simulation(jetBase.withJets(listOf(jet)), ranks).tick(InputFrame()).playerDied, "a fire jet was mitigated")
    }

    @Test
    fun `upgrade ranks are future affecting simulation state`() {
        assertNotEquals(
            simulation(TestLevels.flat(), UpgradeRanks()).digest(),
            simulation(TestLevels.flat(), UpgradeRanks(blackMarketFirmware = 1)).digest(),
        )
    }

    private fun assertReduced(measure: (UpgradeRanks) -> Double, source: String) {
        val baseline = measure(UpgradeRanks())
        val upgraded = measure(UpgradeRanks(reactiveDermalWeave = 5))
        assertTrue(baseline > 0.0, "fixture: $source dealt nothing")
        assertEquals(baseline * 0.75, upgraded, 1e-6, source)
    }

    private fun enemyProjectileDamage(ranks: UpgradeRanks): Double {
        val sim = simulation(TestLevels.flat(), ranks)
        sim.enemies.clear()
        val centre = sim.player.centre(io.github.ksean.cyberslop.physics.Physics.Default)
        sim.projectiles += LiveProjectile(
            centre - Vec2(4.0, 0.0),
            Vec2(4.0 / TICK_SECONDS, 0.0),
            12.0,
            0,
            1.0,
            passesTerrain = false,
            fromPlayer = false,
        )
        val before = sim.run.health
        sim.tick(InputFrame())
        return before - sim.run.health
    }

    private fun enemySwingDamage(ranks: UpgradeRanks): Double {
        val sim = simulation(TestLevels.flat(), ranks)
        sim.enemies.clear()
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, TestLevels.SPAWN_COLUMN + 1)
        var previous = sim.run.health
        repeat(180) {
            sim.tick(InputFrame())
            if (sim.run.health < previous) return previous - sim.run.health
            previous = sim.run.health
        }
        return 0.0
    }

    private fun enemyContactDamage(ranks: UpgradeRanks): Double {
        val sim = simulation(TestLevels.flat(), ranks)
        sim.enemies.clear()
        TestLevels.enemyAt(sim, EnemyArchetype.Swarm, TestLevels.SPAWN_COLUMN).stun(2.0)
        val before = sim.run.health
        sim.tick(InputFrame())
        return before - sim.run.health
    }

    private fun hazardDamage(ranks: UpgradeRanks): Double {
        val level = TestLevels.flat(
            glassColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN,
            barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)),
        ).also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW - 1] = TileKind.Spikes
        }
        val sim = simulation(level, ranks)
        val before = sim.run.health
        sim.tick(InputFrame())
        val expected = (Hazards.SPIKE_RATE + Hazards.GLASS_RATE + Hazards.BARREL_RATE) *
            Balance.contactDamage(1) * TICK_SECONDS
        assertEquals(expected * ranks.incomingDamageMultiplier, before - sim.run.health, 1e-6)
        return before - sim.run.health
    }

    private fun bossAttackDamage(ranks: UpgradeRanks): Double {
        val level = TestLevels.flat(
            bossArena = io.github.ksean.cyberslop.world.Arena(5, 20, TestLevels.FLOOR_ROW + 1),
            mapIndex = 5,
        )
        val sim = simulation(level, ranks)
        sim.enemies.clear()
        sim.boss.fight.engage()
        repeat(1_200) {
            val before = sim.run.health
            sim.tick(TestLevels.standStill(sim))
            if (sim.run.health < before) return before - sim.run.health
        }
        return 0.0
    }

    private fun bossContactDamage(ranks: UpgradeRanks): Double {
        val sim = simulation(TestLevels.flat(), ranks)
        sim.enemies.clear()
        sim.boss.fight.engage()
        sim.boss.placeAt(
            Vec2(
                sim.player.centre(io.github.ksean.cyberslop.physics.Physics.Default).x,
                sim.player.y + sim.player.height(io.github.ksean.cyberslop.physics.Physics.Default),
            ),
        )
        val before = sim.run.health
        sim.tick(InputFrame())
        return before - sim.run.health
    }

    private fun simulation(level: Level, ranks: UpgradeRanks): GameSimulation =
        GameSimulation(level, RunState.begin(SEED, ranks), SEED)

    private fun Level.withJets(jets: List<FireJet>): Level = Level(
        mapIndex,
        theme,
        tiles,
        floorMask,
        arcMask,
        spawnColumn,
        spawnRow,
        miniboss,
        boss,
        jets,
        enemies,
        pickups,
        gateColumn,
        barrels,
    )

    private companion object {
        const val SEED = 0xF17EuL
    }
}
