package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDeathSequenceTest {
    @Test
    fun `every terminal source has its declared cause effect`() {
        assertEquals(
            mapOf(
                PlayerDamageSource.Acid to PlayerDeathEffect.Poison,
                PlayerDamageSource.Fire to PlayerDeathEffect.Flame,
                PlayerDamageSource.Laser to PlayerDeathEffect.Flame,
                PlayerDamageSource.Spike to PlayerDeathEffect.Bleed,
                PlayerDamageSource.Projectile to PlayerDeathEffect.Bleed,
                PlayerDamageSource.Melee to PlayerDeathEffect.Bleed,
                PlayerDamageSource.Void to PlayerDeathEffect.None,
                PlayerDamageSource.Contact to PlayerDeathEffect.None,
            ),
            PlayerDamageSource.entries.associateWith { it.effect },
        )
    }

    @Test
    fun `acid starts at age zero and the first terminal source stays latched`() {
        val level = TestLevels.flat(
            barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW)),
        ).also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = TileKind.Acid
        }
        val sim = simulation(level, health = 0.001)

        val report = sim.tick(InputFrame())

        assertTrue(report.playerDied)
        assertFalse(report.deathSequenceComplete)
        assertEquals(PlayerDamageSource.Fire, sim.deathSequence?.cause)
        assertEquals(0, sim.deathSequence?.elapsedTicks)
    }

    @Test
    fun `acid fire spike and void enter their semantic terminal sources`() {
        assertEquals(PlayerDamageSource.Acid, lethalTile(TileKind.Acid).deathSequence?.cause)
        assertEquals(PlayerDamageSource.Void, lethalTile(TileKind.Void).deathSequence?.cause)

        val fireLevel = TestLevels.flat(
            jets = listOf(
                FireJet(
                    TestLevels.SPAWN_COLUMN,
                    TestLevels.FLOOR_ROW - 2,
                    TestLevels.FLOOR_ROW,
                    periodSeconds = 1.0,
                    onSeconds = 1.0,
                    phaseSeconds = 0.0,
                ),
            ),
        )
        val fire = simulation(fireLevel)
        fire.tick(InputFrame())
        assertEquals(PlayerDamageSource.Fire, fire.deathSequence?.cause)

        val spike = simulation(
            TestLevels.flat(spikeColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
            health = 0.001,
        )
        spike.tick(InputFrame())
        assertEquals(PlayerDamageSource.Spike, spike.deathSequence?.cause)
    }

    @Test
    fun `death freezes gameplay for two seconds prone and completes at four seconds`() {
        val level = TestLevels.flat().also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = TileKind.Acid
        }
        val sim = simulation(level)
        val enemy = TestLevels.enemyAt(sim, EnemyArchetype.Brute, TestLevels.SPAWN_COLUMN + 5)
        sim.tick(InputFrame())
        val player = sim.player
        val enemyPosition = enemy.position
        val elapsed = sim.elapsedTicks
        val digestAtDeath = sim.digest()

        var report = TickReport()
        repeat(DeathSequence.COLLAPSE_TICKS) {
            report = sim.tick(InputFrame(right = true, jump = true, jumpStart = true))
        }

        assertEquals(1.0, sim.deathSequence?.collapseProgress)
        assertFalse(report.deathSequenceComplete)
        assertEquals(player, sim.player)
        assertEquals(enemyPosition, enemy.position)
        assertEquals(elapsed, sim.elapsedTicks)
        assertEquals(elapsed * TICK_SECONDS + 2.0, sim.presentationTimeSeconds, TICK_SECONDS / 2.0)
        assertTrue(digestAtDeath != sim.digest(), "terminal age must be future-affecting")

        repeat(DeathSequence.TOTAL_TICKS - DeathSequence.COLLAPSE_TICKS - 1) {
            report = sim.tick(InputFrame(left = true))
        }
        assertFalse(report.deathSequenceComplete)

        report = sim.tick(InputFrame(left = true))
        assertTrue(report.deathSequenceComplete)
        assertEquals(DeathSequence.TOTAL_TICKS, sim.deathSequence?.elapsedTicks)
        assertEquals(player, sim.player)
        assertEquals(enemyPosition, enemy.position)
        assertEquals(elapsed, sim.elapsedTicks)
    }

    private fun lethalTile(kind: TileKind): GameSimulation {
        val level = TestLevels.flat().also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = kind
        }
        return simulation(level).also { it.tick(InputFrame()) }
    }

    private fun simulation(level: io.github.ksean.cyberslop.world.Level, health: Double = 100.0): GameSimulation {
        val run = RunState.begin(TestLevels.SEED).copy(health = health)
        return GameSimulation(level, run, TestLevels.SEED)
    }
}
