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

    private const val SPIKE_WEIGHT = 2
    private const val GLASS_WEIGHT = 2
    private const val BARREL_WEIGHT = 1
    private const val MAX_STRIP = 3
    private const val MAX_GLASS_PATCH = 2
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
            // Keep the candidate array's seeded ordering stable, but never realise a gate/exit hazard.
            if (at.column >= level.gateColumn) continue
            when (rng.nextInt(SPIKE_WEIGHT + GLASS_WEIGHT + BARREL_WEIGHT)) {
                in 0 until SPIKE_WEIGHT -> {
                    val length = 1 + rng.nextInt(MAX_STRIP)
                    val strip = (0 until length).map { Cell(at.column + it, at.row) }
                    if (strip.any { it !in candidates || it in used }) continue
                    strip.forEach { level.tiles[it.column, it.row] = TileKind.Spikes }
                    used.addAll(strip)
                }
                in SPIKE_WEIGHT until SPIKE_WEIGHT + GLASS_WEIGHT -> {
                    val length = 1 + rng.nextInt(MAX_GLASS_PATCH)
                    val patch = (0 until length).map { Cell(at.column + it, at.row) }
                    if (patch.any { it !in candidates || it in used }) continue
                    val beside = listOf(
                        Cell(patch.first().column - 1, at.row),
                        Cell(patch.last().column + 1, at.row),
                    )
                    if (beside.any { level.tiles[it.column, it.row] == TileKind.BrokenGlass }) continue
                    patch.forEach { level.tiles[it.column, it.row] = TileKind.BrokenGlass }
                    used.addAll(patch)
                }
                else -> {
                    val barrel = Barrel(at.column, at.row)
                    val flame = Cell(barrel.column, barrel.flameRow)
                    if (flame !in eligible || level.tiles.blocksMovement(flame.column, flame.row)) continue
                    if (barrel.cells.any { it in used }) continue
                    barrels.add(barrel)
                    used.addAll(barrel.cells)
                }
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
        val exitSafeBarrels = removeExitHazards(level, barrels)
        val touched = WitnessReplay.replay(level.withBarrels(exitSafeBarrels), witness).touchedHazards
        if (touched.isEmpty()) return exitSafeBarrels
        Hazards.spikeStrips(level)
            .filter { strip -> strip.any { it in touched } }
            .forEach { strip -> strip.forEach { level.tiles[it.column, it.row] = TileKind.Empty } }
        Hazards.glassPatches(level)
            .filter { patch -> patch.any { it in touched } }
            .forEach { patch -> patch.forEach { level.tiles[it.column, it.row] = TileKind.Empty } }
        return exitSafeBarrels.filter { barrel -> barrel.cells.none { it in touched } }
    }

    /**
     * Greedily keeps candidates in deterministic footprint-efficiency order only while both
     * shipped enemy boxes retain a pursuit leap. This is separate from [confirm]: the witness
     * protects the player; this pass protects the enemies' chase envelope.
     */
    fun confirmPursuit(level: Level, barrels: List<Barrel>): List<Barrel> {
        val candidates = (
            Hazards.spikeStrips(level).map { PursuitCandidate(cells = it, kind = TileKind.Spikes) } +
                Hazards.glassPatches(level).map {
                    PursuitCandidate(cells = it, kind = TileKind.BrokenGlass)
                } +
                barrels.map { PursuitCandidate(barrel = it) }
            ).sortedWith(compareBy(PursuitCandidate::priority, PursuitCandidate::column))
        candidates.flatMap { it.cells.orEmpty() }
            .forEach { level.tiles[it.column, it.row] = TileKind.Empty }

        val kept = mutableListOf<Barrel>()
        candidates.forEach { candidate ->
            candidate.cells?.forEach { level.tiles[it.column, it.row] = requireNotNull(candidate.kind) }
            candidate.barrel?.let(kept::add)
            if (EnemyPursuitEnvelope.audit(level.withBarrels(kept)).isNotEmpty()) {
                candidate.cells?.forEach { level.tiles[it.column, it.row] = TileKind.Empty }
                candidate.barrel?.let(kept::remove)
            }
        }
        return kept
    }

    private data class PursuitCandidate(
        val cells: List<Cell>? = null,
        val kind: TileKind? = null,
        val barrel: Barrel? = null,
    ) {
        val column: Int get() = cells?.first()?.column ?: requireNotNull(barrel).column
        /** Most pressure for the least occupied ground first, so safe capacity is not wasted. */
        val priority: Int get() = when {
            kind == TileKind.Spikes && cells?.size == 1 -> 0
            barrel != null -> 1
            kind == TileKind.Spikes -> requireNotNull(cells).size
            else -> MAX_STRIP + requireNotNull(cells).size
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
                if (column >= level.gateColumn) continue
                if (forbidden[row][column] || level.arcMask[column, row]) continue
                if (keepOut.any { column in it }) continue
                add(Cell(column, row))
            }
        }
    }

    /** Final invariant boundary, also covering fault-injected candidates used by verification. */
    private fun removeExitHazards(level: Level, barrels: List<Barrel>): List<Barrel> {
        for (column in level.gateColumn until level.widthTiles) {
            for (row in 0 until level.tiles.height) {
                if (level.tiles[column, row] == TileKind.Spikes ||
                    level.tiles[column, row] == TileKind.BrokenGlass
                ) {
                    level.tiles[column, row] = TileKind.Empty
                }
            }
        }
        return barrels.filter { barrel -> barrel.cells.all { it.column < level.gateColumn } }
    }

    private fun standable(level: Level, cell: Cell): Boolean =
        level.tiles[cell.column, cell.row] == TileKind.Empty &&
            !level.tiles.blocksMovement(cell.column, cell.row - 1) &&
            level.tiles[cell.column, cell.row + 1] == TileKind.Solid
}
