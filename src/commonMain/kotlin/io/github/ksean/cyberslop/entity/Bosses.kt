package io.github.ksean.cyberslop.entity

/**
 * Boss and mini-boss definitions, one per map.
 *
 * Every attack is dodgeable with the four movement inputs and telegraphs for at least
 * [BossAttack.MIN_TELEGRAPH_SECONDS]; both are enforced by construction and by test. Telegraphs
 * shorten slightly with difficulty but never below the floor — the fairness floor does not scale.
 */
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

    fun boss(mapIndex: Int): BossSpec = BossSpec(
        name = NAMES[mapIndex - 1],
        maxHealth = Balance.bossHealth(mapIndex),
        contactDamage = Balance.contactDamage(mapIndex),
        phases = listOf(
            BossPhase(1.00, listOf(slam(mapIndex), sweep(mapIndex))),
            BossPhase(0.60, listOf(slam(mapIndex), sweep(mapIndex), volley(mapIndex))),
            BossPhase(0.25, listOf(slam(mapIndex), sweep(mapIndex), volley(mapIndex), rush(mapIndex))),
        ),
    )

    fun miniboss(mapIndex: Int): BossSpec = BossSpec(
        name = MINIBOSS_NAMES[mapIndex - 1],
        maxHealth = Balance.minibossHealth(mapIndex),
        contactDamage = Balance.contactDamage(mapIndex),
        phases = listOf(BossPhase(1.00, listOf(slam(mapIndex)))),
    )

    private fun telegraph(mapIndex: Int, base: Double): Double {
        val d = (mapIndex - 1) / 9.0
        return (base - 0.15 * d).coerceAtLeast(BossAttack.MIN_TELEGRAPH_SECONDS)
    }

    private fun slam(mapIndex: Int) = BossAttack(
        name = "Slam", telegraphSeconds = telegraph(mapIndex, 0.70), activeSeconds = 0.25,
        damage = Balance.contactDamage(mapIndex) * 1.4, dodge = Dodge.Jump,
    )

    private fun sweep(mapIndex: Int) = BossAttack(
        name = "Sweep", telegraphSeconds = telegraph(mapIndex, 0.65), activeSeconds = 0.30,
        damage = Balance.contactDamage(mapIndex) * 1.1, dodge = Dodge.Crouch,
    )

    private fun volley(mapIndex: Int) = BossAttack(
        name = "Volley", telegraphSeconds = telegraph(mapIndex, 0.60), activeSeconds = 0.50,
        damage = Balance.contactDamage(mapIndex) * 0.8, dodge = Dodge.MoveAside,
    )

    private fun rush(mapIndex: Int) = BossAttack(
        name = "Rush", telegraphSeconds = telegraph(mapIndex, 0.55), activeSeconds = 0.40,
        damage = Balance.contactDamage(mapIndex) * 1.6, dodge = Dodge.Jump,
    )
}
