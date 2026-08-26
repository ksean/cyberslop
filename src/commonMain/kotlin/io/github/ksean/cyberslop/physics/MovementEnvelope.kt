package io.github.ksean.cyberslop.physics

import io.github.ksean.cyberslop.world.TILE_SIZE
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap
import kotlin.math.floor

/** Fraction of a measured maximum that generation may use. The rest is the player's error margin. */
private const val GAP_SCALE = 0.70
private const val STEP_UP_SCALE = 0.80

/**
 * What the movement model can actually do, established by running it.
 *
 * Everything here is measured as **a question generation actually asks** — "can the player cross a
 * gap this wide", "can the player climb a step this tall" — rather than as a displacement. An
 * earlier version measured a horizontal reach and then subtracted a tile to "absorb player width",
 * but its launch point was the player's leading edge and its landing point the trailing edge, so the
 * width had already been removed once. The number was not the reach it claimed to be. Asking the
 * question directly removes the coordinate ambiguity entirely.
 */
class MovementEnvelope internal constructor(
    private val maxGapByDrop: Map<Int, Int>,
    private val maxStandingGapByDrop: Map<Int, Int>,
    val maxStepUpTiles: Int,
    val apexPx: Double,
    val runwayPx: Double,
    val stoppingDistancePx: Double,
) {
    val runwayTiles: Int get() = ceilTiles(runwayPx)

    /**
     * Tiles a walk must stop short of a carved edge. Releasing the key exactly at the target leaves
     * the player coasting while friction bleeds off speed, which carries them past it.
     */
    val brakeMarginTiles: Int get() = ceilTiles(stoppingDistancePx) + 1

    /** Tiles a landing platform needs to absorb the widest jump plus the coast that follows it. */
    fun landingTiles(dropTiles: Int): Int = maxGapTiles(dropTiles) + brakeMarginTiles + 2

    /** Widest gap the player can clear at all, landing [dropTiles] below the take-off. */
    fun maxGapTiles(dropTiles: Int): Int =
        maxGapByDrop[dropTiles] ?: error("no gap measurement for a drop of $dropTiles tiles")

    fun maxGapTilesFromStandstill(dropTiles: Int): Int =
        maxStandingGapByDrop[dropTiles] ?: error("no standing measurement for $dropTiles tiles")

    val measuredDrops: Set<Int> get() = maxGapByDrop.keys

    fun scaledGapTiles(dropTiles: Int): Double = GAP_SCALE * maxGapTiles(dropTiles)

    /** The widest gap generation may place. */
    fun gapMaxTiles(dropTiles: Int): Int = floor(scaledGapTiles(dropTiles)).toInt()

    val scaledStepUpTiles: Double get() = STEP_UP_SCALE * maxStepUpTiles

    val stepUpMaxTiles: Int get() = floor(scaledStepUpTiles).toInt()

    private fun ceilTiles(px: Double): Int {
        val tiles = px / TILE_SIZE
        val whole = tiles.toInt()
        return if (tiles > whole.toDouble()) whole + 1 else whole
    }
}

private const val PLATFORM_ROW = 40
private const val MEASURE_HEIGHT = 96
private const val MAX_TICKS = 900
private const val WIDEST_GAP_TRIED = 14
private const val TALLEST_STEP_TRIED = 10
private val DROPS = 0..8

/**
 * Runs the real integrator against real geometry. Every loop is bounded, so a caller experimenting
 * with [Physics] — a test doubling gravity, or setting acceleration to zero — gets a wrong-looking
 * envelope rather than a hang.
 */
fun measureEnvelope(physics: Physics = Physics.Default): MovementEnvelope {
    val runwayPx = measureRunway(physics)
    val runupTiles = (runwayPx / TILE_SIZE).toInt() + 4

    return MovementEnvelope(
        maxGapByDrop = DROPS.associateWith { drop -> widestGap(physics, drop, runupTiles) },
        maxStandingGapByDrop = DROPS.associateWith { drop -> widestGap(physics, drop, runupTiles = 0) },
        maxStepUpTiles = tallestStep(physics, runupTiles),
        apexPx = measureApex(physics),
        runwayPx = runwayPx,
        stoppingDistancePx = measureStoppingDistance(physics),
    )
}

private fun widestGap(physics: Physics, dropTiles: Int, runupTiles: Int): Int {
    var widest = -1
    for (gap in 0..WIDEST_GAP_TRIED) {
        if (!canCrossGap(physics, gap, dropTiles, runupTiles)) break
        widest = gap
    }
    return widest
}

private fun canCrossGap(physics: Physics, gapTiles: Int, dropTiles: Int, runupTiles: Int): Boolean {
    val lipTile = runupTiles + 2
    val landingTile = lipTile + gapTiles
    val world = TileMap(width = landingTile + 12, height = MEASURE_HEIGHT)
    for (x in 0 until lipTile) world[x, PLATFORM_ROW] = TileKind.Solid
    for (x in landingTile until world.width) world[x, PLATFORM_ROW + dropTiles] = TileKind.Solid

    val start = restingAt(physics, TileMap.toWorld(lipTile) - TILE_SIZE * runupTiles - physics.width)

    // A player picks their take-off moment. Trying each tick of the approach is what that means.
    for (jumpDelay in 0..MAX_JUMP_DELAY) {
        if (crossesFrom(start, physics, world, jumpDelay, landingTile, dropTiles)) return true
    }
    return false
}

private fun crossesFrom(
    start: PlayerState,
    physics: Physics,
    world: TileMap,
    jumpDelay: Int,
    landingTile: Int,
    dropTiles: Int,
): Boolean {
    var state = start
    val run = InputFrame(right = true)
    val leap = InputFrame(right = true, jump = true)

    repeat(jumpDelay) {
        state = MovementModel.step(state, run, world, physics)
        if (!state.onGround) return false
    }
    if (!state.onGround) return false

    state = MovementModel.step(state, leap.copy(jumpStart = true), world, physics)
    var ticks = 0
    while (!state.onGround && ticks < MAX_TICKS) {
        state = MovementModel.step(state, leap, world, physics)
        ticks++
    }
    if (!state.onGround) return false

    // Resting feet sit exactly on the platform's top boundary, so the bottom edge floors to the
    // platform's own row rather than the one below it.
    val landedRow = TileMap.toTile(state.y + physics.standingHeight)
    return state.x >= TileMap.toWorld(landingTile) && landedRow == PLATFORM_ROW + dropTiles
}

private fun tallestStep(physics: Physics, runupTiles: Int): Int {
    var tallest = 0
    for (step in 1..TALLEST_STEP_TRIED) {
        if (!canClimbStep(physics, step, runupTiles)) break
        tallest = step
    }
    return tallest
}

private fun canClimbStep(physics: Physics, stepTiles: Int, runupTiles: Int): Boolean {
    val wallTile = runupTiles + 4
    val world = TileMap(width = wallTile + 12, height = MEASURE_HEIGHT)
    for (x in 0 until wallTile) world[x, PLATFORM_ROW] = TileKind.Solid
    for (x in wallTile until world.width) {
        for (y in PLATFORM_ROW - stepTiles until MEASURE_HEIGHT) world[x, y] = TileKind.Solid
    }

    var state = restingAt(physics, TileMap.toWorld(wallTile) - TILE_SIZE * runupTiles - physics.width)
    val climb = InputFrame(right = true, jump = true)
    state = MovementModel.step(state, climb.copy(jumpStart = true), world, physics)

    var ticks = 0
    while (ticks < MAX_TICKS) {
        state = MovementModel.step(state, climb, world, physics)
        ticks++
        val standingRow = TileMap.toTile(state.y + physics.standingHeight)
        if (state.onGround && standingRow == PLATFORM_ROW - stepTiles) return true
        if (state.onGround && ticks > 4) return false
    }
    return false
}

private fun flatWorld(): TileMap {
    val world = TileMap(width = 400, height = MEASURE_HEIGHT)
    for (x in 0 until world.width) world[x, PLATFORM_ROW] = TileKind.Solid
    return world
}

private fun measureApex(physics: Physics): Double {
    val world = flatWorld()
    var state = restingAt(physics, 64.0)
    val start = state.y
    var peak = start
    repeat(MAX_TICKS) { tick ->
        state = MovementModel.step(state, InputFrame(jump = true, jumpStart = tick == 0), world, physics)
        if (state.y < peak) peak = state.y
    }
    return start - peak
}

private fun measureRunway(physics: Physics): Double {
    val world = flatWorld()
    var state = restingAt(physics, 32.0)
    val start = state.x
    var ticks = 0
    while (state.vx < physics.maxRunSpeed - 0.001 && ticks < MAX_TICKS) {
        state = MovementModel.step(state, InputFrame(right = true), world, physics)
        ticks++
    }
    return state.x - start
}

private fun measureStoppingDistance(physics: Physics): Double {
    val world = flatWorld()
    var state = restingAt(physics, 32.0)
    var ticks = 0
    while (state.vx < physics.maxRunSpeed - 0.001 && ticks < MAX_TICKS) {
        state = MovementModel.step(state, InputFrame(right = true), world, physics)
        ticks++
    }
    val start = state.x
    ticks = 0
    while (state.vx > 0.001 && ticks < MAX_TICKS) {
        state = MovementModel.step(state, InputFrame(), world, physics)
        ticks++
    }
    return state.x - start
}

private fun restingAt(physics: Physics, x: Double): PlayerState =
    PlayerState(
        x = x.coerceAtLeast(0.0),
        y = TileMap.toWorld(PLATFORM_ROW) - physics.standingHeight,
        onGround = true,
    )

private const val MAX_JUMP_DELAY = 240
