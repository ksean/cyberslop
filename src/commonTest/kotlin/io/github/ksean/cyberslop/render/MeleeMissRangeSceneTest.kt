package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.HitIndicator
import io.github.ksean.cyberslop.sim.HitShape
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeleeMissRangeSceneTest {
    @Test
    fun `a melee miss draws four equal dashes and a spark at the exact reach`() {
        val sim = simulation()
        val origin = Vec2(32.0, 40.0)
        val direction = Vec2(0.6, 0.8)
        showMiss(sim, origin, direction)

        val frame = frameOf(sim)
        val segments = missSegments(frame)
        val screenOrigin = screen(origin)
        val ray = direction * (REACH * Scene.ZOOM)
        val endpoint = screenOrigin + ray

        assertEquals(DASHES, segments.size)
        repeat(DASHES) { index ->
            val start = screenOrigin + ray * (index * 2.0 / (DASHES * 2.0 - 1.0))
            val end = screenOrigin + ray * ((index * 2.0 + 1.0) / (DASHES * 2.0 - 1.0))
            assertTrue(segments.any { it.touches(start, end) }, "missing dash $index from $start to $end")
        }
        val sparks = missDots(frame)
        assertEquals(1, sparks.size)
        assertTrue(sparks.single().first.near(endpoint), "spark ${sparks.single().first} is not at $endpoint")
    }

    @Test
    fun `the miss trace fades and its batches do not grow with actor count`() {
        val sim = simulation()
        showMiss(sim, Vec2(32.0, 40.0), Vec2.Right)
        val fresh = frameOf(sim)
        val freshWidth = missSegmentBatches(fresh).single().width
        val sparseBatchCount = missBatches(fresh).size

        sim.lastHit = sim.lastHit!!.copy(secondsLeft = GameSimulation.FLASH_VISIBLE_SECONDS / 2.0)
        val fadedWidth = missSegmentBatches(frameOf(sim)).single().width
        assertTrue(fadedWidth < freshWidth, "the trace did not thin: $freshWidth then $fadedWidth")

        repeat(20) { TestLevels.enemyAt(sim, io.github.ksean.cyberslop.entity.EnemyArchetype.Turret, 20 + it) }
        assertEquals(sparseBatchCount, missBatches(frameOf(sim)).size)
    }

    private fun simulation(): GameSimulation = GameSimulation(
        TestLevels.flat(),
        RunState.begin(SEED),
        SEED,
    ).also { it.enemies.clear() }

    private fun showMiss(sim: GameSimulation, origin: Vec2, direction: Vec2) {
        sim.lastHit = HitIndicator(
            shape = HitShape.MeleeMiss(origin, direction, REACH),
            secondsLeft = GameSimulation.FLASH_VISIBLE_SECONDS,
            totalSeconds = GameSimulation.FLASH_VISIBLE_SECONDS,
        )
    }

    private fun frameOf(sim: GameSimulation): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim),
        timeSeconds = 0.0,
        builder = SceneBuilder(),
    )

    private fun missBatches(frame: DrawList): List<DrawBatch> = frame.batches.filter {
        it.layer == Layer.Effects && it.style == palette.missStyle &&
            (it.primitive == Primitive.Segment || it.primitive == Primitive.Dot)
    }

    private fun missSegmentBatches(frame: DrawList): List<DrawBatch> =
        missBatches(frame).filter { it.primitive == Primitive.Segment }

    private fun missSegments(frame: DrawList): List<Pair<Vec2, Vec2>> = missSegmentBatches(frame)
        .flatMap { batch ->
            (0 until batch.size).map { index ->
                val offset = index * Primitive.Segment.stride
                Vec2(batch[offset], batch[offset + 1]) to Vec2(batch[offset + 2], batch[offset + 3])
            }
        }

    private fun missDots(frame: DrawList): List<Pair<Vec2, Double>> = missBatches(frame)
        .filter { it.primitive == Primitive.Dot }
        .flatMap { batch ->
            (0 until batch.size).map { index ->
                val offset = index * Primitive.Dot.stride
                Vec2(batch[offset], batch[offset + 1]) to batch[offset + 2]
            }
        }

    private fun Pair<Vec2, Vec2>.touches(a: Vec2, b: Vec2): Boolean =
        (first.near(a) && second.near(b)) || (first.near(b) && second.near(a))

    private fun Vec2.near(other: Vec2): Boolean = (this - other).length < TOLERANCE

    private fun screen(world: Vec2): Vec2 = (world - Vec2(CAMERA.x, CAMERA.y)) * Scene.ZOOM

    private val palette get() = Palettes.of(TestLevels.flat().theme)
    private val Palette.missStyle get() = hazardGlow

    private companion object {
        val SEED = 0xA115uL
        val CAMERA = Camera(0.0, 0.0, 320.0, 180.0)
        const val REACH = 64.0
        const val DASHES = 4
        const val TOLERANCE = 1e-6
    }
}
