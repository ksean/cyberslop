package io.github.ksean.cyberslop.entity

import io.github.ksean.cyberslop.world.TILE_SIZE

/** Typed boss-module registry and map-themed encounter definitions (PROD-087). */
object Bosses {
    private val NAMES = listOf(
        "Rustlung", "Scrapheap Marshal", "The Drowned Choir", "Foundry Prime", "Neon Tyrant",
        "Refinery Widow", "Stack Overseer", "Skybridge Colossus", "Reactor Ascendant",
        "The Arcology Mind",
    )

    private val MINIBOSS_NAMES = listOf(
        "Gutter Enforcer", "Scav Captain", "Sump Warden", "Slag Handler", "Slum Baron",
        "Vat Foreman", "Rack Sentinel", "Span Runner", "Coolant Priest", "Vault Sentry",
    )

    private val defaultRoster = BossRoster.forRun(0uL)

    fun boss(mapIndex: Int): BossSpec = boss(mapIndex, defaultRoster.boss(mapIndex))

    fun boss(mapIndex: Int, profile: BossProfile): BossSpec {
        val primary = profile.primaryPair.map { attack(it, mapIndex, mainBoss = true) }
        val signature = attack(requireNotNull(profile.signature), mapIndex, mainBoss = true)
        val expanded = primary + signature
        return BossSpec(
            name = NAMES[mapIndex - 1],
            maxHealth = Balance.bossHealth(mapIndex),
            contactDamage = Balance.contactDamage(mapIndex),
            phases = listOf(
                BossPhase(1.00, primary),
                BossPhase(0.60, expanded),
                BossPhase(0.25, expanded),
            ),
            profile = profile,
            mapIndex = mapIndex,
        )
    }

    fun miniboss(mapIndex: Int): BossSpec = miniboss(mapIndex, defaultRoster.miniboss(mapIndex))

    fun miniboss(mapIndex: Int, profile: BossProfile): BossSpec = BossSpec(
        name = MINIBOSS_NAMES[mapIndex - 1],
        maxHealth = Balance.minibossHealth(mapIndex),
        contactDamage = Balance.contactDamage(mapIndex),
        phases = listOf(
            BossPhase(1.00, profile.primaryPair.map { attack(it, mapIndex, mainBoss = false) }),
        ),
        profile = profile.copy(signature = null),
        mapIndex = mapIndex,
    )

    fun modulesFor(mapIndex: Int): List<BossModule> =
        meleeModulesFor(mapIndex) + rangedModulesFor(mapIndex)

    fun meleeModulesFor(mapIndex: Int): List<BossModule> = when (mapIndex) {
        in 1..3 -> listOf(BossModule.Slam, BossModule.Sweep)
        in 4..6 -> listOf(BossModule.Slam, BossModule.Flurry)
        in 7..10 -> listOf(BossModule.Flurry, BossModule.Rush)
        else -> error("map index outside 1..10: $mapIndex")
    }

    fun rangedModulesFor(mapIndex: Int): List<BossModule> = when (mapIndex) {
        in 1..3 -> listOf(BossModule.Bolt, BossModule.Burst)
        in 4..6 -> listOf(BossModule.Burst, BossModule.Scatter)
        in 7..10 -> listOf(BossModule.Scatter, BossModule.Laser)
        else -> error("map index outside 1..10: $mapIndex")
    }

    fun attack(module: BossModule, mapIndex: Int, mainBoss: Boolean): BossAttack {
        require(module in modulesFor(mapIndex)) { "$module is not legal on map $mapIndex" }
        val unit = Balance.contactDamage(mapIndex) * if (mainBoss) 1.0 else 0.80
        return when (module) {
            BossModule.Slam -> attack(module, mapIndex, 0.70, 0.55, 0.25, 1.10 * unit, 80.0)
            BossModule.Sweep -> attack(module, mapIndex, 0.65, 0.50, 0.30, 0.85 * unit, 80.0)
            BossModule.Flurry -> attack(
                module, mapIndex, 0.60, 0.45, 0.38, 0.38 * unit, 72.0,
                listOf(0.00, 0.14, 0.28),
            )
            BossModule.Rush -> attack(module, mapIndex, 0.55, 0.40, 0.40, 1.45 * unit, 128.0)
            BossModule.Bolt -> attack(module, mapIndex, 0.65, 0.50, 0.10, 0.50 * unit, RANGED_REACH_PX)
            BossModule.Burst -> attack(
                module, mapIndex, 0.65, 0.50, 0.36, 0.22 * unit, RANGED_REACH_PX,
                listOf(0.00, 0.12, 0.24),
            )
            BossModule.Scatter -> attack(module, mapIndex, 0.60, 0.45, 0.10, 0.24 * unit, RANGED_REACH_PX)
            BossModule.Laser -> attack(module, mapIndex, 0.70, 0.55, 0.30, 1.05 * unit, RANGED_REACH_PX)
        }
    }

    private fun attack(
        module: BossModule,
        mapIndex: Int,
        telegraphStart: Double,
        telegraphEnd: Double,
        activeSeconds: Double,
        damage: Double,
        reachPx: Double,
        eventOffsets: List<Double> = listOf(0.0),
    ) = BossAttack(
        module = module,
        name = module.displayName,
        telegraphSeconds = interpolate(mapIndex, telegraphStart, telegraphEnd),
        activeSeconds = activeSeconds,
        damage = damage,
        dodge = module.dodge,
        reachPx = reachPx,
        visual = module.visual,
        eventOffsets = eventOffsets,
    )

    private fun interpolate(mapIndex: Int, start: Double, end: Double): Double {
        val depth = (mapIndex - 1) / 9.0
        return (start + (end - start) * depth).coerceAtLeast(BossAttack.MIN_TELEGRAPH_SECONDS)
    }

    /** Registry metadata is level-spanning; simulation clips each event to its actual level. */
    private const val RANGED_REACH_PX = 1024.0 * TILE_SIZE
}
