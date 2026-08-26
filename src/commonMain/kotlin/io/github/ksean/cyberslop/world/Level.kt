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
    /**
     * The column sealing the boss arena's exit.
     *
     * Solid while the boss lives and cleared on its death, so "defeating the main boss allows the
     * player to reach the end of the map" is a thing the geometry does rather than a state flag.
     */
    val gateColumn: Int = 0,
) {
    val widthTiles: Int get() = tiles.width

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
