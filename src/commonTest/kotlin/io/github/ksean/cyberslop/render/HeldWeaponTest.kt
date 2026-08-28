package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The weapon in the player's hand is the weapon that was on the floor (PROD-049, property 28).
 *
 * This is the clause that makes a drop *teach* something. If the shotgun on the ground and the
 * shotgun being carried are two different drawings, the player learns nothing by picking one up, and
 * PROD-044's whole reason — that contact is irrevocable under PROD-030, so the decision to walk into
 * a drop is the only decision offered — is not served.
 */
class HeldWeaponTest {
    @Test
    fun `the held weapon is the same geometry as its drop`() {
        WEAPONS.forEach { id ->
            val icon = WeaponIcons.of(id)
            val held = heldStrokes(id)

            assertEquals(
                icon.strokes.size * PASSES,
                held.size,
                "$id draws ${held.size} strokes in the hand for an icon of ${icon.strokes.size}, " +
                    "so the hand is not drawing the drop's geometry",
            )
            // Not just the count: the shape. Every stroke's length, as a multiset, must match the
            // icon's own up to the one scale the placement applies.
            val scale = held.first().length() / lengthOf(icon.strokes.first())
            assertTrue(scale > 0.0, "$id was drawn at zero size")
            val expected = icon.strokes.map { lengthOf(it) * scale }.sorted()
            val actual = held.map { it.length() }.sorted()
            expected.indices.forEach { index ->
                assertTrue(
                    abs(actual[index * PASSES] - expected[index]) < TOLERANCE,
                    "$id's held stroke ${actual[index * PASSES]} is not its drop's ${expected[index]}",
                )
            }
        }
    }

    /**
     * Aiming is automatic (PROD-022), so the held weapon is the only thing that tells the player
     * what the game has locked onto — including above and below, which is where change 0005 found
     * the enemy barrel throwing away everything but the sign of its aim.
     */
    @Test
    fun `the held weapon turns with the aim`() {
        val level = LevelGenerator.generate(SEED, MAP).level

        // Aim is automatic (PROD-022), so it is steered the way the game steers it: by putting
        // something to shoot at. Writing `aimDirection` would test a field rather than the game.
        val drawn = listOf(0.0, -TARGET_OFFSET, TARGET_OFFSET).map { above ->
            val sim = GameSimulation(level, RunState.begin(SEED), SEED)
            sim.enemies.clear()
            val target = io.github.ksean.cyberslop.sim.LiveEnemy(
                archetype = io.github.ksean.cyberslop.entity.EnemyArchetype.Swarm,
                position = Vec2(sim.player.x + TARGET_AHEAD, sim.player.y + above),
                health = LOTS,
                homeX = sim.player.x + TARGET_AHEAD,
                patrolPx = 0.0,
            )
            sim.enemies.add(target)
            // Long enough for the opening swing to stop being drawn, holding the target still so
            // the aim does not drift. **A swing overrides the pose's aim with the arm's direction**
            // — correctly, since a swinging weapon follows the arm — and the first three versions of
            // this test measured that arm and called it the aim.
            repeat(SETTLE) {
                target.position = Vec2(sim.player.x + TARGET_AHEAD, sim.player.y + above)
                sim.tick(io.github.ksean.cyberslop.physics.InputFrame())
            }
            assertEquals(
                Action.None,
                Actor.pose(Scene.motionOf(sim)).action,
                "the figure is mid-action, so its arm rather than its aim decides where the " +
                    "weapon points",
            )
            val strokes = heldStrokesOf(sim)
            assertTrue(strokes.isNotEmpty(), "nothing was drawn in the hand aiming at $above")
            sim.aimDirection to strokes
        }

        // The three setups must actually have produced three aims, or the comparison below is
        // comparing one thing with itself.
        val aims = drawn.map { it.first.y }
        assertTrue(
            aims[UP] < aims[LEVEL] - AIM_APART && aims[DOWN] > aims[LEVEL] + AIM_APART,
            "the three targets did not produce three aims: $aims",
        )

        // Compared between the three cases rather than against the screen. An absolute extent is
        // not evidence: an action overlay moves the arm, and the first version of this test failed
        // by 2.8 px on exactly that. A dot product against the frame's origin is worse still — it
        // measures where the camera happens to be.
        val centre = drawn.map { (_, strokes) ->
            strokes.sumOf { (it.y1 + it.y2) / 2.0 } / strokes.size
        }

        assertTrue(
            centre[UP] < centre[LEVEL] - MOVED,
            "aiming up did not raise the weapon: ${centre[UP]} against ${centre[LEVEL]}",
        )
        assertTrue(
            centre[DOWN] > centre[LEVEL] + MOVED,
            "aiming down did not lower the weapon: ${centre[DOWN]} against ${centre[LEVEL]}",
        )
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun lengthOf(stroke: IconOp.Stroke): Double {
        val dx = stroke.x2 - stroke.x1
        val dy = stroke.y2 - stroke.y1
        return sqrt(dx * dx + dy * dy)
    }

    private class Placed(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
        fun length(): Double = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
    }

    private fun heldStrokes(id: WeaponId): List<Placed> {
        val level = LevelGenerator.generate(SEED, MAP).level
        var run = RunState.begin(SEED)
        run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(id)))
        return heldStrokesOf(GameSimulation(level, run, SEED))
    }

    /** The two icon passes on the actor's own layer, which only the held weapon draws. */
    private fun heldStrokesOf(sim: GameSimulation): List<Placed> {
        val frame = Scene.compose(
            sim,
            Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT),
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim.run, sim.level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction),
            0.0,
            SceneBuilder(),
        )
        return frame.batches
            .filter {
                (it.layer == Layer.ActorFront || it.layer == Layer.ActorTrim) &&
                    it.primitive == Primitive.Segment &&
                    (it.style == IconStyles.WEAPON_OUTLINE || it.style == IconStyles.HALO)
            }
            .flatMap { batch ->
                (0 until batch.size).map { index ->
                    val at = index * Primitive.Segment.stride
                    Placed(batch[at], batch[at + 1], batch[at + 2], batch[at + 3])
                }
            }
    }

    private companion object {
        const val SEED = 20260827uL
        const val MAP = 1
        const val MAPS = 10
        const val VIEW_WIDTH = 260.0
        const val VIEW_HEIGHT = 150.0
        const val TOLERANCE = 1e-6
        const val AIM_APART = 0.2
        const val MOVED = 6.0
        const val SETTLE = 24
        const val LOTS = 100000.0

        /** Halo and outline. */
        const val PASSES = 2

        val WEAPONS = listOf(
            WeaponId.BrokenBottle,
            WeaponId.RiotbreakerShotgun,
            WeaponId.MeatgrinderHalo,
            WeaponId.VoiceOfTheDeadNet,
        )

        /**
         * Well outside the broken bottle's 1.6-tile reach, and well inside auto-aim's 22 tiles.
         *
         * At 30 px the player swung at the target every tick, and a swing sets the pose's aim from
         * the *arm*, not from the weapon's aim (`Actor.pose`) — correctly, since a swinging weapon
         * follows the arm. The test was measuring the arm and calling it the aim.
         */
        const val TARGET_AHEAD = 90.0
        const val TARGET_OFFSET = 90.0
        const val LEVEL = 0
        const val UP = 1
        const val DOWN = 2
    }
}
