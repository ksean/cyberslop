# Simulation and Movement

The simulation is `sim.GameSimulation.tick(input, dt)`: a pure function of the previous world state
and one input frame (ENG-050). The movement model inside it is the single source of truth for player
motion and is shared, unchanged, by the game loop and by map verification (ENG-051, ENG-052).

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
  `decor`, `enemy`, `loot`, `backdrop`, the run-wide `boss-roster`, and per-encounter boss attack
  choice), so a change in one phase cannot shift another's output. Profile assignment is replayed
  from the run seed on continue; it never consumes a mutable combat or loot stream.
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
  effect, fire effect, enemy contact, hazard or platform displaces the player.
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
- **P-40** Simulation determinism: a digest of the whole rule-bearing simulation state after N
  ticks of a fixed tape on a fixed seed matches a committed golden value on both targets
  (enemies.md lists the fields). Enemy/boss leap state, selected boss profiles, scheduled multi-hit
  events and boss-projectile ownership are rule-bearing and included. Floating Scrap labels and
  other presentation-only fields are excluded.
