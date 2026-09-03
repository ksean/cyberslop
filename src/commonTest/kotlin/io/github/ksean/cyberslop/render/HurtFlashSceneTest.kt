package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-47: a hurt enemy or boss is drawn red on every figure batch; a damaged enemy shows a health bar. */
class HurtFlashSceneTest {
    @Test
    fun `a hurt enemy of every form is drawn in the hurt style and its eye is not`() {
        listOf(EnemyArchetype.Swarm, EnemyArchetype.Flyer, EnemyArchetype.Turret).forEach { archetype ->
            val sim = simulation()
            val enemy = enemyAt(sim, archetype)
            // The bosses are drawn too, in their own styles; only the enemy's primitives may move.
            val own = ownStyled(frame(sim))
            assertEquals(0, hurtStyled(frame(sim)), "something was red before the hit")
            enemy.hurtSecondsLeft = 0.1
            val hurt = hurtStyled(frame(sim))
            assertTrue(hurt > 0, "a hurt $archetype drew nothing in the hurt style")
            assertEquals(own - hurt, ownStyled(frame(sim)), "a hurt $archetype's figure was not wholly in the hurt style")
            val eyes = frame(sim).batches.filter { it.layer == Layer.ActorGlow && it.size > 0 }
            assertTrue(eyes.none { it.style == Palettes.HURT }, "the $archetype's eye flashed")
            enemy.hurtSecondsLeft = 0.0
            assertEquals(0, hurtStyled(frame(sim)), "the $archetype stayed red after the flash")
        }
    }

    @Test
    fun `a hurt boss flashes unless it is telegraphing`() {
        val sim = simulation(bossArena = Arena(6, 22, TestLevels.FLOOR_ROW + 1))
        sim.boss.fight.engage()
        val palette = Palettes.of(sim.level.theme)
        val crownStyle = palette.glow[palette.glow.size - 1]
        fun crowns() = frame(sim).batches.filter { it.layer == Layer.ActorTrim && it.style == crownStyle }.sumOf { it.size }
        assertTrue(crowns() > 0, "fixture: no crown drawn")
        sim.boss.hurtSecondsLeft = 0.1
        sim.miniboss.hurtSecondsLeft = 0.1
        assertTrue(frame(sim).batches.any { it.layer == Layer.Actors && it.style == Palettes.HURT && it.size > 0 }, "a hurt boss did not flash")
        assertEquals(0, crowns(), "a hurt boss's crown kept its glow")

        while (!sim.boss.telegraphing) sim.tick(io.github.ksean.cyberslop.physics.InputFrame())
        sim.boss.hurtSecondsLeft = 0.1
        val telegraphing = frame(sim).batches.filter { it.layer == Layer.Actors && it.size > 0 }
        assertTrue(telegraphing.any { it.style == palette.hazardGlow }, "the telegraph colour was lost")
        assertTrue(telegraphing.none { it.style == Palettes.HURT }, "the flash hid the telegraph")
    }

    @Test
    fun `a hurt player flashes red while the eye and held weapon keep their styles`() {
        val sim = simulation()
        val normal = frame(sim)
        assertEquals(0, hurtStyled(normal))
        val normalWeapon = actorTrimSignature(normal)
        val normalEyes = normal.batches
            .filter { it.layer == Layer.ActorGlow && it.style == Scene.PLAYER_EYE }
            .sumOf { it.size }

        sim.playerHurtSecondsLeft = 0.1
        val hurt = frame(sim)

        assertTrue(hurtStyled(hurt) > 0, "the hurt player did not flash")
        assertEquals(normalEyes, hurt.batches
            .filter { it.layer == Layer.ActorGlow && it.style == Scene.PLAYER_EYE }
            .sumOf { it.size })
        assertEquals(normalWeapon, actorTrimSignature(hurt), "the held weapon changed style")

        sim.playerHurtSecondsLeft = 0.0
        assertEquals(0, hurtStyled(frame(sim)))
    }

    @Test
    fun `an enemy below full health shows a bar and one at full health does not`() {
        val sim = simulation()
        val enemy = enemyAt(sim, EnemyArchetype.Brute)
        val full = rects(frame(sim))
        enemy.health = enemy.maxHealth * 0.4
        val damaged = rects(frame(sim))
        assertEquals(full.size + 2, damaged.size, "a damaged enemy did not add exactly a back and a fill rect")
        val added = damaged.filter { it !in full }
        val width = LiveEnemy.BODY_SIZE * Scene.ZOOM
        assertTrue(added.any { it[2] == width }, "no back rect of $width: $added")
        assertTrue(added.any { kotlin.math.abs(it[2] - width * 0.4) < 1e-9 }, "no fill rect of 40 %: $added")
        val ground = (enemy.position.y + LiveEnemy.FEET_OFFSET) * Scene.ZOOM
        assertTrue(added.all { it[1] < ground - EnemyLooks.of(enemy.archetype, sim.level.mapIndex).height * Scene.ZOOM }, "the bar is not above the figure")
    }

    /** ENG-061: the flash and the bars are style swaps and shared batches, so their cost cannot grow with the crowd. */
    @Test
    fun `six hundred hurt and damaged enemies open the same batches as ten`() {
        val sim = simulation()
        repeat(10) { enemyAt(sim, EnemyArchetype.entries[it % EnemyArchetype.entries.size]) }
        // Half hurt, half not, so both kinds of batch are open — the worst case, not the tidiest.
        fun rough() = sim.enemies.forEachIndexed { i, e -> if (i % 2 == 0) e.hurtSecondsLeft = 0.1; e.health = e.maxHealth * 0.5 }
        rough()
        val few = frame(sim).batches.size
        repeat(590) { enemyAt(sim, EnemyArchetype.entries[it % EnemyArchetype.entries.size]) }
        rough()
        assertEquals(few, frame(sim).batches.size)
    }

    private fun hurtStyled(frame: DrawList) =
        frame.batches.filter { it.layer in FIGURE_LAYERS && it.style == Palettes.HURT }.sumOf { it.size }

    private fun ownStyled(frame: DrawList) =
        frame.batches.filter { it.layer in FIGURE_LAYERS && it.style in OWN_STYLES }.sumOf { it.size }

    private fun actorTrimSignature(frame: DrawList): List<List<Any>> = frame.batches
        .filter { it.layer == Layer.ActorTrim || it.layer == Layer.ActorWear }
        .map { listOf(it.layer, it.style, it.primitive, it.width, it.size) }

    /** Rects on the effects layer, each as (x, y, w, h). */
    private fun rects(frame: DrawList): List<List<Double>> =
        frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Rect }
            .flatMap { b -> (0 until b.size).map { i -> (0 until 4).map { b[i * 4 + it] } } }

    private fun frame(sim: GameSimulation) =
        Scene.compose(sim, Camera(0.0, 0.0, 560.0, 320.0), Backdrops.of(SEED, sim.level), HudModel.of(sim.run, sim.level.theme, 10, sim.boss.spec.name, sim.boss.healthFraction), 0.0, SceneBuilder())

    private fun simulation(bossArena: Arena = Arena(100, 114, TestLevels.FLOOR_ROW + 1)): GameSimulation {
        val sim = GameSimulation(TestLevels.flat(bossArena = bossArena), RunState.begin(SEED), SEED)
        sim.enemies.clear()
        return sim
    }

    /** Inside the view, so it is drawn rather than culled. */
    private fun enemyAt(sim: GameSimulation, archetype: EnemyArchetype): LiveEnemy {
        val x = sim.player.x + 20.0 * (1 + sim.enemies.size % 20)
        val enemy = LiveEnemy(archetype, Vec2(x, sim.player.y), archetype.healthOn(sim.level.mapIndex), x, 0.0)
        sim.enemies.add(enemy)
        return enemy
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        val FIGURE_LAYERS = setOf(Layer.ActorBehind, Layer.Actors, Layer.ActorHead, Layer.ActorFront, Layer.ActorTrim)
        val OWN_STYLES = setOf(Palettes.ENEMY_BODY, Palettes.ENEMY_PLATE, Palettes.ENEMY_DARK)
    }
}
