package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.sim.DeathSequence
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.PlayerDamageSource
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.TileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlayerDeathPresentationTest {
    @Test
    fun `the lethal pose starts exact collapses with fixed limbs and holds prone after two seconds`() {
        val sim = acidDeath()
        val lethalPose = Actor.pose(Scene.motionOf(sim))

        assertEquals(lethalPose, Scene.playerPose(sim))

        repeat(DeathSequence.COLLAPSE_TICKS / 2) { sim.tick(InputFrame()) }
        val halfway = Scene.playerPose(sim)
        assertNotEquals(lethalPose, halfway)
        assertFixedLimbLengths(lethalPose, halfway)
        assertTrue(halfway.neck.y > lethalPose.neck.y, "the torso did not fall")

        repeat(DeathSequence.COLLAPSE_TICKS / 2) { sim.tick(InputFrame()) }
        val prone = Scene.playerPose(sim)
        assertFixedLimbLengths(lethalPose, prone)
        assertTrue(abs(prone.neck.y - prone.hip.y) < lethalPose.height * 0.08, "torso is not prone")
        assertEquals(Action.None, prone.action)

        repeat(30) { sim.tick(InputFrame()) }
        assertEquals(prone, Scene.playerPose(sim), "the prone pose did not hold")
    }

    @Test
    fun `poison flame and bleed use distinct animated actor-status geometry`() {
        val cases = listOf(
            PlayerDamageSource.Acid to setOf(Scene.POISON_OUTER, Scene.POISON_CORE),
            PlayerDamageSource.Fire to setOf(Scene.BURN_OUTER, Scene.BURN_CORE),
            PlayerDamageSource.Laser to setOf(Scene.BURN_OUTER, Scene.BURN_CORE),
            PlayerDamageSource.Spike to setOf(Scene.BLEED),
            PlayerDamageSource.Projectile to setOf(Scene.BLEED),
            PlayerDamageSource.Melee to setOf(Scene.BLEED),
        )
        cases.forEach { (cause, styles) ->
            val sim = terminal(cause, elapsedTicks = 20)
            val first = statusBatches(frame(sim))
            assertEquals(styles, first.map { it.style }.toSet(), cause.name)
            assertTrue(first.all { it.size > 0 }, "${cause.name} opened an empty status batch")

            sim.deathSequence = DeathSequence(cause, elapsedTicks = 35)
            val later = statusBatches(frame(sim))
            assertNotEquals(first.map(::coordinates), later.map(::coordinates), "${cause.name} did not animate")
            assertEquals(first.size, later.size, "${cause.name} changed its batch count")
        }
    }

    @Test
    fun `neutral deaths draw no cause effect and the camera follow stays locked`() {
        listOf(PlayerDamageSource.Void, PlayerDamageSource.Contact).forEach { cause ->
            assertTrue(statusBatches(frame(terminal(cause, 30))).isEmpty(), cause.name)
        }

        val sim = acidDeath()
        val follow = Scene.drawnFollow(sim, 1.0)
        repeat(DeathSequence.TOTAL_TICKS - 1) { sim.tick(InputFrame(right = true)) }
        assertEquals(follow, Scene.drawnFollow(sim, 1.0))
    }

    private fun acidDeath(): GameSimulation {
        val level = TestLevels.flat().also {
            it.tiles[TestLevels.SPAWN_COLUMN, TestLevels.FLOOR_ROW] = TileKind.Acid
        }
        return TestLevels.simulation(level).also { it.tick(InputFrame()) }
    }

    private fun terminal(cause: PlayerDamageSource, elapsedTicks: Int): GameSimulation =
        TestLevels.simulation().also { it.deathSequence = DeathSequence(cause, elapsedTicks) }

    private fun frame(sim: GameSimulation): DrawList = Scene.compose(
        sim = sim,
        camera = Camera(0.0, 0.0, 560.0, 320.0),
        backdrop = Backdrops.of(SEED, sim.level),
        hud = HudModel.of(sim),
        timeSeconds = sim.presentationTimeSeconds,
        builder = SceneBuilder(),
    )

    private fun statusBatches(frame: DrawList): List<DrawBatch> =
        frame.batches.filter { it.layer == Layer.ActorStatus }

    private fun coordinates(batch: DrawBatch): List<Double> =
        List(batch.size * batch.primitive.stride) { batch[it] }

    private fun assertFixedLimbLengths(expected: Pose, actual: Pose) {
        val expectedBones = boneLengths(expected)
        val actualBones = boneLengths(actual)
        expectedBones.zip(actualBones).forEachIndexed { index, (before, after) ->
            assertEquals(before, after, 1e-6, "bone $index changed length")
        }
    }

    private fun boneLengths(pose: Pose): List<Double> = listOf(
        pose.leadShoulder to pose.leadElbow,
        pose.leadElbow to pose.leadHand,
        pose.rearShoulder to pose.rearElbow,
        pose.rearElbow to pose.rearHand,
        pose.hip to pose.leadKnee,
        pose.leadKnee to pose.leadFoot,
        pose.hip to pose.rearKnee,
        pose.rearKnee to pose.rearFoot,
    ).map { (a, b) -> (a - b).length }

    private companion object {
        val SEED = 0xDEA7uL
    }
}
