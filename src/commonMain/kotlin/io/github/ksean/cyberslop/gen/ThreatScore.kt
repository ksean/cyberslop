package io.github.ksean.cyberslop.gen

import io.github.ksean.cyberslop.entity.EnemyAttacks
import io.github.ksean.cyberslop.entity.EnemySpawn
import io.github.ksean.cyberslop.world.Hazards
import io.github.ksean.cyberslop.world.Level

/**
 * How much the generated population and hazards can hurt, per hundred tiles, **excluding map
 * index** (`specs/enemies.md`, Threat and pressure): each enemy contributes its attack's damage
 * share over its effective in-reach wind-up plus cooldown, each damaging hazard its rate per
 * second. Bosses are excluded because every map has one of each. Measured from what was generated,
 * so a change to enemies is visible to a test and a change to terrain is not masked by one — the
 * counterpart to [DifficultyScore], which deliberately ignores the population.
 */
object ThreatScore {
    fun of(level: Level): Double {
        val enemies = level.enemies.sumOf { pressureOf(it) }
        val hazards = Hazards.spikeStrips(level).size * Hazards.SPIKE_RATE +
            level.barrels.size * Hazards.BARREL_RATE
        return (enemies + hazards) / (level.widthTiles / 100.0)
    }

    /** Damage share per second of the attack cycle, which is the rate a target in reach sees. */
    fun pressureOf(spawn: EnemySpawn): Double = if (spawn.archetype.shoots) {
        val shot = EnemyAttacks.SHOT
        shot.damageShare / (shot.windUpSeconds + shot.cooldownSeconds)
    } else {
        val swing = EnemyAttacks.swing(spawn.archetype)
        val inReachCycle = (swing.windUpSeconds + swing.cooldownSeconds) /
            EnemyAttacks.MELEE_ATTACK_RATE_IN_REACH
        swing.damageShare / inReachCycle
    }
}
