package io.github.ksean.cyberslop.sim

/** Test-observation phases for one telegraphed attack activation; never part of simulation state. */
internal enum class IncomingAttackPhase {
    Started,
    Opportunity,
    DamagedPlayer,
    Completed,
}

/** An inert observation emitted only when a test harness installs an incoming-attack observer. */
internal data class IncomingAttackEvent(
    val activationId: Int,
    val phase: IncomingAttackPhase,
)
