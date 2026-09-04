package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.gen.GeneratedLevel
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.physics.Physics
import io.github.ksean.cyberslop.physics.TICK_SECONDS
import io.github.ksean.cyberslop.run.RunState
import io.github.ksean.cyberslop.world.TileMap
import io.github.ksean.cyberslop.world.TILE_SIZE
import kotlin.math.abs

/**
 * The two play harnesses of `specs/enemies.md` (Threat and pressure): the guaranteed loadout at
 * full health, the game's own auto-aim, gross incoming damage recorded separately from net health.
 */
object PressureHarness {
    const val FIGHT_TICKS = 12_000

    class RouteOutcome(val grossDamage: Double, val died: Boolean, val sim: GameSimulation)

    internal class SurvivalOutcome(
        val died: Boolean,
        val sim: GameSimulation,
        val dodges: DodgeAccounting,
    )

    /** The guaranteed loadout a player *arrives* with, at shipping health on [mapIndex]. */
    fun floorRun(seed: ULong, mapIndex: Int): RunState {
        val run = RunState.begin(seed).copy(mapIndex = mapIndex)
        return run.copy(
            health = run.maxHealth,
            loadout = run.loadout.copy(weapon = LootFloor.weaponArrivingAt(mapIndex), slots = LootFloor.slotsArrivingAt(mapIndex)),
        )
    }

    /**
     * Route pressure: replay the complete witness while the population acts. Sentinel health is a
     * JVM measurement fixture only; it prevents ordinary damage from truncating the pressure tape.
     */
    fun route(seed: ULong, generated: GeneratedLevel): RouteOutcome {
        // Guaranteed-only, in the simulation itself: neither the optional caches the witness
        // walks over nor the drops of whatever the auto-fire kills ever exist.
        val pressureRun = floorRun(seed, generated.level.mapIndex).copy(health = PRESSURE_HEALTH)
        val sim = GameSimulation(generated.level, pressureRun, seed, optionalLoot = false)
        // Map one's starter cache is what `LootFloor.weaponArrivingAt(1)` already models; taking it as
        // well would give the bot two draws at a weapon where a run gets one.
        sim.items.clear()
        pinAwards(sim, generated.level.mapIndex)
        for (step in generated.witness.steps) {
            for (frame in step.frames) {
                if (sim.tick(frame).playerDied) return RouteOutcome(sim.grossDamageTaken, died = true, sim)
            }
            // A witness step is one indivisible, verified traversal ending at rest. Finish it before
            // detouring for an award that appeared in mid-air, then rejoin before the next step.
            if (!collectGuaranteedAwards(sim)) {
                return RouteOutcome(sim.grossDamageTaken, died = true, sim)
            }
        }
        return RouteOutcome(sim.grossDamageTaken, died = false, sim)
    }

    /** Shipping-health route survival with deterministic responses to live telegraphs. */
    internal fun survivalRoute(seed: ULong, generated: GeneratedLevel): SurvivalOutcome {
        val sim = GameSimulation(
            generated.level,
            floorRun(seed, generated.level.mapIndex),
            seed,
            optionalLoot = false,
        )
        val dodges = DodgeAccounting()
        sim.incomingAttackObserver = dodges::observe
        sim.items.clear()
        pinAwards(sim, generated.level.mapIndex)
        fun tick(planned: InputFrame): TickReport = routeTick(sim, planned, dodges)

        for (step in generated.witness.steps) {
            for (frame in step.frames) {
                if (tick(frame).playerDied) return SurvivalOutcome(died = true, sim, dodges)
            }
            if (!collectGuaranteedAwards(sim, ::tick)) {
                return SurvivalOutcome(died = true, sim, dodges)
            }
        }
        return SurvivalOutcome(died = false, sim, dodges)
    }

    /**
     * The reference player takes every guaranteed award at its **weakest** outcome (`LootFloor`),
     * so every award this simulation creates is the floor's own before it can be collected. The
     * rest of the route and the boss are then played with what the floor models, not with whatever
     * the roll gave.
     */
    fun pinAwards(sim: GameSimulation, mapIndex: Int) {
        val floorWeapon = LootFloor.weaponAt(mapIndex)
        val floorPowerup = LootFloor.slotsAt(mapIndex).held.keys.singleOrNull()?.let { Powerups.of(it) }
        sim.awardOverride = { _, _ -> floorWeapon to floorPowerup }
    }

    /**
     * Takes each guaranteed award through ordinary movement and contact. The harness may pin what an
     * award contains, but never writes that loadout into the simulation in place of collection.
     */
    internal fun collectGuaranteedAwards(
        sim: GameSimulation,
        tick: (InputFrame) -> TickReport = sim::tick,
    ): Boolean {
        val routeCentreX = playerCentreX(sim)
        while (true) {
            val item = sim.items.firstOrNull { it.isGuaranteedEquipment } ?: return true
            if (!moveToRest(sim, item.position.x, tick)) return false
            if (!jumpThrough(sim, item, tick)) return false
            if (!moveToRest(sim, routeCentreX, tick)) return false
        }
    }

    private fun moveToRest(
        sim: GameSimulation,
        targetCentreX: Double,
        tick: (InputFrame) -> TickReport,
    ): Boolean {
        var ticks = 0
        while (ticks < AWARD_APPROACH_TICKS) {
            val delta = targetCentreX - playerCentreX(sim)
            if (sim.player.onGround &&
                abs(delta) <= AWARD_ALIGN_TOLERANCE &&
                abs(sim.player.vx) < REST_SPEED
            ) {
                return true
            }

            val braking = sim.player.vx * sim.player.vx / (2.0 * Physics.Default.groundFriction)
            val press = abs(delta) > braking + AWARD_ALIGN_TOLERANCE
            val direction = when {
                !press -> 0
                delta < 0.0 -> -1
                else -> 1
            }
            val jumpStart = sim.player.onGround && terrainRequiresJump(sim, direction)
            val report = tick(
                InputFrame(
                    left = direction < 0,
                    right = direction > 0,
                    jump = jumpStart || !sim.player.onGround,
                    jumpStart = jumpStart,
                ),
            )
            if (report.playerDied) return false
            ticks++
        }
        return false
    }

    private fun terrainRequiresJump(sim: GameSimulation, direction: Int): Boolean {
        if (direction == 0) return false
        val feetY = sim.player.y + sim.player.height(Physics.Default)
        val supportRow = TileMap.toTile(feetY + GROUND_PROBE)
        val frontX = if (direction < 0) sim.player.x else sim.player.x + Physics.Default.width
        var distance = 0.0
        while (distance <= JUMP_LOOKAHEAD) {
            val column = TileMap.toTile(frontX + direction * distance)
            if (!sim.level.tiles.blocksMovement(column, supportRow) ||
                sim.level.tiles.isLethal(column, supportRow) ||
                sim.level.tiles.blocksMovement(column, supportRow - 1)
            ) {
                return true
            }
            distance += JUMP_PROBE_STEP
        }
        return false
    }

    private fun jumpThrough(
        sim: GameSimulation,
        item: GroundItem,
        tick: (InputFrame) -> TickReport,
    ): Boolean {
        var airborne = false
        repeat(AWARD_JUMP_TICKS) { tickIndex ->
            if (tick(InputFrame(jump = true, jumpStart = tickIndex == 0)).playerDied) return false
            if (!sim.player.onGround) airborne = true
            if (airborne && sim.player.onGround) return item !in sim.items
        }
        return false
    }

    private fun playerCentreX(sim: GameSimulation): Double = sim.player.centre(Physics.Default).x

    /** Holds exactly the loadout the floor models at [mapIndex]'s main boss (`LootFloor.weaponAt`/`slotsAt`). */
    fun holdFloor(sim: GameSimulation, mapIndex: Int) =
        sim.holdLoadout(Loadout(LootFloor.weaponAt(mapIndex), LootFloor.slotsAt(mapIndex)))

    /** Boss pressure: after the route, fight with the dodge policy until the boss dies or time runs out. */
    internal fun fight(sim: GameSimulation, dodges: DodgeAccounting? = null): Boolean {
        val dodge = ArenaDodge()
        var ticks = 0
        while (ticks < FIGHT_TICKS && !sim.boss.fight.defeated && !sim.run.dead) {
            val planned = dodge.next(sim)
            if (dodges == null) sim.tick(planned) else survivalTick(sim, planned, dodges)
            ticks++
        }
        return sim.boss.fight.defeated
    }

    private fun survivalTick(
        sim: GameSimulation,
        planned: InputFrame,
        dodges: DodgeAccounting,
        policy: (GameSimulation, InputFrame) -> DodgeResponse = DodgePolicy::response,
    ): TickReport {
        val response = policy(sim, planned)
        dodges.recordResponse(response.activationIds)
        return sim.tick(response.input)
    }

    /** Replays one route control while recording telegraph responses. */
    private fun routeTick(
        sim: GameSimulation,
        planned: InputFrame,
        dodges: DodgeAccounting,
    ): TickReport = survivalTick(sim, planned, dodges, RouteDodgePolicy::response)

    private data class DodgeResponse(val input: InputFrame, val activationIds: Set<Int>)

    private object RouteDodgePolicy {
        fun response(sim: GameSimulation, planned: InputFrame): DodgeResponse {
            val activationIds = buildSet {
                listOf(sim.miniboss, sim.boss).forEach { live ->
                    live.takeIf { it.telegraphing }?.incomingAttackId?.let(::add)
                }
                sim.enemies.filter { it.windingUp }.mapNotNullTo(this) { it.incomingAttackId }
            }

            val minibossAttack = sim.miniboss.currentAttack
            val input = if (!sim.miniboss.fight.defeated &&
                minibossAttack?.dodge == Dodge.Crouch &&
                sim.miniboss.attackElapsed < minibossAttack.telegraphSeconds &&
                sim.miniboss.attackElapsed + TICK_SECONDS >= minibossAttack.telegraphSeconds
            ) {
                planned.copy(crouch = true)
            } else {
                planned
            }
            return DodgeResponse(input, activationIds)
        }
    }

    private object DodgePolicy {
        fun response(sim: GameSimulation, planned: InputFrame): DodgeResponse {
            activeBoss(sim)?.let { live ->
                val activationId = live.incomingAttackId
                val responded = if (live.telegraphing && activationId != null) setOf(activationId) else emptySet()
                return DodgeResponse(bossInput(sim, live, planned), responded)
            }

            val ranged = sim.enemies.filter { it.windingUp && it.archetype.shoots }
            if (ranged.isNotEmpty()) {
                val ids = ranged.mapNotNullTo(mutableSetOf()) { it.incomingAttackId }
                return DodgeResponse(planned, ids)
            }

            val melee = sim.enemies
                .filter { it.windingUp && !it.archetype.shoots }
                .minByOrNull { (it.centre - sim.player.centre(Physics.Default)).lengthSquared }
                ?: return DodgeResponse(planned, emptySet())
            val direction = if (sim.player.centre(Physics.Default).x < melee.centre.x) -1 else 1
            if (!sim.player.onGround || planned.jump || planned.jumpStart) {
                return DodgeResponse(planned, emptySet())
            }
            return DodgeResponse(
                InputFrame(left = direction < 0, right = direction > 0),
                setOfNotNull(melee.incomingAttackId),
            )
        }

        private fun activeBoss(sim: GameSimulation) =
            listOf(sim.miniboss, sim.boss).firstOrNull { !it.fight.defeated && it.currentAttack != null }

        private fun bossInput(
            sim: GameSimulation,
            live: LiveBoss,
            planned: InputFrame,
        ): InputFrame {
            val attack = live.currentAttack ?: return planned
            val retreat = if (sim.player.centre(Physics.Default).x < live.position.x) -1 else 1
            val horizontal = if (live.meleeChargeSelected || attack.dodge == Dodge.MoveAside) {
                InputFrame(left = retreat < 0, right = retreat > 0)
            } else {
                planned
            }
            return when (attack.dodge) {
                Dodge.Jump -> horizontal.copy(jump = true, jumpStart = sim.player.onGround)
                Dodge.Crouch -> horizontal.copy(crouch = true)
                Dodge.MoveAside -> horizontal
            }
        }
    }

    /** Answer each telegraphed attack with its dodge; otherwise maintain body-clear spacing. */
    fun dodgePolicy(sim: GameSimulation): InputFrame {
        if (sim.boss.currentAttack != null) return TestLevels.dodgeActiveBossAttack(sim)
        return TestLevels.closeOnBossWithoutContact(sim)
    }

    /**
     * A body-clear dodge cannot retreat forever in a bounded arena. Between attacks, vault over the
     * boss near an edge, waiting to close the last horizontal gap until the bodies are vertically
     * clear. The vault finishes inside one rest window, so active attacks still get their own dodge.
     */
    private class ArenaDodge {
        private var vaultDirection = 0

        fun next(sim: GameSimulation): InputFrame {
            if (sim.boss.currentAttack != null) {
                vaultDirection = 0
                return dodgePolicy(sim)
            }
            if (vaultDirection != 0) return vault(sim)

            val centreX = playerCentreX(sim)
            val arena = sim.boss.arena
            val nearLeft = centreX <= TileMap.toWorld(arena.leftTile) + EDGE_INSET
            val nearRight = centreX >= TileMap.toWorld(arena.rightTile + 1) - EDGE_INSET
            vaultDirection = when {
                nearLeft && sim.boss.position.x > centreX -> 1
                nearRight && sim.boss.position.x < centreX -> -1
                else -> 0
            }
            return if (vaultDirection == 0) dodgePolicy(sim) else vault(sim)
        }

        private fun vault(sim: GameSimulation): InputFrame {
            val centreX = playerCentreX(sim)
            val clearSide = vaultDirection * (centreX - sim.boss.position.x) >= BODY_CLEAR
            if (clearSide) {
                vaultDirection = 0
                return dodgePolicy(sim)
            }

            val playerBottom = sim.player.y + sim.player.height(Physics.Default)
            val bossTop = sim.boss.position.y - sim.boss.height
            val approachingGap = -vaultDirection * (centreX - sim.boss.position.x)
            val canAdvance = playerBottom <= bossTop || approachingGap > BODY_CLEAR
            return InputFrame(
                left = canAdvance && vaultDirection < 0,
                right = canAdvance && vaultDirection > 0,
                jump = true,
                jumpStart = sim.player.onGround,
            )
        }
    }

    private const val AWARD_APPROACH_TICKS = 1_800
    private const val AWARD_JUMP_TICKS = 240
    private const val AWARD_ALIGN_TOLERANCE = 1.0
    private const val REST_SPEED = 0.001
    private const val EDGE_INSET = 3.0 * TILE_SIZE
    private const val BODY_CLEAR = 30.0
    private const val JUMP_LOOKAHEAD = 24.0
    private const val JUMP_PROBE_STEP = 4.0
    private const val GROUND_PROBE = 0.05
    private const val PRESSURE_HEALTH = 1_000_000_000.0
}
