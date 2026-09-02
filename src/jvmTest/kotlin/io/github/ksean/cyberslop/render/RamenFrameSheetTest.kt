package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes the actual composed ramen and heal-feedback frames for human visual inspection. */
class RamenFrameSheetTest {
    @Test
    fun `grounded ramen and green player frames are written for inspection`() {
        val directory = File("build/icon-sheets/ramen").also { it.mkdirs() }
        val sim = simulation()

        writeFrame(directory.resolve("grounded.svg"), sim)
        sim.playerHealSecondsLeft = GameSimulation.HEAL_FLASH_SECONDS
        writeFrame(directory.resolve("healing.svg"), sim)

        directory.resolve("index.html").writeText(
            "<html><style>body{margin:0;background:#111;color:#eee;font:16px sans-serif;" +
                "display:grid;grid-template-columns:repeat(2,1fr)}figure{margin:8px}" +
                "img{width:100%;background:#05060a}figcaption{padding:4px}</style>" +
                "<figure><img src=\"grounded.svg\"><figcaption>Grounded ramen</figcaption></figure>" +
                "<figure><img src=\"healing.svg\"><figcaption>Green pickup feedback</figcaption></figure></html>",
        )

        assertTrue(directory.resolve("grounded.svg").length() > 0)
        assertTrue(directory.resolve("healing.svg").length() > 0)
    }

    private fun simulation(): GameSimulation {
        val level = TestLevels.flat(mapIndex = MAP_INDEX)
        val run = RunState.begin(SEED).copy(mapIndex = MAP_INDEX)
        return GameSimulation(level, run, SEED, optionalLoot = false).also { sim ->
            sim.enemies.clear()
            sim.items.clear()
            sim.items += GroundItem(
                Vec2(
                    TileMap.toWorld(TestLevels.SPAWN_COLUMN + 1) + TILE_SIZE / 2.0,
                    TileMap.toWorld(TestLevels.FLOOR_ROW) + TILE_SIZE / 2.0,
                ),
                null,
                null,
                ramen = true,
            )
        }
    }

    private fun writeFrame(file: File, sim: GameSimulation) {
        val camera = Camera(
            sim.player.x - MARGIN,
            sim.player.y - RISE,
            VIEW_WIDTH,
            VIEW_HEIGHT,
        )
        val frame = Scene.compose(
            sim,
            camera,
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            0.0,
            SceneBuilder(),
        )
        val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
        FramePainter.paint(frame, sink)
        file.writeText(sink.toSvg())
    }

    private companion object {
        val SEED = 0xB0A1uL
        const val MAP_INDEX = 2
        const val MARGIN = 24.0
        const val RISE = 46.0
        const val VIEW_WIDTH = 105.0
        const val VIEW_HEIGHT = 72.0
    }
}
