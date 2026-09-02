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
    fun `ramen is a small fixed grounded bowl with noodles and two angled chopsticks`() {
        val (sim, at) = simulationWithRamen()

        val first = ramenSignature(frame(sim, 0.0))
        val later = ramenSignature(frame(sim, 0.9))

        assertEquals(first, later, "ramen hovered or animated")
        assertEquals(2, first.count { it.style == Scene.RAMEN_CHOPSTICK })
        assertTrue(first.count { it.style == Scene.RAMEN_NOODLE } >= 4)
        assertTrue(first.any { it.style == Scene.RAMEN_BOWL })
        assertTrue(first.any { it.style == Scene.RAMEN_WEAR })

        val left = first.minOf { minOf(it.x1, it.x2) - it.width / 2.0 }
        val right = first.maxOf { maxOf(it.x1, it.x2) + it.width / 2.0 }
        val top = first.minOf { minOf(it.y1, it.y2) - it.width / 2.0 }
        val bottom = first.maxOf { maxOf(it.y1, it.y2) + it.width / 2.0 }
        assertTrue(right - left <= 20.0, "ramen was ${right - left}px wide")
        assertTrue(bottom - top <= 18.0, "ramen was ${bottom - top}px tall")

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
        val CAMERA = Camera(0.0, 0.0, 560.0, 320.0)
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
