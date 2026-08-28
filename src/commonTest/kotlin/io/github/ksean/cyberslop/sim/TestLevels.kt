package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.ThemeId
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap

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
        bossArena: Arena = Arena(100, 114, FLOOR_ROW + 1),
        mapIndex: Int = 1,
    ): Level {
        val tiles = TileMap(WIDTH, HEIGHT)
        for (x in 0 until WIDTH) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Solid
        for (x in gapColumns) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Empty
        for (x in acidColumns) tiles[x, FLOOR_ROW + 1] = TileKind.Acid
        for (x in committedColumns) tiles[x, 0] = TileKind.Acid
        wallColumn?.let { x -> for (y in FLOOR_ROW - 2..FLOOR_ROW) tiles[x, y] = TileKind.Solid }
        for (x in spikeColumns) tiles[x, FLOOR_ROW] = TileKind.Spikes

        val arc = Mask(WIDTH, HEIGHT).also { it.markRect(0, FLOOR_ROW - 1, WIDTH - 1, FLOOR_ROW) }
        return Level(
            mapIndex = mapIndex,
            theme = ThemeId.RuinedCitySprawl,
            tiles = tiles,
            floorMask = Mask(WIDTH, HEIGHT),
            arcMask = arc,
            spawnColumn = SPAWN_COLUMN,
            spawnRow = FLOOR_ROW + 1,
            miniboss = Arena(80, 92, FLOOR_ROW + 1),
            boss = bossArena,
            jets = emptyList(),
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

    /**
     * The dodge policy of `specs/enemies.md` (boss pressure): answer each telegraphed attack with
     * its listed dodge for the attack's whole duration, otherwise close on the boss.
     */
    fun dodge(sim: GameSimulation): InputFrame {
        val attack = sim.boss.currentAttack
        val towardBoss = sim.boss.centre.x > sim.player.x
        if (attack != null) {
            return when (attack.dodge) {
                Dodge.Jump -> InputFrame(jump = true, jumpStart = sim.player.onGround)
                Dodge.Crouch -> InputFrame(crouch = true)
                Dodge.MoveAside -> InputFrame(left = towardBoss, right = !towardBoss)
            }
        }
        return InputFrame(right = towardBoss, left = !towardBoss)
    }

    /** Close on the boss and never react: what a player who does nothing takes. */
    fun standStill(sim: GameSimulation): InputFrame {
        if (sim.boss.currentAttack != null) return InputFrame()
        val towardBoss = sim.boss.centre.x > sim.player.x
        return InputFrame(right = towardBoss, left = !towardBoss)
    }
}
