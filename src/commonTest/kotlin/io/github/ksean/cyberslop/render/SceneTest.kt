package io.github.ksean.cyberslop.render

import io.github.ksean.cyberslop.combat.Anchor
import io.github.ksean.cyberslop.combat.WeaponClass
import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.gen.LevelGenerator
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.sim.GameSimulation
import io.github.ksean.cyberslop.sim.LiveEnemy
import io.github.ksean.cyberslop.sim.MuzzleFlash
import io.github.ksean.cyberslop.sim.SwingVisual
import io.github.ksean.cyberslop.sim.TestLevels
import io.github.ksean.cyberslop.world.Barrel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENG-061 over the real frame, not over the builder in isolation.
 *
 * `specs/presentation.md` measured per-sprite `save`/`translate`/`rotate`/`restore` at 21% of the frame
 * budget at 600 entities, and the alternative it recommended was never measured. This is the
 * structural answer: the number of style assignments a frame costs cannot grow with what is in it.
 *
 * Stated honestly, and the same way `specs/presentation.md` states it: a batch count bounds **state changes**, which
 * is the expensive part that table measured. It does not bound rasterization, and the full-frame
 * measurement is still owed.
 */
class SceneTest {
    @Test
    fun `six hundred entities cost no more drawing state than ten`() {
        val builder = SceneBuilder()
        val sim = simulation()
        val camera = camera()
        val backdrop = backdrop(sim)

        // The same mix of archetypes at both sizes. ENG-061 bounds the cost by the number of
        // *kinds* of thing on screen, not by the count — a frame that contains a kind the previous
        // one did not legitimately opens that kind's batch, and the bound itself is asserted below.
        trimTo(sim, 0)
        growTo(sim, FEW)
        val sparse = Scene.compose(sim, camera, backdrop, hudOf(sim), 0.0, builder).batches.size

        growTo(sim, MANY)
        val crowded = Scene.compose(sim, camera, backdrop, hudOf(sim), 0.0, builder)

        assertEquals(
            sparse,
            crowded.batches.size,
            "drawing $MANY entities costs more style assignments than drawing $FEW, so the " +
                "frame's cost grows with the scene",
        )
        assertTrue(
            crowded.batches.sumOf { it.size } > sparse * MANY / FEW,
            "the crowded frame did not actually draw more; the test proves nothing",
        )
    }

    /**
     * ENG-061 over what a sink is actually handed, not over a number defined to be one.
     *
     * Round one found the renderer breaking its stroke path inside a batch — 45, 279 and 1,579
     * `beginPath`/`stroke` pairs at 10, 100 and 600 entities while the batch count sat at 34. Round
     * two found the replacement for that test assigning every batch a cost of one *by definition*
     * and never touching a renderer at all, so moving `stroke()` back inside the loop would have
     * left it green. The traversal now lives in `FramePainter`, and this counts the calls it makes.
     */
    @Test
    fun `drawing state handed to a sink does not grow with the scene`() {
        val sparse = CountingSink().also { FramePainter.paint(frameWith(FEW), it) }
        val crowded = CountingSink().also { FramePainter.paint(frameWith(MANY), it) }

        assertEquals(
            sparse.stateChanges,
            crowded.stateChanges,
            "drawing $MANY entities issued ${crowded.stateChanges} state changes against " +
                "${sparse.stateChanges} for $FEW",
        )
        assertTrue(
            crowded.primitives > sparse.primitives * 3,
            "the crowded frame drew ${crowded.primitives} primitives against ${sparse.primitives}" +
                " — not enough more for the comparison to mean anything",
        )
        assertEquals(
            sparse.widths.size,
            crowded.widths.size,
            "the crowded frame used more distinct stroke widths, so its paths break more often",
        )
    }

    /** One call per batch, and never a batch handed over twice. */
    @Test
    fun `a sink is handed each batch exactly once, in layer order`() {
        val frame = frameWith(MANY)
        val sink = CountingSink()

        FramePainter.paint(frame, sink)

        assertEquals(frame.batches.size, sink.batches, "a batch was skipped or issued twice")
        assertEquals(frame.texts.size, sink.texts, "a label was skipped or issued twice")
        assertEquals(
            frame.batches.map { it.layer },
            sink.layers,
            "batches were issued out of layer order, so they paint over each other wrongly",
        )
    }

    private class CountingSink : PaintSink {
        var stateChanges = 0
            private set
        var batches = 0
            private set
        var primitives = 0
            private set
        var texts = 0
            private set
        val widths = mutableSetOf<Double>()
        val layers = mutableListOf<Layer>()

        private companion object {
            /**
             * What each call really costs the canvas. Charging one per batch described the frame as
             * cheaper than it is — a stroke sets style, width and cap — and the point of this
             * number is that it be the true one, not that it be small.
             */
            const val FILL_STATE_CHANGES = 1
            const val STROKE_STATE_CHANGES = 3

            /** `fillStyle`, `font`, `textAlign`. */
            const val TEXT_STATE_CHANGES = 3
        }

        override fun fillRects(style: String, batch: DrawBatch) = record(batch, FILL_STATE_CHANGES)

        override fun strokeSegments(style: String, width: Double, batch: DrawBatch) {
            widths.add(width)
            record(batch, STROKE_STATE_CHANGES)
        }

        override fun fillDots(style: String, batch: DrawBatch) = record(batch, FILL_STATE_CHANGES)

        /**
         * A label costs three state changes — fill style, font, alignment — and they are counted.
         * Ignoring them let the frame's total be described as "one per batch" when it was not.
         * Text does not grow with entity count, so ENG-061 still holds; the number just has to be
         * the real one.
         */
        override fun drawText(item: TextItem) {
            texts++
            stateChanges += TEXT_STATE_CHANGES
        }

        private fun record(batch: DrawBatch, cost: Int) {
            stateChanges += cost
            batches++
            primitives += batch.size
            layers.add(batch.layer)
        }
    }

    private fun frameWith(entities: Int): DrawList {
        val sim = simulation()
        trimTo(sim, 0)
        growTo(sim, entities)
        return Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
    }

    @Test
    fun `no segment batch mixes stroke widths`() {
        val sim = simulation()
        growTo(sim, MANY)
        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

        val segments = frame.batches.filter { it.primitive == Primitive.Segment }
        assertTrue(segments.isNotEmpty(), "nothing was stroked, so the check proves nothing")
        assertTrue(
            segments.all { it.width > 0.0 },
            "a segment batch has no stroke width, so the renderer would draw a hairline",
        )
        assertTrue(
            segments.map { it.width }.distinct().size <= Scene.strokeLadderSize,
            "segment widths are not snapped to the ladder, so their count is unbounded",
        )
    }

    @Test
    fun `a frame is bounded by a handful of batches`() {
        val sim = simulation()
        growTo(sim, MANY)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

        assertTrue(
            frame.batches.size <= MAX_BATCHES,
            "a frame opened ${frame.batches.size} batches, over the $MAX_BATCHES this design " +
                "claims: ${frame.batches.map { "${it.style}/${it.primitive}" }}",
        )
    }

    @Test
    fun `every archetype on a map still shares its batches`() {
        val sim = simulation()
        trimTo(sim, 0)
        // One of each, so nothing about the mix can open a batch per enemy.
        EnemyArchetype.entries.forEach { archetype -> sim.enemies.add(enemy(sim, archetype)) }
        val withOne = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

        repeat(20) { EnemyArchetype.entries.forEach { sim.enemies.add(enemy(sim, it)) } }
        val withMany = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

        assertEquals(
            withOne.batches.size,
            withMany.batches.size,
            "enemy appearance opens a batch per enemy, which is what a continuous luminance " +
                "would do (ENG-061)",
        )
    }

    @Test
    fun `the frame is composed from the simulation without a clock`() {
        val sim = simulation()
        val camera = camera()
        val backdrop = backdrop(sim)

        val once = Scene.compose(sim, camera, backdrop, hudOf(sim), 1.5, SceneBuilder())
        val again = Scene.compose(sim, camera, backdrop, hudOf(sim), 1.5, SceneBuilder())

        assertEquals(once.batches.size, again.batches.size)
        assertEquals(
            once.batches.map { it.size },
            again.batches.map { it.size },
            "the same tick composed two different frames, so something outside the simulation " +
                "is being read (ENG-062)",
        )
    }

    @Test
    fun `the player is posed from the simulation`() {
        val sim = simulation()
        repeat(30) { sim.tick(InputFrame(right = true)) }

        val motion = Scene.motionOf(sim)

        assertEquals(sim.player.vx, motion.speedX, "the rig is not reading the player's velocity")
        assertEquals(sim.playerStridePx, motion.stridePx, "the gait is not reading distance walked")
        assertTrue(motion.stridePx > 0.0, "walking right for half a second advanced no stride")
        assertEquals(Clip.Run, Actor.clipOf(motion), "a player walking right is not running")
    }

    /**
     * The loop interpolates between ticks, and the camera's target is interpolated. Drawing the
     * figure at the raw tick position instead slid it against a camera that had already moved,
     * which reads as the player vibrating whenever the frame did not land on a tick boundary.
     */
    @Test
    fun `the player is drawn where the camera thinks it is`() {
        val sim = simulation()
        repeat(40) { sim.tick(InputFrame(right = true)) }
        assertTrue(
            sim.player.x - sim.previousPlayer.x > 1.0,
            "the player did not move this tick, so interpolation cannot be observed",
        )

        val camera = camera()
        val backdrop = backdrop(sim)
        val start = playerFeetX(Scene.compose(sim, camera, backdrop, hudOf(sim), 0.0, SceneBuilder(), 0.0))
        val half = playerFeetX(Scene.compose(sim, camera, backdrop, hudOf(sim), 0.0, SceneBuilder(), 0.5))
        val end = playerFeetX(Scene.compose(sim, camera, backdrop, hudOf(sim), 0.0, SceneBuilder(), 1.0))

        assertTrue(start < half && half < end, "the figure does not move with alpha at all")
        assertEquals(
            (start + end) / 2.0,
            half,
            absoluteTolerance = 1e-6,
            message = "half a tick did not draw the figure halfway between the two ticks",
        )
    }

    /** The spine, which only the player draws in the player's own body colour. */
    private fun playerFeetX(frame: DrawList): Double {
        val spine = frame.batches.first {
            it.style == Scene.PLAYER_BODY && it.primitive == Primitive.Segment
        }
        return spine[0]
    }

    /**
     * The interpolation must survive a stance change, which the horizontal-only version did not.
     *
     * Crouching re-anchors the player's `y` by the difference between the two stance heights, so
     * interpolating the top-left corner and then adding the *new* height threw the figure a whole
     * stance height off the floor for the frames either side of a crouch.
     */
    @Test
    fun `crouching does not launch the figure off the floor`() {
        val sim = simulation()
        // Settle, then crouch: the tick that changes stance is the one that used to break.
        repeat(20) { sim.tick(InputFrame()) }
        sim.tick(InputFrame(crouch = true))
        assertEquals(Stance.Crouch, sim.player.stance, "the player did not crouch")
        assertTrue(
            sim.player.y != sim.previousPlayer.y,
            "the stance change did not move the box's corner, so nothing is being tested",
        )

        val feet = (0..4).map { step ->
            val alpha = step / 4.0
            playerFeetY(
                Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder(), alpha),
            )
        }

        val spread = feet.max() - feet.min()
        assertTrue(
            spread < ONE_PIXEL,
            "the figure's feet move $spread screen pixels across a crouch that never left the " +
                "ground: $feet",
        )
    }

    /**
     * The camera, which the crouch test above could not see because it uses a fixed one.
     *
     * Round three fixed the figure and left the camera interpolating the stance-dependent corner;
     * round six moved it to the body's centre, which still subtracts the current stance height. Both
     * were recorded as complete. What the camera follows must not move at all when only the stance
     * does.
     */
    @Test
    fun `crouching does not move the camera`() {
        val sim = simulation()
        repeat(20) { sim.tick(InputFrame()) }
        val standing = Scene.drawnFollow(sim, 1.0)

        sim.tick(InputFrame(crouch = true))
        assertEquals(Stance.Crouch, sim.player.stance, "the player did not crouch")

        val across = (0..4).map { Scene.drawnFollow(sim, it / 4.0).y }
        assertTrue(
            across.max() - across.min() < TINY,
            "the camera's target moves ${across.max() - across.min()} world px across a crouch",
        )
        assertEquals(
            standing.y,
            across.last(),
            absoluteTolerance = TINY,
            message = "the crouch left the camera somewhere other than where standing had it",
        )
    }

    /** The lowest point of the player's own legs. */
    private fun playerFeetY(frame: DrawList): Double {
        val legs = frame.batches.filter {
            it.style == Scene.PLAYER_LIMB && it.primitive == Primitive.Segment
        }
        return legs.flatMap { batch ->
            (0 until batch.size).flatMap { n ->
                listOf(batch[n * Primitive.Segment.stride + 1], batch[n * Primitive.Segment.stride + 3])
            }
        }.max()
    }

    /**
     * The animation's action window must be the simulation's, not a second constant beside it.
     * Written independently they drifted at once: the arm's sweep ran 0.18 s against a swing the
     * simulation stopped drawing at 0.16, so the arm snapped back at 89% of its arc every time.
     */
    @Test
    fun `an action lasts exactly as long as the thing it depicts`() {
        val sim = simulation(WeaponId.RustlineMachete)
        while (sim.lastSwing == null) sim.tick(InputFrame())

        val swing = sim.lastSwing!!
        val motion = Scene.motionOf(sim)

        assertEquals(
            swing.totalSeconds,
            motion.swingSeconds,
            "the arm sweeps over a different window than the swing is drawn for",
        )
        assertEquals(Action.Swing, Actor.actionOf(motion))

        // One tick before the simulation drops it, the arm is still swinging; after, it is not.
        val nearlyDone = motion.copy(secondsSinceSwing = swing.totalSeconds - 0.001)
        val done = motion.copy(secondsSinceSwing = swing.totalSeconds + 0.001)
        assertEquals(Action.Swing, Actor.actionOf(nearlyDone))
        assertEquals(Action.None, Actor.actionOf(done))
    }

    /**
     * `specs/presentation.md` promises a shooter that tracks the player and a turret whose head sweeps. A
     * review round found neither implemented: both were drawn along their patrol facing, and a
     * turret never moves, so its barrel pointed one way forever while it shot the player behind it.
     */
    @Test
    fun `an armed enemy turns to face what it is shooting at`() {
        val sim = simulation()
        trimTo(sim, 0)
        val behind = sim.player.x - CLOSE
        sim.enemies.add(facingAway(sim, EnemyArchetype.Shooter, behind))
        sim.enemies.add(facingAway(sim, EnemyArchetype.Turret, behind))

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val barrel = frame.batches.first {
            it.style == Palettes.ENEMY_PLATE && it.primitive == Primitive.Segment
        }

        // The barrel runs from the head outward; the player is to its right, so must the barrel be.
        assertTrue(
            barrel[2] > barrel[0],
            "the turret's barrel points away from a player standing in its firing range",
        )
    }

    @Test
    fun `a shooter firing upward aims its weapon upward`() {
        val sim = simulation()
        trimTo(sim, 0)
        // Beside the player and well below it, so tracking has a real vertical component.
        val below = LiveEnemy(
            EnemyArchetype.Shooter,
            Vec2(sim.player.x + CLOSE, sim.player.y + CLOSE * 2.0),
            EnemyArchetype.Shooter.healthOn(1), sim.player.x + CLOSE, 0.0,
        ).also { it.facing = 1 }
        sim.enemies.add(below)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val held = frame.batches.first {
            it.layer == Layer.ActorFront && it.primitive == Primitive.Segment
        }

        // The lead arm and the barrel share a style and a width, so they share a batch: reading the
        // first primitive reads the *arm*, and a barrel left horizontal would pass. Every segment
        // in the batch has to rise, and the last of them is the barrel.
        assertTrue(held.size >= 3, "expected two arm segments and a barrel, got ${held.size}")
        for (n in 0 until held.size) {
            val i = n * Primitive.Segment.stride
            assertTrue(
                held[i + 3] < held[i + 1],
                "segment $n of the weapon arm points level or down while the enemy shoots at " +
                    "something above it",
            )
        }
    }

    /** Roles are layers, so what paints over what cannot depend on which enemy came first. */
    @Test
    fun `a head never paints over its own eye`() {
        val sim = simulation()
        trimTo(sim, 0)
        // The Flyer first: it opens a glow batch before any biped's head batch exists.
        sim.enemies.add(facingAway(sim, EnemyArchetype.Flyer, sim.player.x + CLOSE))
        sim.enemies.add(facingAway(sim, EnemyArchetype.Brute, sim.player.x + CLOSE * 2.0))

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val order = frame.batches.map { it.layer }

        // Every part role, not just the eye: a Flyer's pod could paint over a biped's head while
        // both sat on one actor layer, which is what the first version of this test missed.
        val drawn = ROLE_STACK.filter { it in order }
        assertTrue(drawn.size >= 4, "too few part roles were drawn to prove an ordering: $drawn")
        assertEquals(
            drawn.map { role -> order.indexOfFirst { it == role } }.sorted(),
            drawn.map { role -> order.indexOfFirst { it == role } },
            "actor parts are painted out of role order, so a part can fall behind one it sits " +
                "inside: $order",
        )
    }

    /**
     * The role order itself, which is a design decision and not something a test can derive.
     *
     * The first version of this took its expected order from `Layer.entries`, which made it
     * self-referential: reordering the enum reordered the expectation too, so it could only ever
     * catch a *sorting* failure and never a wrong order. Found by mutation — moving `ActorTrim`
     * after `ActorGlow`, letting a crown paint over the eye it frames, left it green.
     */
    @Test
    fun `the actor role stack is in the order the parts sit in`() {
        assertEquals(
            ROLE_STACK,
            Layer.entries.filter { it in ROLE_STACK },
            "the actor layers are declared in a different order than the parts stack up in",
        )
    }

    @Test
    fun `an unarmed enemy keeps walking its patrol`() {
        val sim = simulation()
        trimTo(sim, 0)
        sim.enemies.add(facingAway(sim, EnemyArchetype.Brute, sim.player.x - CLOSE))

        val motionless = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

        assertTrue(
            motionless.batches.any { it.primitive == Primitive.Segment },
            "the brute was not drawn at all",
        )
        // Nothing to assert about aim: a brute has no weapon, so it must not be turned by one.
        assertEquals(
            -1,
            sim.enemies.first().facing,
            "drawing the enemy changed the simulation's own facing (ENG-062)",
        )
    }

    @Test
    fun `an armed enemy out of range keeps its patrol facing`() {
        val sim = simulation()
        trimTo(sim, 0)
        val faraway = sim.player.x + GameSimulation.SHOOTER_RANGE * 4.0
        sim.enemies.add(facingAway(sim, EnemyArchetype.Turret, faraway))

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val barrel = frame.batches.firstOrNull {
            it.style == Palettes.ENEMY_PLATE && it.primitive == Primitive.Segment
        }

        assertTrue(barrel != null, "the distant turret was not drawn")
        assertTrue(
            barrel!![2] < barrel[0],
            "a turret out of firing range is still tracking the player",
        )
    }

    /** Placed to the player's side and pointing the other way, so tracking has to visibly turn it. */
    private fun facingAway(sim: GameSimulation, archetype: EnemyArchetype, x: Double): LiveEnemy =
        LiveEnemy(
            archetype = archetype,
            position = Vec2(x, sim.player.y),
            health = archetype.healthOn(sim.level.mapIndex),
            homeX = x,
            patrolPx = 0.0,
        ).also { it.facing = -1 }

    /**
     * The player's own weapon arm, which R7 round five fixed and round six found untested: the
     * upward-aim test inspects an *enemy*, so reverting either half of the player path left it
     * green.
     *
     * Aiming takes no input (PROD-022). The held weapon is the only thing that can tell a player
     * what the game has locked onto, so an arm pointing along the walk while shots leave on another
     * bearing is the animation failing at the one job it has here.
     */
    @Test
    fun `the player's weapon follows what the game is aiming at`() {
        val sim = simulation()
        trimTo(sim, 0)
        // Directly above and slightly behind: no facing-relative default can produce this.
        val target = Vec2(sim.player.x - CLOSE, sim.player.y - CLOSE * 3.0)
        sim.enemies.add(
            LiveEnemy(
                EnemyArchetype.Swarm, target, EnemyArchetype.Swarm.healthOn(1), target.x, 0.0,
            ),
        )
        sim.tick(InputFrame(right = true))

        assertTrue(
            sim.aimDirection.y < 0.0 && sim.aimDirection.x < 0.0,
            "the simulation is not aiming up and behind at the only target there is: " +
                "${sim.aimDirection}",
        )
        assertEquals(
            sim.aimDirection,
            Scene.motionOf(sim).weaponAim,
            "the rig is not reading where the game is aiming",
        )

        val pose = Actor.pose(Scene.motionOf(sim))
        assertTrue(
            pose.weaponAim.y < 0.0,
            "the weapon points level or down while the game shoots at something above: " +
                "${pose.weaponAim}",
        )
        assertTrue(
            pose.leadHand.y < pose.leadShoulder.y,
            "the hand is not raised toward what is being shot at",
        )
    }

    /**
     * The swing must sweep the arc that dealt damage, not the direction the weapon is being held.
     *
     * The first version of this only compared an early and a late hand position, which move apart
     * whichever direction the sweep is built around — so a swing animated around the held aim
     * instead of the arc that resolved would have passed. The two are made to disagree here.
     */
    @Test
    fun `a melee swing sweeps the arc that dealt damage, not where the weapon is held`() {
        val sim = simulation(WeaponId.RustlineMachete)
        while (sim.lastSwing == null) sim.tick(InputFrame())

        val base = Scene.motionOf(sim)
        assertEquals(Action.Swing, Actor.actionOf(base), "the swing did not take the arm")

        // Deliberately opposed: the swing went one way, the weapon is held the other.
        val swung = Vec2(0.0, -1.0)
        val motion = base.copy(
            facing = 1,
            swingDirection = swung,
            weaponAim = Vec2(0.0, 1.0),
            secondsSinceSwing = base.swingSeconds / 2.0,
        )

        val pose = Actor.pose(motion)
        val reach = pose.leadHand - pose.leadShoulder

        assertTrue(
            reach.y < 0.0,
            "the hand swept toward the held aim rather than the arc that dealt damage: $reach",
        )

        // And the sweep really is a sweep: the hand is somewhere else a moment later.
        val later = Actor.pose(motion.copy(secondsSinceSwing = base.swingSeconds * 0.95))
        assertTrue(pose.leadHand != later.leadHand, "the hand does not move through the arc")
    }

    /**
     * PROD-063 and P-38: an enemy's strike is drawn as the same swoosh the player's is, from its
     * posed hand, and the outer arc is the reach the hit test used.
     */
    @Test
    fun `an enemy in a swing draws a swoosh whose outer radius is its reach`() {
        val sim = simulation()
        trimTo(sim, 0)
        val brute = enemy(sim, EnemyArchetype.Brute)
        val reach = 24.0
        brute.lastSwing = SwingVisual(
            origin = Vec2.Zero, direction = Vec2.Right, arcDegrees = 90.0, reachPx = reach,
            secondsLeft = 0.16, totalSeconds = 0.16,
        )
        sim.enemies.add(brute)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val arcs = frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment }
        assertTrue(arcs.isNotEmpty(), "no swoosh was drawn for a striking enemy")

        val hand = enemyHand(sim, brute)
        var outer = 0.0
        arcs.forEach { batch ->
            for (n in 0 until batch.size) {
                val i = n * Primitive.Segment.stride
                outer = maxOf(outer, (Vec2(batch[i], batch[i + 1]) - hand).length)
                outer = maxOf(outer, (Vec2(batch[i + 2], batch[i + 3]) - hand).length)
            }
        }
        assertEquals(reach * Scene.ZOOM, outer, 1e-6, "the swoosh's outer arc is not the swing's reach")
    }

    /** PROD-063 and P-38: a shot's flash sits at the barrel the figure is posed holding. */
    @Test
    fun `an enemy shot draws a flash at the posed barrel`() {
        val sim = simulation()
        trimTo(sim, 0)
        val shooter = enemy(sim, EnemyArchetype.Shooter)
        shooter.lastShot = MuzzleFlash(direction = Vec2.Right, secondsLeft = 0.1, totalSeconds = 0.1)
        sim.enemies.add(shooter)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val flash = frame.batches.firstOrNull { it.layer == Layer.Effects && it.primitive == Primitive.Dot }
        assertTrue(flash != null && flash.size >= 1, "no muzzle flash was drawn for a firing enemy")

        val pose = Actor.pose(Scene.enemyMotion(sim, shooter))
        val barrel = enemyFeet(sim, shooter) + Scene.barrelTip(pose) * Scene.ZOOM
        assertEquals(barrel.x, flash!![0], 1e-6, "the flash is not at the barrel")
        assertEquals(barrel.y, flash[1], 1e-6, "the flash is not at the barrel")
    }

    /**
     * PROD-063 and P-35: a boss attack's telegraph is its `WindUp`; its active window is a `Swing`
     * with a swoosh (Slam, Sweep, Rush) or a `Fire` with a muzzle flash (Volley).
     */
    @Test
    fun `a telegraphing boss poses its wind-up and an active attack its swing or shot`() {
        val sim = TestLevels.simulation()
        val boss = sim.boss
        boss.fight.engage()
        // Phase two, so the Volley is in the cycle.
        boss.fight.damage(boss.spec.maxHealth * 0.5)

        val seen = mutableSetOf<String>()
        var ticks = 0
        while (seen.size < 3 && ticks < 3000) {
            sim.tick(InputFrame())
            ticks++
            val attack = boss.currentAttack ?: continue
            val motion = Scene.bossMotion(sim, boss)
            if (boss.telegraphing) {
                assertEquals(Action.WindUp, Actor.actionOf(motion), "${attack.name} telegraphs without a wind-up pose")
                continue
            }
            if (!boss.striking || attack.name in seen) continue
            seen.add(attack.name)
            val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
            val effects = frame.batches.filter { it.layer == Layer.Effects }
            if (attack.name == "Volley") {
                assertEquals(Action.Fire, Actor.actionOf(motion), "an active Volley is not a shot")
                assertTrue(effects.any { it.primitive == Primitive.Dot }, "an active Volley draws no flash")
            } else {
                assertEquals(Action.Swing, Actor.actionOf(motion), "an active ${attack.name} is not a swing")
                assertTrue(effects.any { it.primitive == Primitive.Segment }, "an active ${attack.name} draws no swoosh")
            }
        }
        assertTrue(seen.containsAll(listOf("Slam", "Sweep", "Volley")), "only saw $seen strike in $ticks ticks")
    }

    @Test
    fun `an approaching boss runs its gait`() {
        val sim = TestLevels.simulation()
        val boss = sim.boss
        boss.fight.engage()

        var running = false
        var advanced = false
        var before = Scene.bossMotion(sim, boss).stridePx
        repeat(40) {
            sim.tick(InputFrame())
            val motion = Scene.bossMotion(sim, boss)
            if (boss.currentAttack == null) {
                running = running || Actor.clipOf(motion) == Clip.Run
                advanced = advanced || motion.stridePx > before
            }
            before = motion.stridePx
        }
        assertTrue(running, "a boss walking toward the player never selects its run clip")
        assertTrue(advanced, "a boss walking toward the player never advances its gait")
    }

    /** PROD-066: the player's swoosh spans the reach the hit test used, powerups included. */
    @Test
    fun `the player's swoosh reaches exactly as far as the resolved swing`() {
        var slots = PowerupSlots.empty()
        repeat(2) { slots = slots.collect(PowerupId.RangerOptics).first }
        val sim = simulation(WeaponId.RustlineMachete, slots)
        while (sim.lastSwing == null) sim.tick(InputFrame())
        val swing = sim.lastSwing!!
        assertTrue(swing.reachPx > Weapons.of(WeaponId.RustlineMachete).rangePx, "fixture: optics did not extend the reach")
        trimTo(sim, 0)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val origin = playerCentre(sim) * Scene.ZOOM
        var outer = 0.0
        frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment }.forEach { batch ->
            for (n in 0 until batch.size) {
                val i = n * Primitive.Segment.stride
                outer = maxOf(outer, (Vec2(batch[i], batch[i + 1]) - origin).length, (Vec2(batch[i + 2], batch[i + 3]) - origin).length)
            }
        }
        assertEquals(swing.reachPx * Scene.ZOOM, outer, 1e-6, "the swoosh's outer arc is not the resolved reach")
    }

    /** PROD-066: a shot's flash sits at the muzzle of the weapon the figure is drawn holding. */
    @Test
    fun `the player's shot cue sits at the held weapon's muzzle`() {
        val pistol = Weapons.all.first { it.cls == WeaponClass.Ranged && it.projectileSpeed > 0.0 && it.anchor == Anchor.Self }
        val sim = simulation(pistol.id)
        while (sim.lastShot == null) sim.tick(InputFrame())
        trimTo(sim, 0)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val pose = Actor.pose(Scene.motionOf(sim))
        val feet = Vec2(playerCentre(sim).x, sim.player.y + sim.player.height(Physics.Default)) * Scene.ZOOM
        val muzzle = feet + Scene.muzzleOf(pose, pistol) * Scene.ZOOM
        // The core shares the accent batch with the projectile it launched, so every dot is a candidate.
        val dots = frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Dot }.flatMap { batch ->
            (0 until batch.size).map { n -> Vec2(batch[n * Primitive.Dot.stride], batch[n * Primitive.Dot.stride + 1]) }
        }
        assertTrue(dots.any { (it - muzzle).length < 1e-6 }, "no flash at the muzzle $muzzle; dots at $dots")
    }

    /** PROD-066: a weapon with no barrel shows an activation pulse — a ring around the weapon. */
    @Test
    fun `the Kessler draws an activation pulse rather than a flash`() = assertPulses(WeaponId.KesslerOrbitalUplink)

    /** Gate-2 finding: a psychic orb has no barrel either, whatever it is anchored to. */
    @Test
    fun `a psychic weapon draws an activation pulse rather than a flash`() = assertPulses(WeaponId.NeuralSpike)

    private fun assertPulses(id: WeaponId) {
        val sim = simulation(id)
        while (sim.lastShot == null) sim.tick(InputFrame())
        trimTo(sim, 0)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val cues = frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment }
        assertTrue(cues.isNotEmpty(), "no activation cue was drawn")
        val pose = Actor.pose(Scene.motionOf(sim))
        val feet = Vec2(playerCentre(sim).x, sim.player.y + sim.player.height(Physics.Default)) * Scene.ZOOM
        val weapon = feet + Scene.muzzleOf(pose, Weapons.of(id)) * Scene.ZOOM
        val radii = mutableListOf<Double>()
        cues.forEach { batch ->
            for (n in 0 until batch.size) {
                val i = n * Primitive.Segment.stride
                radii.add((Vec2(batch[i], batch[i + 1]) - weapon).length)
                radii.add((Vec2(batch[i + 2], batch[i + 3]) - weapon).length)
            }
        }
        // Tracers and hit indicators share the layer (PROD-071), so the pulse is the one radius
        // that a whole polygon of endpoints sits on.
        val onRing = radii.filter { it > 1.0 }.groupBy { kotlin.math.round(it * 1e6) / 1e6 }.values.maxOfOrNull { it.size } ?: 0
        assertTrue(onRing >= 8, "the cue is not a ring around the weapon: radii $radii")
    }

    private fun playerCentre(sim: GameSimulation) =
        Vec2(sim.player.x + Physics.Default.width / 2.0, sim.player.y + sim.player.height(Physics.Default) / 2.0)

    /** `specs/hazards.md`: spikes and barrels are drawn on the hazard layer, in the hazard colours. */
    @Test
    fun `spikes and barrels draw on the hazard layer`() {
        val level = TestLevels.flat(
            spikeColumns = TestLevels.SPAWN_COLUMN + 2..TestLevels.SPAWN_COLUMN + 3,
            barrels = listOf(Barrel(TestLevels.SPAWN_COLUMN + 5, TestLevels.FLOOR_ROW)),
        )
        val sim = TestLevels.simulation(level)
        val plain = TestLevels.simulation(TestLevels.flat())

        fun hazardPrimitives(s: GameSimulation) = Scene.compose(s, camera(), backdrop(s), hudOf(s), 0.0, SceneBuilder())
            .batches.filter { it.layer == Layer.Hazard }.sumOf { it.size }

        assertTrue(hazardPrimitives(plain) == 0, "fixture: the bare level already draws hazards")
        assertTrue(hazardPrimitives(sim) > 0, "spikes and a barrel drew nothing on the hazard layer")
    }

    /** Round-3 finding: a shot resolves on the aim taken at wind-up start, so that is what the telegraph shows. */
    @Test
    fun `a shooter's telegraph holds the aim it took, not where the player is now`() {
        val sim = simulation()
        trimTo(sim, 0)
        val shooter = enemy(sim, EnemyArchetype.Shooter)
        shooter.position = Vec2(sim.player.x + CLOSE * 2.0, sim.player.y)
        sim.enemies.add(shooter)
        // The player is to the shooter's left; the stored aim says right.
        shooter.attackDirection = Vec2.Right
        shooter.attackTarget = Vec2(shooter.position.x + 100.0, shooter.position.y)
        shooter.windUpTotal = 0.25
        shooter.windUpLeft = 0.2
        assertTrue(sim.player.x < shooter.position.x, "fixture: the player is not to the shooter's left")

        val motion = Scene.enemyMotion(sim, shooter)
        assertEquals(Vec2.Right, motion.weaponAim, "the telegraph tracks the player instead of holding the aim")
        assertEquals(1, motion.facing)
    }

    /** PROD-063: every enemy's attack has a visible wind-up — the pod and the emplacement included. */
    @Test
    fun `a flyer and a turret winding up look different from ones that are not`() {
        listOf(EnemyArchetype.Flyer, EnemyArchetype.Turret).forEach { archetype ->
            val sim = simulation()
            trimTo(sim, 0)
            val enemy = enemy(sim, archetype)
            sim.enemies.add(enemy)
            fun primitives() = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
                .batches.filter { it.layer == Layer.ActorGlow || it.layer == Layer.Effects || it.layer == Layer.ActorFront }
                .flatMap { b -> (0 until b.size * b.primitive.stride).map { b[it] } }

            val idle = primitives()
            enemy.windUpTotal = 0.3
            enemy.windUpLeft = 0.2
            val telegraphing = primitives()
            assertTrue(idle != telegraphing, "a $archetype winding up is drawn exactly like one that is not")
        }
    }

    /** Where an enemy's posed lead hand lands on screen, with the camera at the origin. */
    private fun enemyHand(sim: GameSimulation, enemy: LiveEnemy): Vec2 =
        enemyFeet(sim, enemy) + Actor.pose(Scene.enemyMotion(sim, enemy)).leadHand * Scene.ZOOM

    private fun enemyFeet(sim: GameSimulation, enemy: LiveEnemy): Vec2 =
        Vec2(enemy.position.x + 7.0, enemy.position.y + 16.0) * Scene.ZOOM

    /**
     * PROD-041 and `specs/presentation.md`: the weapon attaches to the lead hand.
     *
     * Round eleven found it drawn for enemies and never for the player — the geometry was gated on
     * an enemy archetype being `armed`, and the player has no archetype, so the one figure that is
     * always carrying something (PROD-023) was the only one drawn empty-handed.
     */
    @Test
    fun `the player is drawn holding the weapon it is carrying`() {
        val sim = simulation()
        trimTo(sim, 0)

        val frame = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())
        val held = frame.batches.filter {
            it.layer == Layer.ActorFront && it.primitive == Primitive.Segment
        }

        assertTrue(held.isNotEmpty(), "nothing is drawn in front of the player at all")
        // Two arm segments plus the weapon; the arm alone would be two.
        val segments = held.sumOf { it.size }
        assertTrue(
            segments >= 3,
            "the player is drawn with $segments segments in front — an empty hand, not a weapon",
        )
    }

    @Test
    fun `a longer weapon is drawn longer`() {
        val bottle = Scene.weaponReach(Weapons.startingWeapon)
        val longest = Scene.weaponReach(Weapons.all.maxByOrNull { it.rangePx }!!)

        assertTrue(
            longest > bottle,
            "every weapon is drawn the same length, so the registry's range means nothing to the " +
                "figure holding it: $bottle against $longest",
        )
    }

    @Test
    fun `the heads-up display carries the build`() {
        val sim = simulation()
        val model = hudOf(sim)

        assertTrue(model.weaponName.isNotBlank(), "the HUD cannot name the weapon")
        assertEquals(sim.level.theme.displayName, model.themeName)
        assertEquals(sim.run.mapIndex, model.mapIndex)
        assertTrue(model.healthFraction in 0.0..1.0)
    }

    // ---- PROD-071 / P-43: every shot shows where it went ---------------------------------------

    private fun segmentsOf(frame: DrawList, style: String? = null): List<Pair<Vec2, Vec2>> =
        frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment && (style == null || it.style == style) }
            .flatMap { batch ->
                (0 until batch.size).map { n ->
                    val i = n * Primitive.Segment.stride
                    Vec2(batch[i], batch[i + 1]) to Vec2(batch[i + 2], batch[i + 3])
                }
            }

    private fun dotsOf(frame: DrawList, style: String? = null): List<Pair<Vec2, Double>> =
        frame.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Dot && (style == null || it.style == style) }
            .flatMap { batch ->
                (0 until batch.size).map { n ->
                    val i = n * Primitive.Dot.stride
                    Vec2(batch[i], batch[i + 1]) to batch[i + 2]
                }
            }

    private fun hasSegment(segments: List<Pair<Vec2, Vec2>>, a: Vec2, b: Vec2) =
        segments.any { (p, q) -> ((p - a).length < 1e-6 && (q - b).length < 1e-6) || ((p - b).length < 1e-6 && (q - a).length < 1e-6) }

    private fun frameOf(sim: GameSimulation) = Scene.compose(sim, camera(), backdrop(sim), hudOf(sim), 0.0, SceneBuilder())

    @Test
    fun `a projectile draws a body and a tracer back along its velocity`() {
        val pistol = Weapons.of(WeaponId.ScraplineZipPistol)
        val sim = simulation(pistol.id)
        while (sim.projectiles.none { it.fromPlayer }) sim.tick(InputFrame())
        trimTo(sim, 0)
        val palette = Palettes.of(sim.level.theme)
        val shot = sim.projectiles.first { it.fromPlayer }
        val head = shot.position * Scene.ZOOM
        val tail = (shot.position - shot.velocity * Scene.TRACER_SECONDS) * Scene.ZOOM

        val frame = frameOf(sim)

        val style = palette.glow[palette.glow.size - 1]
        val body = dotsOf(frame, style).firstOrNull { (at, _) -> (at - head).length < 1e-6 }
        assertTrue(body != null, "no body at $head")
        assertEquals(shot.radius * Scene.ZOOM, body!!.second, 1e-9, "the body is not drawn at the hit radius")
        assertTrue(hasSegment(segmentsOf(frame, style), head, tail), "no tracer from $head to $tail: ${segmentsOf(frame, style)}")
    }

    @Test
    fun `an enemy projectile draws its body and tracer in the hazard colour`() {
        val sim = simulation()
        trimTo(sim, 0)
        val palette = Palettes.of(sim.level.theme)
        val position = playerCentre(sim) + Vec2(40.0, 0.0)
        val velocity = Vec2(-340.0, 0.0)
        sim.projectiles.add(io.github.ksean.cyberslop.sim.LiveProjectile(position, velocity, 1.0, 0, 1.0, passesTerrain = false, fromPlayer = false))

        val frame = frameOf(sim)

        val head = position * Scene.ZOOM
        val tail = (position - velocity * Scene.TRACER_SECONDS) * Scene.ZOOM
        assertTrue(dotsOf(frame, palette.hazard).any { (at, _) -> (at - head).length < 1e-6 }, "no enemy body at $head")
        assertTrue(hasSegment(segmentsOf(frame, palette.hazard), head, tail), "no enemy tracer from $head to $tail")
    }

    /** The Kessler resolves instantly at the aim point: a beam from the top of the view onto it, and a ring at its radius. */
    @Test
    fun `a Kessler strike draws a beam onto the strike point and a ring at its radius`() {
        val sim = simulation(WeaponId.KesslerOrbitalUplink)
        while (sim.lastHit == null) sim.tick(InputFrame())
        val hit = sim.lastHit!!
        val beam = hit.shape as io.github.ksean.cyberslop.sim.HitShape.Beam
        val foot = beam.foot * Scene.ZOOM

        val frame = frameOf(sim)

        val segments = segmentsOf(frame)
        assertTrue(segments.any { (p, q) -> (q - foot).length < 1e-6 && p.x == foot.x && p.y == 0.0 }, "no beam from the top of the view onto $foot")
        val ring = segments.filter { (p, _) -> kotlin.math.abs((p - foot).length - beam.radius * Scene.ZOOM) < 1e-6 }
        assertTrue(ring.size >= 8, "no ring of radius ${beam.radius * Scene.ZOOM} around $foot")
    }

    @Test
    fun `a chain draws one segment per jump through the targets it struck, in order`() {
        val sim = simulation(WeaponId.GhostwireTether)
        while (sim.lastHit == null) sim.tick(InputFrame())
        val chain = sim.lastHit!!.shape as io.github.ksean.cyberslop.sim.HitShape.Chain
        assertTrue(chain.points.size >= 2, "fixture: a chain with nothing struck was recorded")

        val frame = frameOf(sim)

        val segments = segmentsOf(frame)
        chain.points.zipWithNext().forEach { (a, b) ->
            assertTrue(hasSegment(segments, a * Scene.ZOOM, b * Scene.ZOOM), "no chain segment from $a to $b")
        }
    }

    @Test
    fun `a chain that strikes nothing leaves no indicator`() {
        val sim = io.github.ksean.cyberslop.sim.GameSimulation(
            TestLevels.flat(),
            RunState.begin(TestLevels.SEED).copy(loadout = RunState.begin(TestLevels.SEED).loadout.copy(weapon = Weapons.of(WeaponId.GhostwireTether))),
            TestLevels.SEED,
        )
        repeat(120) { sim.tick(InputFrame()) }
        assertTrue(sim.lastHit == null, "an empty chain recorded ${sim.lastHit}")
    }

    @Test
    fun `a blast draws a ring of the resolved radius where it resolved`() {
        val sim = simulation(WeaponId.MigraineLoop)
        while (sim.lastHit == null) sim.tick(InputFrame())
        val ring = sim.lastHit!!.shape as io.github.ksean.cyberslop.sim.HitShape.Ring
        val centre = ring.centre * Scene.ZOOM

        val frame = frameOf(sim)

        val radii = segmentsOf(frame).filter { (p, _) -> kotlin.math.abs((p - centre).length - ring.radius * Scene.ZOOM) < 1e-6 }
        assertTrue(radii.size >= 8, "no ring of radius ${ring.radius * Scene.ZOOM} around $centre")
    }

    @Test
    fun `a hit indicator is gone after the flash window`() {
        val sim = simulation(WeaponId.MigraineLoop)
        while (sim.lastHit == null) sim.tick(InputFrame())
        trimTo(sim, 0)
        sim.autoFire.remaining = 100.0
        repeat((GameSimulation.FLASH_VISIBLE_SECONDS / io.github.ksean.cyberslop.physics.TICK_SECONDS).toInt() + 1) { sim.tick(InputFrame()) }
        assertTrue(sim.lastHit == null, "the indicator outlived its window")
    }

    /** Round-1 finding: a shot that spawns, travels and hits inside one tick was never drawn. */
    @Test
    fun `a projectile spent on the tick it was fired still leaves its line of flight`() {
        val sim = simulation(WeaponId.ScraplineZipPistol)
        trimTo(sim, 0)
        val palette = Palettes.of(sim.level.theme)
        // Standing in the muzzle: the slug hits before the frame it would have been drawn in.
        val target = enemy(sim, EnemyArchetype.Brute)
        target.position = playerCentre(sim) + Vec2(8.0, -7.0)
        target.health = 1e9
        sim.enemies.add(target)
        while (sim.lastShot == null) sim.tick(InputFrame())
        assertTrue(sim.projectiles.isEmpty(), "fixture: the slug is still flying")
        val impact = sim.impacts.singleOrNull() ?: error("no impact recorded for a same-tick hit")
        val shape = impact.shape as io.github.ksean.cyberslop.sim.HitShape.Impact
        val head = shape.at * Scene.ZOOM
        val tail = (shape.at - shape.velocity * Scene.TRACER_SECONDS) * Scene.ZOOM

        val frame = frameOf(sim)

        val style = palette.glow[palette.glow.size - 1]
        assertTrue(hasSegment(segmentsOf(frame, style), head, tail), "no tracer for the spent slug from $head to $tail")

        // Round-2 finding: the impact's tracer thins with the window like every other indicator.
        fun width(f: DrawList) = f.batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment && it.style == style }
            .filter { b -> (0 until b.size).any { n -> (Vec2(b[n * 4], b[n * 4 + 1]) - head).length < 1e-6 } }.maxOf { it.width }
        val fresh = width(frame)
        sim.autoFire.remaining = 100.0
        repeat(4) { sim.tick(InputFrame()) }
        assertTrue(sim.impacts.isNotEmpty(), "fixture: the impact expired")
        assertTrue(width(frameOf(sim)) < fresh, "the impact tracer did not fade")
    }

    /** Round-1 finding: the Volley showed a fan along the boss's facing, not the band it aims at. */
    @Test
    fun `an active Volley shows the band it was aimed at, in the enemy shot colour`() {
        val sim = TestLevels.simulation()
        val boss = sim.boss
        boss.fight.engage()
        boss.fight.damage(boss.spec.maxHealth * 0.5)
        val palette = Palettes.of(sim.level.theme)
        var ticks = 0
        while (!(boss.striking && boss.currentAttack?.visual?.ranged == true) && ticks < 3000) { sim.tick(InputFrame()); ticks++ }
        assertTrue(boss.striking, "fixture: no Volley struck in $ticks ticks")

        val frame = frameOf(sim)

        val left = Vec2((boss.aimedX - io.github.ksean.cyberslop.sim.LiveBoss.VOLLEY_WIDTH) * Scene.ZOOM, boss.position.y * Scene.ZOOM)
        val right = Vec2((boss.aimedX + io.github.ksean.cyberslop.sim.LiveBoss.VOLLEY_WIDTH) * Scene.ZOOM, boss.position.y * Scene.ZOOM)
        val shots = segmentsOf(frame, palette.hazard)
        assertTrue(hasSegment(shots, left, right), "no band from $left to $right in the shot colour; segments $shots")
        assertTrue(shots.size > 1, "no tracers toward the band")
    }

    @Test
    fun `a pull draws a ring at its declared radius and an orbit at its orbit radius`() {
        listOf(WeaponId.BlackboxChorus, WeaponId.NullEgoSingularity).forEach { id ->
            val sim = simulation(id)
            while (sim.lastHit == null) sim.tick(InputFrame())
            val ring = sim.lastHit!!.shape as io.github.ksean.cyberslop.sim.HitShape.Ring
            val pattern = Weapons.of(id).pattern
            val declared = when (pattern) {
                is io.github.ksean.cyberslop.combat.FirePattern.Pull -> pattern.radius
                is io.github.ksean.cyberslop.combat.FirePattern.Orbit -> pattern.radius
                else -> error("$id is not a pull or orbit")
            }
            assertEquals(declared, ring.radius, 1e-9, "$id resolved at a radius other than its declared one")
            val centre = ring.centre * Scene.ZOOM
            val on = segmentsOf(frameOf(sim)).count { (p, _) -> kotlin.math.abs((p - centre).length - ring.radius * Scene.ZOOM) < 1e-6 }
            assertTrue(on >= 8, "$id: no ring of radius ${ring.radius * Scene.ZOOM}")
        }
    }

    @Test
    fun `a hit indicator fades over its window`() {
        val sim = simulation(WeaponId.MigraineLoop)
        while (sim.lastHit == null) sim.tick(InputFrame())
        trimTo(sim, 0)
        sim.autoFire.remaining = 100.0
        val ring = sim.lastHit!!.shape as io.github.ksean.cyberslop.sim.HitShape.Ring
        val centre = ring.centre * Scene.ZOOM
        fun ringWidth(): Double = frameOf(sim).batches.filter { it.layer == Layer.Effects && it.primitive == Primitive.Segment }
            .filter { batch -> (0 until batch.size).any { n -> kotlin.math.abs((Vec2(batch[n * 4], batch[n * 4 + 1]) - centre).length - ring.radius * Scene.ZOOM) < 1e-6 } }
            .maxOf { it.width }
        val fresh = ringWidth()
        repeat(4) { sim.tick(InputFrame()) }
        val faded = ringWidth()
        assertTrue(faded < fresh, "the ring did not fade: $fresh then $faded")
    }

    private fun simulation(weapon: WeaponId = WeaponId.BrokenBottle, slots: PowerupSlots? = null): GameSimulation {
        val level = LevelGenerator.generate(SEED, 1).level
        var run = RunState.begin(SEED)
        if (weapon != WeaponId.BrokenBottle) {
            run = run.copy(loadout = run.loadout.copy(weapon = Weapons.of(weapon)))
        }
        if (slots != null) run = run.copy(loadout = run.loadout.copy(slots = slots))
        return GameSimulation(level, run, SEED)
    }

    private fun camera() = Camera(0.0, 0.0, VIEW_WIDTH, VIEW_HEIGHT)

    private fun backdrop(sim: GameSimulation) =
        Backdrops.of(SEED, sim.level)

    private fun hudOf(sim: GameSimulation) =
        HudModel.of(sim.run, sim.level.theme, MAPS, sim.boss.spec.name, sim.boss.healthFraction)

    private fun trimTo(sim: GameSimulation, count: Int) {
        while (sim.enemies.size > count) sim.enemies.removeAt(sim.enemies.size - 1)
    }

    private fun growTo(sim: GameSimulation, count: Int) {
        val archetypes = EnemyArchetype.entries
        while (sim.enemies.size < count) {
            sim.enemies.add(enemy(sim, archetypes[sim.enemies.size % archetypes.size]))
        }
    }

    /** Placed inside the view, so it is actually drawn rather than culled. */
    private fun enemy(sim: GameSimulation, archetype: EnemyArchetype): LiveEnemy {
        val index = sim.enemies.size
        val x = sim.player.x + (index % SPREAD) * SPACING
        return LiveEnemy(
            archetype = archetype,
            position = Vec2(x, sim.player.y),
            health = archetype.healthOn(sim.level.mapIndex),
            homeX = x,
            patrolPx = 0.0,
        )
    }

    private companion object {
        val SEED = 0xC0FFEEuL
        const val VIEW_WIDTH = 560.0
        const val VIEW_HEIGHT = 320.0
        const val FEW = 10
        const val MANY = 600
        const val MAPS = 10
        const val SPREAD = 24
        const val SPACING = 20.0
        const val CLOSE = 40.0
        const val ONE_PIXEL = 1.0
        const val TINY = 1e-9

        /**
         * Back to front, as an actor is built: rear limbs, then the body they hang off, then the
         * head, then what is held in front of it, then armour bolted on, then the lit eye.
         */
        val ROLE_STACK = listOf(
            Layer.ActorBehind,
            Layer.Actors,
            Layer.ActorHead,
            Layer.ActorFront,
            Layer.ActorTrim,
            Layer.ActorGlow,
        )

        /**
         * Bounded by `layers x styles x widths`: fourteen layers over roughly a dozen and a half
         * distinct colours, of which only a fraction ever co-occur. Generous, and still a constant
         * that no number of entities can move.
         *
         * Raised from 72 when item icons arrived (`specs/presentation.md`, Item icons). **Measured** on a deliberately
         * worst-case frame — 600 enemies, all forty-four icons on the ground at once, a full
         * five-slot build in the display and an Ascended weapon in hand — at **90**: 23 of them on
         * the two item layers, where a rectangle used to cost 3. The ceiling is what it is because
         * the design opens a batch per ladder width per colour, and five rarity scales put eight
         * ladder widths in play; what matters is that a frame with one drop and a frame with a
         * hundred open the same ones.
         */
        const val MAX_BATCHES = 100
    }
}
