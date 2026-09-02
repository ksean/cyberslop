package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PROD-110 / P-88: fixed bowl geometry and presentation-only green feedback. */
class RamenSceneTest {
    @Test
    fun `ramen doubles its introduction geometry while staying fixed and grounded`() {
        val (sim, at) = simulationWithRamen()

        val first = ramenSignature(frame(sim, 0.0))
        val later = ramenSignature(frame(sim, 0.9))

        assertEquals(first, later, "ramen hovered or animated")
        assertSignatureEquals(INTRODUCTION_SIGNATURE, normaliseToIntroductionSize(first, at))
        assertEquals(2, first.count { it.style == Scene.RAMEN_CHOPSTICK })
        assertTrue(first.count { it.style == Scene.RAMEN_NOODLE } >= 4)
        assertTrue(first.any { it.style == Scene.RAMEN_BOWL })
        assertTrue(first.any { it.style == Scene.RAMEN_WEAR })

        val left = first.minOf { minOf(it.x1, it.x2) - it.width / 2.0 }
        val right = first.maxOf { maxOf(it.x1, it.x2) + it.width / 2.0 }
        val top = first.minOf { minOf(it.y1, it.y2) - it.width / 2.0 }
        val bottom = first.maxOf { maxOf(it.y1, it.y2) + it.width / 2.0 }
        assertEquals(37.5, right - left, absoluteTolerance = 1e-9)
        assertEquals(32.5, bottom - top, absoluteTolerance = 1e-9)
        assertTrue(right - left <= 40.0, "ramen was ${right - left}px wide")
        assertTrue(bottom - top <= 36.0, "ramen was ${bottom - top}px tall")

        val supportY = (at.y + TILE_SIZE / 2.0) * Scene.ZOOM
        assertEquals(supportY, bottom, absoluteTolerance = 1e-9, message = "the bowl floats above its support")
    }

    @Test
    fun `heal feedback flashes the figure green without recolouring its eye or weapon`() {
        val sim = simulationWithRamen().first
        val ordinary = frame(sim)
        val ordinaryTrim = trimSignature(ordinary)
        val ordinaryEyes = ordinary.batches
            .filter { it.layer == Layer.ActorGlow && it.style == Scene.PLAYER_EYE }
            .sumOf { it.size }

        sim.playerHealSecondsLeft = GameSimulation.HEAL_FLASH_SECONDS
        val healing = frame(sim)

        assertTrue(figureCount(healing, Palettes.HEAL) > 0)
        assertEquals(ordinaryEyes, healing.batches
            .filter { it.layer == Layer.ActorGlow && it.style == Scene.PLAYER_EYE }
            .sumOf { it.size })
        assertEquals(ordinaryTrim, trimSignature(healing), "the held weapon changed style")

        repeat((GameSimulation.HEAL_FLASH_SECONDS / TICK_SECONDS).toInt() + 1) {
            sim.tick(InputFrame())
        }
        assertEquals(0.0, sim.playerHealSecondsLeft)
        assertEquals(0, figureCount(frame(sim), Palettes.HEAL))
    }

    @Test
    fun `hurt feedback finishes before the complete green window`() {
        val sim = simulationWithRamen().first
        sim.playerHurtSecondsLeft = GameSimulation.HURT_FLASH_SECONDS
        sim.playerHealSecondsLeft = GameSimulation.HEAL_FLASH_SECONDS

        assertTrue(figureCount(frame(sim), Palettes.HURT) > 0)
        assertEquals(0, figureCount(frame(sim), Palettes.HEAL))

        val initialGreen = sim.playerHealSecondsLeft
        while (sim.playerHurtSecondsLeft > 0.0) {
            sim.tick(InputFrame())
            if (sim.playerHurtSecondsLeft > 0.0) {
                assertEquals(initialGreen, sim.playerHealSecondsLeft)
            }
        }

        assertEquals(initialGreen, sim.playerHealSecondsLeft)
        assertTrue(figureCount(frame(sim), Palettes.HEAL) > 0)
    }

    private data class Segment(
        val style: String,
        val width: Double,
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
    )

    private fun ramenSignature(frame: DrawList): List<Segment> = frame.batches
        .filter { it.style in RAMEN_STYLES }
        .flatMap { batch ->
            assertEquals(Primitive.Segment, batch.primitive)
            (0 until batch.size).map { index ->
                val offset = index * Primitive.Segment.stride
                Segment(
                    batch.style,
                    batch.width,
                    batch[offset],
                    batch[offset + 1],
                    batch[offset + 2],
                    batch[offset + 3],
                )
            }
        }

    private fun normaliseToIntroductionSize(segments: List<Segment>, at: Vec2): List<Segment> {
        val anchorX = at.x * Scene.ZOOM
        val supportY = (at.y + TILE_SIZE / 2.0) * Scene.ZOOM
        return segments.map { segment ->
            Segment(
                segment.style,
                segment.width / RAMEN_SCALE,
                (segment.x1 - anchorX) / RAMEN_SCALE,
                (segment.y1 - supportY) / RAMEN_SCALE,
                (segment.x2 - anchorX) / RAMEN_SCALE,
                (segment.y2 - supportY) / RAMEN_SCALE,
            )
        }
    }

    private fun assertSignatureEquals(expected: List<Segment>, actual: List<Segment>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (expectedSegment, actualSegment) ->
            assertEquals(expectedSegment.style, actualSegment.style, "segment $index style")
            assertEquals(expectedSegment.width, actualSegment.width, 1e-9, "segment $index width")
            assertEquals(expectedSegment.x1, actualSegment.x1, 1e-9, "segment $index x1")
            assertEquals(expectedSegment.y1, actualSegment.y1, 1e-9, "segment $index y1")
            assertEquals(expectedSegment.x2, actualSegment.x2, 1e-9, "segment $index x2")
            assertEquals(expectedSegment.y2, actualSegment.y2, 1e-9, "segment $index y2")
        }
    }

    private fun figureCount(frame: DrawList, style: String): Int = frame.batches
        .filter { it.layer in FIGURE_LAYERS && it.style == style }
        .sumOf { it.size }

    private fun trimSignature(frame: DrawList): List<List<Any>> = frame.batches
        .filter { it.layer == Layer.ActorTrim || it.layer == Layer.ActorWear }
        .filterNot { it.style == Palettes.HEAL || it.style == Palettes.HURT }
        .map { listOf(it.layer, it.style, it.primitive, it.width, it.size) }

    private fun frame(sim: GameSimulation, time: Double = 0.0): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(SEED, sim.level),
        HudModel.of(sim.run, sim.level.theme, 10, sim.boss.spec.name, sim.boss.healthFraction),
        time,
        SceneBuilder(),
    )

    private fun simulationWithRamen(): Pair<GameSimulation, Vec2> {
        val level = TestLevels.flat(mapIndex = MAP_INDEX)
        val run = RunState.begin(SEED).copy(mapIndex = MAP_INDEX)
        val sim = GameSimulation(level, run, SEED, optionalLoot = false)
        sim.enemies.clear()
        val at = Vec2(
            TileMap.toWorld(RAMEN_COLUMN) + TILE_SIZE / 2.0,
            TileMap.toWorld(TestLevels.FLOOR_ROW) + TILE_SIZE / 2.0,
        )
        sim.items += GroundItem(at, null, null, ramen = true)
        return sim to at
    }

    private companion object {
        val SEED = 0xB0A1uL
        const val MAP_INDEX = 2
        const val RAMEN_COLUMN = 8
        const val RAMEN_SCALE = 2.0
        val CAMERA = Camera(0.0, 0.0, 560.0, 320.0)
        val INTRODUCTION_SIGNATURE = listOf(
            Segment(Scene.RAMEN_OUTLINE, 2.0, -8.0, -7.0, 8.0, -7.0),
            Segment(Scene.RAMEN_OUTLINE, 2.0, -7.5, -6.5, -4.0, -1.0),
            Segment(Scene.RAMEN_OUTLINE, 2.0, 7.5, -6.5, 4.0, -1.0),
            Segment(Scene.RAMEN_OUTLINE, 2.0, -4.0, -1.0, 4.0, -1.0),
            Segment(Scene.RAMEN_BOWL, 1.5, -8.0, -7.0, 8.0, -7.0),
            Segment(Scene.RAMEN_BOWL, 1.5, -7.5, -6.5, -4.0, -1.0),
            Segment(Scene.RAMEN_BOWL, 1.5, 7.5, -6.5, 4.0, -1.0),
            Segment(Scene.RAMEN_BOWL, 1.5, -4.0, -1.0, 4.0, -1.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, -5.0, -7.0, -6.5, -9.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, -6.5, -9.0, -4.5, -11.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, -4.5, -11.0, -6.0, -13.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, -1.0, -7.0, 0.5, -9.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, 0.5, -9.0, -1.5, -11.0),
            Segment(Scene.RAMEN_NOODLE, 1.5, -1.5, -11.0, 0.0, -13.0),
            Segment(Scene.RAMEN_CHOPSTICK, 1.5, 2.0, -6.5, 7.0, -15.5),
            Segment(Scene.RAMEN_CHOPSTICK, 1.5, 4.0, -6.5, 9.0, -15.5),
            Segment(Scene.RAMEN_WEAR, 1.5, 3.5, -1.8, 6.4, -5.8),
        )
        val RAMEN_STYLES = setOf(
            Scene.RAMEN_OUTLINE,
            Scene.RAMEN_BOWL,
            Scene.RAMEN_WEAR,
            Scene.RAMEN_NOODLE,
            Scene.RAMEN_CHOPSTICK,
        )
        val FIGURE_LAYERS = setOf(
            Layer.ActorBehind,
            Layer.Actors,
            Layer.ActorHead,
            Layer.ActorFront,
            Layer.ActorTrim,
        )
    }
}
