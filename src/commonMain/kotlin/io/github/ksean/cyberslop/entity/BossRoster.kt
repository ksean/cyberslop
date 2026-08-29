package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.core.Rng

/** All twenty run encounters, assigned once from an isolated deterministic stream. */
class BossRoster private constructor(private val profiles: List<BossProfile>) {
    init {
        require(profiles.size == ENCOUNTERS)
    }

    fun miniboss(mapIndex: Int): BossProfile = profiles[index(mapIndex, mainBoss = false)]
    fun boss(mapIndex: Int): BossProfile = profiles[index(mapIndex, mainBoss = true)]

    override fun equals(other: Any?): Boolean = other is BossRoster && profiles == other.profiles
    override fun hashCode(): Int = profiles.hashCode()
    override fun toString(): String = "BossRoster($profiles)"

    companion object {
        private const val MAPS = 10
        private const val ENCOUNTERS = MAPS * 2

        fun forRun(seed: ULong): BossRoster {
            val rng = Rng.derive(seed, 0, "boss-roster")
            val profiles = mutableListOf<BossProfile>()
            var previousPair: List<BossModule>? = null
            for (map in 1..MAPS) {
                val melee = Bosses.meleeModulesFor(map)
                val ranged = Bosses.rangedModulesFor(map)
                repeat(2) { rank ->
                    val pairs = melee.flatMap { m -> ranged.map { r -> listOf(m, r) } }
                        .filter { it != previousPair }
                    val pair = pairs[rng.nextInt(pairs.size)]
                    val signature = if (rank == 1) {
                        val unused = (melee + ranged).filter { it !in pair }
                        unused[rng.nextInt(unused.size)]
                    } else {
                        null
                    }
                    profiles += BossProfile(pair[0], pair[1], signature)
                    previousPair = pair
                }
            }
            return BossRoster(profiles)
        }

        private fun index(mapIndex: Int, mainBoss: Boolean): Int {
            require(mapIndex in 1..MAPS)
            return (mapIndex - 1) * 2 + if (mainBoss) 1 else 0
        }
    }
}
