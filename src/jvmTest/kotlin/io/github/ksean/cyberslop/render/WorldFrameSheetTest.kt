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
import io.github.ksean.cyberslop.sim.LiveBoss
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Arena
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

    @Test
    fun `early middle and late mini and main boss frames are written for inspection`() {
        val directory = File("build/icon-sheets/boss-profiles").also { it.mkdirs() }
        val cards = mutableListOf<String>()

        for (map in BOSS_MAPS) for (main in listOf(false, true)) {
            val seed = BOSS_SEED + map.toULong()
            val level = TestLevels.flat(
                mapIndex = map,
                bossArena = Arena(10, 30, TestLevels.FLOOR_ROW + 1),
            )
            val sim = GameSimulation(level, RunState.begin(seed), seed)
            sim.enemies.clear()
            val live = if (main) sim.boss else sim.miniboss
            live.fight.engage()
            if (main) live.fight.damage(live.spec.maxHealth * 0.5)
            live.placeAt(Vec2(sim.player.x + BOSS_OFFSET, sim.player.y + PLAYER_HEIGHT))
            live.restSecondsLeft = 0.0
            advanceToEvent(sim, live)

            val camera = Camera(
                sim.player.x - BOSS_MARGIN,
                sim.player.y + PLAYER_HEIGHT - BOSS_RISE,
                BOSS_VIEW_WIDTH,
                BOSS_VIEW_HEIGHT,
            )
            val frame = Scene.compose(
                sim,
                camera,
                Backdrops.of(seed, level),
                HudModel.of(sim.run, level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction),
                0.0,
                SceneBuilder(),
            )
            val sink = SvgPaintSink(BOSS_VIEW_WIDTH * Scene.ZOOM, BOSS_VIEW_HEIGHT * Scene.ZOOM, "#05060a")
            FramePainter.paint(frame, sink)
            val rank = if (main) "main" else "mini"
            val attack = live.currentAttack?.module?.name ?: error("no active attack")
            val file = directory.resolve("map-$map-$rank-$attack.svg")
            file.writeText(sink.toSvg())
            assertTrue(file.length() > 0, "no $rank boss frame was written for map $map")
            assertTrue(frame.batches.any { it.layer == Layer.ActorTrim && it.size > 0 }, "map $map $rank has no hardware")
            assertTrue(
                frame.batches.any { it.layer in ATTACK_LAYERS && it.size > 0 },
                "map $map $rank has no active attack effect",
            )
            cards += "<figure><img src=\"${file.name}\"><figcaption>Map $map $rank — ${live.spec.profile.modules.joinToString()} — $attack</figcaption></figure>"
        }

        directory.resolve("index.html").writeText(
            "<html><style>body{background:#111;color:#eee;font:16px sans-serif;display:grid;grid-template-columns:repeat(2,1fr)}" +
                "figure{margin:12px}img{width:100%;background:#05060a}figcaption{padding:6px}</style>" +
                cards.joinToString("") + "</html>",
        )
    }

    private fun advanceToEvent(sim: GameSimulation, live: LiveBoss) {
        repeat(BOSS_TICKS) {
            sim.tick(InputFrame())
            if (live.events.isNotEmpty()) return
        }
        error("${live.spec.name} produced no attack event")
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
        const val PLAYER_HEIGHT = 26.0
        const val BOSS_OFFSET = 72.0
        const val BOSS_MARGIN = 35.0
        const val BOSS_RISE = 105.0
        const val BOSS_VIEW_WIDTH = 190.0
        const val BOSS_VIEW_HEIGHT = 130.0
        const val BOSS_TICKS = 4_000
        val BOSS_SEED = 0xB055uL
        val BOSS_MAPS = listOf(1, 5, 10)
        val ATTACK_LAYERS = setOf(Layer.ShotGlow, Layer.ShotBody, Layer.ShotCore, Layer.Effects)

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
