# Tasks

Open implementation work, one entry per step of [`plan.md`](plan.md).

Completed work must be removed from this file, never retained as a checked task. Lasting
requirements and decisions belong in `specs/`.

## Open

- **MAP-BALANCE-1 — Flatten player health and steepen enemy scaling.**
  Approval: pending after phase-one specification review; record explicit approval here before
  adding a failing test or changing production code.
  - Add the smallest balance tests proving unupgraded player maximum health is 100 on maps 1–10,
    enemy health scales linearly from 1× to 5×, enemy damage scales linearly from 1× to 7×, and
    survivable-hazard damage retains its independent linear 1× to 5× curve; run them and preserve
    the expected failures.
  - Implement the smallest `commonMain` balance change, including a distinct hazard-damage unit,
    without changing permanent-upgrade multipliers, enemy or damaging-hazard density, lethal
    hazards, or other map difficulty curves.
  - Update dependent calibration fixtures and verify map-to-map health carry, permanent health
    upgrades, enemy attacks and contact, and spike/glass/barrel damage with focused tests.
  - Run `./scripts/check.sh` and retain the existing loot-floor, pressure and survivability
    requirements; do not silently rebalance weapons, drops or unrelated combat mechanics.

## Deferred

Not scheduled by the current plan; kept so they are not forgotten.

- Human playtest of a full run with a written rubric (fairness, telegraph readability, camera).
- Recalibrate `WeaponScore` against `expectedDps` (see `specs/combat.md`, Known gaps).
- A committed, reproducible frame-time benchmark (the 7.6× transform figure is unretained).
- Draw projectiles as their weapon's own shape (a slug, a nail, a grenade) — the tracer in CPS-3
  gives them a line of flight and LOOK-4 a lit body, not a silhouette.
- Pass-two styling: grime, scanlines, screen shake, hit flashes, particles.
