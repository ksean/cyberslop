package io.github.ksean.cyberslop.combat

/**
 * The weapon registry.
 *
 * Tiers are rarity bands and are held apart in power by [WeaponRegistryTest]: a tier's weakest
 * weapon must out-damage the tier below's strongest by a margin. That is what makes a rarer drop
 * worth walking over, since walking over it always equips it (PROD-070).
 *
 * Melee is the high-risk class: every melee weapon reaches at least two metres, beyond any enemy
 * swing, and within a tier the melee mean DPS (bottle excluded) exceeds the ranged mean.
 */
object Weapons {
    private const val METRE = 16.0

    val startingWeapon = WeaponSpec(
        id = WeaponId.BrokenBottle,
        name = "Broken Bottle",
        cls = WeaponClass.Melee,
        tier = Tier.Street,
        damage = 8.0, cooldown = 2.0, rangePx = 2.2 * METRE, projectileSpeed = 0.0,
        pattern = FirePattern.ArcSwing(arcDegrees = 70.0, lingerSeconds = 0.1),
    )

    val all: List<WeaponSpec> = listOf(
        startingWeapon,
        melee(WeaponId.RustlineMachete, "Rustline Machete", Tier.Street, 17.0, 1.4, 2.3, 80.0,
            onHit = listOf(HitEffect.Bleed(perSecond = 2.0, seconds = 3.0))),
        melee(WeaponId.CorpoRiotBaton, "Corpo Riot Baton", Tier.Scav, 17.0, 1.1, 2.2, 90.0,
            knockback = 320.0, onHit = listOf(HitEffect.Stun(chance = 1.0, seconds = 0.3))),
        melee(WeaponId.ChromeFang, "Chrome Fang", Tier.Scav, 13.0, 1.2, 2.0, 35.0, projectiles = 2),
        melee(WeaponId.StaticLash, "Static Lash", Tier.Chromed, 24.0, 0.9, 4.0, 60.0,
            onHit = listOf(HitEffect.Shock(extraTargets = 1))),
        melee(WeaponId.GutterjackCleaver, "Gutterjack Cleaver", Tier.Chromed, 42.0, 1.3, 2.4, 75.0,
            onHit = listOf(HitEffect.Execute(healthFraction = 0.15))),
        melee(WeaponId.KillSwitchKatana, "Kill-Switch Katana", Tier.Blacksite, 40.0, 0.65, 2.8, 50.0,
            onFire = listOf(FireEffect.DashStrike(reachPx = 3.0 * METRE, invulnerableSeconds = 0.2))),
        melee(WeaponId.ChromewreckMaul, "Chromewreck Maul", Tier.Blacksite, 100.0, 1.6, 3.6, 100.0,
            knockback = 640.0),
        WeaponSpec(
            id = WeaponId.MeatgrinderHalo, name = "Meatgrinder Halo", cls = WeaponClass.Melee,
            tier = Tier.Ascended, damage = 40.0, cooldown = 0.35, rangePx = 2.8 * METRE,
            projectileSpeed = 0.0,
            pattern = FirePattern.Orbit(radius = 2.8 * METRE, revolutionsPerMinute = 220.0),
        ),

        ranged(WeaponId.ScraplineZipPistol, "Scrapline Zip Pistol", Tier.Street, 7.0, 0.8, 520.0),
        ranged(WeaponId.TenementNailgun, "Tenement Nailgun", Tier.Street, 4.0, 0.7, 600.0,
            projectiles = 2, spread = 12.0, pierce = 1),
        ranged(WeaponId.GanglordSmg, "Ganglord SMG", Tier.Scav, 4.0, 0.75, 700.0,
            projectiles = 3, burst = 0.05),
        ranged(WeaponId.RiotbreakerShotgun, "Riotbreaker Shotgun", Tier.Scav, 6.0, 1.5, 640.0,
            projectiles = 5, spread = 30.0,
            falloff = Falloff.Linear(5.0 * METRE, 9.0 * METRE, minimum = 0.5)),
        ranged(WeaponId.VultureRailCarbine, "Vulture Rail Carbine", Tier.Chromed, 28.0, 1.0, 900.0,
            pierce = 2),
        ranged(WeaponId.AshfallGrenadeLobber, "Ashfall Grenade Lobber", Tier.Chromed, 33.0, 1.4, 420.0,
            onHit = listOf(HitEffect.BlastOnHit(radius = 2.5 * METRE, damageFraction = 0.6))),
        ranged(WeaponId.SableCorpRailgun, "Sable Corp Railgun", Tier.Blacksite, 95.0, 1.7, 1400.0,
            pierce = Int.MAX_VALUE, windUp = 0.4),
        // Its cooldown is already a burst: one straight round every 0.12 s (PROD-075).
        ranged(WeaponId.DebtCollectorMinigun, "\"Debt Collector\" Minigun", Tier.Blacksite, 7.0, 0.12,
            820.0, windUp = 0.6),
        WeaponSpec(
            id = WeaponId.KesslerOrbitalUplink, name = "Kessler Orbital Uplink",
            cls = WeaponClass.Ranged, tier = Tier.Ascended, damage = 120.0, cooldown = 1.2,
            rangePx = 30.0 * METRE, projectileSpeed = 0.0, anchor = Anchor.Cursor,
            pattern = FirePattern.Strike(delaySeconds = 0.35, radius = 3.0 * METRE),
        ),

        psychic(WeaponId.NeuralSpike, "Neural Spike", Tier.Street, 10.0, 1.1, 260.0,
            homing = Homing.Seek(60.0, 2.0 * METRE)),
        WeaponSpec(
            id = WeaponId.MigraineLoop, name = "Migraine Loop", cls = WeaponClass.Psychic,
            tier = Tier.Scav, damage = 13.0, cooldown = 0.85, rangePx = 12.0 * METRE,
            projectileSpeed = 0.0, anchor = Anchor.Cursor,
            pattern = FirePattern.Blast(radius = 3.0 * METRE, falloff = 0.0),
        ),
        psychic(WeaponId.WetwareScreamer, "Wetware Screamer", Tier.Scav, 9.0, 1.0, 300.0,
            projectiles = 2, homing = Homing.Seek(120.0, 3.0 * METRE)),
        WeaponSpec(
            id = WeaponId.GhostwireTether, name = "Ghostwire Tether", cls = WeaponClass.Psychic,
            tier = Tier.Chromed, damage = 18.0, cooldown = 0.7, rangePx = 8.0 * METRE,
            projectileSpeed = 0.0,
            pattern = FirePattern.Chain(jumps = 3, jumpRange = 4.0 * METRE, decay = 0.25),
        ),
        WeaponSpec(
            id = WeaponId.BlackboxChorus, name = "Blackbox Chorus", cls = WeaponClass.Psychic,
            tier = Tier.Chromed, damage = 36.0, cooldown = 1.2, rangePx = 10.0 * METRE,
            projectileSpeed = 0.0, anchor = Anchor.Cursor,
            pattern = FirePattern.Pull(force = 400.0, radius = 5.0 * METRE),
        ),
        WeaponSpec(
            id = WeaponId.SynapseHemorrhage, name = "Synapse Hemorrhage", cls = WeaponClass.Psychic,
            tier = Tier.Blacksite, damage = 44.0, cooldown = 0.8, rangePx = 14.0 * METRE,
            projectileSpeed = 0.0, anchor = Anchor.Cursor,
            pattern = FirePattern.Blast(radius = 3.5 * METRE, falloff = 0.0),
        ),
        WeaponSpec(
            id = WeaponId.NullEgoSingularity, name = "Null-Ego Singularity",
            cls = WeaponClass.Psychic, tier = Tier.Ascended, damage = 55.0, cooldown = 1.0,
            rangePx = 6.0 * METRE, projectileSpeed = 0.0, projectileCount = 2,
            pierce = Int.MAX_VALUE, anchor = Anchor.Cursor,
            pattern = FirePattern.Orbit(radius = 3.0 * METRE, revolutionsPerMinute = 180.0),
        ),
        WeaponSpec(
            id = WeaponId.VoiceOfTheDeadNet, name = "Voice of the Dead Net",
            cls = WeaponClass.Psychic, tier = Tier.Ascended, damage = 95.0, cooldown = 1.05,
            rangePx = 12.0 * METRE, projectileSpeed = 0.0,
            onFire = listOf(FireEffect.FreeRecast(chance = 0.4)),
            pattern = FirePattern.Chain(jumps = 8, jumpRange = 5.0 * METRE, decay = 0.0),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun of(id: WeaponId): WeaponSpec = byId.getValue(id)

    fun ofTier(tier: Tier): List<WeaponSpec> = all.filter { it.tier == tier }

    @Suppress("LongParameterList")
    private fun melee(
        id: WeaponId, name: String, tier: Tier, damage: Double, cooldown: Double,
        reachMetres: Double, arcDegrees: Double, projectiles: Int = 1, knockback: Double = 0.0,
        onHit: List<HitEffect> = emptyList(), onFire: List<FireEffect> = emptyList(),
    ) = WeaponSpec(
        id = id, name = name, cls = WeaponClass.Melee, tier = tier, damage = damage,
        cooldown = cooldown, rangePx = reachMetres * METRE, projectileSpeed = 0.0,
        projectileCount = projectiles, knockback = knockback, onHit = onHit, onFire = onFire,
        pattern = FirePattern.ArcSwing(arcDegrees = arcDegrees, lingerSeconds = 0.1),
    )

    @Suppress("LongParameterList")
    private fun ranged(
        id: WeaponId, name: String, tier: Tier, damage: Double, cooldown: Double, speed: Double,
        projectiles: Int = 1, spread: Double = 0.0, burst: Double = 0.0, pierce: Int = 0, windUp: Double = 0.0,
        falloff: Falloff = Falloff.None, onHit: List<HitEffect> = emptyList(),
    ) = WeaponSpec(
        id = id, name = name, cls = WeaponClass.Ranged, tier = tier, damage = damage,
        cooldown = cooldown, rangePx = 20.0 * METRE, projectileSpeed = speed,
        projectileCount = projectiles, spreadDegrees = spread, burstIntervalSeconds = burst, pierce = pierce,
        windUpSeconds = windUp, falloff = falloff, onHit = onHit,
        pattern = FirePattern.Projectile(gravity = 0.0, lifetimeSeconds = 2.0),
    )

    @Suppress("LongParameterList")
    private fun psychic(
        id: WeaponId, name: String, tier: Tier, damage: Double, cooldown: Double, speed: Double,
        projectiles: Int = 1, homing: Homing = Homing.None,
    ) = WeaponSpec(
        id = id, name = name, cls = WeaponClass.Psychic, tier = tier, damage = damage,
        cooldown = cooldown, rangePx = 16.0 * METRE, projectileSpeed = speed,
        projectileCount = projectiles, homing = homing,
        pattern = FirePattern.Projectile(gravity = 0.0, lifetimeSeconds = 3.0),
    )
}
