package io.github.ksean.cyberslop.loot

/**
 * The powerup registry.
 *
 * Every curve is sub-linear or, where the effect is a whole number of things, exactly linear. Three
 * copies of one powerup is therefore worth less than three different ones, which is what stops a
 * build collapsing into a single stat.
 */
object Powerups {
    val all: List<Powerup> = listOf(
        Powerup(PowerupId.FractureLens, "Fracture Lens", PowerupTier.Street,
            Combination.Additive, listOf(0.08, 0.14, 0.18)),
        Powerup(PowerupId.KineticDamper, "Kinetic Damper", PowerupTier.Street,
            Combination.Multiplicative, listOf(0.60, 1.10, 1.50)),
        Powerup(PowerupId.RangerOptics, "Ranger Optics", PowerupTier.Street,
            Combination.Multiplicative, listOf(0.20, 0.35, 0.50)),
        Powerup(PowerupId.GuillotineCodec, "Guillotine Codec", PowerupTier.Street,
            Combination.Additive, listOf(0.50, 0.85, 1.10)),

        Powerup(PowerupId.HollowpointFirmware, "Hollowpoint Firmware", PowerupTier.Scav,
            Combination.Additive, listOf(0.25, 0.45, 0.60)),
        Powerup(PowerupId.SpikeDriver, "Spike Driver", PowerupTier.Scav,
            Combination.Additive, listOf(1.0, 2.0, 3.0)),
        Powerup(PowerupId.RedMarketSiphon, "Red Market Siphon", PowerupTier.Scav,
            Combination.Additive, listOf(0.020, 0.035, 0.045)),
        Powerup(PowerupId.MassDriver, "Mass Driver", PowerupTier.Scav,
            Combination.Multiplicative, listOf(0.25, 0.45, 0.60)),

        // Stored as the cooldown *reduction*, so a bigger number is always a stronger powerup and
        // the "always increases" property means what it says.
        Powerup(PowerupId.OverclockCoil, "Overclock Coil", PowerupTier.Chromed,
            Combination.Multiplicative, listOf(0.12, 0.21, 0.28)),
        Powerup(PowerupId.ChillProtocol, "Chill Protocol", PowerupTier.Chromed,
            Combination.Multiplicative, listOf(0.18, 0.30, 0.38)),
        Powerup(PowerupId.BurnRig, "Burn Rig", PowerupTier.Chromed,
            Combination.Additive, listOf(0.15, 0.25, 0.32)),
        Powerup(PowerupId.RicochetRom, "Ricochet ROM", PowerupTier.Chromed,
            Combination.Additive, listOf(1.0, 2.0, 3.0)),

        Powerup(PowerupId.SeekerDaemon, "Seeker Daemon", PowerupTier.Blacksite,
            Combination.Additive, listOf(90.0, 160.0, 210.0)),
        Powerup(PowerupId.ArcCascade, "Arc Cascade", PowerupTier.Blacksite,
            Combination.Additive, listOf(1.0, 2.0, 3.0)),
        // Expected stun-seconds: chance times duration.
        Powerup(PowerupId.BrownoutCharge, "Brownout Charge", PowerupTier.Blacksite,
            Combination.Additive, listOf(0.048, 0.090, 0.132)),

        // Extra projectiles at a per-projectile penalty; magnitude is the effective gain.
        Powerup(PowerupId.ForkBomb, "Fork Bomb", PowerupTier.Ascended,
            Combination.Additive, listOf(0.70, 1.20, 1.65)),
        Powerup(PowerupId.ThermitePayload, "Thermite Payload", PowerupTier.Ascended,
            Combination.Additive, listOf(0.35, 0.45, 0.55)),
        Powerup(PowerupId.KillstreakCache, "Killstreak Cache", PowerupTier.Ascended,
            Combination.Event, listOf(0.15, 0.25, 0.35)),
    )

    private val byId = all.associateBy { it.id }

    fun of(id: PowerupId): Powerup = byId.getValue(id)

    fun ofTier(tier: PowerupTier): List<Powerup> = all.filter { it.tier == tier }
}
