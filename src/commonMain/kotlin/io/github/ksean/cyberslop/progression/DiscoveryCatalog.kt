package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.Powerups
import io.github.ksean.cyberslop.render.Icon
import io.github.ksean.cyberslop.render.PowerupIcons
import io.github.ksean.cyberslop.render.WeaponIcons

/** The typed identity shared by pickup reports, the persistent profile and discovery cards. */
sealed interface DiscoveryId {
    data class Weapon(val id: WeaponId) : DiscoveryId
    data class Powerup(val id: PowerupId) : DiscoveryId
}

data class DiscoveryEntry(
    val id: DiscoveryId,
    val name: String,
    val description: String,
    /** The canonical registry object: cards never carry a second drawing of an item. */
    val icon: Icon,
) {
    val announcement: String get() = "$name. $description"
}

/** Authored first-pickup copy in deterministic weapon-then-powerup order (P-57). */
object DiscoveryCatalog {
    val all: List<DiscoveryEntry> by lazy {
        Weapons.all.map { weapon ->
            DiscoveryEntry(
                DiscoveryId.Weapon(weapon.id),
                weapon.name,
                weaponDescriptions.getValue(weapon.id),
                WeaponIcons.of(weapon.id),
            )
        } + Powerups.all.map { powerup ->
            DiscoveryEntry(
                DiscoveryId.Powerup(powerup.id),
                powerup.name,
                powerupDescriptions.getValue(powerup.id),
                PowerupIcons.of(powerup.id),
            )
        }
    }

    private val byId: Map<DiscoveryId, DiscoveryEntry> by lazy { all.associateBy { it.id } }

    fun of(id: DiscoveryId): DiscoveryEntry = byId.getValue(id)

    private val weaponDescriptions: Map<WeaponId, String> = mapOf(
        WeaponId.BrokenBottle to "Swings a jagged bottle through a 70° close-range arc.",
        WeaponId.RustlineMachete to "Cuts nearby enemies in an arc and leaves them bleeding for three seconds.",
        WeaponId.CorpoRiotBaton to "Swings a wide arc that knocks enemies back and stuns them briefly.",
        WeaponId.ChromeFang to "Sweeps a narrow twin-blade arc at close range.",
        WeaponId.StaticLash to "Reaches four metres with a fast 60° electrified swing.",
        WeaponId.GutterjackCleaver to "Executes enemies below 15% health with a heavy close-range swing.",
        WeaponId.KillSwitchKatana to "Delivers rapid, focused arc strikes with extended melee reach.",
        WeaponId.ChromewreckMaul to "Sweeps a broad 100° arc with heavy damage and knockback.",
        WeaponId.MeatgrinderHalo to "Strikes every nearby enemy in a full circle at high speed.",
        WeaponId.ScraplineZipPistol to "Fires one quick, straight projectile at the nearest target.",
        WeaponId.TenementNailgun to "Fires two piercing projectiles at once across a 12° spread.",
        WeaponId.GanglordSmg to "Fires a straight three-round burst with 0.05 seconds between rounds.",
        WeaponId.RiotbreakerShotgun to "Fires five projectiles at once in a 30° spread.",
        WeaponId.VultureRailCarbine to "Fires a fast, heavy projectile that pierces multiple targets.",
        WeaponId.AshfallGrenadeLobber to "Launches a slow, heavy grenade-like projectile.",
        WeaponId.SableCorpRailgun to "Fires an extremely fast shot that can pierce up to eight targets.",
        WeaponId.DebtCollectorMinigun to "Fires one straight round every 0.12 seconds.",
        WeaponId.KesslerOrbitalUplink to "Calls down a wide blast at the current target.",
        WeaponId.NeuralSpike to "Launches a slow psychic projectile that turns toward nearby enemies.",
        WeaponId.MigraineLoop to "Detonates a three-metre psychic blast at its target through terrain.",
        WeaponId.WetwareScreamer to "Launches two psychic projectiles with strong homing.",
        WeaponId.GhostwireTether to "Chains through up to three enemies, losing 25% damage with each jump.",
        WeaponId.BlackboxChorus to "Crushes every enemy in a five-metre blast around its target.",
        WeaponId.SynapseHemorrhage to "Detonates a 3.5-metre psychic blast at its target.",
        WeaponId.NullEgoSingularity to "Hits every enemy in a three-metre ring around its target.",
        WeaponId.VoiceOfTheDeadNet to "Chains through up to eight enemies without losing damage between jumps.",
    )

    private val powerupDescriptions: Map<PowerupId, String> = mapOf(
        PowerupId.FractureLens to "Raises the chance that weapon hits deal critical damage.",
        PowerupId.KineticDamper to "Increases knockback from weapons that already push enemies.",
        PowerupId.RangerOptics to "Extends melee reach and increases projectile speed.",
        PowerupId.GuillotineCodec to "Raises the damage multiplier applied by critical hits.",
        PowerupId.HollowpointFirmware to "Raises every weapon's damage.",
        PowerupId.SpikeDriver to "Lets weapon attacks pierce additional targets.",
        PowerupId.RedMarketSiphon to "Heals you for a fraction of the damage dealt by every weapon hit.",
        PowerupId.MassDriver to "Enlarges projectile hitboxes, blasts, and melee reach.",
        PowerupId.OverclockCoil to "Shortens the delay between weapon activations.",
        PowerupId.ChillProtocol to "Slows enemies hit by your weapon for two seconds.",
        PowerupId.BurnRig to "Ignites enemies hit by your weapon for three seconds.",
        PowerupId.RicochetRom to "Lets ranged projectiles bounce off terrain while retaining 85% damage per bounce.",
        PowerupId.SeekerDaemon to "Makes non-melee projectiles turn toward nearby enemies.",
        PowerupId.ArcCascade to "Adds extra targets to attacks that chain between enemies.",
        PowerupId.BrownoutCharge to "Gives weapon hits a chance to stun enemies briefly.",
        PowerupId.ForkBomb to "Adds projectiles to each activation while dividing damage across the larger volley.",
        PowerupId.ThermitePayload to "Adds a nearby splash blast to instant weapon hits.",
        PowerupId.KillstreakCache to "Can clear the current weapon cooldown when an enemy dies.",
    )
}

data class DiscoveryUpdate(
    val profile: PlayerProfile,
    val entries: List<DiscoveryEntry>,
)

/** Pure first-seen transition; browser persistence remains outside the simulation. */
object DiscoveryRecorder {
    fun record(profile: PlayerProfile, collected: Iterable<DiscoveryId>): DiscoveryUpdate {
        var updated = profile
        val entries = mutableListOf<DiscoveryEntry>()
        collected.forEach { id ->
            val next = when (id) {
                is DiscoveryId.Weapon -> updated.discovering(id.id)
                is DiscoveryId.Powerup -> updated.discovering(id.id)
            }
            if (next != updated) {
                updated = next
                entries += DiscoveryCatalog.of(id)
            }
        }
        return DiscoveryUpdate(updated, entries)
    }
}
