# Tasks

Open implementation work, one entry per step of [`plan.md`](plan.md).

Completed work must be removed from this file, never retained as a checked task. Lasting
requirements and decisions belong in `specs/`.

## Open

None.

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon's own shape (a slug, a nail, a grenade) — the tracer in CPS-3
  gives them a line of flight and LOOK-4 a lit body, not a silhouette.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
