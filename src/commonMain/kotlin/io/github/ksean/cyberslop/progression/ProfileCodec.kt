package io.github.ksean.cyberslop.progression

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.loot.PowerupId

/** Canonical, versioned browser-profile encoding. */
object ProfileCodec {
    const val VERSION = 1
    private const val FIELD = '|'
    private const val ITEM = ','
    private const val EMPTY = "-"
    private const val FIELD_COUNT = 6

    fun encode(profile: PlayerProfile): String = listOf(
        VERSION.toString(),
        profile.spendableScrap.toString(),
        profile.lifetimeScrap.toString(),
        listOf(
            profile.upgrades.reinforcedChassis,
            profile.upgrades.blackMarketFirmware,
            profile.upgrades.reactiveDermalWeave,
        ).joinToString(ITEM.toString()),
        encodeWeapons(profile.discoveredWeapons),
        encodePowerups(profile.discoveredPowerups),
    ).joinToString(FIELD.toString())

    fun decode(encoded: String?): Result<PlayerProfile> {
        if (encoded.isNullOrBlank()) return Result.failure(ProfileError("no profile present"))
        return runCatching {
            val fields = encoded.split(FIELD)
            require(fields.size == FIELD_COUNT) { "expected $FIELD_COUNT fields, found ${fields.size}" }
            val version = fields[0].toInt()
            require(version == VERSION) { "profile version $version, this build reads $VERSION" }
            val spendable = fields[1].toInt()
            val lifetime = fields[2].toInt()
            val ranks = fields[3].split(ITEM)
            require(ranks.size == UpgradeId.entries.size) { "expected ${UpgradeId.entries.size} upgrade ranks" }
            PlayerProfile(
                spendableScrap = spendable,
                lifetimeScrap = lifetime,
                upgrades = UpgradeRanks(ranks[0].toInt(), ranks[1].toInt(), ranks[2].toInt()),
                discoveredWeapons = decodeWeapons(fields[4]),
                discoveredPowerups = decodePowerups(fields[5]),
            )
        }.recoverCatching { throw ProfileError(it.message ?: "malformed profile") }
    }

    fun decodeLegacyScrap(encoded: String?): Result<PlayerProfile> {
        if (encoded.isNullOrBlank()) return Result.failure(ProfileError("no legacy profile present"))
        return runCatching { PlayerProfile.fromLegacyScrap(encoded.toInt()) }
            .recoverCatching { throw ProfileError(it.message ?: "malformed legacy profile") }
    }

    private fun encodeWeapons(ids: Set<WeaponId>): String =
        ids.sortedBy { it.ordinal }.joinToString(ITEM.toString()) { it.name }.ifEmpty { EMPTY }

    private fun encodePowerups(ids: Set<PowerupId>): String =
        ids.sortedBy { it.ordinal }.joinToString(ITEM.toString()) { it.name }.ifEmpty { EMPTY }

    private fun decodeWeapons(encoded: String): Set<WeaponId> {
        if (encoded == EMPTY) return emptySet()
        require(encoded.isNotBlank()) { "blank weapon discoveries" }
        val values = encoded.split(ITEM).map(WeaponId::valueOf)
        require(values.distinct().size == values.size) { "duplicate weapon discovery" }
        return values.toSet()
    }

    private fun decodePowerups(encoded: String): Set<PowerupId> {
        if (encoded == EMPTY) return emptySet()
        require(encoded.isNotBlank()) { "blank powerup discoveries" }
        val values = encoded.split(ITEM).map(PowerupId::valueOf)
        require(values.distinct().size == values.size) { "duplicate powerup discovery" }
        return values.toSet()
    }
}

class ProfileError(message: String) : Exception(message)
