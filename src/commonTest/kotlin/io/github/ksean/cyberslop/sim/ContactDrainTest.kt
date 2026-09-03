package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Balance
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.world.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A living enemy's body hurts to touch: the contact drain of `specs/enemies.md` (PROD-069, P-41). */
class ContactDrainTest {
    private fun perTick(mapIndex: Int = 1) = EnemyAttacks.CONTACT_DRAIN * Balance.contactDamage(mapIndex) * TICK_SECONDS
    private fun bossPerTick(mapIndex: Int = 1) = EnemyAttacks.BOSS_CONTACT_MULTIPLIER * perTick(mapIndex)

    /** A stunned Swarm standing in the player: it cannot swing, so only its body can hurt. */
    private fun overlappingSwarm(sim: GameSimulation): LiveEnemy {
        val swarm = TestLevels.enemyAt(sim, EnemyArchetype.Swarm, column = TestLevels.SPAWN_COLUMN)
        swarm.stun(seconds = 30.0)
        return swarm
    }

    private fun overlaps(sim: GameSimulation, enemy: LiveEnemy): Boolean {
        val width = Physics.Default.width
        val height = sim.player.height(Physics.Default)
        return enemy.position.x < sim.player.x + width && enemy.position.x + LiveEnemy.BODY_SIZE > sim.player.x &&
            enemy.position.y < sim.player.y + height && enemy.position.y + LiveEnemy.BODY_SIZE > sim.player.y
    }

    private fun overlapBoss(sim: GameSimulation, boss: LiveBoss) {
        boss.placeAt(Vec2(sim.player.centre(Physics.Default).x, sim.player.y + sim.player.height(Physics.Default)))
    }

    @Test
    fun `overlapping a living enemy drains the contact rate per tick`() {
        val sim = TestLevels.simulation()
        val swarm = overlappingSwarm(sim)

        repeat(60) { sim.tick(InputFrame()) }

        assertTrue(overlaps(sim, swarm), "fixture: the swarm is not standing in the player")
        assertEquals(sim.run.maxHealth - 60 * perTick(), sim.run.health, 1e-6, "contact did not drain at its rate")
    }

    @Test
    fun `one tick of contact does not kill`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)

        sim.tick(InputFrame())

        assertTrue(sim.run.health > sim.run.maxHealth * 0.9, "one tick of contact took ${sim.run.maxHealth - sim.run.health}")
    }

    @Test
    fun `two overlapping enemies drain both`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)
        val brute = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN)
        brute.stun(seconds = 30.0)

        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(sim.run.maxHealth - 120 * perTick(), sim.run.health, 1e-6, "two bodies did not drain twice")
    }

    @Test
    fun `a dead enemy and an enemy one pixel clear drain nothing`() {
        val sim = TestLevels.simulation()
        val dead = overlappingSwarm(sim)
        dead.health = 0.0
        val clear = TestLevels.enemyAt(sim, EnemyArchetype.Brute, column = TestLevels.SPAWN_COLUMN)
        clear.stun(seconds = 30.0)
        sim.tick(InputFrame())
        clear.position = Vec2(sim.player.x + Physics.Default.width + 1.0, clear.position.y)
        val before = sim.run.health

        repeat(30) {
            sim.tick(InputFrame())
            clear.position = Vec2(sim.player.x + Physics.Default.width + 1.0, clear.position.y)
        }

        assertTrue(!overlaps(sim, clear), "fixture: the clear enemy overlaps")
        assertEquals(before, sim.run.health, 1e-9, "a dead or a clear enemy drained")
    }

    @Test
    fun `contact never displaces the player`() {
        val sim = TestLevels.simulation()
        overlappingSwarm(sim)
        repeat(5) { sim.tick(InputFrame()) }
        val x = sim.player.x
        val y = sim.player.y

        repeat(60) { sim.tick(InputFrame()) }

        assertEquals(x, sim.player.x, "contact moved the player in x")
        assertEquals(y, sim.player.y, "contact moved the player in y")
        assertTrue(sim.run.health < sim.run.maxHealth, "fixture: nothing drained")
    }

    @Test
    fun `no contact drains over a committed column or on the boss's ground`() {
        val committed = TestLevels.simulation(
            TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
        )
        overlappingSwarm(committed)
        repeat(60) { committed.tick(InputFrame()) }
        assertEquals(committed.run.maxHealth, committed.run.health, "contact drained over a committed column")

        val ground = TestLevels.simulation(TestLevels.flat(bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1)))
        ground.boss.fight.engage()
        ground.boss.fight.damage(ground.boss.spec.maxHealth)
        overlappingSwarm(ground)
        repeat(60) { ground.tick(InputFrame()) }
        assertEquals(ground.run.maxHealth, ground.run.health, "contact drained on the boss's ground")
    }

    @Test
    fun `mini-boss and main-boss bodies drain three times normal contact`() {
        listOf<(GameSimulation) -> LiveBoss>({ it.boss }, { it.miniboss }).forEach { pick ->
            val sim = TestLevels.simulation()
            val live = pick(sim)
            live.fight.engage()
            // Inside the rest before its first attack, standing in the player: only a body could hurt.
            repeat(20) {
                overlapBoss(sim, live)
                sim.tick(InputFrame())
            }
            assertEquals(
                sim.run.maxHealth - 20 * bossPerTick(),
                sim.run.health,
                1e-6,
                "${live.spec.name}'s body did not drain at three times normal contact",
            )
        }
    }

    @Test
    fun `boss contact uses the physical body boundary and defeated bodies do not drain`() {
        fun healthAfterGap(gap: Double): Double {
            val sim = TestLevels.simulation()
            val live = sim.boss
            live.fight.engage()
            val playerRight = sim.player.x + Physics.Default.width
            // The boss walks left by SPEED * dt before contact is resolved; leave [gap] afterward.
            live.placeAt(
                Vec2(
                    playerRight + live.halfWidth + LiveBoss.SPEED * TICK_SECONDS + gap,
                    sim.player.y + sim.player.height(Physics.Default),
                ),
            )
            sim.tick(InputFrame())
            return sim.run.health
        }

        assertEquals(TestLevels.simulation().run.maxHealth, healthAfterGap(1.0), 1e-9, "one clear pixel drained")
        assertEquals(
            TestLevels.simulation().run.maxHealth - bossPerTick(),
            healthAfterGap(-1.0),
            1e-6,
            "one pixel of body overlap did not drain",
        )

        val defeated = TestLevels.simulation()
        defeated.boss.fight.engage()
        defeated.boss.fight.damage(defeated.boss.spec.maxHealth)
        overlapBoss(defeated, defeated.boss)
        val before = defeated.run.health
        defeated.tick(InputFrame())
        assertEquals(before, defeated.run.health, "a defeated boss body drained")
    }

    @Test
    fun `overlapping both boss bodies drains both and never displaces the player`() {
        val sim = TestLevels.simulation()
        listOf(sim.miniboss, sim.boss).forEach {
            it.fight.engage()
            overlapBoss(sim, it)
        }
        val x = sim.player.x
        val y = sim.player.y

        sim.tick(InputFrame())

        assertEquals(sim.run.maxHealth - 2 * bossPerTick(), sim.run.health, 1e-6)
        assertEquals(x, sim.player.x, "boss contact moved the player in x")
        assertEquals(y, sim.player.y, "boss contact moved the player in y")
    }

    @Test
    fun `boss contact remains active during an attack telegraph`() {
        val sim = TestLevels.simulation()
        val live = sim.boss
        live.fight.engage()
        val target = BossTarget(live.centre + Vec2(-40.0, 0.0), onGround = true, crouched = false)
        while (live.currentAttack == null) live.tick(TICK_SECONDS, target)
        overlapBoss(sim, live)
        val before = sim.run.health

        sim.tick(InputFrame())

        assertTrue(live.currentAttack != null, "fixture: the telegraph ended")
        assertEquals(before - bossPerTick(), sim.run.health, 1e-6, "telegraphing disabled body contact")
    }

    @Test
    fun `committed columns suppress boss contact but boss ground does not`() {
        val committed = TestLevels.simulation(
            TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
        )
        committed.boss.fight.engage()
        overlapBoss(committed, committed.boss)
        committed.tick(InputFrame())
        assertEquals(committed.run.maxHealth, committed.run.health, "boss contact drained over a committed column")

        val arena = TestLevels.simulation(
            TestLevels.flat(bossArena = Arena(2, 12, TestLevels.FLOOR_ROW + 1)),
        )
        arena.boss.fight.engage()
        overlapBoss(arena, arena.boss)
        arena.tick(InputFrame())
        assertEquals(arena.run.maxHealth - bossPerTick(), arena.run.health, 1e-6, "boss ground suppressed its boss")
    }

    @Test
    fun `boss contact waits out landing grace after a committed column`() {
        val sim = TestLevels.simulation(
            TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN),
        )
        sim.boss.fight.engage()
        fun follow() = overlapBoss(sim, sim.boss)
        fun overCommitted() = sim.level.isCommitted(io.github.ksean.cyberslop.world.TileMap.toTile(sim.player.x))

        var walked = 0
        while (overCommitted() && walked < 120) {
            follow()
            sim.tick(InputFrame(right = true))
            walked++
        }
        assertTrue(!overCommitted(), "fixture: never left the committed column")
        assertEquals(sim.run.maxHealth, sim.run.health, "boss contact drained over the committed column")

        repeat(2) { follow(); sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth, sim.run.health, "boss contact drained inside landing grace")

        repeat((GameSimulation.LANDING_GRACE / TICK_SECONDS).toInt() + 2) {
            follow()
            sim.tick(InputFrame())
        }
        assertTrue(sim.run.health < sim.run.maxHealth, "boss contact never drained after landing grace")
    }

    @Test
    fun `no contact drains inside the landing grace after a committed column`() {
        val level = TestLevels.flat(committedColumns = TestLevels.SPAWN_COLUMN..TestLevels.SPAWN_COLUMN)
        val sim = TestLevels.simulation(level)
        val swarm = overlappingSwarm(sim)
        fun follow() { swarm.position = Vec2(sim.player.x, swarm.position.y) }
        fun overCommitted() = sim.level.isCommitted(io.github.ksean.cyberslop.world.TileMap.toTile(sim.player.x))

        // Walk right, the swarm riding in the player's box, until the column is behind them.
        var walked = 0
        while (overCommitted() && walked < 120) { follow(); sim.tick(InputFrame(right = true)); walked++ }
        assertTrue(!overCommitted(), "fixture: never left the committed column")
        assertEquals(sim.run.maxHealth, sim.run.health, "contact drained over the committed column")

        repeat(2) { follow(); sim.tick(InputFrame()) }
        assertEquals(sim.run.maxHealth, sim.run.health, "contact drained inside the landing grace")

        repeat((GameSimulation.LANDING_GRACE / TICK_SECONDS).toInt() + 2) { follow(); sim.tick(InputFrame()) }
        assertTrue(sim.run.health < sim.run.maxHealth, "contact never drained after the grace")
    }
}
