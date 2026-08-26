package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.MovementModel
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.PlayerState
import io.github.ksean.cyberslop.physics.Stance
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.verify.WitnessStep
import io.github.ksean.cyberslop.world.FireJet
import kotlin.math.ceil
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.abs

/**
 * Walks the player through the level *while it is being carved*, recording the input frames that
 * did it.
 *
 * This is what makes the witness emitted rather than searched for. The generator picks a move from
 * the measured envelope, carves it, and asks this walker to perform it; if the walker cannot, the
 * move was not really within budget and generation fails loudly instead of shipping a map nobody can
 * cross. Every frame recorded here was produced by the game's own movement model against the real
 * tiles, so replaying them later is a proof rather than a re-derivation.
 *
 * Every move ends at **rest** — grounded, with horizontal velocity zero. That makes each anchor one
 * physical state rather than a family of them, so moves compose: the next move always begins from
 * exactly the state the previous one ended in.
 */
class SpineWalker(
    private val tiles: TileMap,
    private val arcMask: Mask,
    private val physics: Physics = Physics.Default,
    start: PlayerState,
) {
    var state: PlayerState = start
        private set

    private val frames = mutableListOf<InputFrame>()
    private val steps = mutableListOf<WitnessStep>()
    private var emitted = 0

    /** A point a move can be abandoned back to, so a proposal that fails is never committed. */
    class Checkpoint internal constructor(
        internal val state: PlayerState,
        internal val frames: Int,
        internal val steps: Int,
        internal val emitted: Int,
    )

    fun checkpoint(): Checkpoint = Checkpoint(state, frames.size, steps.size, emitted)

    /**
     * Abandons everything since [mark]. The arc mask is deliberately *not* rewound: marking extra
     * open cells only makes decoration more cautious, and unmarking cells another move may since
     * have relied on would be unsafe.
     */
    fun rollback(mark: Checkpoint) {
        state = mark.state
        emitted = mark.emitted
        while (frames.size > mark.frames) frames.removeLast()
        while (steps.size > mark.steps) steps.removeLast()
    }

    fun witness(): Witness {
        flush()
        return Witness(steps.toList())
    }

    /** Stand still until settled. Establishes the rest state every move begins from. */
    fun rest(): Boolean = settled()

    /**
     * The tile row the player is standing on.
     *
     * Every move checks this against the row the generator carved. Without it a move can report
     * success while the player is at the bottom of the map: the world's out-of-bounds floor is
     * solid, so falling into a pit still satisfies "grounded and at rest". The generator then keeps
     * carving a spine the player is no longer on.
     */
    val standingRow: Int
        get() = TileMap.toTile(state.y + state.height(physics))

    /**
     * Walks right and **stops on** [targetX] rather than at it.
     *
     * Releasing the key exactly at the target is wrong: the player still carries full speed and
     * coasts roughly a tile further while friction bleeds it off. On a platform whose last tile is
     * the target, that coast carries them over the edge and into the void — which is precisely how
     * the first version of this generator failed, several moves into every level it tried to build.
     * Braking distance is a function of current speed, so the release point is too.
     */
    fun walkRightTo(targetX: Double, expectedRow: Int, maxTicks: Int = 1200): Boolean {
        val reached = run(maxTicks, until = { it.x + brakingDistance(it) >= targetX }) {
            InputFrame(right = true)
        }
        if (!reached) return false
        val ok = settled() && standingRow == expectedRow
        flush()
        return ok
    }

    fun crouchRightTo(targetX: Double, expectedRow: Int, maxTicks: Int = 1800): Boolean {
        val reached = run(maxTicks, until = { it.x + brakingDistance(it) >= targetX }) {
            InputFrame(right = true, crouch = true)
        }
        if (!reached) return false
        // Stay crouched while braking; standing up under a duct ceiling is impossible anyway, and
        // the stance resolves itself once the ceiling clears.
        run(SETTLE_TICKS, until = { it.onGround && abs(it.vx) < REST_EPSILON }) {
            InputFrame(crouch = true)
        }
        return state.onGround && abs(state.vx) < REST_EPSILON
    }

    private fun brakingDistance(current: PlayerState): Double =
        if (!current.onGround) 0.0
        else current.vx * current.vx / (2.0 * physics.groundFriction)

    /**
     * Runs, jumps and lands with the player's box fully past [landingLeftX] and standing on
     * [landingRow]. The take-off tick is chosen by trying each one, which is what a player does.
     */
    fun jumpRightOnto(landingLeftX: Double, landingRow: Int, maxTicks: Int = 300): Boolean {
        for (delay in 0..MAX_TAKE_OFF_DELAY) {
            val attempt = attemptJump(delay, landingLeftX, landingRow, maxTicks) ?: continue
            frames.addAll(attempt.frames)
            emitted += attempt.frames.size
            attempt.trail.zipWithNext { from, to -> markArc(from, to) }
            state = attempt.end
            val ok = settled() && standingRow == landingRow
            flush()
            return ok
        }
        return false
    }

    /** Ticks emitted so far. The level clock runs from zero, so this *is* the current time. */
    val elapsedTicks: Int get() = emitted

    /**
     * Stands still until [jet] is off for long enough to cross, emitting the wait as real frames.
     *
     * An earlier version recorded a symbolic "wait for this jet" step resolved at replay time. That
     * made the stored witness not literally the input sequence PROD-024 says the generator holds.
     * Because the level clock is deterministic from zero, the wait is computable here, so the
     * witness stays a plain tape.
     */
    fun waitForJetOff(jet: FireJet, crossingSeconds: Double): Boolean {
        val delay = jet.secondsUntilSafeWindow(elapsedTicks * TICK_SECONDS, crossingSeconds)
        if (!delay.isFinite()) return false
        val ticks = ceil(delay / TICK_SECONDS).toInt().coerceAtLeast(0)
        if (ticks > MAX_JET_WAIT_TICKS) return false
        repeat(ticks) {
            val previous = state
            state = MovementModel.step(state, InputFrame(), tiles, physics)
            frames.add(InputFrame())
            emitted++
            markArc(previous, state)
            if (state.touchedLethal) return false
        }
        return true
    }

    /** True when [jet] is off for every tick in `[fromTick, toTick)`. */
    fun jetStayedOff(jet: FireJet, fromTick: Int, toTick: Int): Boolean =
        (fromTick until toTick).none { jet.isOnAt(it * TICK_SECONDS) }

    private class Attempt(
        val frames: List<InputFrame>,
        val end: PlayerState,
        /** Every intermediate state, so the swept volume can be masked exactly. */
        val trail: List<PlayerState>,
    )

    private fun attemptJump(
        delay: Int,
        landingLeftX: Double,
        landingRow: Int,
        maxTicks: Int,
    ): Attempt? {
        var simulated = state
        val recorded = mutableListOf<InputFrame>()
        val trail = mutableListOf(simulated)
        val approach = InputFrame(right = true)

        repeat(delay) {
            simulated = MovementModel.step(simulated, approach, tiles, physics)
            recorded.add(approach)
            trail.add(simulated)
            if (!simulated.onGround || simulated.touchedLethal) return null
        }
        if (!simulated.onGround) return null

        val leap = InputFrame(right = true, jump = true)
        var frame = leap.copy(jumpStart = true)
        var ticks = 0
        while (ticks < maxTicks) {
            simulated = MovementModel.step(simulated, frame, tiles, physics)
            recorded.add(frame)
            trail.add(simulated)
            if (simulated.touchedLethal) return null
            ticks++
            if (simulated.onGround && ticks > 1) break
            frame = leap
        }
        if (!simulated.onGround) return null

        val standingRow = TileMap.toTile(simulated.y + physics.standingHeight)
        if (standingRow != landingRow) return null
        if (simulated.x < landingLeftX) return null
        return Attempt(recorded, simulated, trail)
    }

    private fun run(
        maxTicks: Int,
        until: (PlayerState) -> Boolean = { false },
        frame: (PlayerState) -> InputFrame,
    ): Boolean {
        var ticks = 0
        while (ticks < maxTicks) {
            if (until(state)) return true
            val input = frame(state)
            val previous = state
            state = MovementModel.step(state, input, tiles, physics)
            frames.add(input)
            emitted++
            markArc(previous, state)
            if (state.touchedLethal) return false
            ticks++
        }
        return until(state)
    }

    private fun settled(): Boolean {
        var ticks = 0
        while (ticks < SETTLE_TICKS && !(state.onGround && abs(state.vx) < REST_EPSILON)) {
            val previous = state
            state = MovementModel.step(state, InputFrame(), tiles, physics)
            frames.add(InputFrame())
            emitted++
            markArc(previous, state)
            if (state.touchedLethal) return false
            ticks++
        }
        return state.onGround && abs(state.vx) < REST_EPSILON
    }

    /**
     * Records the volume the player's box **sweeps through**, so decoration cannot obstruct it.
     *
     * Marking only where the box lands each tick leaves holes: a fall at terminal velocity covers
     * 16.7 px in one tick, wider than a tile, so whole rows between two sampled positions went
     * unmarked and were free for decoration to fill with something solid. The union of the two
     * boxes covers the sweep.
     */
    private fun markArc(from: PlayerState, to: PlayerState) {
        val left = TileMap.toTile(minOf(from.x, to.x))
        val right = TileMap.toTile(maxOf(from.x, to.x) + physics.width - EDGE)
        val top = TileMap.toTile(minOf(from.y, to.y))
        val bottom = TileMap.toTile(
            maxOf(from.y + from.height(physics), to.y + to.height(physics)) - EDGE,
        )
        // Only open cells. The union rectangle is a conservative cover of an L-shaped path and can
        // enclose geometry the spine deliberately carved; the invariant this mask serves is that
        // *decoration* must not add anything solid where the player passes.
        for (x in left..right) {
            for (y in top..bottom) {
                if (!tiles.blocksMovement(x, y)) arcMask[x, y] = true
            }
        }
    }

    private fun flush() {
        if (frames.isEmpty()) return
        steps.add(WitnessStep(frames.toList()))
        frames.clear()
    }

    private companion object {
        const val MAX_TAKE_OFF_DELAY = 160
        const val SETTLE_TICKS = 120
        const val REST_EPSILON = 0.001
        const val EDGE = 0.001
        const val MAX_JET_WAIT_TICKS = 600
    }
}
