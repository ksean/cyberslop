package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.PickupSite
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs

/**
 * Small hand-built levels for behaviour tests, where a generated map's geometry would be noise.
 *
 * A flat floor along [FLOOR_ROW] with the corridor row above it marked in the arc mask, so committed
 * columns are computed the way the generator's would be. Gaps, acid and walls are cut in by name.
 */
object TestLevels {
    const val WIDTH = 120
    const val HEIGHT = 24
    /** The row the player and enemies stand in; the tile below it is solid. */
    const val FLOOR_ROW = 15
    const val SPAWN_COLUMN = 3

    fun flat(
        gapColumns: IntRange = IntRange.EMPTY,
        acidColumns: IntRange = IntRange.EMPTY,
        wallColumn: Int? = null,
        /** Columns made committed by a lethal tile at the ceiling, leaving the floor walkable. */
        committedColumns: IntRange = IntRange.EMPTY,
        /** Spike strips written into the standing row. */
        spikeColumns: IntRange = IntRange.EMPTY,
        barrels: List<Barrel> = emptyList(),
        jets: List<FireJet> = emptyList(),
        pickups: List<PickupSite> = emptyList(),
        bossArena: Arena = Arena(100, 114, FLOOR_ROW + 1),
        spawnColumn: Int = SPAWN_COLUMN,
        mapIndex: Int = 1,
    ): Level {
        val tiles = TileMap(WIDTH, HEIGHT)
        for (x in 0 until WIDTH) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Solid
        for (x in gapColumns) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Empty
        for (x in acidColumns) tiles[x, FLOOR_ROW + 1] = TileKind.Acid
        for (x in committedColumns) tiles[x, 0] = TileKind.Acid
        wallColumn?.let { x -> for (y in FLOOR_ROW - 2..FLOOR_ROW) tiles[x, y] = TileKind.Solid }
        for (x in spikeColumns) tiles[x, FLOOR_ROW] = TileKind.Spikes

        val floorMask = Mask(WIDTH, HEIGHT)
        for (x in 0 until WIDTH) for (y in 1 until HEIGHT) {
            if (tiles.blocksMovement(x, y) && !tiles.blocksMovement(x, y - 1)) floorMask[x, y] = true
        }
        val arc = Mask(WIDTH, HEIGHT).also { it.markRect(0, FLOOR_ROW - 1, WIDTH - 1, FLOOR_ROW) }
        return Level(
            mapIndex = mapIndex,
            theme = ThemeId.RuinedCitySprawl,
            tiles = tiles,
            floorMask = floorMask,
            arcMask = arc,
            spawnColumn = spawnColumn,
            spawnRow = FLOOR_ROW + 1,
            miniboss = Arena(80, 92, FLOOR_ROW + 1),
            boss = bossArena,
            jets = jets,
            pickups = pickups,
            gateColumn = 115,
            barrels = barrels,
        )
    }

    fun simulation(level: Level = flat(), seed: ULong = SEED): GameSimulation =
        GameSimulation(level, RunState.begin(seed), seed)

    /**
     * An enemy standing in [column] on the floor, with its patrol span in tiles.
     *
     * Durable by default: the player's weapon fires by itself, and a map-one Swarm dies to one
     * bottle swing before it can do the thing a behaviour test is watching for.
     */
    fun enemyAt(
        sim: GameSimulation,
        archetype: EnemyArchetype,
        column: Int,
        patrolTiles: Int = 1,
        row: Int = FLOOR_ROW,
        health: Double = archetype.healthOn(10),
    ): LiveEnemy {
        val enemy = LiveEnemy(
            archetype = archetype,
            position = Vec2(TileMap.toWorld(column), TileMap.toWorld(row)),
            health = health,
            homeX = TileMap.toWorld(column),
            patrolPx = TileMap.toWorld(patrolTiles),
        )
        sim.enemies.add(enemy)
        return enemy
    }

    val SEED = 0xD1FFuL
    private const val BOSS_STANDOFF_PX = 64.0

    /**
     * The dodge policy of `specs/enemies.md` (boss pressure): answer each telegraphed attack with
     * its listed dodge for the attack's whole duration, otherwise close without body contact.
     */
    fun dodge(sim: GameSimulation): InputFrame {
        if (sim.boss.currentAttack != null) return dodgeActiveBossAttack(sim)
        return closeOnBossWithoutContact(sim)
    }

    /** Remove the earlier encounter so a main-boss harness measures one boss, not a skipped pair. */
    fun isolateMainBoss(sim: GameSimulation) {
        if (!sim.miniboss.fight.defeated) {
            sim.miniboss.fight.engage()
            sim.miniboss.fight.damage(sim.miniboss.spec.maxHealth)
            sim.tick(InputFrame()) // resolve its reward transition before discarding that fixture loot
        }
        sim.enemies.clear()
        sim.items.removeAll { it.guaranteed }
        sim.boss.placeAt(
            Vec2(
                TileMap.toWorld(sim.boss.arena.centreTile),
                TileMap.toWorld(sim.boss.arena.floorRow),
            ),
        )
    }

    /** Perform exactly the active attack's declared real-input dodge. */
    fun dodgeActiveBossAttack(sim: GameSimulation): InputFrame {
        val attack = requireNotNull(sim.boss.currentAttack)
        val spacing = closeOnBossWithoutContact(sim)
        return when (attack.dodge) {
            Dodge.Jump -> InputFrame(
                left = spacing.left,
                right = spacing.right,
                jump = true,
                jumpStart = sim.player.onGround,
            )
            Dodge.Crouch -> InputFrame(left = spacing.left, right = spacing.right, crouch = true)
            Dodge.MoveAside -> InputFrame(
                left = spacing.left,
                right = spacing.right,
                jump = true,
                jumpStart = sim.player.onGround,
            )
        }
    }

    /** Close to melee range without body contact, then never react while an attack is active. */
    fun standStill(sim: GameSimulation): InputFrame {
        if (sim.boss.currentAttack != null) return InputFrame()
        return closeOnBossWithoutContact(sim)
    }

    /** The boss-pressure approach: enter melee range without conflating attacks with body contact. */
    fun closeOnBossWithoutContact(sim: GameSimulation): InputFrame {
        val playerCentreX = sim.player.x + Physics.Default.width / 2.0
        val offset = sim.boss.position.x - playerCentreX
        if (abs(offset) < Vec2.EPSILON) return InputFrame()
        val toward = if (offset > 0.0) 1 else -1
        val closingSpeed = maxOf(0.0, sim.player.vx * toward)
        // Use the slower air response even on the ground: a ranged dodge may end one tick before
        // landing, and a ground-only braking estimate can carry that airborne player through the body.
        val reversalSeconds = closingSpeed / Physics.Default.airAccel
        val reversalDistance = closingSpeed * closingSpeed / (2.0 * Physics.Default.airAccel)
        val bossAdvance = LiveBoss.SPEED * reversalSeconds
        val shouldClose = abs(offset) > BOSS_STANDOFF_PX + reversalDistance + bossAdvance
        val direction = if (shouldClose) toward else -toward
        return InputFrame(left = direction < 0, right = direction > 0)
    }
}
