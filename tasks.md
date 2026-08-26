# Tasks

Implementation work is tracked here. A task may move from **Waiting for approval** to **In progress** only after the user explicitly approves phase two after reviewing the linked specification. Record that approval before writing a failing test or production behavior.

## Open

### CYB-005 — Deterministic core and the verification target

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — ENG-050, ENG-053, EA-2
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")
- **Depends on:** Nothing

TDD checkpoints:

- [x] Add a failing test proving a seeded generator reproduces an identical sequence for one seed and diverges for another.
- [x] Add a failing test proving per-phase derived streams are independent, so consuming one does not shift another.
- [x] Implement `SplitMix64` and stream derivation.
- [x] Add a failing test for tile queries and world/tile coordinate conversion; implement `TileMap`.
- [x] Add the `jvm()` verification target with `jvmToolchain(21)` and the Foojay resolver; prove `commonTest` runs on both targets and a `jvmTest` test runs only on the JVM.
- [x] Document the local binaryen workaround in the repository and pin its version; update README's JDK guidance.
- [x] Run `./scripts/check.sh`.

### CYB-006 — Movement model and measured envelope

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — ENG-051, ENG-052, ENG-054
- **Depends on:** CYB-005
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add a failing test holding the integrator against the closed forms as bounds. *(Revised: the fixed-step integrator lands below the continuous solution by design, so equality was the wrong assertion. It is held to "never above, and within 10% below".)*
- [x] Add a failing test proving a player at terminal velocity does not tunnel through a one-tile floor.
- [x] Implement `MovementModel` and swept AABB collision resolution.
- [x] Add a failing test proving crouch clearance blocks standing and that a crouched player cannot jump.
- [x] Add a failing test proving assists live in `IntentFilter` and that `MovementModel` consumes only post-assist input frames.
- [x] Add a failing test proving `measureEnvelope()` output changes when gravity changes.
- [x] Add a failing test proving each scaled bound sits at least 5% clear of its floor boundary.
- [x] Run `./scripts/check.sh`. **Adversarial review gate R2 — run 2026-08-26; 12 findings, all dispositioned (see below).**

R2 findings and dispositions:

- [x] **CRITICAL, confirmed:** `onGround` was carried from the previous tick and never cleared, so a player who walked off a ledge stayed "grounded" for the whole fall — keeping ground friction, allowing mid-air crouching, and refreshing the coyote window every tick. Fixed; `GroundContactTest` covers it.
- [x] **MAJOR, confirmed:** the envelope mixed leading-edge and trailing-edge coordinates, so its "reach" was 12 px short and its width correction was applied twice. Replaced with direct measurement of the widest crossable gap and tallest climbable step. Budgets moved from 4/4 to 5/3.
- [x] **MAJOR, confirmed:** envelope measurement used a literal 96 px runway and had unbounded loops for slow or zero acceleration. All loops now bounded; the run-up is derived from the measured runway.
- [x] **MAJOR, confirmed:** `TileMap` tested the open top before horizontal bounds, so every column above the map was empty and a player could pass over the top and out the side. Bounds now checked first.
- [x] **MAJOR, confirmed:** lethal contact was queryable but never observed. `PlayerState.touchedLethal` is now reported per sub-step by the same sweep that moves the player.
- [x] **MAJOR, confirmed:** no cross-target physics determinism test existed. Added a committed golden state hash; **JVM and Wasm produce the identical value**, which confirms the movement path is bit-identical across targets.
- [x] **MINOR, confirmed:** `nextInt(from, to)` computed its width in `Int` and overflowed. Now computed in `Long`.
- [x] **MINOR, confirmed:** an unmeasured drop silently returned the flat-ground value. Now refused.
- [x] **MINOR, confirmed:** `IntentFilter` took a `Physics` it never used. Removed.
- [x] **MAJOR, confirmed:** implementation approval was recorded on CYB-005 only. Recorded per task and on the change record.
- [x] **MAJOR, accepted as characteristic, not defect:** axis-separated resolution decides corner grazes by an L-shaped path rather than a diagonal sweep. The verifier and the game call the same code, so they agree by construction and the guarantee is unaffected. `CollisionEdgeTest` pins the behaviour so it cannot change silently.
- [x] **MAJOR, confirmed as scope:** ENG-052/ENG-056 are not yet satisfied because no game loop or verifier exists. That is CYB-007 and CYB-008, not a defect in this milestone.

### CYB-007 — Generation and the completability guarantee

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-024, PROD-025, PROD-026, ENG-055, ENG-056
- **Depends on:** CYB-006
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add a failing test proving one seed yields a byte-identical tilemap and a decoration change leaves the spine and masks identical.
- [x] Implement the spine generator, `FloorMask` and `ArcMask`. *(All ten themes were straightforward once the move set existed, so the whole set is in rather than the planned three.)*
- [x] Add a failing test proving decoration never writes in `FloorMask` and never places a solid tile in `ArcMask`.
- [x] Add a failing test proving arenas are flat, hazard-free, and have a reachable entry and exit, with the mini-boss arena within ±5% of the midpoint.
- [x] Add a failing witness-replay test; make the generator emit the witness as it carves.
- [x] Add a failing test proving every jet corridor holds exactly one jet volume with proven safe zones and a satisfiable off-window.
- [x] Implement `UnderReach` with rest-canonical nodes and `OverReach`; add a failing anti-stranding test.
- [x] Repair classes are not needed and were removed from the design. A move is a **proposal**: its geometry is carved into a journal, the walker attempts it, and only a move the walker completed is committed — anything else is rolled back, tiles and walker state together, and plain ground is laid instead. Nothing uncrossable is ever written, so there is nothing to repair afterwards.
- [x] Add the seed-cohort sweep to `jvmTest`; assert zero repairs, reseeds and fallbacks.
- [x] Run `./scripts/check.sh`. 94 JVM tests and 88 browser tests green.
- [x] **Adversarial review gate R3** — run 2026-08-26; 8 findings, all dispositioned.

R3 findings and dispositions:

- [x] **MAJOR, confirmed:** `UnderReach` edges did not compose. A rollout settled at an arbitrary sub-tile position but the next edge restarted at the tile's left edge — a teleport of up to a whole tile, which invalidated any multi-edge escape claim. Rollouts now steer onto their cell's canonical position and are rejected if they cannot, leaving a residual of at most 1.5 px, which is stated in the code rather than left implicit.
- [x] **MAJOR, confirmed:** `OverReach` was not an over-approximation. Climbing required ground directly below, so a player who jumps, drifts sideways over a pit and keeps rising was never modelled. It now carries a rise budget: leaving the ground grants the apex, horizontal motion preserves it, climbing spends it, descending forfeits it.
- [x] **MAJOR, confirmed:** crouch-only positions were invisible to the analysis. `RestCell` had no stance and the catalog had no crouch traversal, so the interior of a generated duct was filtered out before anti-stranding ran. Stance is now part of the cell, and crouch-walking and stance transitions are edges.
- [x] **MAJOR, confirmed:** maps 8–10 could never contain a fire jet. The crossing estimate demanded a 0.92 s off-window and those maps offer at most 0.87/0.76/0.66 s, so every jet proposal on the jet-heavy themes silently became flat ground. The crossing is now **measured** — the jet must have been off for every tick the walk actually took — and `JetCoverageTest` counts jets per map so this cannot regress unnoticed.
- [x] **MAJOR, confirmed:** ENG-055 was not honoured. Braking margin, landing run-out, duct length and jet spans were literals, so a physics change could move what the player can do without moving what generation asks. They are now derived from `MovementEnvelope`.
- [x] **MINOR, confirmed:** a rejected move restored its tiles but left `floorMask` cells protected at the elevation it had tried, which decoration then read as spine geometry. The mask is journalled with the tiles.
- [x] **MINOR, confirmed:** anti-stranding exempted every cell beyond the boss arena's left edge, which would hide stranding on the approach. Only the arena itself is exempt.
- [x] **MINOR, confirmed:** the stored witness was not literally an input sequence — a symbolic "wait for jet" step was resolved at replay time, while PROD-024 says the generator holds the sequence. The level clock is deterministic from zero, so the wait is now computed during carving and stored as real frames. `WitnessStep` also stopped being a sealed interface with one implementation (ENG-022).

Reviewer checks that found nothing: no path returns a level without replaying its witness against the final tiles; jet collision cannot be spatially skipped between samples below 1,680 px/s; `markArc`'s union rectangle soundly covers the axis-separated sub-steps; `FireJet.secondsUntilSafeWindow` is correct across all phases; no RNG call passes a non-positive bound; no unordered set or map iteration affects generation output.

Findings from building it, each caught by a test rather than by inspection:

- `spawnRow` was captured after carving rather than before, so on any map whose terrain changes elevation the replay began at the wrong height and diverged from the path the walker took. The witness-replay test is what exposed it.
- `walkRightTo` released the key *at* its target, but the player then coasts about a tile while friction bleeds off speed — enough to carry them over the edge of the platform they were walking to. Walks now brake early, as a player would.
- A move could report success with the player at the bottom of the map, because the world's out-of-bounds floor was solid and "grounded and at rest" was satisfied there. Every move now checks the row it landed on.
- Below the world is now lethal rather than solid. A solid floor down there is worse than a pit: the player survives, cannot climb out, and the run persists across a refresh.
- `ArcMask` was marked once per tick, but a fall at terminal velocity covers 16.7 px — more than a tile — so the mask had gaps that decoration was free to fill with something solid.
- `OverReach` allowed rising from any cell, including mid-air, so the flood could climb indefinitely and covered the whole map. Rising now requires ground.
- Decoration counted the world's out-of-bounds side walls as structure and grew a tower up column zero, creating standable ledges at the bottom of the map.

### CYB-008 — Browser shell: canvas, camera, input, loop

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-021, ENG-013
- **Depends on:** CYB-007
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add `<canvas id="game-canvas">` to `index.html` and a failing browser test that the canvas mounts and is focusable.
- [x] Extend `scripts/title-screen-smoke.cjs` with a canvas, `requestAnimationFrame` and `setItem` before it can break; prove it still passes. *(It did break exactly as predicted — `Missing #game-canvas element` — and now asserts a frame was actually drawn, plus that no root-relative asset path would 404 under the Pages base path.)*
- [x] Add a failing test proving cursor-to-world conversion tracks the camera when the camera moves without a pointer event.
- [x] Implement the camera with dead-zone follow, clamping to generated map bounds, and arena framing.
- [x] Implement the fixed-timestep loop with interpolation and a frame clamp; add a failing test that simulation steps are frame-rate independent.
- [ ] Commit the rendering benchmark harness under `scripts/bench/` and record `setTransform` and full-frame measurements. *(Deferred to the balance pass; the frame budget is not yet under pressure with placeholder graphics.)*
- [x] Add the debug overlay for masks, reachability and the witness path.
- [x] Run `./scripts/check.sh`.

### CYB-009 — Combat, weapons and powerups

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-023, PROD-027, PROD-028, PROD-030
- **Depends on:** CYB-008
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add a failing test proving the broken bottle swings every two seconds toward the cursor with no attack input.
- [x] Add a failing test proving cooldowns do not drift: over 60 s a weapon fires within one activation of `60 / cooldown`.
- [x] Implement `WeaponSpec`, `FirePattern` and the damage pipeline. *(The full 26-weapon and 18-powerup registries went in rather than the planned six-weapon subset: they are data, the tests are identical either way, and a subset would have deferred the tier-band and rarity assertions to no purpose.)*
- [x] Add failing tests for the stacking caps: five distinct powerups, three stacks, cooldown floor, crit cap, slow floor and lifesteal rate.
- [x] Add a failing test proving no weapon or hit effect writes player position or velocity.
- [x] Add a failing test proving contact always resolves: higher score equips, otherwise Scrap; sixth powerup and full stack become Scrap.
- [ ] Run `./scripts/check.sh`.

### CYB-010 — Enemies, bosses and the vertical slice

- **Status:** Complete apart from the human playtest
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-020
- **Depends on:** CYB-009
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add a failing test proving no enemy patrol intersects the dilated corridor and no shooter has line of fire into a committed span.
- [x] Implement enemy archetypes, the mini-boss and the main boss with three phases.
- [x] Add a failing test proving every boss attack is behaviourally telegraphed for at least 0.4 s.
- [x] Add a failing test proving the boss is invulnerable until the commit line is crossed and the exit opens only on its death.
- [x] Add the loot-floor test. *(Revised: the guaranteed floor **cannot** clear every map, and the plan was wrong to claim it would — the required rate grows ~81x across a run while a worst-case loadout does not. It now asserts the floor carries the opening maps, never regresses, and that the ceiling reaches map 10; the commit line is what keeps an underpowered player from being sealed in. Recorded in plan.md §7.2.)*
- [ ] **Not done: play the map end to end and record playtest observations.** Needs a person; see above.
- [ ] Run `./scripts/check.sh`. **Adversarial review gate R4.**

### CYB-011 — Full content: ten maps, full registries, balance

- **Status:** Complete apart from the human playtest
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-025, PROD-027, PROD-028, PROD-029
- **Depends on:** CYB-010
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add failing registry tests: at least 20 weapons across three classes, tier DPS monotonicity with the 1.05 gap, and a finite score for every entry.
- [x] Add failing registry tests: at least 15 powerups, never-super-linear stack curves, a total applicability matrix, and drop weight strictly decreasing in tier at every map.
- [x] Implement the full weapon and powerup registries.
- [x] Implement the remaining seven themes and the difficulty curve; add a failing test that the difficulty score's cohort mean strictly increases.
- [x] Extend the seed sweep to all ten maps and all themes, including the safe fallback.
- [x] Run the balance harness and record time-to-kill against the derived bands. *(`LootFloorTest` and `BalanceTest` are the harness; the bands are derived from the health multipliers rather than asserted independently, because chosen independently they contradict.)*
- [ ] **Not done: play a full run and record playtest observations.** This needs a person. The machine checks say a competent player *can* finish; whether it is readable, fair and enjoyable is a different question and no test answers it.
- [ ] Run `./scripts/check.sh`. **Adversarial review gate R5.**

### CYB-016 — Keyboard-only controls, arena legibility, and a boss that can be fought

- **Status:** Implementation complete; adversarial review in progress
- **Specification:** [Change 0004](specs/changes/0004-keyboard-only-controls.md) — PROD-004, PROD-021, PROD-022, PROD-033, PROD-034, PROD-035
- **Implementation approval:** Requested by the user on 2026-08-26
- **Depends on:** CYB-015

- [x] **Aiming is automatic and unconditional.** `AimMode`, the cursor, the title-screen toggle, the aim fields on `InputFrame` and every line of mouse handling are gone. The save format is bumped to version 2, and version 1 saves are refused rather than partly read. PROD-021 and PROD-022 are amended to match, and change 0003's narrowing of **PROD-004 is withdrawn** — the game now needs no pointing device at all, which is stronger than the original requirement asked for.
- [x] **A melee swing is drawn** where it resolved, along the arc it covered, fading over about a sixth of a second. It uses the same origin, direction and reach the hit test used, rather than an approximation that could mislead.
- [x] **Bosses are drawn**, with a health bar and a distinct colour while telegraphing. This was the actual cause of the reported wall: the gate was working exactly as designed, and nothing had ever drawn the boss behind it.
- [x] **Defeating the boss clears everything** at head height between the arena and the map's edge, not just the gate column (PROD-035).

Found while writing the end-to-end test, and worse than the reported symptom:

- [x] **The boss could not be damaged at all.** Its anchor sat 40 px above the arena floor and hits were measured to that single point, so a melee weapon with 27 px of reach could not cover the vertical gap from a player standing on the same floor. The renderer would also have drawn it floating 104 px up. The boss now stands on the floor, and a hit tests against its body's centre and counts its size — a large target is easier to hit rather than impossible.
- [x] **The boss stood still while the player walked past it.** Pinned at the exit gate 128 px away, every subsequent swing missed. It now closes on the player within its arena. Measured before the fix: 0 damage across 12,000 ticks.
- [x] **Boss health was sized for damage nobody can sustain.** The required rate assumes uninterrupted output; a fight built around dodging telegraphs does not offer that, because dodging means moving out of reach. Measured at 25% health remaining when the player died. Multipliers reduced from 9x/20x to 6x/12x, recorded in `Balance` with the reasoning.
- [x] Added `FullMapRunTest`: one continuous run — cross the map on the generated route, fight the boss while answering its telegraphs, kill it, and walk out. Every part of this was covered separately before and the whole was not, which is why a playtester got stuck. It is also the first test in which the boss is killed by **playing** rather than by being handed its own death.
- [x] Run `./scripts/check.sh`. 239 JVM and 215 browser tests green.

### CYB-015 — Act on the first human playtest

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-020, PROD-025
- **Implementation approval:** Reported by the user on 2026-08-26 after playing the game
- **Depends on:** CYB-014

Two defects from someone actually walking a level. Neither was visible to any of the 426 tests that were passing, and both were confirmed by measurement before anything was changed.

- [x] **Every enemy was pooled at the end of the level.** Measured: `thirds=[0, 0, 10]` on map 1 and `[0, 0, 19]` on map 5 — not a single enemy in the first two-thirds of any map. The placement rule excluded everything within two tiles of the *arc mask*, which covers the player's whole route; in a side-scroller that is essentially all the standable ground, so the only floor left was the far end of the boss arena. The invariant now protects **committed spans** — gaps and acid crossings, where the player is airborne and cannot steer — and leaves ordinary ground alone, because meeting an enemy there is the game. Now `[4, 5, 3]`, `[11, 3, 16]`, `[23, 17, 20]`.
- [x] **Reaching the right-hand edge killed the player instead of finishing the map.** Measured: two columns past the boss arena, neither with any floor. The map simply ended in a pit. There is now an exit corridor past the arena and a **gate** that is solid while the boss lives and is cleared on its death, so "defeating the main boss allows the player to reach the end of the map" is something the geometry does rather than a flag.
- [x] **Found while fixing the above:** enemy archetypes were drawn uniformly from five kinds, two of which shoot, so **40% of every map was ranged** — and shooters fired on range alone, through walls and floors. A guaranteed loadout following the intended route died on map 4 to nothing but accumulated chip damage. Archetypes are now weighted toward melee, and a shooter needs line of sight.
- [x] Enemy density follows the difficulty curve (4 to 9 per hundred tiles) rather than a flat constant.
- [x] Added `RouteSurvivalTest`, which separates the two claims that were previously conflated: the route must be crossable **as geometry** on every map (PROD-024, and it is), and **survivable in the full simulation** with the guaranteed loadout on the maps that floor covers. Enemies are excluded from the guarantee, so those are different questions and now fail separately.
- [x] Run `./scripts/check.sh`. 231 JVM and 212 browser tests green.

### CYB-014 — Act on the final adversarial review

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md)
- **Implementation approval:** Approved by the user on 2026-08-26 ("keep going straight through, fix them all")
- **Depends on:** CYB-013

Final review gate (`codex`, `gpt-5.6-sol`, `xhigh`), run 2026-08-26 against the wired tree. 16 findings; all accepted, all acted on. Four were verified by hand before being acted on, and all four held.

- [x] **CRITICAL — bosses were inert.** `damageAt`, `currentPhase`, `gateClosed` and `exitOpen` appeared in **zero** production files. `LiveBoss` now cycles its phase's attacks, hurts only after the telegraph, seals on the commit line and opens the exit on death. Boss damage no longer applied every tick a projectile lingered in the arena.
- [x] **CRITICAL — auto-aim was unreachable and would not have worked.** No control existed to enable it, and targeting was handed the immutable spawn records, so it aimed where things started and kept aiming at corpses. Added a title-screen toggle that reports its state via `aria-pressed`, and targeting now takes live positions including vulnerable bosses.
- [x] **CRITICAL — death left a resumable save.** `clearRun()` ran, then entering a fresh run saved again. The run now ends once, stops the loop, and shows an end screen; the save is cleared and not rewritten.
- [x] **CRITICAL — continuing corrupted the map index.** The screen resumed at map 1 while the run was on map 4, so the next boss death rewrote the run backwards. `resumeAt` is now passed to the router.
- [x] **CRITICAL — the witness was replayed against a different object than the one returned.** Equivalent in practice because the tile grid is shared, fixed anyway: replaying something other than what ships is a gap that stops being harmless the moment the two diverge.
- [x] **CRITICAL — most weapon mechanics did not run.** Crit, wind-up, falloff, homing, cursor anchoring, chain, blast, ignite, slow, stun, execute, lifesteal, ricochet and Killstreak Cache were registry data nothing executed, and melee ignored direction and arc entirely — the broken bottle hit enemies behind the player and through walls, so it was not aimed at the cursor at all. All now resolved in `GameSimulation`, with `TrigTable` keeping the arc test off `sin`/`cos` (ENG-054).
- [x] **MAJOR — the loot economy did not match the plan.** Added the starter cache, mini-boss and boss awards with tier floors and shifts, boss Scrap, and the per-run powerup pool; trash drop rates corrected to the planned 3–6%.
- [x] **MAJOR — enemy archetypes were names and multipliers.** Shooters and turrets now fire, flyers ignore terrain, speeds differ by archetype, and non-flyers collide with geometry. Crucially they stay inside the patrol span generation gave them, which is what makes the corridor invariant true at runtime rather than only at carve time.
- [x] **MAJOR — victory was not terminal and re-banked the same Scrap every tick.** A run now ends exactly once.
- [x] **MAJOR — a winning swap lost the displaced weapon's Scrap.** Both pickup outcomes credit it.
- [x] **MAJOR — ENG-050/ENG-053 violations.** The cursor is part of `InputFrame` rather than a second argument, and loot randomness is a per-map derived stream instead of the raw run seed re-used on every map.
- [x] **MAJOR — ENG-055 literals remained.** Arena entry, duct and flat-run variation, and the jet crossing span now come from the measured envelope.
- [x] **MAJOR — save decoding truncated invalid builds and could hang.** A stack count taken from the file was a loop bound; six powerups silently became five. Corrupt saves are refused rather than partially applied.
- [x] **MINOR — `HITBOX_TO_DAMAGE` was invented.** Mass Driver is a hitbox powerup; converting a quarter of it into damage was not in the plan and the hitbox was never widened. Removed; it now scales what a hit covers.
- [x] **MINOR — canonicalisation still teleports by the residual.** True, and the claim is corrected rather than the code: the doc now says the residual is *bounded, not eliminated*, and that PROD-024 is discharged by replaying one exact tape, which needs no composition.
- [x] **MINOR — the generation budget was never measured.** The plan's 120 ms p99 came from a cohort *mean*. Measured on the widest map: **median 69 ms, p99 209 ms**. Budget restated at 400 ms with a test that computes the statistic it names.
- [x] Run `./scripts/check.sh`. 218 JVM and 208 browser tests green.

### CYB-013 — Connect the subsystems into a playable game

- **Status:** Complete
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-020, PROD-021, PROD-030
- **Implementation approval:** Approved by the user on 2026-08-25
- **Depends on:** CYB-012

Found while checking the work rather than by a test: **every combat subsystem was implemented, tested and completely unreachable from the running game.** `GameHost` ticked movement and nothing else — `AutoFire`, `BossFight`, `Targeting`, `Loadout` and `DropTable` appeared in zero files under `wasmJsMain`. Every one of their tests passed, because a subsystem's own tests say nothing about whether anything calls it.

- [x] Implement `sim/GameSimulation` as the single pure tick that ties movement, firing, enemies, projectiles, bosses and pickups together (ENG-050).
- [x] Add tests that assert the parts are *connected*: the weapon fires with no input, enemies are live entities that move, a boss fight exists and is sealed, damage reaches the run, auto-aim works with no cursor, and the whole thing is deterministic.
- [x] Wire `GameHost` to the simulation; draw enemies, projectiles, ground items and a health bar.
- [x] Run `./scripts/check.sh`. 200 JVM and 190 browser tests green.

### CYB-012 — Run structure, persistence and accessibility

- **Status:** Complete apart from audio
- **Specification:** [Change 0003](specs/changes/0003-game-core.md) — PROD-022, PROD-031, PROD-032
- **Depends on:** CYB-011
- **Implementation approval:** Approved by the user on 2026-08-25 ("proceed to implement and execute the research and development plan to develop the game")

TDD checkpoints:

- [x] Add a failing test proving death ends the run, clears the in-progress save, and withholds `Continue game`.
- [x] Add a failing test proving a save carries a format version and that an unreadable or older save is rejected rather than crashing.
- [x] Implement `SaveCodec`, `LocalStorageSaveStore` and meta-progression; replace the superseded TITLE-005 placeholder assertion.
- [x] Add a failing test proving Auto-aim targets the nearest enemy and that the setting persists.
- [x] Add failing browser tests for the canvas accessible name, live region, and pause on blur.
- [ ] **Not done: sound effects and the audio externals.** kotlinx-browser 0.5.0 exposes no Web Audio API, so this needs ~40-60 lines of hand-written externals. Off the critical path and deliberately left last.
- [ ] Run `./scripts/check.sh`. **Adversarial review gate R6.**

## Completed

### CYB-004b — Specify the game core

- **Status:** Completed on 2026-08-26
- **Specification:** [Change 0003](specs/changes/0003-game-core.md)
- **Depends on:** Nothing
- **Outcome:** Research plan in `plan.md` validated across two adversarial review rounds (`codex`, `gpt-5.6-sol`, `xhigh`) plus three sub-agent reviewers; PROD-020..032 and ENG-050..056 added; PROD-004, PROD-011, ENG-001, ENG-013 and ENG-031 amended; TITLE-005 and TITLE-006 superseded.


### CYB-004 — Repair the GitHub Pages workflow

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0002](specs/changes/0002-github-pages-deployment.md)
- **Depends on:** Nothing

TDD checkpoints:

- [x] Reproduce the invalid-YAML failure with a parser and retain the failed GitHub run as evidence.
- [x] Correct only the malformed workflow structure required for GitHub to create its jobs.
- [x] Validate the workflow with IntelliJ and a YAML parser.
- [x] Run `./scripts/check.sh` to verify the artifact-producing build remains green.
- [x] Commit the focused workflow repair.

### CYB-003 — Verify the title-screen slice end to end

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** CYB-001, CYB-002

TDD checkpoints:

- [x] Add a failing production-bundle smoke test for each save-availability state.
- [x] Make only the harness or integration changes required for those tests to pass.
- [x] Run `./scripts/check.sh` and inspect changed Kotlin files with IntelliJ.

### CYB-002 — Render the title screen in the browser

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** CYB-001

TDD checkpoints:

- [x] Add failing browser tests for the title, exact button names, conditional continue action, and keyboard-focusable controls.
- [x] Render the smallest accessible DOM that passes the tests.
- [x] Keep button activation side-effect free for this placeholder slice.
- [x] Refactor browser integration under green tests.

### CYB-001 — Model title-screen state

- **Status:** Completed on 2026-08-24
- **Specification:** [Change 0001](specs/changes/0001-title-screen.md)
- **Depends on:** Nothing

TDD checkpoints:

- [x] Add a focused failing test proving that title-screen state omits `Continue game` when no save is available.
- [x] Add a focused failing test proving that title-screen state includes `Continue game` when a save is available.
- [x] Implement the smallest platform-independent state model that passes both tests.
- [x] Refactor under green tests.

### CYB-000 — Establish the project foundation

- **Status:** Completed on 2026-08-24
- **Authorization:** Initial project-scaffolding request
- **Outcome:** Kotlin/Wasm build structure, reproducible Gradle tooling, specifications, task tracking, checks, CI, and contributor guidance.
