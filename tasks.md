# Tasks

Open implementation work, one entry per step of [`plan.md`](plan.md).

Completed work must be removed from this file, never retained as a checked task. Lasting
requirements and decisions belong in `specs/`.

## Open

### LOB — Ballistic lobbed projectiles (awaiting approval)

Requested: a grenade launcher, and every other weapon identified as lobbing its projectile, must
launch upward and follow a gravity-driven arc toward its target instead of firing straight at it.

Phase one is complete in `specs/product.md`, `specs/combat.md`, `specs/presentation.md` and
`specs/simulation.md`. Implementation is not approved yet; do not add a failing test or production
code until the user explicitly approves this recorded phase.

- **LOB-1 — Ballistic launch contract:** after approval, record it here, then add the smallest
  pure tests for the whole-tick launch solution and registry classification. Preserve the expected
  failure, implement the solver and set Ashfall's gravity to 600 px/s², then run the focused tests.
- **LOB-2 — Simulation and presentation integration:** add focused simulation tests for upward
  launch, per-tick gravity, target snapshot, collision, zero-gravity regression, relevant powerup
  interactions and digest sensitivity, plus a frame test for the launch-aligned flash and curved
  tracer. Preserve the expected failures, carry gravity and lob aim through live/pending state,
  integrate gravity before swept movement, compose from actual velocity, run the focused JVM and
  Wasm tests, then run `./scripts/check.sh`.

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Sound effects: kotlinx-browser exposes no Web Audio API, so this needs hand-written externals.
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon's own shape (a slug, a nail, a grenade) — the tracer in CPS-3
  gives them a line of flight and LOOK-4 a lit body, not a silhouette.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
