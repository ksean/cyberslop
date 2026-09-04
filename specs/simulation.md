# Simulation and Movement

The simulation is `sim.GameSimulation.tick(input, viewport)`: a pure function of the previous world
state, one input frame and one immutable gameplay viewport (ENG-050). The movement model
inside it is the single source of truth for player motion and is shared, unchanged, by the game
loop and by map verification (ENG-051, ENG-052).

## Gameplay viewport input

`sim.GameplayViewport` is the world-space rectangle initialized from the level-entry camera and
then last composed for the player. The browser converts the current platform-independent `Camera`
to this value before each fixed tick; multiple catch-up ticks before another frame therefore
consume the same visible rectangle. Canvas size, camera following and interpolation remain
presentation concerns, while the numeric rectangle is an explicit rule input used only for
PROD-101's player-ranged boundary and PROD-116's player-ranged target acquisition. It contains no
browser or DOM reference and is not stored in the simulation digest or canonical save. A
deterministic replay must supply the same viewport tape as well as the same control-input tape.

## Movement model

`physics.MovementModel.step(state, input, world)` integrates one fixed tick of `1/60 s`. Collision
is swept AABB with axis separation, so there is no tunnelling at terminal velocity. All physics
state is `Double`.

| Constant | Value | Constant | Value |
|---|---|---|---|
| gravity | 2400 px/s² | jump impulse | 680 px/s |
| jump-release clamp | 160 px/s | ground acceleration | 1600 px/s² |
| ground friction | 2400 px/s² | air acceleration | 1200 px/s² |
| max run speed | 240 px/s | terminal velocity | 1000 px/s |
| crouch speed factor | 0.5 | tile | 16 px |
| AABB standing | 12 × 26 px | AABB crouched | 12 × 14 px |
| coyote time | 0.10 s | jump buffer | 0.12 s |

Derived quantities are read from the integrator, never from the closed forms: a fixed step applies a
whole tick of gravity, so the discrete apex is 90.67 px (5.67 tiles) against a continuous 96.33 px.
Stopping distance is 10 px discrete; runway to full speed is 9 ticks / 20 px.

The death-drop rail is a consumer of this movement contract, not a new movement rule. Its fixed
two-tile rise is a design distance; P-64 runs the normal jump through this integrator to prove the
drop remains reachable and runs grounded poses to prove contact still requires that jump. A physics
change that breaks either side must deliberately revise the drop height or the physics rather than
trust a closed-form apex.

**Crouch semantics.** A crouched player cannot jump. Standing up requires two tiles of clearance.

## Assists sit above the integrator

Coyote time and jump buffering live in `physics.IntentFilter`, which converts raw key state into an
`InputFrame`. `MovementModel.step` consumes only `InputFrame` and contains no assist logic. A
witness is a tape of `InputFrame`s, so replay is exact and assist-independent; assists can only add
capability for a human pressing keys, never change what a recorded tape means.

## Key state reaches the filter through a ledger

Browser key events arrive between animation frames while the simulation samples once per fixed
tick, so the two are joined by `physics.KeyLedger` (platform-independent, fed by
`input/BrowserInput`). It holds the keys currently down and **latches every press until a sample
consumes it**: a key pressed and released between two samples is reported as held for exactly one
sample, so a tap can never be shorter than the simulation's ability to see it. A frame stall only
delays the press; it cannot erase it.

- Gameplay bindings are identified by physical position (`KeyboardEvent.code`) first: arrows or
  `KeyA`/`KeyD`/`KeyS`/`KeyW`, with `Space` as a third jump binding. A missing or unrecognised code
  falls back to the arrow value, case-insensitive `a`/`d`/`s`/`w`, `" "` or legacy `Spacebar`, so
  keypad arrows and synthetic assistive events work too. Every recognised gameplay `keydown`
  prevents the browser's scrolling behaviour.
- Bindings are sources for one of the four canonical actions, not keys that compete for its one
  slot. If two aliases for an action are down together — `ArrowLeft` and `A`, for example —
  releasing either one leaves the action held until the other is released. A press edge from
  either alias is still latched exactly once by the ledger.
- Held keys are released wholesale whenever a `keyup` might have been lost: on window blur, on the
  page becoming hidden or being put away, and when the canvas loses focus. Window blur and a
  hidden page also pause the loop; canvas focus loss does not.
- A jump held while the player cannot stand — because Down is held or because a ceiling forces the
  crouch — stays **pending** and starts on the first tick the player can stand. A crouched player
  still never jumps; the press is simply not thrown away at the filter/model boundary.
- Catch-up ticks in one animation frame (at most eight, ≈133 ms) all see the same key state. This is
  bounded and accepted.
- `IntentFilter` is scoped to one simulation: it is recreated with each map and each run.

`Escape` is a lifecycle control, not a fifth `KeyLedger` action (PROD-091). While a run is playing,
the first non-repeated `keydown` whose code or value is `Escape` toggles the manual pause and
prevents browser handling; key repeat cannot immediately undo the transition. Opening and closing
the pause both release every gameplay source, discard latched presses and recreate the
`IntentFilter`, so movement held across the menu cannot resume by itself. The fixed-step loop is
paused when any of window/page pause, manual pause or discovery pause is active. Manual pause
persists across blur/focus and visibility changes; regaining focus never closes its menu. While it
is active no simulation tick, discovery interval, hover, status indicator, exit sparkle, hurt
flash, Scrap label or other simulation-time presentation timer advances.

## Death sequence

The first damage event that changes player health from a positive value to zero starts one
terminal `DeathSequence` with age zero (PROD-103). Its semantic cause is captured from the damage
source, not inferred later from nearby actors, projectiles or tiles:

| Terminal source | Cause effect |
|---|---|
| `Acid` tile — the poison pit | poison bubbles |
| active fire jet, burning-barrel body or flame, boss `Laser` beam, or a future source explicitly typed as fire or laser | flame |
| `Spikes` or `BrokenGlass` tile, any hostile projectile (`Bolt`, `Burst` or `Scatter` included), or any enemy or boss melee attack | bleed |
| `Void`, enemy or boss body contact, or an otherwise unclassified future source | none |

Distinct damage sources resolve in the simulation's deterministic event order. The first one to
make health zero owns the cause permanently; another contact later in that tick cannot replace it.
Death also wins over a map-clear transition on the same tick. A first discovery collected on that
tick remains resolved and persisted, but its card is suppressed as PROD-083 requires.

Starting with the next fixed tick, gameplay input is ignored and every gameplay mutation is
frozen: movement, automatic fire, attacks, projectiles, enemies, bosses, hazards, pickups, map
completion, RNG streams, cooldowns and statuses do not advance. Only the terminal age and purely
presentational animation advance. `Escape` cannot open the pause menu or skip the sequence. As
with the ordinary loop, window blur or a hidden page suspends fixed ticks, so the interval is four
seconds of active foreground time rather than elapsed wall time.

The collapse progress is `clamp(age / 2.0 s, 0, 1)`. The sequence becomes complete at exactly
`age = 4.0 s`: after 120 death-only ticks the player is fully prone but the canvas remains, and
only after 240 such ticks may the browser replace it with the `You died` screen. Cause and
terminal age are common, deterministic values; the collapse and cause effects remain
presentation-only and cannot change health or any other rule.

## The measured envelope

`physics.MovementEnvelope.measure(physics)` runs the real integrator against synthetic geometry and
answers the questions generation asks — "can the player cross a gap this wide", "can the player
climb a step this tall" — at every plausible take-off tick. Generation consumes only the scaled
result (ENG-055):

- `gapMaxTiles(drop) = floor(0.70 × measured crossable gap at that drop)`
- `stepUpMaxTiles = floor(0.80 × measured climbable step)`
- runway and stopping distance are outputs of the measurement.

At the default constants the player crosses a level gap of 8 tiles and climbs a step of 4, giving
`gapMaxTiles(0) = 5` and `stepUpMaxTiles = 3`.

## Determinism across targets

Kotlin/Wasm and the JVM agree bit-for-bit on IEEE-754 `+ − × ÷ √`; they make no such promise for
`sin`, `cos` or `pow`. Therefore (ENG-053, ENG-054):

- Randomness is a first-party SplitMix64 over `ULong` with per-phase derived streams (`spine`,
  `decor`, `enemy`, `loot`, `ramen`, `backdrop`, the run-wide `boss-roster`, and per-encounter boss
  attack choice and melee-charge selection), so a change in one phase cannot shift another's
  output. The map-scoped `ramen` stream consumes exactly one draw per resolved rank-and-file death
  and is isolated from weapon/powerup loot and combat rolls (PROD-110).
  Profile assignment is replayed from the run seed on continue; it never consumes a mutable combat
  or loot stream.
- Everything reachable from the tick uses basic arithmetic and comparisons; transcendentals go
  through `core.TrigTable`; exponential growth is repeated multiplication.
- Non-finite values never enter hashed state.
- `wasm-opt` is version-pinned so development and CI optimise with the same binary.

## Verified properties

- **P-03** Same seed → byte-identical tile map; a decoration-only change leaves the spine and both
  masks byte-identical.
- **P-09** Every scaled envelope bound sits at least 5 % clear of its floor boundary, so a small
  tuning cannot silently drop a whole move kind. Changing gravity changes generator output.
- **P-16** Cooldown fidelity: over 60 s of simulation each weapon fires within one activation of
  `60 / cooldown`, counted from its wind-up; persistent-ring weapons are excluded.
- **P-19** Player physics determinism: a fixed `PlayerState` and input tape run through
  `MovementModel` for N ticks hash to a committed golden value on both targets. This covers the
  movement model only. Player position and velocity are written only by the movement model: no hit
  effect, fire effect, enemy or boss contact, hazard or platform displaces the player.
- **P-48** Press latching: a key pressed and released between two `KeyLedger` samples is held in
  exactly one sample; a key still down is held in every sample; `releaseAll` empties the ledger.
- **P-49** Pending jump: a jump held while standing is blocked, or while Down is held, produces no
  `jumpStart` and then produces exactly one on the first tick the player can stand.
- **P-50** Input wiring: in a browser, `keydown` marks a key held, `keyup` releases it, and canvas
  blur, `pagehide` and window blur release every key.
- **P-54** Alternate bindings: arrows map to left/right/crouch/jump; physical A/D/S/W map to the
  same four actions; Space maps to jump; the case-insensitive and space key-value fallbacks do the
  same; each `keydown` prevents default browser handling. Holding two bindings for one action and
  releasing one keeps that action held, while releasing both clears it; focus-loss clearing
  removes every alias.
- **P-65** Manual pause: one physical `Escape` press during play opens the pause menu, freezes the
  fixed-step tick count and simulation-time presentation, clears held and latched gameplay input,
  and prevents default; repeated keydown does not close it. A later `Escape` or `Resume` closes it
  and the first resumed tick contains no pre-pause input. Blur/focus while manually paused leaves
  it paused. `Return to title` banks the live run Scrap once, clears the run save and reaches a
  title with no `Continue game` for that run; opening, closing or focus-pausing banks nothing.
- **P-40** Simulation determinism: a digest of the whole rule-bearing simulation state after N
  ticks of fixed input and viewport tapes on a fixed seed matches a committed golden value on both
  targets
  (enemies.md lists the fields). Enemy/boss leap state, selected boss profiles, scheduled multi-hit
  events, the current boss melee-charge selection, charge-stream state and already-consumed charged
  opportunities, boss-projectile ownership, deterministic death-drop positions and the player's
  active `ArcSwing` are rule-bearing and included. An active death sequence's terminal phase and
  elapsed fixed ticks are included because they govern the end-screen transition; its cause and
  pose/effect geometry are presentation-only. A live projectile's gravity and already-hit target
  identities, and a pending lobbed burst's snapshotted aim point, are likewise included. Floating
  Scrap labels, enemy swing/flash visuals, the player's heal-flash timer and other presentation-only
  fields are excluded. A grounded ramen item's payload and position and the `ramen` stream state
  are included because they can change future health and drops.
