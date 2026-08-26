package io.github.ksean.cyberslop.entity

/**
 * Enemy kinds, distinguished by the things generation and verification have to reason about:
 * how much health they carry, whether they shoot, and whether terrain constrains them.
 */
enum class EnemyArchetype(
    val healthMultiplier: Double,
    val shoots: Boolean,
    val ignoresTerrain: Boolean,
) {
    Swarm(healthMultiplier = 0.6, shoots = false, ignoresTerrain = false),
    Shooter(healthMultiplier = 0.8, shoots = true, ignoresTerrain = false),
    Brute(healthMultiplier = 2.2, shoots = false, ignoresTerrain = false),
    Flyer(healthMultiplier = 0.7, shoots = false, ignoresTerrain = true),
    Turret(healthMultiplier = 1.5, shoots = true, ignoresTerrain = false),
    ;

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
