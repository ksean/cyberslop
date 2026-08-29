package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind

/**
 * How hard a generated map is, measured **from the tiles that were produced** rather than from the
 * parameters that asked for them.
 *
 * Scoring the parameters would be circular: a curve that rises by construction proves nothing about
 * the artifact. Map index is deliberately not an input, for the same reason — including it would
 * make the metric trivially monotone and say nothing.
 *
 * Enemy density is deliberately **not** an input. Placement is rejection-based against the corridor
 * invariants, so its density carries real noise, and enemies are excluded from the traversal
 * guarantee anyway — this measures what crossing the map demands, which is what the difficulty curve
 * actually controls.
 *
 * This is a *generation* metric. It is not a claim about how hard a human finds the map, which is a
 * different question and needs a person to answer (`specs/engineering.md`, Verification).
 */
object DifficultyScore {
    fun of(level: Level): Double {
        val columns = level.widthTiles.toDouble()

        var hazardTiles = 0
        var floorTiles = 0
        var gapColumns = 0
        var highestFloor = level.tiles.height
        var lowestFloor = 0

        for (column in 0 until level.widthTiles) {
            var hasFloor = false
            for (row in 0 until level.tiles.height) {
                when (level.tiles[column, row]) {
                    TileKind.Acid -> hazardTiles++
                    TileKind.Solid -> {
                        floorTiles++
                        if (!hasFloor) {
                            hasFloor = true
                            if (row < highestFloor) highestFloor = row
                            if (row > lowestFloor) lowestFloor = row
                        }
                    }
                    else -> Unit
                }
            }
            if (!hasFloor) gapColumns++
        }

        val hazardFraction = hazardTiles / columns
        val gapFraction = gapColumns / columns
        // Duty cycle rather than count: how much of the time a corridor is actually blocked is what
        // the curve drives, and it is far less noisy than how many corridors happened to be placed.
        val jetPressure = level.jets.sumOf { it.onSeconds / it.periodSeconds } / columns * 100.0
        val verticality = (lowestFloor - highestFloor).coerceAtLeast(0) / VERTICAL_REFERENCE
        val openness = 1.0 - (floorTiles / (columns * level.tiles.height))

        return GAP_WEIGHT * gapFraction +
            HAZARD_WEIGHT * hazardFraction +
            JET_WEIGHT * jetPressure +
            VERTICAL_WEIGHT * verticality +
            OPENNESS_WEIGHT * openness
    }

    // Weighted toward the things that actually make a map harder to cross. Openness carries very
    // little: it varies with a theme's look rather than with what the map asks of the player, and
    // weighting it heavily let a decorative difference outvote a real one.
    private const val GAP_WEIGHT = 60.0
    private const val HAZARD_WEIGHT = 70.0
    private const val JET_WEIGHT = 25.0
    private const val VERTICAL_WEIGHT = 12.0
    private const val OPENNESS_WEIGHT = 1.0
    private const val VERTICAL_REFERENCE = 32.0
}
