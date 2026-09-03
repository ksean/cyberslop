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
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.ThemeId
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
    fun `filled spikes and themed barrels are written for all ten maps`() {
        val directory = File("build/icon-sheets/themed-hazards").also { it.mkdirs() }
        val cards = mutableListOf<String>()

        ThemeId.entries.forEachIndexed { index, theme ->
            val map = index + 1
            val level = TestLevels.flat(
                spikeColumns = HAZARD_SPIKES,
                barrels = listOf(Barrel(HAZARD_BARREL_COLUMN, TestLevels.FLOOR_ROW)),
                mapIndex = map,
                theme = theme,
            )
            val run = RunState.begin(HAZARD_SEED).copy(mapIndex = map)
            val sim = GameSimulation(level, run, HAZARD_SEED)
            val frame = Scene.compose(
                sim,
                HAZARD_CAMERA,
                Backdrops.of(HAZARD_SEED, level),
                HudModel.of(sim),
                0.0,
                SceneBuilder(),
            )
            val sink = SvgPaintSink(
                HAZARD_CAMERA.viewWidth * Scene.ZOOM,
                HAZARD_CAMERA.viewHeight * Scene.ZOOM,
                "#05060a",
            )
            FramePainter.paint(frame, sink)
            val file = directory.resolve("map-$map.svg")
            file.writeText(sink.toSvg())

            assertTrue(file.length() > 0, "no hazard frame was written for $theme")
            assertTrue(
                frame.batches.any { it.layer == Layer.Hazard && it.primitive == Primitive.Triangle },
                "$theme has no filled spike blades",
            )
            cards += "<figure><img src=\"${file.name}\"><figcaption>Map $map — ${theme.displayName}</figcaption></figure>"
        }

        directory.resolve("index.html").writeText(
            "<html><style>body{margin:0;background:#05060a;color:#eee;font:16px sans-serif;" +
                "display:grid;grid-template-columns:repeat(2,1fr)}figure{margin:8px}" +
                "img{width:100%;background:#05060a}figcaption{padding:4px 0}</style>" +
                cards.joinToString("") + "</html>",
        )
    }

    @Test
    fun `all ten cyberpunk backdrops are written for inspection`() {
        val directory = File("build/icon-sheets/backdrops").also { it.mkdirs() }
        val cards = mutableListOf<String>()

        ThemeId.entries.forEachIndexed { index, theme ->
            val map = index + 1
            val seed = SEED + map.toULong()
            val level = LevelGenerator.generate(seed, map).level
            val sim = GameSimulation(level, RunState.begin(seed).copy(mapIndex = map), seed)
            val camera = Camera(
                sim.player.x - BACKDROP_MARGIN,
                sim.player.y - BACKDROP_RISE,
                BACKDROP_VIEW_WIDTH,
                BACKDROP_VIEW_HEIGHT,
            )
            val frame = Scene.compose(
                sim,
                camera,
                Backdrops.of(seed, level),
                HudModel.of(sim),
                0.0,
                SceneBuilder(),
            )
            val sink = SvgPaintSink(
                BACKDROP_VIEW_WIDTH * Scene.ZOOM,
                BACKDROP_VIEW_HEIGHT * Scene.ZOOM,
                "#05060a",
            )
            FramePainter.paint(frame, sink)
            val file = directory.resolve("map-$map.svg")
            file.writeText(sink.toSvg())

            assertTrue(file.length() > 0, "no backdrop frame was written for $theme")
            assertTrue(
                frame.batches.any {
                    it.layer in BACKDROP_LAYERS && it.primitive == Primitive.Segment && it.size > 0
                },
                "$theme drew no structural backdrop detail",
            )
            cards += "<figure><img src=\"${file.name}\"><figcaption>Map $map — ${theme.displayName}</figcaption></figure>"
        }

        directory.resolve("index.html").writeText(
            "<html><style>body{margin:0;background:#05060a;color:#eee;font:16px sans-serif;" +
                "display:grid;grid-template-columns:repeat(2,1fr)}figure{margin:8px}" +
                "img{width:100%;background:#05060a}figcaption{padding:4px 0}</style>" +
                cards.joinToString("") + "</html>",
        )
    }

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
                GroundItem.equipment(
                    Vec2(sim.player.x + FIRST + index * PITCH, ground),
                    weapon = Weapons.of(id),
                ),
            )
        }
        LOOT.forEachIndexed { index, id ->
            sim.items.add(
                GroundItem.equipment(
                    position = Vec2(sim.player.x + FIRST + (DROPS.size + index) * PITCH, ground),
                    powerup = Powerups.of(id),
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
            (0 until 5).all { tier ->
                frame.batches.any { it.layer == Layer.Items && it.style == IconStyles.weaponRing(tier) }
            },
            "the frame does not show every weapon-tier ring",
        )
    }

    @Test
    fun `a close world frame with broken glass is written for inspection`() {
        val glass = (TestLevels.SPAWN_COLUMN + 2)..(TestLevels.SPAWN_COLUMN + 3)
        val level = TestLevels.flat(glassColumns = glass)
        val sim = TestLevels.simulation(level)
        val camera = Camera(
            sim.player.x - GLASS_MARGIN,
            sim.player.y - GLASS_RISE,
            GLASS_VIEW_WIDTH,
            GLASS_VIEW_HEIGHT,
        )
        val frame = Scene.compose(
            sim,
            camera,
            Backdrops.of(SEED, level),
            HudModel.of(sim),
            0.0,
            SceneBuilder(),
        )
        val sink = SvgPaintSink(
            GLASS_VIEW_WIDTH * Scene.ZOOM,
            GLASS_VIEW_HEIGHT * Scene.ZOOM,
            "#05060a",
        )
        FramePainter.paint(frame, sink)
        val out = File("build/icon-sheets").also { it.mkdirs() }.resolve("broken-glass.svg")
        out.writeText(sink.toSvg())

        assertTrue(out.length() > 0, "no broken-glass frame was written")
        assertTrue(
            frame.batches.count { it.layer == Layer.HazardSurface } == 2,
            "broken glass did not retain its two constant-batch surface layers",
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
        const val BACKDROP_MARGIN = 25.0
        const val BACKDROP_RISE = 105.0
        const val BACKDROP_VIEW_WIDTH = 230.0
        const val BACKDROP_VIEW_HEIGHT = 130.0
        const val GLASS_MARGIN = 30.0
        const val GLASS_RISE = 42.0
        const val GLASS_VIEW_WIDTH = 120.0
        const val GLASS_VIEW_HEIGHT = 80.0
        const val HAZARD_BARREL_COLUMN = 10
        val HAZARD_SPIKES = 6..8
        val HAZARD_SEED = 0xA2A2DuL
        val HAZARD_CAMERA = Camera(32.0, 180.0, 160.0, 130.0)
        val BOSS_SEED = 0xB055uL
        val BOSS_MAPS = listOf(1, 5, 10)
        val ATTACK_LAYERS = setOf(Layer.ShotGlow, Layer.ShotBody, Layer.ShotCore, Layer.Effects)
        val BACKDROP_LAYERS = setOf(Layer.BackdropFar, Layer.BackdropMid, Layer.BackdropNear)

        val DROPS = listOf(
            WeaponId.BrokenBottle,
            WeaponId.CorpoRiotBaton,
            WeaponId.StaticLash,
            WeaponId.SableCorpRailgun,
            WeaponId.VoiceOfTheDeadNet,
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
