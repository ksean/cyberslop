package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROD-086: positive in-run Scrap awards create presentation-only floating feedback. */
class ScrapGainTest {
    @Test
    fun `a boss Scrap award creates one label for the exact amount`() {
        val sim = TestLevels.simulation()
        val before = sim.run.scrap
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)

        sim.tick(InputFrame())

        assertEquals(before + GameSimulation.BOSS_SCRAP, sim.run.scrap)
        assertEquals(listOf(GameSimulation.BOSS_SCRAP), sim.scrapGains.map { it.amount })
    }

    @Test
    fun `Scrap awards in one tick aggregate and later awards remain distinct`() {
        val sim = TestLevels.simulation()
        val before = sim.run.scrap
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.items += GroundItem(playerCentre(sim), Weapons.of(WeaponId.RustlineMachete), null)

        sim.tick(InputFrame())

        assertEquals(listOf(sim.run.scrap - before), sim.scrapGains.map { it.amount })

        val beforeLaterAward = sim.run.scrap
        sim.items += GroundItem(playerCentre(sim), Weapons.of(WeaponId.VultureRailCarbine), null)
        sim.tick(InputFrame())

        assertEquals(2, sim.scrapGains.size)
        assertEquals(sim.run.scrap - beforeLaterAward, sim.scrapGains.last().amount)
    }

    @Test
    fun `a label stays at its birth point and expires after its declared lifetime`() {
        val sim = TestLevels.simulation()
        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())
        val origin = sim.scrapGains.single().origin

        repeat(20) { sim.tick(InputFrame(right = true)) }

        assertEquals(origin, sim.scrapGains.single().origin)
        assertTrue(sim.player.x + Physics.Default.width / 2.0 > origin.x)

        repeat(60) { sim.tick(InputFrame()) }
        assertTrue(sim.scrapGains.isEmpty(), "the label outlived ${GameSimulation.SCRAP_GAIN_SECONDS} s")
    }

    @Test
    fun `enemy kills and scrapped powerups use the same award boundary`() {
        val kill = TestLevels.simulation()
        val enemy = TestLevels.enemyAt(
            kill,
            EnemyArchetype.Swarm,
            column = TestLevels.SPAWN_COLUMN + 1,
            health = 0.01,
        )
        repeat(30) { if (enemy.alive) kill.tick(InputFrame()) }
        assertTrue(!enemy.alive, "fixture: the nearby enemy was not killed")
        assertEquals(listOf(GameSimulation.SCRAP_PER_KILL), kill.scrapGains.map { it.amount })

        var maxed = Loadout.starting()
        repeat(3) { maxed = maxed.collect(PowerupId.HollowpointFirmware, 1).first }
        val scrapped = GameSimulation(
            TestLevels.flat(),
            RunState.begin(TestLevels.SEED).copy(loadout = maxed),
            TestLevels.SEED,
        )
        scrapped.items += GroundItem(
            playerCentre(scrapped),
            null,
            Powerups.of(PowerupId.HollowpointFirmware),
        )

        val before = scrapped.run.scrap
        scrapped.tick(InputFrame())

        assertTrue(scrapped.run.scrap > before, "fixture: the fourth stack was not scrapped")
        assertEquals(listOf(scrapped.run.scrap - before), scrapped.scrapGains.map { it.amount })
    }

    @Test
    fun `a pickup that awards no Scrap creates no label`() {
        val sim = TestLevels.simulation()
        sim.items += GroundItem(
            playerCentre(sim),
            null,
            Powerups.of(PowerupId.HollowpointFirmware),
        )

        sim.tick(InputFrame())

        assertEquals(1, sim.run.loadout.slots.stacksOf(PowerupId.HollowpointFirmware))
        assertTrue(sim.scrapGains.isEmpty())
    }

    private fun playerCentre(sim: GameSimulation) =
        Vec2(sim.player.x + Physics.Default.width / 2.0, sim.player.y + sim.player.height(Physics.Default) / 2.0)
}
