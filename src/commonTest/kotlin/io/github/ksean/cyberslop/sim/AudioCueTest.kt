package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-77: sound follows semantic transitions, not projectile or visual-state polling. */
class AudioCueTest {
    @Test
    fun `a player melee activation reports one swing cue`() {
        val report = simulation(WeaponId.BrokenBottle).tick(InputFrame())

        assertEquals(listOf(AudioCue.MeleeSwing), report.audioCues)
    }

    @Test
    fun `single spread and timed burst fire report per firing event`() {
        val single = simulation(WeaponId.ScraplineZipPistol)
        assertEquals(listOf(AudioCue.RangedFire), single.tick(InputFrame()).audioCues)

        val spread = simulation(WeaponId.RiotbreakerShotgun)
        val spreadReport = spread.tick(InputFrame())
        assertEquals(5, spread.projectiles.count { it.fromPlayer })
        assertEquals(listOf(AudioCue.RangedFire), spreadReport.audioCues)

        val burst = simulation(WeaponId.GanglordSmg)
        val burstCues = (1..7).map { burst.tick(InputFrame()).audioCues }
        assertEquals(
            listOf(
                listOf(AudioCue.RangedFire),
                emptyList(),
                emptyList(),
                listOf(AudioCue.RangedFire),
                emptyList(),
                emptyList(),
                listOf(AudioCue.RangedFire),
            ),
            burstCues,
        )
    }

    @Test
    fun `psychic and enemy attacks report no player weapon cue`() {
        val psychic = simulation(WeaponId.MigraineLoop)
        val psychicReport = psychic.tick(InputFrame())
        assertTrue(psychic.lastHit != null, "fixture: the psychic weapon did not activate")
        assertEquals(emptyList(), psychicReport.audioCues)

        val enemy = TestLevels.simulation().also {
            it.enemies.clear()
            it.autoFire.remaining = 100.0
            TestLevels.enemyAt(it, EnemyArchetype.Turret, TestLevels.SPAWN_COLUMN + 8)
        }
        val enemyCues = buildList {
            repeat(90) { addAll(enemy.tick(InputFrame()).audioCues) }
        }
        assertTrue(enemy.projectiles.any { !it.fromPlayer }, "fixture: the enemy never fired")
        assertEquals(emptyList(), enemyCues)
    }

    @Test
    fun `each contacted ground item reports one pickup pulse across resolution outcomes`() {
        fun collect(vararg items: GroundItem): List<AudioCue> {
            val sim = TestLevels.simulation().also { it.autoFire.remaining = 100.0 }
            sim.items += items
            return sim.tick(InputFrame()).audioCues
        }

        val at = playerCentre(TestLevels.simulation())
        assertEquals(
            listOf(AudioCue.PickupPulse),
            collect(GroundItem(at, Weapons.of(WeaponId.BrokenBottle), null)),
            "a matching weapon converted to Scrap without a pulse",
        )
        assertEquals(
            listOf(AudioCue.PickupPulse),
            collect(GroundItem(at, null, Powerups.of(PowerupId.HollowpointFirmware))),
        )
        assertEquals(
            listOf(AudioCue.PickupPulse),
            collect(
                GroundItem(
                    at,
                    Weapons.of(WeaponId.VultureRailCarbine),
                    Powerups.of(PowerupId.SpikeDriver),
                    guaranteed = true,
                ),
            ),
            "a paired award produced more or fewer than one pulse",
        )
        assertEquals(
            listOf(AudioCue.PickupPulse, AudioCue.PickupPulse),
            collect(
                GroundItem(at, Weapons.of(WeaponId.BrokenBottle), null),
                GroundItem(at, null, Powerups.of(PowerupId.HollowpointFirmware)),
            ),
        )
    }

    @Test
    fun `reading cue values changes neither simulation digest nor later reports`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        val first = sim.tick(InputFrame())
        val digest = sim.digest()

        assertEquals(listOf(AudioCue.RangedFire), first.audioCues)
        assertEquals(digest, sim.digest())
        assertEquals(emptyList(), sim.tick(InputFrame()).audioCues)
        assertEquals(listOf(AudioCue.RangedFire), first.audioCues, "a later tick mutated an old report")
    }

    private fun simulation(weapon: WeaponId): GameSimulation {
        val run = RunState.begin(TestLevels.SEED).copy(
            loadout = Loadout.starting().copy(weapon = Weapons.of(weapon)),
        )
        return GameSimulation(TestLevels.flat(committedColumns = 1..5), run, TestLevels.SEED).also {
            it.enemies.clear()
            TestLevels.enemyAt(it, EnemyArchetype.Turret, column = 16)
        }
    }

    private fun playerCentre(sim: GameSimulation) = Vec2(sim.player.x + 6.0, sim.player.y + 13.0)
}
