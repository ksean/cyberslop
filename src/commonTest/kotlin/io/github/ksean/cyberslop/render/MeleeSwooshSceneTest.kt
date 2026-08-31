package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.MeleeSector
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.ActiveMeleeSwing
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeleeSwooshSceneTest {
    @Test
    fun `opening midpoint and final frames draw the exact cumulative closed sector`() {
        listOf(0.0, 0.05, 0.10).forEach { elapsed ->
            val sim = simulation(elapsed)
            val swing = sim.activeSwing!!
            val sector = swing.sector
            val segments = fanSegments(frameOf(sim))
            val origin = screen(sector.origin)
            val trailing = origin + sector.trailingDirection * (sector.reachPx * Scene.ZOOM)
            val leading = origin + sector.leadingDirection * (sector.reachPx * Scene.ZOOM)

            assertTrue(segments.any { it.touches(origin, trailing) }, "no trailing boundary at $elapsed")
            assertTrue(segments.any { it.touches(origin, leading) }, "no leading boundary at $elapsed")
            assertTrue(segments.flatMap { listOf(it.first, it.second) }.all { point ->
                sector.contains(world(point))
            }, "a fan stroke escaped the active sector at $elapsed")

            val outer = segments.flatMap { listOf(it.first, it.second) }
                .maxOf { (it - origin).length }
            assertEquals(sector.reachPx * Scene.ZOOM, outer, 1e-6)
        }
    }

    @Test
    fun `the held arm and weapon follow the active sector leading angle`() {
        val sim = simulation(elapsed = 0.05)
        val sector = sim.activeSwing!!.sector
        val pose = Actor.pose(Scene.motionOf(sim))
        val dot = pose.weaponAim.x * sector.leadingDirection.x + pose.weaponAim.y * sector.leadingDirection.y

        assertEquals(1.0, dot, 1e-6)
    }

    @Test
    fun `fan batches are constant and disappear with active gameplay state`() {
        val empty = simulation(elapsed = 0.05)
        val emptyCount = fanBatches(frameOf(empty))
        repeat(20) { TestLevels.enemyAt(empty, io.github.ksean.cyberslop.entity.EnemyArchetype.Turret, 20 + it) }
        assertEquals(emptyCount, fanBatches(frameOf(empty)))

        empty.activeSwing = null
        assertEquals(0, fanSegments(frameOf(empty)).size)
    }

    private fun simulation(elapsed: Double): GameSimulation {
        val sim = GameSimulation(TestLevels.flat(), RunState.begin(SEED), SEED)
        sim.tick(InputFrame())
        val fired = sim.activeSwing!!
        sim.activeSwing = ActiveMeleeSwing(
            origin = fired.origin,
            direction = Vec2.Right,
            arcDegrees = 80.0,
            reachPx = 40.0,
            elapsedSeconds = elapsed,
            totalSeconds = 0.10,
            weapon = fired.weapon,
        )
        sim.enemies.clear()
        return sim
    }

    private fun frameOf(sim: GameSimulation): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim.run, sim.level.theme, 10, sim.boss.spec.name, sim.boss.healthFraction),
        timeSeconds = 0.0,
        builder = SceneBuilder(),
    )

    private fun fanBatches(frame: DrawList): Int = frame.batches.count {
        it.layer == Layer.Effects && it.style == Palettes.of(TestLevels.flat().theme).hazardGlow &&
            it.primitive == Primitive.Segment
    }

    private fun fanSegments(frame: DrawList): List<Pair<Vec2, Vec2>> = frame.batches
        .filter {
            it.layer == Layer.Effects && it.style == Palettes.of(TestLevels.flat().theme).hazardGlow &&
                it.primitive == Primitive.Segment
        }
        .flatMap { batch ->
            (0 until batch.size).map { n ->
                val i = n * Primitive.Segment.stride
                Vec2(batch[i], batch[i + 1]) to Vec2(batch[i + 2], batch[i + 3])
            }
        }

    private fun Pair<Vec2, Vec2>.touches(a: Vec2, b: Vec2): Boolean =
        (first.near(a) && second.near(b)) || (first.near(b) && second.near(a))

    private fun Vec2.near(other: Vec2): Boolean = (this - other).length < 1e-6
    private fun screen(world: Vec2): Vec2 = (world - Vec2(CAMERA.x, CAMERA.y)) * Scene.ZOOM
    private fun world(screen: Vec2): Vec2 = Vec2(screen.x / Scene.ZOOM + CAMERA.x, screen.y / Scene.ZOOM + CAMERA.y)

    private companion object {
        val SEED = 0x5A005uL
        val CAMERA = Camera(0.0, 0.0, 320.0, 180.0)
    }
}
