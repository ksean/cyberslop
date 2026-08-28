package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.Arena
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
    ): Level {
        val tiles = TileMap(WIDTH, HEIGHT)
        for (x in 0 until WIDTH) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Solid
        for (x in gapColumns) for (y in FLOOR_ROW + 1 until HEIGHT) tiles[x, y] = TileKind.Empty
        for (x in acidColumns) tiles[x, FLOOR_ROW + 1] = TileKind.Acid
        for (x in committedColumns) tiles[x, 0] = TileKind.Acid
        wallColumn?.let { x -> for (y in FLOOR_ROW - 2..FLOOR_ROW) tiles[x, y] = TileKind.Solid }

        val arc = Mask(WIDTH, HEIGHT).also { it.markRect(0, FLOOR_ROW - 1, WIDTH - 1, FLOOR_ROW) }
        return Level(
            mapIndex = 1,
            theme = ThemeId.RuinedCitySprawl,
            tiles = tiles,
            floorMask = Mask(WIDTH, HEIGHT),
            arcMask = arc,
            spawnColumn = SPAWN_COLUMN,
            spawnRow = FLOOR_ROW + 1,
            miniboss = Arena(80, 92, FLOOR_ROW + 1),
            boss = Arena(100, 114, FLOOR_ROW + 1),
            jets = emptyList(),
            gateColumn = 115,
        )
    }

    fun simulation(level: Level = flat()): GameSimulation =
        GameSimulation(level, RunState.begin(SEED), SEED)

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
}
