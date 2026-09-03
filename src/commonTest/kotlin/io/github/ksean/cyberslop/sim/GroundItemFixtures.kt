package io.github.ksean.cyberslop.sim

/** Test-side payload queries keep assertions concise without weakening the production model. */
internal val GroundItem.equipmentPayload: GroundItem.Equipment?
    get() = payload as? GroundItem.Equipment

internal val GroundItem.isGuaranteedEquipment: Boolean
    get() = equipmentPayload?.guaranteed == true

internal fun GroundItem.requireEquipment(): GroundItem.Equipment =
    equipmentPayload ?: error("fixture: ground item is not equipment")
