package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.verify.Foothold
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.PickupSite

/**
 * Places the pickups a map carries before the player fires a shot (PROD-047).
 *
 * `plan.md` §6.7 planned these as "cache / dead terminal" awards on maps 3–10 and **none of it was
 * ever built** — the only pre-placed item in the game was map 1's starter cache.
 *
 * Two rules decide where one may stand. It must be a cell the **witness actually stood on** —
 * proof, not a proxy: the map's own verified tape put the player's feet there, through the game's
 * own movement model, so no search of its own is needed and none runs at generation time where the
 * budget is measured (`plan.md` §9.2 property 22).
 *
 * The first version used the arc mask instead, and a review round showed that to be unsound:
 * [SpineWalker.rollback] deliberately does not rewind the mask, so it retains cells from abandoned
 * move proposals no witness ever traverses. A pickup could have been placed on a route that was
 * carved, rejected, and never walked.
 *
 * And it must clear a **committed span** by [Populator.COMMITTED_BUFFER], for exactly `Populator`'s
 * reason: a pickup beside a gap is a lure onto a jump the player cannot steer out of, which is
 * worse than an enemy there because the player *wants* to go.
 *
 * Arenas are excluded because a pickup sealed in with a boss arrives after the fight it would have
 * helped with.
 */
object StaticDrops {
    /** Uniform over `[MIN, MAX]`, whose mean is the 2.0 PROD-047 asks for. */
    const val MIN = 1
    const val MAX = 3

    /** How far apart two pickups must be to read as two finds rather than one pile. */
    const val MIN_SEPARATION_TILES = 12

    fun place(level: Level, footholds: Set<Foothold>, rng: Rng): List<PickupSite> {
        val wanted = MIN + rng.nextInt(MAX - MIN + 1)
        val sites = eligibleSites(level, footholds)
        if (sites.isEmpty()) return emptyList()

        // One per band, and separated. Banding alone does not space them: two candidates either
        // side of a boundary are neighbouring cells, and the fallback below can pick anywhere. A
        // review round caught that claim being made and not kept, so the separation is now a filter.
        //
        // A band with nothing eligible falls back to the whole list rather than yielding fewer than
        // asked for — a count that quietly shrinks would move the mean off 2.0 with no test seeing
        // why — and the separation relaxes only if nothing at all qualifies, which the cohort test
        // asserts never happens.
        val chosen = mutableListOf<PickupSite>()
        val bandWidth = level.widthTiles.toDouble() / wanted
        repeat(wanted) { band ->
            val from = (band * bandWidth).toInt()
            val to = ((band + 1) * bandWidth).toInt()
            val spaced = sites.filter { it.column in from until to && isClearOf(chosen, it) }
            val pool = spaced
                .ifEmpty { sites.filter { isClearOf(chosen, it) } }
                .ifEmpty { sites.filter { it !in chosen } }
            if (pool.isNotEmpty()) chosen.add(pool[rng.nextInt(pool.size)])
        }
        return chosen
    }

    /**
     * Every cell a pickup may stand on. Enumerated once rather than sampled with retries: a map is
     * at most 720 columns, and a rejection loop whose failure mode is "placed fewer than asked" is
     * how the mean silently stops being what the requirement says.
     */
    private fun eligibleSites(level: Level, footholds: Set<Foothold>): List<PickupSite> {
        val committed = BooleanArray(level.widthTiles) { Populator.isCommitted(level, it) }

        // Sorted so placement is a function of the level and the seed, never of a hash order.
        return footholds
            .asSequence()
            .filter { it.column in 0 until level.widthTiles }
            .filter { !level.miniboss.containsColumn(it.column) }
            .filter { !level.boss.containsColumn(it.column) }
            .filter { !nearCommitted(committed, it.column) }
            .filter { standable(level, it.column, it.row) }
            .map { PickupSite(it.column, it.row) }
            .distinct()
            .sortedWith(compareBy({ it.column }, { it.row }))
            .toList()
    }

    /**
     * The witness proves the player stood here; this proves a pickup can too.
     *
     * Kept as a separate check rather than trusted from the replay, because a foothold is where the
     * player's *feet* were and a pickup has to sit in a cell that is clear, not lethal, and has
     * floor beneath it. On a well-formed level these agree; asserting it costs three tile reads.
     */
    private fun standable(level: Level, column: Int, row: Int): Boolean =
        !level.tiles.blocksMovement(column, row) &&
            !level.tiles.isLethal(column, row) &&
            level.tiles.blocksMovement(column, row + 1)

    /** Far enough from everything already placed that the two read as separate finds. */
    private fun isClearOf(chosen: List<PickupSite>, site: PickupSite): Boolean =
        chosen.none { held ->
            val gap = held.column - site.column
            (if (gap < 0) -gap else gap) < MIN_SEPARATION_TILES
        }

    private fun nearCommitted(committed: BooleanArray, column: Int): Boolean {
        val from = (column - Populator.COMMITTED_BUFFER).coerceAtLeast(0)
        val to = (column + Populator.COMMITTED_BUFFER).coerceAtMost(committed.size - 1)
        for (at in from..to) if (committed[at]) return true
        return false
    }

}
