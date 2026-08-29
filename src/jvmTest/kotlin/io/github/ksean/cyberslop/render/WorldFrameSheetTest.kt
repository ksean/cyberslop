package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes a real composed frame, with drops on the ground, for a person to look at.
 *
 * The icon sheet answers "is it the thing" against a flat background. This answers the question the
 * sheet cannot: whether a drop is still readable lying on terrain, at the size the game draws it,
 * beside a player and a HUD — which is where `specs/presentation.md` records six defects being found, and no
 * test finding any of them.
 */
class WorldFrameSheetTest {
    @Test
    fun `a frame with drops on the ground is written for the owner to look at`() {
        val level = LevelGenerator.generate(SEED, MAP).level
        var run = RunState.begin(SEED)
        run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(WeaponId.RiotbreakerShotgun)))
        BUILD.forEach { run = run.copy(loadout = run.loadout.collect(it, MAP).first) }
        val sim = GameSimulation(level, run, SEED)
        repeat(SETTLE) { sim.tick(InputFrame()) }

        val ground = sim.player.y
        sim.items.clear()
        DROPS.forEachIndexed { index, id ->
            sim.items.add(
                GroundItem(Vec2(sim.player.x + FIRST + index * PITCH, ground), Weapons.of(id), null),
            )
        }
        LOOT.forEachIndexed { index, id ->
            sim.items.add(
                GroundItem(
                    Vec2(sim.player.x + FIRST + (DROPS.size + index) * PITCH, ground),
                    null,
                    Powerups.of(id),
                ),
            )
        }

        val camera = Camera(sim.player.x - MARGIN, ground - RISE, VIEW_WIDTH, VIEW_HEIGHT)
        val frame = Scene.compose(
            sim,
            camera,
            Backdrops.of(SEED, level),
            HudModel.of(sim.run, level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction),
            0.0,
            SceneBuilder(),
        )

        val sink = SvgPaintSink(VIEW_WIDTH * Scene.ZOOM, VIEW_HEIGHT * Scene.ZOOM, "#05060a")
        FramePainter.paint(frame, sink)
        val out = File("build/icon-sheets").also { it.mkdirs() }.resolve("world-frame.svg")
        out.writeText(sink.toSvg())

        assertTrue(out.length() > 0, "no frame was written")
        assertTrue(
            frame.batches.any { it.layer == Layer.Items && it.style == IconStyles.WEAPON_RING },
            "the frame drew no weapon drop, so the sheet shows nothing worth looking at",
        )
    }

    private companion object {
        const val SEED = 20260827uL
        const val MAP = 1
        const val MAPS = 10
        const val SETTLE = 30
        const val VIEW_WIDTH = 260.0
        const val VIEW_HEIGHT = 150.0
        const val FIRST = 20.0
        const val PITCH = 22.0
        const val MARGIN = 40.0
        const val RISE = 90.0

        val DROPS = listOf(
            WeaponId.BrokenBottle,
            WeaponId.RiotbreakerShotgun,
            WeaponId.KillSwitchKatana,
            WeaponId.SableCorpRailgun,
        )
        val BUILD = listOf(
            PowerupId.OverclockCoil,
            PowerupId.SeekerDaemon,
            PowerupId.BurnRig,
        )
        val LOOT = listOf(
            PowerupId.OverclockCoil,
            PowerupId.ChillProtocol,
            PowerupId.KillstreakCache,
        )
    }
}
