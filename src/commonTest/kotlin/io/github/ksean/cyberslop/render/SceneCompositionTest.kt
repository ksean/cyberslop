package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.GroundItem
import io.github.ksean.cyberslop.sim.LiveProjectile
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Barrel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ordered frame contract at Scene's composition boundary (ENG-014, ENG-025). */
class SceneCompositionTest {
    @Test
    fun `a representative frame keeps its ordered draw-list signature`() {
        val sim = representativeSimulation()
        val frame = Scene.compose(
            sim,
            Camera(0.0, 0.0, 560.0, 320.0),
            Backdrops.of(SEED, sim.level),
            HudModel.of(sim),
            timeSeconds = 0.25,
            builder = SceneBuilder(),
        )
        val signature = frame.batches
            .filter { it.size > 0 }
            .map { "${it.layer}:${it.primitive}:${it.size}" }

        assertEquals(EXPECTED_SIGNATURE.split("|"), signature)
    }

    private fun representativeSimulation(): GameSimulation {
        val level = TestLevels.flat(
            spikeColumns = 9..9,
            glassColumns = 10..10,
            barrels = listOf(Barrel(11, TestLevels.FLOOR_ROW)),
        )
        return TestLevels.simulation(level).also { sim ->
            sim.enemies.clear()
            TestLevels.enemyAt(sim, EnemyArchetype.Shooter, column = 12)
            sim.items.clear()
            sim.items += GroundItem.equipment(Vec2(150.0, 170.0), weapon = Weapons.of(WeaponId.ChromeFang))
            sim.items += GroundItem.ramen(Vec2(175.0, 170.0))
            sim.projectiles += LiveProjectile(
                position = Vec2(190.0, 150.0),
                velocity = Vec2(-200.0, 0.0),
                damage = 1.0,
                pierceLeft = 0,
                secondsLeft = 1.0,
                passesTerrain = false,
                fromPlayer = false,
            )
        }
    }

    private companion object {
        val SEED = TestLevels.SEED
        const val LEGACY_SIGNATURE =
            "Sky:Rect:1|Sky:Rect:1|BackdropFar:Rect:63|BackdropFar:Segment:61|BackdropFar:Rect:94|BackdropFar:Dot:9|BackdropMid:Rect:66|BackdropMid:Segment:112|BackdropMid:Rect:128|BackdropMid:Dot:21|BackdropNear:Rect:72|BackdropNear:Segment:172|BackdropNear:Rect:116|BackdropNear:Dot:34|Haze:Rect:1|Terrain:Rect:222|Terrain:Rect:222|Terrain:Rect:37|Terrain:Rect:2|Hazard:Rect:2|Hazard:Triangle:3|Hazard:Rect:2|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|Hazard:Segment:3|HazardSurface:Segment:5|HazardSurface:Dot:3|ItemHalo:Segment:18|ItemHalo:Segment:1|ItemHalo:Segment:2|ItemHalo:Dot:3|ItemHalo:Segment:4|Items:Segment:16|Items:Segment:1|Items:Segment:2|Items:Segment:2|Items:Dot:1|Items:Dot:2|Items:Segment:4|Items:Segment:6|Items:Segment:2|ItemWear:Segment:1|ItemWear:Segment:2|ItemWear:Segment:1|ActorBehind:Segment:4|ActorBehind:Segment:2|ActorBehind:Segment:6|ActorBehind:Segment:2|ActorBehind:Segment:4|ActorBehind:Segment:4|ActorBehind:Segment:2|Actors:Segment:1|Actors:Segment:2|Actors:Segment:1|ActorHead:Dot:1|ActorHead:Dot:2|ActorHead:Dot:1|ActorFront:Segment:3|ActorFront:Segment:4|ActorFront:Segment:1|ActorFront:Segment:2|ActorFront:Segment:2|ActorFront:Dot:1|ActorFront:Segment:4|ActorFront:Segment:1|ActorFront:Segment:1|ActorTrim:Rect:10|ActorTrim:Segment:5|ActorTrim:Dot:1|ActorTrim:Segment:2|ActorTrim:Segment:3|ActorTrim:Segment:2|ActorTrim:Segment:4|ActorTrim:Dot:1|ActorTrim:Segment:4|ActorTrim:Segment:1|ActorTrim:Segment:1|ActorGlow:Dot:1|ActorGlow:Dot:4|ActorGlow:Dot:1|ShotGlow:Segment:1|ShotGlow:Dot:1|ShotBody:Dot:1|ShotCore:Segment:1|ShotCore:Dot:1|Effects:Rect:2|Effects:Rect:2|Hud:Rect:1|Hud:Dot:1|Hud:Segment:4|Hud:Segment:1|Hud:Segment:1|HudOverlay:Rect:1|HudOverlay:Dot:1|HudOverlay:Segment:4|HudOverlay:Segment:1|HudOverlay:Segment:1"
        val EXPECTED_SIGNATURE = LEGACY_SIGNATURE
            .replace(
                "Sky:Rect:1|Sky:Rect:1|BackdropFar",
                "Sky:Rect:1|Sky:Rect:1|BackdropDistant:Segment:18|BackdropDistant:Rect:9|" +
                    "BackdropDistant:Triangle:8|BackdropDistant:Segment:12|" +
                    "BackdropDistant:Segment:96|BackdropDistant:Dot:2|BackdropFar",
            )
            .replace(
                "Terrain:Rect:2|Hazard:Rect:2|Hazard:Triangle:3|Hazard:Rect:2|Hazard:Segment",
                "Terrain:Rect:2|Hazard:Rect:3|Hazard:Triangle:3|Hazard:Rect:1|Hazard:Segment",
            )
    }
}
