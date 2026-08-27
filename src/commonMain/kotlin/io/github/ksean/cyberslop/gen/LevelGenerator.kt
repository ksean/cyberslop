package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.core.Rng
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.measureEnvelope
import io.github.ksean.cyberslop.verify.Witness
import io.github.ksean.cyberslop.verify.WitnessReplay
import io.github.ksean.cyberslop.world.Arena
import io.github.ksean.cyberslop.world.FireJet
import io.github.ksean.cyberslop.world.Level
import io.github.ksean.cyberslop.world.Mask
import io.github.ksean.cyberslop.world.TileKind
import io.github.ksean.cyberslop.world.TileMap

data class GenerationReport(
    val attempts: Int,
    val repairs: Int,
    val usedFallback: Boolean,
    /** Why each earlier attempt was discarded. Empty when the first attempt succeeded. */
    val discarded: List<String> = emptyList(),
)

data class GeneratedLevel(val level: Level, val witness: Witness, val report: GenerationReport)

/**
 * Builds a map and the witness that crosses it, in one pass.
 *
 * The spine is laid from moves clamped to the measured movement envelope, and each move is performed
 * by [SpineWalker] against the real tiles as it is carved — so the witness is a by-product of
 * construction rather than the result of searching afterwards. Verification then replays that
 * witness through the shipping simulation, and a map whose witness does not arrive is never returned
 * (ENG-056).
 */
object LevelGenerator {
    private const val HEIGHT = 64
    private const val SPAWN_TILES = 10
    private const val ARENA_CLEARANCE = 6
    private const val RAMP_TILES = 6
    private const val MAX_ATTEMPTS = 8
    private const val REACTION_SECONDS = 0.25
    private const val JET_CROSSING_SPEED_FRACTION = 0.75
    private const val EXIT_CORRIDOR_TILES = 10
    private const val STEP_UP_WEIGHT = 0.14
    private const val DROP_WEIGHT = 0.12
    private const val DUCT_WEIGHT = 0.10
    private const val FLAT_WEIGHT = 0.18

    /**
     * Distances the player's own physics decides, taken from the measured envelope rather than
     * written down (ENG-055). Typing them as literals meant a change to friction or jump could move
     * what the player can do without moving what generation asks of them.
     */
    private class Budget(envelope: io.github.ksean.cyberslop.physics.MovementEnvelope) {
        val brakeMargin = envelope.brakeMarginTiles
        val landing = envelope.landingTiles(0)
        val ductLength = envelope.brakeMarginTiles * 2 + 2
        val ductVariation = envelope.brakeMarginTiles
        val jetApproach = envelope.brakeMarginTiles
        val jetSafe = envelope.brakeMarginTiles + 2
        val jetCrossing = envelope.brakeMarginTiles * 2
        val maxDrop = envelope.stepUpMaxTiles + 1
        val flatVariation = envelope.landingTiles(0) / 2
        /** How far into an arena the player walks before it counts as entered. */
        val arenaEntry = envelope.brakeMarginTiles
    }

    fun generate(seed: ULong, mapIndex: Int): GeneratedLevel =
        build(seed, mapIndex, decorate = true)

    /** Generation with the decoration pass skipped, so tests can compare against the bare spine. */
    fun generateWithoutDecoration(seed: ULong, mapIndex: Int): GeneratedLevel =
        build(seed, mapIndex, decorate = false)

    private fun build(seed: ULong, mapIndex: Int, decorate: Boolean): GeneratedLevel {
        val theme = Themes.forMap(mapIndex)
        val profile = Themes.of(theme)
        val curve = DifficultyCurve.at(mapIndex)
        val envelope = measureEnvelope(Physics.Default)

        val failures = mutableListOf<String>()
        for (attempt in 1..MAX_ATTEMPTS) {
            val attemptSeed = if (attempt == 1) seed else mixAttempt(seed, attempt)
            val outcome = carve(attemptSeed, mapIndex, theme, profile, curve, envelope)
            val built = outcome.built
            if (built == null) { failures += "attempt $attempt: ${outcome.reason}"; continue }


            if (decorate) {
                Decorator.decorate(built.level, Rng.derive(attemptSeed, mapIndex, "decor"))
            }
            val populated = built.withEnemies(
                Populator.populate(
                    built.level, Rng.derive(attemptSeed, mapIndex, "enemy"), curve,
                ),
            )

            // Against the populated level, which is the one handed to the player. The tile grid is
            // shared, so this was equivalent in practice — but replaying a different object from the
            // one returned is the kind of gap that stops being harmless the moment the two diverge.
            val replay = WitnessReplay.replay(populated.level, populated.witness)
            if (!replay.succeeded) {
                failures += "attempt $attempt: replay boss=${replay.reachedBoss} " +
                    "miniboss=${replay.reachedMiniboss} lethal=${replay.touchedLethal} " +
                    "x=${replay.finalState.x}"
                continue
            }

            // Only now, from the footholds the replay just proved. A pickup is placed on ground the
            // witness stood on rather than on a mask that also holds abandoned proposals; and it is
            // placed *after* the replay because the replay is what produces the proof.
            //
            // This is the one field that differs between the object replayed and the object
            // returned. `WitnessReplayTest` asserts that adding pickups cannot change a replay,
            // which is what keeps the single replay honest.
            val withPickups = populated.withPickups(
                StaticDrops.place(
                    populated.level,
                    replay.footholds,
                    Rng.derive(attemptSeed, mapIndex, "cache"),
                ),
            )

            return GeneratedLevel(
                withPickups.level,
                withPickups.witness,
                GenerationReport(
                    attempts = attempt,
                    repairs = 0,
                    usedFallback = false,
                    discarded = failures.toList(),
                ),
            )
        }
        error(
            "no completable level for seed $seed map $mapIndex after $MAX_ATTEMPTS attempts: " +
                failures.joinToString("; "),
        )
    }

    private class Built(val level: Level, val witness: Witness) {
        fun withEnemies(enemies: List<io.github.ksean.cyberslop.entity.EnemySpawn>) =
            copy(enemies = enemies)

        fun withPickups(pickups: List<io.github.ksean.cyberslop.world.PickupSite>) =
            copy(pickups = pickups)

        private fun copy(
            enemies: List<io.github.ksean.cyberslop.entity.EnemySpawn> = level.enemies,
            pickups: List<io.github.ksean.cyberslop.world.PickupSite> = level.pickups,
        ) = Built(
            Level(
                mapIndex = level.mapIndex, theme = level.theme, tiles = level.tiles,
                floorMask = level.floorMask, arcMask = level.arcMask,
                spawnColumn = level.spawnColumn, spawnRow = level.spawnRow,
                miniboss = level.miniboss, boss = level.boss, jets = level.jets,
                enemies = enemies, pickups = pickups, gateColumn = level.gateColumn,
            ),
            witness,
        )
    }

    /** A carve attempt: the level if it worked, otherwise why it did not. */
    private class Outcome(val built: Built?, val reason: String)

    private fun carve(
        seed: ULong,
        mapIndex: Int,
        theme: io.github.ksean.cyberslop.world.ThemeId,
        profile: ThemeProfile,
        curve: DifficultyCurve,
        envelope: io.github.ksean.cyberslop.physics.MovementEnvelope,
    ): Outcome {
        val rng = Rng.derive(seed, mapIndex, "spine")
        val width = curve.widthTiles
        val tiles = TileMap(width, HEIGHT)
        val floorMask = Mask(width, HEIGHT)
        val arcMask = Mask(width, HEIGHT)
        val jets = mutableListOf<FireJet>()

        val band = curve.verticalBandTiles
        val baseRow = HEIGHT - 14
        val highestRow = (baseRow - band).coerceAtLeast(ARENA_CLEARANCE + 2)

        var floorRow = baseRow
        var cursor = 0
        var segmentReason = ""

        // Every tile a move writes is journalled, so a move that turns out not to work can be
        // abandoned completely rather than repaired in place. That keeps "correct by construction"
        // honest: nothing uncrossable is ever committed and later patched.
        val journal = mutableListOf<Triple<Int, Int, TileKind>>()

        // The mask is journalled too. Restoring only tiles left protected cells behind at the
        // elevation a rejected move tried, which decoration then read as spine geometry — so a move
        // nobody took still shaped the map.
        val maskJournal = mutableListOf<Pair<Int, Int>>()

        fun write(x: Int, y: Int, kind: TileKind) {
            if (!tiles.contains(x, y)) return
            journal.add(Triple(x, y, tiles[x, y]))
            tiles[x, y] = kind
        }

        fun protect(x: Int, y: Int) {
            if (!tiles.contains(x, y) || floorMask[x, y]) return
            maskJournal.add(x to y)
            floorMask[x, y] = true
        }

        fun rollbackTo(tileMark: Int, maskMark: Int) {
            while (journal.size > tileMark) {
                val (x, y, kind) = journal.removeLast()
                tiles[x, y] = kind
            }
            while (maskJournal.size > maskMark) {
                val (x, y) = maskJournal.removeLast()
                floorMask[x, y] = false
            }
        }

        fun carveFloor(from: Int, count: Int, row: Int) {
            for (x in from until (from + count).coerceAtMost(width)) {
                write(x, row, TileKind.Solid)
                protect(x, row)
                for (y in (row - ARENA_CLEARANCE).coerceAtLeast(0) until row) protect(x, y)
            }
        }

        carveFloor(0, SPAWN_TILES, floorRow)
        cursor = SPAWN_TILES

        val spawnColumn = 2
        // Captured now, not at the end. `floorRow` moves as the spine climbs and drops, and a Level
        // built with the final value starts the replay at the wrong height — so the witness diverges
        // from the path the walker actually took, on exactly those maps whose terrain changes
        // elevation.
        val spawnRow = floorRow
        val walker = SpineWalker(
            tiles = tiles,
            arcMask = arcMask,
            start = Level(
                mapIndex, theme, tiles, floorMask, arcMask, spawnColumn, floorRow,
                Arena(0, 0, floorRow), Arena(0, 0, floorRow), emptyList(),
            ).spawnState(),
        )
        if (!walker.rest()) return Outcome(null, "spawn rest failed")

        val arenaWidth = profile.arenaWidthTiles
        val minibossLeft = width / 2 - arenaWidth / 2
        // Room past the arena for a gate and a corridor to walk out through. Leaving only a couple
        // of columns made the right-hand edge a pit: the player reached the end of the map and fell
        // out of the world instead of finishing.
        val bossLeft = width - arenaWidth - EXIT_CORRIDOR_TILES - 1

        // --- first half ---
        if (!runSegment(walker, rng, profile, curve, envelope, tiles, jets,
                ::carveFloor, { journal.size to maskJournal.size }, ::rollbackTo, ::write,
                cursor, minibossLeft - RAMP_TILES, floorRow, baseRow, highestRow
            ).also { cursor = it.cursor; floorRow = it.floorRow; segmentReason = it.reason }.ok
        ) return Outcome(null, "first segment: $segmentReason")

        // Carve the approach *and* the arena before walking. Braking overshoots slightly, so
        // walking toward ground that has not been carved yet drops the player into the pit below.
        val miniboss = Arena(minibossLeft, minibossLeft + arenaWidth - 1, floorRow)
        carveFloor(cursor, minibossLeft - cursor, floorRow)
        carveFloor(minibossLeft, arenaWidth, floorRow)
        if (!walker.walkRightTo(
                TileMap.toWorld(miniboss.rightTile - Budget(envelope).brakeMargin), floorRow,
            )
        ) {
            return Outcome(null, "walk through miniboss failed at cursor=$cursor row=$floorRow x=${walker.state.x}")
        }
        cursor = miniboss.rightTile + 1

        // --- second half ---
        if (!runSegment(walker, rng, profile, curve, envelope, tiles, jets,
                ::carveFloor, { journal.size to maskJournal.size }, ::rollbackTo, ::write,
                cursor, bossLeft - RAMP_TILES, floorRow, baseRow, highestRow
            ).also { cursor = it.cursor; floorRow = it.floorRow; segmentReason = it.reason }.ok
        ) return Outcome(null, "second segment: $segmentReason")

        val budget = Budget(envelope)
        val boss = Arena(bossLeft, (bossLeft + arenaWidth - 1).coerceAtMost(width - 1), floorRow)
        carveFloor(cursor, bossLeft - cursor, floorRow)
        carveFloor(boss.leftTile, boss.widthTiles, floorRow)

        // Walk *into* the arena rather than up to its edge. Braking begins before the target, so
        // aiming at the boundary itself leaves the player a tile or two short of having entered.
        if (!walker.walkRightTo(TileMap.toWorld(boss.leftTile + budget.arenaEntry), floorRow)) {
            return Outcome(null, "walk into boss arena failed at cursor=$cursor x=${walker.state.x}")
        }

        // The exit corridor, then the gate that seals it.
        val gateColumn = boss.rightTile + 1
        carveFloor(gateColumn, width - gateColumn, floorRow)
        for (row in floorRow - ARENA_CLEARANCE until floorRow) {
            write(gateColumn, row, TileKind.Solid)
            floorMask[gateColumn, row] = true
        }

        val level = Level(
            mapIndex = mapIndex,
            theme = theme,
            tiles = tiles,
            floorMask = floorMask,
            arcMask = arcMask,
            spawnColumn = spawnColumn,
            spawnRow = spawnRow,
            miniboss = miniboss,
            boss = boss,
            jets = jets.toList(),
            gateColumn = gateColumn,
        )
        return Outcome(Built(level, walker.witness()), "")
    }

    private class SegmentResult(
        val ok: Boolean,
        val cursor: Int,
        val floorRow: Int,
        val reason: String = "",
    )

    /**
     * Lays one half of the spine.
     *
     * Each move is a *proposal*: the geometry is carved, the walker is asked to perform it, and only
     * a move the walker actually completed is kept. Anything else is rolled back — tiles and walker
     * state both — and a plain stretch of ground is laid instead. That is what keeps the guarantee
     * constructive: the map never contains a move nobody has crossed, so there is nothing to repair
     * afterwards.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun runSegment(
        walker: SpineWalker,
        rng: Rng,
        profile: ThemeProfile,
        curve: DifficultyCurve,
        envelope: io.github.ksean.cyberslop.physics.MovementEnvelope,
        tiles: TileMap,
        jets: MutableList<FireJet>,
        carveFloor: (Int, Int, Int) -> Unit,
        journalSize: () -> Pair<Int, Int>,
        rollbackTo: (Int, Int) -> Unit,
        write: (Int, Int, TileKind) -> Unit,
        startCursor: Int,
        limit: Int,
        startRow: Int,
        baseRow: Int,
        highestRow: Int,
    ): SegmentResult {
        val budget = Budget(envelope)
        var cursor = startCursor
        var floorRow = startRow

        while (cursor < limit - profile.restPlatformTiles - budget.landing) {
            val rest = profile.restPlatformTiles
            carveFloor(cursor, rest, floorRow)
            cursor += rest
            if (!walker.walkRightTo(TileMap.toWorld(cursor - budget.brakeMargin), floorRow)) {
                return SegmentResult(false, cursor, floorRow, "rest platform at row $floorRow")
            }

            if (limit - cursor < budget.landing + budget.brakeMargin) break

            val mark = walker.checkpoint()
            val (tileMark, maskMark) = journalSize()
            val proposed = attemptMove(
                pickMove(rng, profile, curve), walker, rng, profile, curve, envelope,
                tiles, jets, carveFloor, write, cursor, limit, floorRow, baseRow, highestRow,
            )

            if (proposed != null) {
                cursor = proposed.cursor
                floorRow = proposed.floorRow
                continue
            }

            walker.rollback(mark)
            rollbackTo(tileMark, maskMark)

            val plain = budget.landing
            if (cursor + plain >= limit) break
            carveFloor(cursor, plain, floorRow)
            if (!walker.walkRightTo(TileMap.toWorld(cursor + plain - budget.brakeMargin), floorRow)) {
                return SegmentResult(false, cursor, floorRow, "fallback stretch at row $floorRow")
            }
            cursor += plain
        }
        return SegmentResult(true, cursor, floorRow)
    }

    private class MoveResult(val cursor: Int, val floorRow: Int)

    @Suppress("LongParameterList", "ReturnCount")
    private fun attemptMove(
        kind: MoveKind,
        walker: SpineWalker,
        rng: Rng,
        profile: ThemeProfile,
        curve: DifficultyCurve,
        envelope: io.github.ksean.cyberslop.physics.MovementEnvelope,
        tiles: TileMap,
        jets: MutableList<FireJet>,
        carveFloor: (Int, Int, Int) -> Unit,
        write: (Int, Int, TileKind) -> Unit,
        startCursor: Int,
        limit: Int,
        startRow: Int,
        baseRow: Int,
        highestRow: Int,
    ): MoveResult? {
        val budget = Budget(envelope)
        var cursor = startCursor
        val floorRow = startRow

        when (kind) {
            MoveKind.Gap -> {
                val widest = curve.maxGapTiles.coerceAtMost(envelope.gapMaxTiles(0))
                val gap = 1 + rng.nextInt(widest.coerceAtLeast(1))
                if (cursor + gap + budget.landing >= limit) return null
                if (profile.allowsAcid && rng.nextDouble() < curve.hazardFrequency) {
                    for (x in cursor until cursor + gap) write(x, floorRow, TileKind.Acid)
                }
                cursor += gap
                carveFloor(cursor, budget.landing, floorRow)
                if (!walker.jumpRightOnto(TileMap.toWorld(cursor), floorRow)) return null
                return MoveResult(cursor + budget.landing, floorRow)
            }

            MoveKind.StepUp -> {
                val step = 1 + rng.nextInt(envelope.stepUpMaxTiles.coerceAtLeast(1))
                val target = floorRow - step
                if (target < highestRow || cursor + budget.landing + 2 >= limit) return null
                cursor += 1
                carveFloor(cursor, budget.landing, target)
                if (!walker.jumpRightOnto(TileMap.toWorld(cursor), target)) return null
                return MoveResult(cursor + budget.landing, target)
            }

            MoveKind.Drop -> {
                val drop = 1 + rng.nextInt(budget.maxDrop)
                val target = (floorRow + drop).coerceAtMost(baseRow)
                if (target == floorRow || cursor + budget.landing + 2 >= limit) return null
                carveFloor(cursor, budget.landing, target)
                if (!walker.walkRightTo(
                        TileMap.toWorld(cursor + budget.landing - budget.brakeMargin), target,
                    )
                ) return null
                return MoveResult(cursor + budget.landing, target)
            }

            MoveKind.CrouchDuct -> {
                val length = budget.ductLength + rng.nextInt(budget.ductVariation)
                if (cursor + length + budget.brakeMargin >= limit) return null
                carveFloor(cursor, length, floorRow)
                for (x in cursor until cursor + length) write(x, floorRow - 2, TileKind.Solid)
                if (!walker.crouchRightTo(
                        TileMap.toWorld(cursor + length - budget.brakeMargin), floorRow,
                    )
                ) return null
                return MoveResult(cursor + length, floorRow)
            }

            MoveKind.JetCorridor -> {
                val safe = budget.jetSafe
                val corridor = safe * 2 + 1
                if (cursor + corridor + budget.brakeMargin >= limit) return null
                carveFloor(cursor, corridor, floorRow)

                val jetColumn = cursor + safe
                // Only the span from the waiting tile to clear of the jet matters, and the player
                // has the approach to build speed over. Costing the whole corridor at half speed
                // demanded a 0.92 s off-window, which maps 8-10 never offer — so every jet on the
                // jet-heavy themes was silently dropped.
                val crossing = TileMap.toWorld(budget.jetCrossing) /
                    (Physics.Default.maxRunSpeed * JET_CROSSING_SPEED_FRACTION)
                val period = curve.jetPeriodSeconds
                val on = period * curve.jetDuty
                if (period - on < crossing + REACTION_SECONDS) return null

                if (!walker.walkRightTo(TileMap.toWorld(jetColumn - budget.jetApproach), floorRow)) {
                    return null
                }

                val jet = FireJet(
                    column = jetColumn,
                    topRow = floorRow - ARENA_CLEARANCE,
                    bottomRow = floorRow - 1,
                    periodSeconds = period,
                    onSeconds = on,
                    phaseSeconds = rng.nextDouble() * period,
                )
                jets.add(jet)

                if (!walker.waitForJetOff(jet, crossing + REACTION_SECONDS)) {
                    jets.removeLast()
                    return null
                }

                // The crossing is then *measured*, not estimated: whatever the walk actually cost,
                // the jet must have been off for all of it. An estimate that is merely close is not
                // a guarantee, and one that is too pessimistic silently removes jets from the very
                // themes built around them.
                val crossingStart = walker.elapsedTicks
                if (!walker.walkRightTo(
                        TileMap.toWorld(cursor + corridor - budget.brakeMargin), floorRow,
                    )
                ) {
                    jets.removeLast()
                    return null
                }
                if (!walker.jetStayedOff(jet, crossingStart, walker.elapsedTicks)) {
                    jets.removeLast()
                    return null
                }
                return MoveResult(cursor + corridor, floorRow)
            }

            MoveKind.Flat -> {
                val length = budget.landing + rng.nextInt(budget.flatVariation)
                if (cursor + length >= limit) return null
                carveFloor(cursor, length, floorRow)
                if (!walker.walkRightTo(
                        TileMap.toWorld(cursor + length - budget.brakeMargin), floorRow,
                    )
                ) return null
                return MoveResult(cursor + length, floorRow)
            }
        }
    }

    private enum class MoveKind { Flat, Gap, StepUp, Drop, CrouchDuct, JetCorridor }

    /**
     * Picks a move by weight, normalised over the kinds this theme allows.
     *
     * The weights were once cumulative thresholds compared against a single roll. As gap frequency
     * rose across the run the bands overflowed past 1.0 and silently truncated whatever came last —
     * so the hardest maps ended up proposing *fewer* jet corridors than the middle ones, and
     * measured difficulty fell at the end of the run. Normalising keeps every share the share it
     * says it is.
     */
    private fun pickMove(rng: Rng, profile: ThemeProfile, curve: DifficultyCurve): MoveKind {
        val weights = mutableListOf<Pair<MoveKind, Double>>()
        weights.add(MoveKind.Gap to curve.gapFrequency)
        if (profile.allowsStepUp) weights.add(MoveKind.StepUp to STEP_UP_WEIGHT)
        if (profile.allowsDrop) weights.add(MoveKind.Drop to DROP_WEIGHT)
        if (profile.allowsCrouchDuct) weights.add(MoveKind.CrouchDuct to DUCT_WEIGHT)
        if (profile.allowsJets) weights.add(MoveKind.JetCorridor to curve.jetFrequency)
        weights.add(MoveKind.Flat to FLAT_WEIGHT)

        val total = weights.sumOf { it.second }
        var draw = rng.nextDouble() * total
        for ((kind, weight) in weights) {
            draw -= weight
            if (draw <= 0.0) return kind
        }
        return MoveKind.Flat
    }

    private fun crossingSeconds(corridorTiles: Int): Double =
        TileMap.toWorld(corridorTiles) / Physics.Default.maxRunSpeed

    private fun mixAttempt(seed: ULong, attempt: Int): ULong =
        seed xor (attempt.toULong() * 0x9E3779B97F4A7C15uL)
}
