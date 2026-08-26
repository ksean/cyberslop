package io.github.ksean.cyberslop.verify

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs

/** A place the player can stop: ground below, headroom for [stance], no lethal contact. */
data class RestCell(val column: Int, val row: Int, val stance: Stance = Stance.Stand)

/**
 * Two reachability relations over one level, because the guarantee reasons in opposite directions.
 *
 * **What this does and does not prove.** The shipped completability guarantee (PROD-024) is
 * discharged by [WitnessReplay], which runs one exact tape and needs no reachability analysis at
 * all. What lives here is the *escape* analysis: a level can be completable and still trap a player
 * who steps off the intended route. That question ranges over states no single tape visits, so it
 * needs a relation rather than a replay.
 *
 * [underReachEdges] must never claim a traversal the player cannot perform. Every edge is a control
 * program executed by the real movement model, and every node is a **rest** state — grounded,
 * motionless, near a canonical position within its tile.
 *
 * **The residual is bounded, not eliminated.** A rollout settles wherever physics leaves it and then
 * steers onto its cell's canonical position, failing the edge if it cannot get within
 * [CANONICAL_TOLERANCE] px. The next rollout begins from the exact canonical point, so a chain of
 * edges teleports by up to that tolerance at each step. Bounding it to a pixel and a half is a great
 * deal better than the tile it was before, but it is not a proof that an edge validated from the
 * exact centre is executable from every accepted predecessor. Stated plainly because this is a
 * verification aid, not the shipped guarantee: PROD-024 is discharged by replaying one exact tape
 * ([WitnessReplay]), which needs no composition at all.
 *
 * [overReach] must never miss a place the player might end up. It floods with a rise budget rather
 * than a ground test: a player who jumps, drifts sideways over a pit and keeps climbing is somewhere
 * a ground-only rule would not look.
 */
object Reachability {
    private const val REST_EPSILON = 0.001
    private const val MAX_ROLLOUT_TICKS = 240

    /** How close to a cell's canonical position a rollout must settle for its edge to count. */
    const val CANONICAL_TOLERANCE = 1.5

    fun restCells(level: Level, physics: Physics = Physics.Default): Set<RestCell> {
        val cells = mutableSetOf<RestCell>()
        for (column in 0 until level.widthTiles) {
            for (row in 0 until level.tiles.height - 1) {
                if (!level.tiles.blocksMovement(column, row + 1)) continue
                if (level.tiles.isLethal(column, row)) continue
                for (stance in Stance.entries) {
                    if (clearFor(level, column, row, stance, physics)) {
                        cells.add(RestCell(column, row, stance))
                    }
                }
            }
        }
        return cells
    }

    private fun clearFor(
        level: Level,
        column: Int,
        row: Int,
        stance: Stance,
        physics: Physics,
    ): Boolean {
        val height = if (stance == Stance.Crouch) physics.crouchingHeight else physics.standingHeight
        val tiles = ceilTiles(height)
        for (offset in 0 until tiles) {
            if (level.tiles.blocksMovement(column, row - offset)) return false
        }
        return true
    }

    fun underReachEdges(
        level: Level,
        cells: Set<RestCell> = restCells(level),
        physics: Physics = Physics.Default,
    ): Map<RestCell, Set<RestCell>> {
        val edges = mutableMapOf<RestCell, MutableSet<RestCell>>()
        for (cell in cells) {
            val from = canonicalState(cell, physics)
            val reached = edges.getOrPut(cell) { mutableSetOf() }
            for (program in CATALOG) {
                val landing = rollout(from, program, level, physics) ?: continue
                if (landing != cell && landing in cells) reached.add(landing)
            }
        }
        return edges
    }

    fun reachableFrom(start: RestCell, edges: Map<RestCell, Set<RestCell>>): Set<RestCell> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in edges[current].orEmpty()) if (seen.add(next)) queue.add(next)
        }
        return seen
    }

    fun reversed(edges: Map<RestCell, Set<RestCell>>): Map<RestCell, Set<RestCell>> {
        val backward = mutableMapOf<RestCell, MutableSet<RestCell>>()
        for ((from, targets) in edges) {
            backward.getOrPut(from) { mutableSetOf() }
            for (to in targets) backward.getOrPut(to) { mutableSetOf() }.add(from)
        }
        return backward
    }

    /**
     * Every open cell the player could conceivably occupy.
     *
     * Carries a rise budget: leaving the ground grants [apexTiles] of climb, horizontal motion
     * preserves it, climbing spends it, and descending forfeits it until ground is touched again.
     * An earlier version allowed climbing only from a cell with ground directly beneath, which
     * misses the ordinary case of jumping and then drifting sideways over a pit while still rising —
     * so real player positions went unexamined and real pockets could hide.
     */
    fun overReach(level: Level, start: RestCell, apexTiles: Int): Set<Pair<Int, Int>> {
        val best = mutableMapOf<Pair<Int, Int>, Int>()
        val queue = ArrayDeque<Triple<Int, Int, Int>>()

        fun offer(column: Int, row: Int, budget: Int) {
            if (column !in 0 until level.widthTiles) return
            if (row !in 0 until level.tiles.height) return
            if (level.tiles.blocksMovement(column, row)) return
            val grounded = level.tiles.blocksMovement(column, row + 1)
            val effective = if (grounded) apexTiles else budget
            val key = column to row
            if ((best[key] ?: -1) >= effective) return
            best[key] = effective
            queue.add(Triple(column, row, effective))
        }

        offer(start.column, start.row, apexTiles)
        while (queue.isNotEmpty()) {
            val (column, row, budget) = queue.removeFirst()
            offer(column - 1, row, budget)
            offer(column + 1, row, budget)
            offer(column, row + 1, 0)
            if (budget > 0) offer(column, row - 1, budget - 1)
        }
        return best.keys
    }

    private class Program(
        val runFrames: Int,
        val jumpHold: Int,
        val direction: Int,
        val jump: Boolean,
        val crouch: Boolean = false,
    )

    private val CATALOG: List<Program> = buildList {
        for (direction in listOf(-1, 0, 1)) {
            for (runFrames in listOf(0, 9, 18)) {
                for (hold in listOf(4, 8, 40)) {
                    add(Program(runFrames, hold, direction, jump = true))
                }
            }
        }
        for (direction in listOf(-1, 1)) {
            add(Program(runFrames = 120, jumpHold = 0, direction = direction, jump = false))
            // Crouch traversal, without which a duct's interior is unreachable in the analysis even
            // though the generator carves ducts the player walks through.
            add(Program(120, jumpHold = 0, direction = direction, jump = false, crouch = true))
        }
        // Standing up and crouching in place, so stance changes are edges rather than assumptions.
        add(Program(runFrames = 30, jumpHold = 0, direction = 0, jump = false, crouch = true))
        add(Program(runFrames = 30, jumpHold = 0, direction = 0, jump = false))
    }

    private fun rollout(
        start: PlayerState,
        program: Program,
        level: Level,
        physics: Physics,
    ): RestCell? {
        var state = start
        val move = InputFrame(
            left = program.direction < 0,
            right = program.direction > 0,
            crouch = program.crouch,
        )

        repeat(program.runFrames) {
            state = MovementModel.step(state, move, level.tiles, physics)
            if (state.touchedLethal) return null
        }

        if (program.jump) {
            if (!state.onGround) return null
            state = MovementModel.step(
                state, move.copy(jump = true, jumpStart = true), level.tiles, physics,
            )
            if (state.touchedLethal) return null
            var held = 1
            var ticks = 0
            while (ticks < MAX_ROLLOUT_TICKS) {
                state = MovementModel.step(
                    state, move.copy(jump = held < program.jumpHold), level.tiles, physics,
                )
                held++
                ticks++
                if (state.touchedLethal) return null
                if (state.onGround) break
            }
            if (!state.onGround) return null
        }

        val settled = settle(state, level, physics, program.crouch) ?: return null
        val cell = RestCell(
            column = TileMap.toTile(settled.x + physics.width / 2.0),
            row = TileMap.toTile(settled.y + settled.height(physics)) - 1,
            stance = settled.stance,
        )

        // Steer onto the cell's canonical position, or this edge does not compose with the next.
        val aligned = align(settled, cell, level, physics, program.crouch) ?: return null
        return if (abs(aligned.x - canonicalX(cell, physics)) <= CANONICAL_TOLERANCE) cell else null
    }

    private fun settle(
        from: PlayerState,
        level: Level,
        physics: Physics,
        crouch: Boolean,
    ): PlayerState? {
        var state = from
        var ticks = 0
        while (ticks < MAX_ROLLOUT_TICKS && !(state.onGround && abs(state.vx) < REST_EPSILON)) {
            state = MovementModel.step(state, InputFrame(crouch = crouch), level.tiles, physics)
            if (state.touchedLethal) return null
            ticks++
        }
        return if (state.onGround && abs(state.vx) < REST_EPSILON) state else null
    }

    private fun align(
        from: PlayerState,
        cell: RestCell,
        level: Level,
        physics: Physics,
        crouch: Boolean,
    ): PlayerState? {
        var state = from
        val target = canonicalX(cell, physics)
        var ticks = 0
        while (ticks < ALIGN_TICKS) {
            val delta = target - state.x
            if (abs(delta) <= CANONICAL_TOLERANCE && abs(state.vx) < REST_EPSILON) return state
            val braking = state.vx * state.vx / (2.0 * physics.groundFriction)
            val press = abs(delta) > braking + CANONICAL_TOLERANCE
            val input = InputFrame(
                left = press && delta < 0,
                right = press && delta > 0,
                crouch = crouch,
            )
            state = MovementModel.step(state, input, level.tiles, physics)
            if (state.touchedLethal) return null
            if (!state.onGround) return null
            ticks++
        }
        return null
    }

    private fun canonicalX(cell: RestCell, physics: Physics): Double =
        TileMap.toWorld(cell.column) + (TILE_SIZE - physics.width) / 2.0

    private fun canonicalState(cell: RestCell, physics: Physics): PlayerState {
        val height =
            if (cell.stance == Stance.Crouch) physics.crouchingHeight else physics.standingHeight
        return PlayerState(
            x = canonicalX(cell, physics),
            y = TileMap.toWorld(cell.row + 1) - height,
            onGround = true,
            stance = cell.stance,
        )
    }

    private fun ceilTiles(px: Double): Int {
        val tiles = px / TILE_SIZE
        val whole = tiles.toInt()
        return if (tiles > whole.toDouble()) whole + 1 else whole
    }

    private const val ALIGN_TICKS = 120
}
