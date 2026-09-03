package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.TrigTable
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.ActiveMeleeSwing
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.TestLevels
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MeleeSwingFrameSheetTest {
    @Test
    fun `opening boundary and entered target frames are written for inspection`() {
        val directory = File("build/icon-sheets/melee-swing").also { it.mkdirs() }
        val cards = SCENARIOS.map { scenario ->
            val sim = simulation(scenario.elapsedSeconds, scenario.hit)
            val origin = sim.activeSwing!!.origin
            val camera = Camera(origin.x - 58.0, origin.y - 62.0, VIEW_WIDTH, VIEW_HEIGHT)
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
            val file = directory.resolve("${scenario.name}.svg")
            file.writeText(sink.toSvg())

            assertTrue(file.length() > 0)
            assertTrue(frame.batches.any { it.layer == Layer.Effects && it.primitive == Primitive.Segment })
            "<figure><img src=\"${file.name}\"><figcaption>${scenario.caption}</figcaption></figure>"
        }
        directory.resolve("index.html").writeText(
            "<html><style>body{margin:0;background:#111;color:#eee;font:16px sans-serif;" +
                "display:grid;grid-template-columns:repeat(3,1fr)}figure{margin:8px}" +
                "img{width:100%;background:#05060a}figcaption{padding:6px}</style>" +
                cards.joinToString("") + "</html>",
        )
    }

    private fun simulation(elapsed: Double, hit: Boolean): GameSimulation {
        val sim = GameSimulation(TestLevels.flat(), RunState.begin(SEED), SEED)
        sim.enemies.clear()
        val origin = sim.player.centre(Physics.Default)
        val targetCentre = origin + TrigTable.rotate(Vec2.Right, TARGET_DEGREES) * TARGET_DISTANCE
        sim.enemies += LiveEnemy(
            EnemyArchetype.Brute,
            targetCentre - Vec2(LiveEnemy.BODY_HALF, LiveEnemy.BODY_HALF),
            health = 1_000.0,
            homeX = targetCentre.x - LiveEnemy.BODY_HALF,
            patrolPx = 0.0,
        ).also { if (hit) it.hurtSecondsLeft = GameSimulation.HURT_FLASH_SECONDS }
        sim.activeSwing = ActiveMeleeSwing(
            origin,
            Vec2.Right,
            arcDegrees = 80.0,
            reachPx = 48.0,
            elapsedSeconds = elapsed,
            totalSeconds = 0.10,
            weapon = sim.autoFire.weapon,
        )
        return sim
    }

    private data class Scenario(
        val name: String,
        val caption: String,
        val elapsedSeconds: Double,
        val hit: Boolean,
    )

    private companion object {
        val SEED = 0x5A337uL
        const val VIEW_WIDTH = 132.0
        const val VIEW_HEIGHT = 96.0
        const val TARGET_DEGREES = 25.0
        const val TARGET_DISTANCE = 50.0
        val SCENARIOS = listOf(
            Scenario("opening", "Opening: target outside the trailing boundary", 0.0, false),
            Scenario("entry", "Midpoint: target body enters and is hit", 0.05, true),
            Scenario("complete", "Complete: closed fan matches the full sector", 0.10, true),
        )
    }
}
