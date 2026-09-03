package io.github.ksean.cyberslop.world

import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState

/** A flat, hazard-free room with a left entry and a right exit at floor level. */
data class Arena(val leftTile: Int, val rightTile: Int, val floorRow: Int) {
    val widthTiles: Int get() = rightTile - leftTile + 1
    val centreTile: Int get() = (leftTile + rightTile) / 2

    fun containsColumn(tile: Int): Boolean = tile in leftTile..rightTile
}

/**
 * A vertical jet occupying one column between two rows, cycling on and off.
 *
 * Generation places at most one of these per corridor. Two jets in one corridor can be anti-phased
 * so their union is never off, which no per-jet check would catch — so the configuration is never
 * built rather than detected later.
 */
data class FireJet(
    val column: Int,
    val topRow: Int,
    val bottomRow: Int,
    val periodSeconds: Double,
    val onSeconds: Double,
    val phaseSeconds: Double,
) {
    val offWindowSeconds: Double get() = periodSeconds - onSeconds

    fun isOnAt(timeSeconds: Double): Boolean {
        val cycle = (timeSeconds + phaseSeconds) % periodSeconds
        return cycle < onSeconds
    }

    /** Seconds until this jet is off and stays off for at least [forSeconds]. */
    fun secondsUntilSafeWindow(timeSeconds: Double, forSeconds: Double): Double {
        if (offWindowSeconds < forSeconds) return Double.POSITIVE_INFINITY
        val cycle = (timeSeconds + phaseSeconds) % periodSeconds
        return when {
            cycle < onSeconds -> onSeconds - cycle
            cycle + forSeconds <= periodSeconds -> 0.0
            else -> periodSeconds - cycle + onSeconds
        }
    }

    fun coversRow(row: Int): Boolean = row in topRow..bottomRow
}

/**
 * Where a statically placed pickup stands (PROD-047).
 *
 * Generation decides *where*; the simulation decides *what*, because what a pickup yields depends on
 * the run — which weapons the account has unlocked, and that run's powerup pool — and generation
 * knows none of that.
 */
data class PickupSite(val column: Int, val row: Int) {
    /**
     * Where the pickup actually sits: the middle of the cell, not its top-left corner.
     *
     * A review round found it realised at the corner and then drawn centred there, which put every
     * "ground" pickup most of a tile above the surface and half a tile to its left.
     */
    val centre: io.github.ksean.cyberslop.core.Vec2
        get() = io.github.ksean.cyberslop.core.Vec2(
            TileMap.toWorld(column) + TILE_SIZE / 2.0,
            TileMap.toWorld(row) + TILE_SIZE / 2.0,
        )
}

class Level(
    val mapIndex: Int,
    val theme: ThemeId,
    val tiles: TileMap,
    val floorMask: Mask,
    val arcMask: Mask,
    val spawnColumn: Int,
    val spawnRow: Int,
    val miniboss: Arena,
    val boss: Arena,
    val jets: List<FireJet>,
    val enemies: List<io.github.ksean.cyberslop.entity.EnemySpawn> = emptyList(),
    /** Statically placed pickups, averaging two per map across seeds (PROD-047). */
    val pickups: List<PickupSite> = emptyList(),
    /**
     * The column sealing the boss arena's exit.
     *
     * Solid while the boss lives and cleared on its death, so "defeating the main boss allows the
     * player to reach the end of the map" is a thing the geometry does rather than a state flag.
     */
    val gateColumn: Int = 0,
    /** Burning barrels, placed off the witness route after it is proven (`specs/hazards.md`). */
    val barrels: List<Barrel> = emptyList(),
) {
    val widthTiles: Int get() = tiles.width

    /** The same level with a different set of barrels; the tile grid is shared. */
    fun withBarrels(barrels: List<Barrel>): Level = Level(
        mapIndex, theme, tiles, floorMask, arcMask, spawnColumn, spawnRow, miniboss, boss, jets,
        enemies, pickups, gateColumn, barrels,
    )

    /**
     * Columns the player crosses committed: airborne over no floor, or over a lethal tile
     * (`specs/completability.md`). Generation keeps spawns away from them; the simulation keeps
     * Flyers out of them and lets no enemy damage land while the player is over one.
     */
    val committedColumns: BooleanArray by lazy {
        BooleanArray(widthTiles) { column -> isCommittedNow(column) }
    }

    fun isCommitted(column: Int): Boolean = column in 0 until widthTiles && committedColumns[column]

    /** The mini-boss patrol ground normal enemies may enter after engagement (PROD-112). */
    fun isMinibossGround(column: Int, approachTiles: Int): Boolean =
        column in miniboss.leftTile - approachTiles..miniboss.rightTile

    /** The final encounter and exit ground rank-and-file enemies never enter (PROD-112). */
    fun isMainBossGround(column: Int, approachTiles: Int): Boolean =
        column >= boss.leftTile - approachTiles

    private fun isCommittedNow(column: Int): Boolean {
        for (row in 0 until tiles.height) if (tiles.isLethal(column, row)) return true
        val corridorRow = (0 until tiles.height).firstOrNull { arcMask[column, it] } ?: return false
        return (corridorRow until tiles.height).none { tiles.blocksMovement(column, it) }
    }

    /** The player standing at rest on the spawn plateau. */
    fun spawnState(physics: Physics = Physics.Default): PlayerState =
        PlayerState(
            x = TileMap.toWorld(spawnColumn),
            y = TileMap.toWorld(spawnRow) - physics.standingHeight,
            onGround = true,
        )
}

enum class ThemeId(val displayName: String) {
    RuinedCitySprawl("Ruined City Sprawl"),
    RustFlats("Rust Flats"),
    FloodedUndercity("Flooded Undercity"),
    ChemFoundry("Chem Foundry"),
    NeonSlums("Neon Slums"),
    SableRefinery("Sable Refinery"),
    ServerStacks("Server Stacks"),
    SkybridgeRuin("Skybridge Ruin"),
    ReactorCore("Reactor Core"),
    ArcologyVault("Arcology Vault"),
}
