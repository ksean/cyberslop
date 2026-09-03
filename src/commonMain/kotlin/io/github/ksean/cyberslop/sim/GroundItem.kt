package io.github.ksean.cyberslop.sim

import io.github.ksean.cyberslop.combat.WeaponSpec
import io.github.ksean.cyberslop.core.Vec2
import io.github.ksean.cyberslop.loot.Powerup

/** Something lying on the map, with exactly one collectible payload (ENG-057). */
data class GroundItem(
    val position: Vec2,
    val payload: Payload,
) {
    sealed interface Payload

    /** One equipment award; paired awards resolve weapon first, then powerup (PROD-070). */
    data class Equipment(
        val weapon: WeaponSpec? = null,
        val powerup: Powerup? = null,
        /** Boss awards and the starter cache are guaranteed for `LootFloor`. */
        val guaranteed: Boolean = false,
    ) : Payload {
        init {
            require(weapon != null || powerup != null) { "equipment payload must contain an award" }
        }
    }

    /** The grounded healing payload from PROD-110. */
    data object Ramen : Payload

    /** Where a paired powerup is drawn; every other payload stays at [position]. */
    val powerupPosition: Vec2
        get() = when (val contents = payload) {
            is Equipment -> if (contents.weapon != null && contents.powerup != null) {
                position + Vec2(PAIRED_OFFSET, 0.0)
            } else {
                position
            }
            Ramen -> position
        }

    /** Contact is made at whichever equipment icon the player reaches, or at the ramen bowl. */
    fun inReachOf(centre: Vec2, reach: Double): Boolean =
        (position - centre).lengthSquared < reach * reach ||
            (powerupPosition - centre).lengthSquared < reach * reach

    companion object {
        /** A paired award's powerup icon sits one tile to the right of its weapon. */
        const val PAIRED_OFFSET = 16.0

        fun equipment(
            position: Vec2,
            weapon: WeaponSpec? = null,
            powerup: Powerup? = null,
            guaranteed: Boolean = false,
        ): GroundItem = GroundItem(position, Equipment(weapon, powerup, guaranteed))

        fun ramen(position: Vec2): GroundItem = GroundItem(position, Ramen)
    }
}
