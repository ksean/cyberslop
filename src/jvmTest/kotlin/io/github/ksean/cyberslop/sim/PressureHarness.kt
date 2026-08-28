package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.entity.Dodge
import io.github.ksean.cyberslop.gen.GeneratedLevel
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.LootFloor
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.physics.InputFrame
import io.github.ksean.cyberslop.run.RunState

/**
 * The two play harnesses of `specs/enemies.md` (Threat and pressure): the guaranteed loadout at
 * full health, the game's own auto-aim, gross incoming damage recorded separately from net health.
 */
object PressureHarness {
    const val FIGHT_TICKS = 12_000

    class RouteOutcome(val grossDamage: Double, val died: Boolean, val sim: GameSimulation)

    /** The guaranteed loadout a player *arrives* with, at full health on [mapIndex]. */
    fun floorRun(seed: ULong, mapIndex: Int): RunState {
        val run = RunState.begin(seed).copy(mapIndex = mapIndex)
        return run.copy(
            health = run.maxHealth,
            loadout = run.loadout.copy(weapon = LootFloor.weaponArrivingAt(mapIndex), slots = LootFloor.slotsArrivingAt(mapIndex)),
        )
    }

    /**
     * Route pressure: replay the witness while the population acts; the tape ends at the boss
     * arena entrance. A death ends the map and counts as the map's full max health.
     */
    fun route(seed: ULong, generated: GeneratedLevel): RouteOutcome {
        // Guaranteed-only, in the simulation itself: neither the optional caches the witness
        // walks over nor the drops of whatever the auto-fire kills ever exist.
        val sim = GameSimulation(generated.level, floorRun(seed, generated.level.mapIndex), seed, optionalLoot = false)
        // Map one's starter cache is what `LootFloor.weaponArrivingAt(1)` already models; taking it as
        // well would give the bot two draws at a weapon where a run gets one.
        sim.items.clear()
        pinAwards(sim, generated.level.mapIndex)
        for (step in generated.witness.steps) {
            for (frame in step.frames) {
                if (sim.tick(frame).playerDied) return RouteOutcome(sim.run.maxHealth, died = true, sim)
            }
        }
        return RouteOutcome(sim.grossDamageTaken, died = false, sim)
    }

    /**
     * The reference player takes every guaranteed award at its **weakest** outcome (`LootFloor`),
     * so every award this simulation creates is the floor's own before it can be collected — an
     * award dropped and taken inside one tick included. The rest of the route and the boss are
     * then played with what the floor models, not with whatever the roll gave.
     */
    fun pinAwards(sim: GameSimulation, mapIndex: Int) {
        val floorWeapon = LootFloor.weaponAt(mapIndex)
        val floorPowerup = LootFloor.slotsAt(mapIndex).held.keys.singleOrNull()?.let { Powerups.of(it) }
        sim.awardOverride = { _, _ -> floorWeapon to floorPowerup }
    }

    /** Holds exactly the loadout the floor models at [mapIndex]'s main boss (`LootFloor.weaponAt`/`slotsAt`). */
    fun holdFloor(sim: GameSimulation, mapIndex: Int) =
        sim.holdLoadout(Loadout(LootFloor.weaponAt(mapIndex), LootFloor.slotsAt(mapIndex)))

    /** Boss pressure: after the route, fight with the dodge policy until the boss dies or time runs out. */
    fun fight(sim: GameSimulation): Boolean {
        var ticks = 0
        while (ticks < FIGHT_TICKS && !sim.boss.fight.defeated && !sim.run.dead) {
            sim.tick(dodgePolicy(sim))
            ticks++
        }
        return sim.boss.fight.defeated
    }

    /** Answer each telegraphed attack with its dodge for the attack's whole duration; otherwise close. */
    fun dodgePolicy(sim: GameSimulation): InputFrame {
        val attack = sim.boss.currentAttack
        val towardBoss = sim.boss.centre.x > sim.player.x
        if (attack != null) {
            return when (attack.dodge) {
                Dodge.Jump -> InputFrame(jump = true, jumpStart = sim.player.onGround)
                Dodge.Crouch -> InputFrame(crouch = true)
                Dodge.MoveAside -> InputFrame(left = towardBoss, right = !towardBoss)
            }
        }
        return InputFrame(right = towardBoss, left = !towardBoss)
    }
}
