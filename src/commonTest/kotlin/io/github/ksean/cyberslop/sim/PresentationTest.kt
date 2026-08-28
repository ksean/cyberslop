package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The game is played on four arrow keys and nothing else, and it has to show the player what it is
 * doing on their behalf.
 *
 * A human playtest found the sharp end of this: standing in the boss arena facing a wall, with the
 * boss drawn nowhere, having killed every enemy on the map. The wall was doing exactly what it was
 * designed to do; nothing had ever told the player there was a boss.
 */
class PresentationTest {
    @Test
    fun `aiming needs no pointing device and no setting`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)

        repeat(400) { sim.tick(InputFrame()) }

        assertTrue(
            sim.projectiles.isNotEmpty() || sim.enemies.any { !it.alive },
            "the weapon never engaged anything without a cursor",
        )
    }

    @Test
    fun `a melee swing is visible`() {
        val sim = simulation(WeaponId.RustlineMachete)

        var seen: SwingVisual? = null
        repeat(300) {
            sim.tick(InputFrame())
            sim.lastSwing?.let { swing -> seen = swing }
        }

        val swing = seen
        assertTrue(swing != null, "a melee weapon swung 300 times and showed nothing")
        assertTrue(swing!!.reachPx > 0.0, "the swing has no reach to draw")
        assertTrue(swing.arcDegrees > 0.0, "the swing has no arc to draw")
    }

    @Test
    fun `a swing fades rather than staying on screen`() {
        val sim = simulation(WeaponId.RustlineMachete)

        while (sim.lastSwing == null) sim.tick(InputFrame())
        val atSwing = sim.lastSwing!!.secondsLeft
        repeat(20) { sim.tick(InputFrame()) }

        assertTrue(
            sim.lastSwing == null || sim.lastSwing!!.secondsLeft < atSwing,
            "the swing never faded",
        )
    }

    @Test
    fun `the boss exposes what a renderer needs to show it`() {
        val sim = simulation()

        assertTrue(sim.boss.healthFraction == 1.0, "a fresh boss is not at full health")
        assertTrue(sim.boss.spec.name.isNotBlank(), "the boss has no name to show")

        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth / 2.0)

        assertTrue(sim.boss.healthFraction < 1.0, "damage is not reflected in what is drawn")
    }

    @Test
    fun `killing the boss clears every wall between the arena and the map edge`() {
        val sim = simulation()
        val level = sim.level
        val floor = level.boss.floorRow

        fun blocked() = (level.boss.leftTile until level.widthTiles).filter { column ->
            (floor - PLAYER_TILES until floor).any { level.tiles.blocksMovement(column, it) }
        }

        assertTrue(blocked().isNotEmpty(), "nothing sealed the exit to begin with")

        sim.boss.fight.engage()
        sim.boss.fight.damage(sim.boss.spec.maxHealth)
        sim.tick(InputFrame())

        assertTrue(
            blocked().isEmpty(),
            "walls still block the way out at ${blocked()} after the boss died",
        )
    }

    @Test
    fun `the mini-boss never seals anything`() {
        // Only the main boss gates the exit. A mini-boss that walled the player in would strand a
        // run halfway through a map.
        val sim = simulation()
        val level = sim.level

        val sealed = (level.miniboss.leftTile..level.miniboss.rightTile).any { column ->
            (level.miniboss.floorRow - PLAYER_TILES until level.miniboss.floorRow)
                .any { level.tiles.blocksMovement(column, it) }
        }

        assertFalse(sealed, "the mini-boss arena is walled")
    }

    private fun simulation(
        weapon: WeaponId = WeaponId.BrokenBottle,
        mapIndex: Int = 1,
    ): GameSimulation {
        val level = LevelGenerator.generate(SEED, mapIndex).level
        var run = RunState.begin(SEED).copy(mapIndex = mapIndex)
        if (weapon != WeaponId.BrokenBottle) {
            run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(weapon)))
        }
        return GameSimulation(level, run, SEED)
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val PLAYER_TILES = 2
    }
}
