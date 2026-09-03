package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.entity.EnemyArchetype
import io.github.ksean.cyberslop.entity.EnemySpawn
import io.github.ksean.cyberslop.world.Level

/**
 * Places enemies along the route, but never where a hit would be unavoidable.
 *
 * An earlier version kept patrols clear of the *entire* corridor. In a side-scroller the corridor is
 * essentially all the standable ground, so almost nowhere qualified — and the only floor the player
 * never walked was the far end of the boss arena. Every enemy on every map ended up pooled there,
 * which a human playtest found immediately and no test had noticed.
 *
 * What actually needs protecting is a **committed span**: a gap or an acid crossing, where the
 * player is airborne on a trajectory they can no longer change. Two rules follow. Nothing patrols
 * within [COMMITTED_BUFFER] tiles of one, and nothing that shoots has a clear line into one.
 * Everywhere else, meeting an enemy is the game.
 *
 * The claim is deliberately narrow: the route can be crossed **without being forced into an
 * unavoidable hit**. It is not a claim that enemies can be ignored.
 */
object Populator {
    /** Clearance kept between a patrol and a span the player cannot steer out of. */
    const val COMMITTED_BUFFER = 3

    /** Inclusive horizontal radius around map start that no complete initial patrol may enter. */
    const val START_CLEAR_TILES = 22

    /**
     * No initial patrol touches an arena or its approach. This is distinct from runtime pursuit,
     * which may carry an engaged enemy onto mini-boss ground (PROD-112). Twenty tiles, not the six
     * of the carved ramp, also lets the player outrun a pack before the final encounter.
     */
    const val ARENA_APPROACH_TILES = 20

    /** Per map, not per cohort (`specs/enemies.md`): most of what the player meets is melee. */
    const val MAX_RANGED_SHARE = 0.35
    const val MIN_ARCHETYPES = 3

    fun populate(level: Level, rng: Rng, curve: DifficultyCurve): List<EnemySpawn> {
        // Density follows the difficulty curve, as `specs/generation.md` says it should.
        val target = (level.widthTiles / 100.0 * curve.enemiesPerHundredTiles)
            .toInt()
            .coerceIn(MIN_ENEMIES, MAX_ENEMIES)
        val placed = mutableListOf<EnemySpawn>()

        var attempts = 0
        while (placed.size < target && attempts < target * ATTEMPTS_PER_ENEMY) {
            attempts++
            val column = rng.nextInt(level.widthTiles)
            val archetype = pickArchetype(rng)
            val patrol = 1 + rng.nextInt(3)
            val row = standableRow(level, column) ?: continue
            val spawn = EnemySpawn(archetype, column, row, patrol)

            if (!isClearOfStart(level, spawn)) continue
            if (!isClearOfCommittedSpans(level, spawn)) continue
            if (!isClearOfArenas(level, spawn)) continue
            if (archetype.shoots && seesCommittedSpan(level, spawn)) continue
            if (archetype.shoots && (placed.count { it.archetype.shoots } + 1) > target * MAX_RANGED_SHARE) continue
            placed.add(spawn)
        }
        return withArchetypeFloor(placed)
    }

    /**
     * Independent draws can leave a map with two kinds; the spec promises three. The last spawns
     * are re-drawn as the missing melee kinds, which every placement rule already admits.
     */
    private fun withArchetypeFloor(placed: MutableList<EnemySpawn>): List<EnemySpawn> {
        val melee = listOf(EnemyArchetype.Swarm, EnemyArchetype.Brute, EnemyArchetype.Flyer)
        var index = placed.size - 1
        while (placed.map { it.archetype }.toSet().size < MIN_ARCHETYPES && index >= 0) {
            val missing = melee.first { kind -> placed.none { it.archetype == kind } }
            placed[index] = placed[index].copy(archetype = missing)
            index--
        }
        return placed
    }

    /**
     * Weighted so that most of what the player meets is melee.
     *
     * Drawing uniformly from the five kinds made two of them ranged, so 40% of every map shot at the
     * player from off-screen — a guaranteed loadout following the intended route died on map four
     * to nothing but accumulated chip damage.
     */
    private fun pickArchetype(rng: Rng): EnemyArchetype {
        val roll = rng.nextDouble()
        var running = 0.0
        WEIGHTS.forEach { (archetype, weight) ->
            running += weight
            if (roll < running) return archetype
        }
        return EnemyArchetype.Swarm
    }

    private val WEIGHTS = listOf(
        EnemyArchetype.Swarm to 0.38,
        EnemyArchetype.Brute to 0.22,
        EnemyArchetype.Flyer to 0.18,
        EnemyArchetype.Shooter to 0.15,
        EnemyArchetype.Turret to 0.07,
    )

    /** Initial patrols stay out of both encounter zones; engaged pursuit may enter mini-boss ground. */
    fun isClearOfArenas(level: Level, spawn: EnemySpawn): Boolean {
        return (spawn.leftTile..spawn.rightTile).none { column ->
            level.isMinibossGround(column, ARENA_APPROACH_TILES) ||
                level.isMainBossGround(column, ARENA_APPROACH_TILES)
        }
    }

    /** Keeps initial awareness and auto-aim quiet until the player advances from the spawn. */
    fun isClearOfStart(level: Level, spawn: EnemySpawn): Boolean =
        spawn.rightTile < level.spawnColumn - START_CLEAR_TILES ||
            spawn.leftTile > level.spawnColumn + START_CLEAR_TILES

    /** No part of the patrol may sit on or beside a span the player crosses committed. */
    fun isClearOfCommittedSpans(level: Level, spawn: EnemySpawn): Boolean {
        for (column in spawn.leftTile - COMMITTED_BUFFER..spawn.rightTile + COMMITTED_BUFFER) {
            if (isCommitted(level, column)) return false
        }
        return true
    }

    /**
     * A committed span is one the player crosses without being able to change course: a gap, or
     * anything over acid. A shooter with an unobstructed line into one can land a hit that no input
     * would have avoided.
     */
    fun seesCommittedSpan(level: Level, spawn: EnemySpawn): Boolean {
        for (column in 0 until level.widthTiles) {
            if (!isCommitted(level, column)) continue
            for (row in 0 until level.tiles.height) {
                if (!level.arcMask[column, row]) continue
                if (hasLineOfFire(level, spawn.column, spawn.row, column, row)) return true
            }
        }
        return false
    }

    fun isCommitted(level: Level, column: Int): Boolean = level.isCommitted(column)

    private fun hasLineOfFire(
        level: Level,
        fromColumn: Int,
        fromRow: Int,
        toColumn: Int,
        toRow: Int,
    ): Boolean {
        val steps = maxOf(kotlin.math.abs(toColumn - fromColumn), kotlin.math.abs(toRow - fromRow))
        if (steps == 0) return true
        if (steps > MAX_SIGHT_TILES) return false
        for (step in 1 until steps) {
            val column = fromColumn + (toColumn - fromColumn) * step / steps
            val row = fromRow + (toRow - fromRow) * step / steps
            if (level.tiles.blocksMovement(column, row)) return false
        }
        return true
    }

    /**
     * The lowest standable row in a column — the main floor rather than whatever decoration happens
     * to sit highest. Scanning downward found ledges first and stood enemies on the scenery.
     */
    private fun standableRow(level: Level, column: Int): Int? {
        for (row in level.tiles.height - 2 downTo 0) {
            if (level.tiles.blocksMovement(column, row + 1) &&
                !level.tiles.blocksMovement(column, row) &&
                !level.tiles.isLethal(column, row)
            ) {
                return row
            }
        }
        return null
    }

    private const val MIN_ENEMIES = 8
    private const val ATTEMPTS_PER_ENEMY = 24
    /** Above the documented map-ten target of 64; a lower cap made final-map density dip. */
    private const val MAX_ENEMIES = 72
    private const val MAX_SIGHT_TILES = 24
}
