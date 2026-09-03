package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.CombatBodies
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.TestLevels
import kotlin.test.Test
import kotlin.test.assertTrue

class CombatBodyRenderEnvelopeTest {
    @Test
    fun `every rank and file damaging body primitive fits its combat disc`() {
        for (mapIndex in listOf(1, 10)) for (archetype in EnemyArchetype.entries) {
            val sim = simulation(mapIndex)
            val enemy = TestLevels.enemyAt(sim, archetype, column = 20)
            val centre = screen(enemy.centre)

            assertBodyInside(frameOf(sim), centre, CombatBodies.ENEMY_RADIUS * Scene.ZOOM, "$mapIndex $archetype")
        }
    }

    @Test
    fun `mini and main boss damaging body primitives fit their combat discs`() {
        val sim = simulation(mapIndex = 10)
        assertBodyInside(
            frameOf(sim),
            screen(sim.miniboss.centre),
            CombatBodies.MINIBOSS_RADIUS * Scene.ZOOM,
            "miniboss",
            CORE_BODY_LAYERS,
        )
        assertBossPoseInside(sim, sim.miniboss, CombatBodies.MINIBOSS_RADIUS, "miniboss")
        assertBodyInside(
            frameOf(sim),
            screen(sim.boss.centre),
            CombatBodies.BOSS_RADIUS * Scene.ZOOM,
            "main boss",
            CORE_BODY_LAYERS,
        )
        assertBossPoseInside(sim, sim.boss, CombatBodies.BOSS_RADIUS, "main boss")
    }

    private fun assertBodyInside(
        frame: DrawList,
        centre: Vec2,
        radius: Double,
        label: String,
        bodyLayers: Set<Layer> = ALL_BODY_LAYERS,
    ) {
        var inspected = 0
        frame.batches.filter { it.layer in bodyLayers }.forEach { batch ->
            for (n in 0 until batch.size) {
                val i = n * batch.primitive.stride
                val bounds = when (batch.primitive) {
                    Primitive.Segment -> listOf(
                        Vec2(batch[i], batch[i + 1]) to batch.width / 2.0,
                        Vec2(batch[i + 2], batch[i + 3]) to batch.width / 2.0,
                    )

                    Primitive.Rect -> {
                        val left = batch[i]
                        val top = batch[i + 1]
                        val right = left + batch[i + 2]
                        val bottom = top + batch[i + 3]
                        listOf(
                            Vec2(left, top) to 0.0,
                            Vec2(right, top) to 0.0,
                            Vec2(left, bottom) to 0.0,
                            Vec2(right, bottom) to 0.0,
                        )
                    }

                    Primitive.Dot -> listOf(Vec2(batch[i], batch[i + 1]) to batch[i + 2])
                }
                if (bounds.none { (point, extent) -> (point - centre).length <= radius * 2.0 + extent }) continue
                inspected++
                bounds.forEach { (point, extent) ->
                    assertTrue(
                        (point - centre).length + extent <= radius + 1e-6,
                        "$label ${batch.layer}/${batch.primitive} escaped its combat body: $point around $centre",
                    )
                }
            }
        }
        assertTrue(inspected > 0, "$label fixture found no body primitives")
    }

    private fun assertBossPoseInside(
        sim: GameSimulation,
        boss: io.github.ksean.cyberslop.sim.LiveBoss,
        radius: Double,
        label: String,
    ) {
        val pose = Actor.pose(Scene.bossMotion(sim, boss))
        val combatCentre = Vec2(0.0, -boss.height / 2.0)
        val points = listOf(
            pose.hip, pose.neck, pose.head,
            pose.leadShoulder, pose.leadElbow, pose.leadHand,
            pose.rearShoulder, pose.rearElbow, pose.rearHand,
            pose.leadKnee, pose.leadFoot, pose.rearKnee, pose.rearFoot,
        )
        points.forEach { point ->
            assertTrue(
                (point - combatCentre).length <= radius,
                "$label posed body point $point escaped its combat body"
            )
        }
        assertTrue(
            (pose.head - combatCentre).length + pose.headRadius *
                EnemyLooks.boss(sim.level.mapIndex, boss === sim.boss).headScale <= radius,
            "$label head escaped its combat body",
        )
    }

    private fun simulation(mapIndex: Int): GameSimulation {
        val seed = 0xB0D1uL
        return GameSimulation(
            TestLevels.flat(mapIndex = mapIndex),
            RunState.begin(seed).copy(mapIndex = mapIndex),
            seed
        )
            .also { it.enemies.clear() }
    }

    private fun frameOf(sim: GameSimulation): DrawList = Scene.compose(
        sim,
        CAMERA,
        Backdrops.of(0xB0D1uL, sim.level),
        HudModel.of(sim.run, sim.level.theme, 10, sim.boss.spec.name, sim.boss.healthFraction),
        0.0,
        SceneBuilder(),
    )

    private fun screen(world: Vec2): Vec2 = world * Scene.ZOOM

    private companion object {
        val CAMERA = Camera(0.0, 0.0, 2_000.0, 500.0)
        val ALL_BODY_LAYERS = setOf(
            Layer.ActorBehind,
            Layer.Actors,
            Layer.ActorHead,
            Layer.ActorFront,
            Layer.ActorTrim,
        )
        val CORE_BODY_LAYERS = setOf(Layer.ActorBehind, Layer.Actors, Layer.ActorHead)
    }
}
