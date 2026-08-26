# Change 0004: Keyboard-only controls and arena legibility

- **Status:** Implemented on 2026-08-26
- **Implementation approval:** Requested by the user on 2026-08-26
- **Created:** 2026-08-26

## Intent

Two playtest reports and one simplification. Aiming becomes automatic and the game becomes
keyboard-only; the things the player has to fight become visible; and defeating the boss reliably
opens the way out.

## Requirements amended

- **PROD-021** required the weapon to aim at the player's cursor. It now aims at the nearest valid
  target, and the control scheme is the four arrow keys and nothing else.
- **PROD-022** required an opt-in Auto-aim setting. That requirement existed only because aiming was
  cursor-directed; with aiming automatic there is nothing to opt into, so it now requires the
  opposite — that aiming take no input and no configuration.
- **PROD-004** was narrowed by change 0003 to non-gameplay controls, because a cursor-aimed game
  could not be keyboard-operable. That narrowing is **withdrawn**: the game now needs no pointing
  device at all, which is a stronger position than the original requirement asked for.

## Requirements added

- **PROD-033** a melee attack must be visible when it resolves.
- **PROD-034** bosses must be drawn while alive, with their remaining health.
- **PROD-035** defeating the main boss must leave nothing obstructing the way out.

## Why PROD-034 and PROD-035 exist

A playtester reached the boss arena having killed every enemy on the map, found a wall, and could
not finish. The wall was the exit gate behind which the boss stood — working exactly as designed.
Nothing was wrong with the gate. What was wrong is that **the boss was drawn nowhere at all**: the
renderer had no code to draw one, so the arena presented as a dead end rather than as a fight.

PROD-035 is deliberately blunt rather than precise. The gate was already opening correctly on the
boss's death; the requirement now says that *nothing* may obstruct the exit afterwards, whatever put
it there, because a player who has beaten the boss has finished the map and any remaining wall is a
soft-lock however it arose.

## Acceptance examples

1. Given a running game, no pointing device is needed at any point, and moving the mouse changes
   nothing.
2. Given a melee weapon, each swing is visible at the moment it resolves, oriented the way it struck.
3. Given a live boss, it is drawn with its remaining health; given a defeated one, it is not.
4. Given the main boss is defeated, every tile between the arena and the map's right-hand edge that
   could obstruct the player is cleared.
5. Given a save written before this change, it is refused rather than partially applied, and
   `Continue game` is not offered for it.

## Out of scope

Art, animation and audio. The visual indications here are placeholder shapes, as change 0003 records.
