package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.verify.Foothold
import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.verify.WitnessReplay
import io.github.ksean.cyberslop.world.Barrel
import io.github.ksean.cyberslop.world.Cell
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TileKind

/**
 * Places damaging hazards last, off the proven route (`specs/hazards.md`).
 *
 * A candidate cell is standable and every cell of the hazard's footprint keeps [CLEARANCE] from
 * every witness foothold and every static pickup, is outside the arc mask, and is outside both
 * arenas and the ramp before each. That is placement *by construction*; [confirm] then replays the
 * tape against the placed hazards and removes anything it still touches, so the shipped route is
 * hazard-free *by measurement* too.
 */
object HazardPlacer {
    /** Chebyshev distance every footprint cell keeps from footholds and pickups. */
    const val CLEARANCE = 2

    /** The approach before an arena that stays clear, matching the generator's ramp. */
    const val RAMP_TILES = 6

    private const val SPIKE_SHARE = 2
    private const val MAX_STRIP = 3
    private const val ATTEMPTS_PER_HAZARD = 20

    /** Writes spike strips into the tiles and returns the barrels to carry on the level. */
    fun place(level: Level, footholds: Set<Foothold>, rng: Rng, curve: DifficultyCurve): List<Barrel> {
        val wanted = (curve.damagingHazardsPerHundredTiles * level.widthTiles / 100.0).toInt()
        if (wanted == 0) return emptyList()

        val eligible = eligibleCells(level, footholds)
        val candidates = eligible.filter { standable(level, it) }
        if (candidates.isEmpty()) return emptyList()

        val used = mutableSetOf<Cell>()
        val barrels = mutableListOf<Barrel>()
        var placed = 0
        var attempts = 0
        while (placed < wanted && attempts < wanted * ATTEMPTS_PER_HAZARD) {
            attempts++
            val at = candidates[rng.nextInt(candidates.size)]
            if (rng.nextInt(SPIKE_SHARE + 1) < SPIKE_SHARE) {
                val length = 1 + rng.nextInt(MAX_STRIP)
                val strip = (0 until length).map { Cell(at.column + it, at.row) }
                if (strip.any { it !in candidates || it in used }) continue
                strip.forEach { level.tiles[it.column, it.row] = TileKind.Spikes }
                used.addAll(strip)
            } else {
                val barrel = Barrel(at.column, at.row)
                val flame = Cell(barrel.column, barrel.flameRow)
                if (flame !in eligible || level.tiles.blocksMovement(flame.column, flame.row)) continue
                if (barrel.cells.any { it in used }) continue
                barrels.add(barrel)
                used.addAll(barrel.cells)
            }
            placed++
        }
        return barrels
    }

    /**
     * The confirming pass: replays the tape against the placed hazards and removes every spike
     * strip and barrel it touched. Deterministic, and it removes nothing else.
     */
    fun confirm(level: Level, barrels: List<Barrel>, witness: Witness): List<Barrel> {
        val touched = WitnessReplay.replay(level.withBarrels(barrels), witness).touchedHazards
        if (touched.isEmpty()) return barrels
        Hazards.spikeStrips(level)
            .filter { strip -> strip.any { it in touched } }
            .forEach { strip -> strip.forEach { level.tiles[it.column, it.row] = TileKind.Empty } }
        return barrels.filter { barrel -> barrel.cells.none { it in touched } }
    }

    /**
     * Keeps the candidates, in deterministic placement order, only while both shipped enemy boxes
     * retain a pursuit leap. This is separate from [confirm]: the witness protects the player;
     * this pass protects the enemies' chase envelope.
     */
    fun confirmPursuit(level: Level, barrels: List<Barrel>): List<Barrel> {
        val kept = mutableListOf<Barrel>()
        kept.addAll(barrels)
        while (true) {
            val violations = EnemyPursuitEnvelope.audit(level.withBarrels(kept))
            if (violations.isEmpty()) return kept
            // Select against one immutable snapshot. The same obstacle can produce one violation
            // per body and direction; mutating between those reports made the later duplicate
            // remove the next, unrelated hazard.
            val strips = Hazards.spikeStrips(level)
            val barrelSnapshot = kept.toList()
            val stripsToRemove = mutableSetOf<List<Cell>>()
            val barrelsToRemove = mutableSetOf<Barrel>()
            violations.forEach { violation ->
                val direction = violation.direction
                fun ahead(column: Int): Int {
                    val distance = (column - violation.takeoffColumn) * direction
                    return if (distance >= 0) distance else Int.MAX_VALUE
                }

                val strip = strips.minByOrNull { candidate ->
                    candidate.minOf { ahead(it.column) }
                }?.takeIf { candidate -> candidate.any { ahead(it.column) != Int.MAX_VALUE } }
                val barrel = barrelSnapshot.minByOrNull { ahead(it.column) }
                    ?.takeIf { ahead(it.column) != Int.MAX_VALUE }

                val stripDistance = strip?.minOf { ahead(it.column) } ?: Int.MAX_VALUE
                val barrelDistance = barrel?.let { ahead(it.column) } ?: Int.MAX_VALUE
                when {
                    stripDistance <= barrelDistance && strip != null -> {
                        stripsToRemove += strip
                    }
                    barrel != null -> barrelsToRemove += barrel
                }
            }
            if (stripsToRemove.isNotEmpty() || barrelsToRemove.isNotEmpty()) {
                stripsToRemove.flatten().forEach { level.tiles[it.column, it.row] = TileKind.Empty }
                kept.removeAll(barrelsToRemove)
            } else {
                // The terrain-only audit ran immediately before placement, so this is defensive:
                // a hazard behind an unusually wide box can still be the overlap being reported.
                val strip = Hazards.spikeStrips(level).firstOrNull()
                when {
                    strip != null -> strip.forEach { level.tiles[it.column, it.row] = TileKind.Empty }
                    kept.isNotEmpty() -> kept.removeAt(0)
                    else -> return kept
                }
            }
        }
    }

    /** Every cell a footprint may occupy, before asking whether one can stand there. */
    private fun eligibleCells(level: Level, footholds: Set<Foothold>): Set<Cell> {
        val width = level.widthTiles
        val height = level.tiles.height
        val forbidden = Array(height) { BooleanArray(width) }

        fun forbidAround(column: Int, row: Int) {
            for (y in row - CLEARANCE + 1..row + CLEARANCE - 1) {
                for (x in column - CLEARANCE + 1..column + CLEARANCE - 1) {
                    if (level.tiles.contains(x, y)) forbidden[y][x] = true
                }
            }
        }
        footholds.forEach { forbidAround(it.column, it.row) }
        level.pickups.forEach { forbidAround(it.column, it.row) }

        val keepOut = listOf(level.miniboss, level.boss).flatMap { arena ->
            listOf(arena.leftTile - RAMP_TILES..arena.rightTile)
        }

        return buildSet {
            for (row in 0 until height) for (column in 0 until width) {
                if (forbidden[row][column] || level.arcMask[column, row]) continue
                if (keepOut.any { column in it }) continue
                add(Cell(column, row))
            }
        }
    }

    private fun standable(level: Level, cell: Cell): Boolean =
        level.tiles[cell.column, cell.row] == TileKind.Empty &&
            !level.tiles.blocksMovement(cell.column, cell.row - 1) &&
            level.tiles[cell.column, cell.row + 1] == TileKind.Solid
}
