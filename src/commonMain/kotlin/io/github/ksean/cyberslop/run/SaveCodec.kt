package io.github.ksean.cyberslop.run

import io.github.ksean.cyberslop.combat.WeaponId
import io.github.ksean.cyberslop.combat.Weapons
import io.github.ksean.cyberslop.loot.Loadout
import io.github.ksean.cyberslop.loot.Pickup
import io.github.ksean.cyberslop.loot.Powerup
import io.github.ksean.cyberslop.loot.PowerupId
import io.github.ksean.cyberslop.loot.PowerupSlots

/** A save that could not be read, and why. */
data class SaveRejected(val reason: String)

/**
 * Serialises a run and the meta-progression around it.
 *
 * The format carries an explicit version from the first release, and anything it cannot read is
 * **rejected rather than guessed at**. A save written by an older deployment must never crash a
 * newer one, and a half-understood save is worse than none: it would resurrect a run into a state
 * the game no longer has rules for.
 *
 * Hand-rolled rather than pulled from a library (ENG-004): the shape is a handful of fields, and a
 * serialisation dependency would be doing nothing Kotlin cannot.
 */
object SaveCodec {
    // Bumped when aiming stopped being a setting: a version 1 save carries a field this build has
    // no concept of, and guessing at it is worse than asking the player to start again.
    const val VERSION = 2
    private const val FIELD = '|'
    private const val ITEM = ','
    private const val PAIR = ':'

    fun encodeRun(run: RunState, meta: MetaProgression): String = listOf(
        VERSION.toString(),
        run.seed.toString(),
        run.mapIndex.toString(),
        run.loadout.weapon.id.name,
        run.loadout.slots.held.entries.joinToString(ITEM.toString()) { "${it.key.name}$PAIR${it.value}" },
        run.health.toString(),
        run.scrap.toString(),
        meta.scrap.toString(),
    ).joinToString(FIELD.toString())

    fun decodeRun(encoded: String?): Result<Pair<RunState, MetaProgression>> {
        if (encoded.isNullOrBlank()) return Result.failure(SaveError("no save present"))
        val fields = encoded.split(FIELD)
        if (fields.size != FIELD_COUNT) {
            return Result.failure(SaveError("expected $FIELD_COUNT fields, found ${fields.size}"))
        }

        val version = fields[0].toIntOrNull()
            ?: return Result.failure(SaveError("unreadable version '${fields[0]}'"))
        if (version != VERSION) {
            // There is only one version so far. When a second exists, migration belongs here; until
            // then, refusing is the honest response to a save this build does not understand.
            return Result.failure(SaveError("save version $version, this build reads $VERSION"))
        }

        return runCatching {
            val seed = fields[1].toULong()
            val mapIndex = fields[2].toInt()
            require(mapIndex in 1..MAX_MAP) { "map index $mapIndex out of range" }
            val weapon = Weapons.of(WeaponId.valueOf(fields[3]))
            val slots = decodeSlots(fields[4])
            val health = fields[5].toDouble()
            require(health > 0.0) { "a dead run cannot be resumed" }
            val scrap = fields[6].toInt()
            val metaScrap = fields[7].toInt()

            RunState(seed, mapIndex, Loadout(weapon, slots), health, scrap) to
                MetaProgression(metaScrap, MetaProgression.unlocksFor(metaScrap))
        }.recoverCatching { throw SaveError(it.message ?: "malformed save") }
    }

    /**
     * Rejects anything that would not fit the caps, rather than applying what it can.
     *
     * Silently truncating is worse than refusing: six distinct powerups would become five and a
     * stack of four would become three, so the run resumes as a build the player never had. An
     * unchecked count is also a loop bound taken from the save file — a corrupt one would hang the
     * title screen while it checks whether a save exists.
     */
    private fun decodeSlots(encoded: String): PowerupSlots {
        var slots = PowerupSlots.empty()
        if (encoded.isBlank()) return slots

        val entries = encoded.split(ITEM)
        require(entries.size <= PowerupSlots.MAX_SLOTS) {
            "save holds ${entries.size} powerups, more than the ${PowerupSlots.MAX_SLOTS} slots allow"
        }
        entries.forEach { entry ->
            val parts = entry.split(PAIR)
            require(parts.size == 2) { "malformed powerup entry '\$entry'" }
            val id = PowerupId.valueOf(parts[0])
            val count = parts[1].toInt()
            require(count in 1..Powerup.MAX_STACKS) { "\$id has \$count stacks" }
            repeat(count) {
                val (next, outcome) = slots.collect(id)
                require(outcome is Pickup.Applied) { "\$id would not fit the build" }
                slots = next
            }
        }
        return slots
    }

    private const val FIELD_COUNT = 8
    private const val MAX_MAP = 10
}

class SaveError(message: String) : Exception(message)
