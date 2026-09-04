package io.github.ksean.cyberslop.sim

internal class DodgeAccounting {
    private data class Activation(
        var responded: Boolean = false,
        var reachedOpportunity: Boolean = false,
        var damagedPlayer: Boolean = false,
        var completed: Boolean = false,
    )

    private val activations = mutableMapOf<Int, Activation>()

    val counted: Int
        get() = activations.values.count { it.reachedOpportunity && it.completed }

    val dodged: Int
        get() = activations.values.count {
            it.reachedOpportunity && it.completed && it.responded && !it.damagedPlayer
        }

    val dodgeRate: Double
        get() = if (counted == 0) 1.0 else dodged.toDouble() / counted

    fun observe(event: IncomingAttackEvent) {
        val activation = activations.getOrPut(event.activationId, ::Activation)
        when (event.phase) {
            IncomingAttackPhase.Started -> Unit
            IncomingAttackPhase.Opportunity -> activation.reachedOpportunity = true
            IncomingAttackPhase.DamagedPlayer -> activation.damagedPlayer = true
            IncomingAttackPhase.Completed -> activation.completed = true
        }
    }

    fun recordResponse(activationIds: Set<Int>) {
        activationIds.forEach { activationId ->
            activations.getOrPut(activationId, ::Activation).responded = true
        }
    }
}
