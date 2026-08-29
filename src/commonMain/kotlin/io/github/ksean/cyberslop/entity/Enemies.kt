package io.github.ksean.cyberslop.entity

/**
 * Enemy kinds, distinguished by the things generation and verification have to reason about:
 * how much health they carry, whether they shoot, and whether terrain constrains them.
 */
enum class EnemyArchetype(
    val healthMultiplier: Double,
    val shoots: Boolean,
    val ignoresTerrain: Boolean,
    /** Fraction of the base enemy speed. Every product must stay below the player's run speed. */
    val speedScale: Double,
) {
    Swarm(healthMultiplier = 0.6, shoots = false, ignoresTerrain = false, speedScale = 1.4),
    Shooter(healthMultiplier = 0.8, shoots = true, ignoresTerrain = false, speedScale = 0.8),
    Brute(healthMultiplier = 2.2, shoots = false, ignoresTerrain = false, speedScale = 0.6),
    Flyer(healthMultiplier = 0.7, shoots = false, ignoresTerrain = true, speedScale = 1.1),
    Turret(healthMultiplier = 1.5, shoots = true, ignoresTerrain = false, speedScale = 0.45),
    ;

    /** Melee archetypes close on the player; the rest keep their distance or never move. */
    val melee: Boolean get() = !shoots

    fun healthOn(mapIndex: Int): Double = healthMultiplier * Balance.trashHealth(mapIndex)
}

/** A placed enemy: an archetype, where it stands, and how far it patrols. */
data class EnemySpawn(
    val archetype: EnemyArchetype,
    val column: Int,
    val row: Int,
    val patrolTiles: Int,
) {
    val leftTile: Int get() = column - patrolTiles
    val rightTile: Int get() = column + patrolTiles
}
