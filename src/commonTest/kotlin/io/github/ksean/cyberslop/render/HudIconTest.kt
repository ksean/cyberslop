package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The display names an item and shows its icon (PROD-045, PROD-049).
 *
 * The third of the three places PROD-049 requires one icon to serve, and the one that makes the
 * other two teach: a player who has seen the Overclock Coil in their own build recognises the next
 * one lying on the floor.
 */
class HudIconTest {
    @Test
    fun `the model names the items by id, so the browser layer resolves nothing`() {
        val model = model(WeaponId.GutterjackCleaver, PowerupId.SeekerDaemon, PowerupId.BurnRig)

        assertEquals(WeaponId.GutterjackCleaver, model.weaponId, "the HUD cannot find its weapon")
        assertEquals(
            setOf(PowerupId.SeekerDaemon, PowerupId.BurnRig),
            model.powerups.map { it.id }.toSet(),
            "the HUD cannot find the powerups it is listing",
        )
    }

    @Test
    fun `the display draws the same geometry the ground does`() {
        val weapon = WeaponId.GutterjackCleaver
        val held = listOf(PowerupId.SeekerDaemon, PowerupId.BurnRig)
        val expected = WeaponIcons.of(weapon).strokes.size +
            held.sumOf { PowerupIcons.of(it).strokes.size }

        val frame = compose(weapon, *held.toTypedArray())

        // The display's own halo layer only: the same frame also draws the weapon in the player's
        // hand, in the same materials, which would otherwise be counted twice. The halo pass is
        // one stroke per icon stroke; the colour pass adds a streak per weathered stroke.
        val drawn = frame.batches
            .filter {
                it.layer == Layer.Hud && it.primitive == Primitive.Segment && it.style == IconStyles.HALO
            }
            .sumOf { it.size }

        assertEquals(
            expected,
            drawn,
            "the display drew $drawn strokes for icons whose own geometry is $expected strokes",
        )
    }

    /** P-51: the display draws the icon in its materials and wears no kind ring. */
    @Test
    fun `the display draws materials and no kind ring`() {
        val frame = compose(WeaponId.ChromeFang, PowerupId.ForkBomb)
        val styles = frame.batches
            .filter { it.layer == Layer.HudOverlay || it.layer == Layer.Hud || it.layer == Layer.HudWear }
            .map { it.style }
            .toSet()

        assertTrue(Material.Steel.colour in styles, "no steel in the display: $styles")
        assertTrue(IconStyles.WEAPON_RING !in styles, "the display ringed the weapon")
        assertTrue(IconStyles.POWERUP_RING !in styles, "the display ringed a powerup")
    }

    /** PROD-004's path is text, and it must not have moved. */
    @Test
    fun `the announcement is unchanged by the icons`() {
        val model = model(WeaponId.ChromeFang, PowerupId.ForkBomb)

        assertTrue(
            model.announcement.contains("Chrome Fang"),
            "the spoken announcement lost the weapon's name: ${model.announcement}",
        )
    }

    private fun simulationOf(weapon: WeaponId, vararg held: PowerupId): GameSimulation {
        val level = LevelGenerator.generate(SEED, MAP).level
        var run = RunState.begin(SEED)
        run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(weapon)))
        held.forEach { run = run.copy(loadout = run.loadout.collect(it, MAP).first) }
        return GameSimulation(level, run, SEED)
    }

    private fun model(weapon: WeaponId, vararg held: PowerupId): HudModel =
        HudModel.of(simulationOf(weapon, *held))

    private fun compose(weapon: WeaponId, vararg held: PowerupId): DrawList {
        val sim = simulationOf(weapon, *held)
        sim.items.clear()
        return Scene.compose(
            sim,
            Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            0.0,
            SceneBuilder(),
        )
    }

    private companion object {
        const val SEED = 20260827uL
        const val MAP = 1
        const val VIEW_WIDTH = 260.0
        const val VIEW_HEIGHT = 150.0
    }
}
