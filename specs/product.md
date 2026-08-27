# Product Specification

## Vision

Cyberslop is a dystopian side-scrolling adventure game. Its world, narrative, and mechanics will be specified incrementally.

## Runtime

- **PROD-001:** A player must need only a modern web browser to play a deployed build.
- **PROD-002:** The game must execute client-side as a Kotlin/WebAssembly application; a player must not need to install a native application, runtime, or browser extension.
- **PROD-003:** Supported browsers must provide WebAssembly garbage collection. The initial compatibility baseline is Chrome or Chromium 119+, Firefox 120+, and Safari 18.2+.
- **PROD-004:** Player-facing controls must be operable with a keyboard alone and expose accessible names to browser assistive technology. *(Restored to its original meaning by [change 0004](changes/0004-keyboard-only-controls.md): aiming is now automatic, so the game requires no pointing device and the narrowing that change 0003 introduced is withdrawn.)*

## Initial experience

- **PROD-010:** Opening the game URL must present the title-screen behavior defined by [change 0001](changes/0001-title-screen.md).
- **PROD-011:** Starting and continuing gameplay are specified by [change 0003](changes/0003-game-core.md). *(Superseded; the placeholder obligation is discharged.)*

## Gameplay

- **PROD-020:** A run must consist of ten procedurally generated maps of increasing difficulty. The player must progress by moving right; each map must contain a mini-boss at its midpoint and a main boss at its end, and the main boss must gate the map exit.
- **PROD-021:** The only controls must be left, right, crouch and jump, bound to the arrow keys. The equipped weapon must fire automatically on its own cooldown, aimed at the nearest valid target. There must be no attack input and no pointing device. *(Amended by [change 0004](changes/0004-keyboard-only-controls.md).)*
- **PROD-022:** Aiming must require no player input and no configuration. *(Amended by [change 0004](changes/0004-keyboard-only-controls.md): this previously required an opt-in setting, which existed only because aiming was cursor-directed.)*
- **PROD-033:** A melee attack must be visibly indicated at the moment it resolves, showing the direction and extent of the swing.
- **PROD-034:** A boss and a mini-boss must be visibly rendered while alive, showing remaining health, so a player can tell what an arena requires of them.
- **PROD-035:** Once the main boss is defeated, nothing may obstruct the player's path from the arena to the end of the map.
- **PROD-023:** A run must begin with a broken bottle melee weapon that swings every two seconds.
- **PROD-024:** Every map the game presents must be completable: the generator must hold a witness — an input sequence which, replayed through the game's own movement model, transits the mini-boss arena and reaches the boss arena without contacting a lethal hazard. A map without a verified witness must not be shown.
- **PROD-025:** Each of the ten maps must have a distinct sub-theme consistent with the cyberpunk-dystopian setting, and must vary in geometry, hazard mix and enemy population rather than in decoration alone.
- **PROD-026:** Maps must include platform traversal, acid pits and timed fire jets. A fire-jet crossing must always be survivable from a proven safe standing position.
- **PROD-027:** At least twenty distinct weapons must be available across melee, ranged and psychic classes, spanning a range of power, with the three classes mechanically distinct.
- **PROD-028:** At least fifteen distinct powerups must be available, including attack speed, damage, target seeking and target slowing. At most five distinct powerups may be active at once, and each may stack up to three times with increasing but never super-linear strength.
- **PROD-029:** Stronger weapons and powerups must be rarer. Drop weight must be strictly decreasing in rarity tier at every map index.
- **PROD-030:** Weapons and powerups must resolve on contact when the player walks over them, with no additional input. Contact must always resolve: a pickup that is not taken must convert to Scrap.
- **PROD-031:** Death must end the run and return the player to the first map with the starting weapon. Scrap earned during a run must persist and expand the pool of weapons and powerups available to later runs.
- **PROD-032:** `Continue game` must resume an in-progress run only. It must never resume a run that has ended, and must not be offered when no valid saved run exists.
- **PROD-046:** One slain rank-and-file enemy in five must drop something, at every map index. Three in ten of those drops must be a weapon and seven in ten a powerup. Mini-bosses and main bosses are not covered by this rate: each awards loot on every death, which [change 0003](changes/0003-game-core.md) requires and which the guaranteed-loot floor is computed from. *(Added by [change 0005](changes/0005-visual-identity-and-loot-density.md). Supersedes the 1.5%-to-3% ramp recorded in `plan.md` §6.7 and the 3%-to-6% ramp that was implemented; the two disagreed with each other.)*
  > **This narrows the request it came from, and that is the owner's call to confirm.** The instruction was "1 in 5 enemies slain", with no exception. Applying it to bosses would put every guaranteed award behind a four-in-five chance of nothing, which the loot floor is computed from and `plan.md` §6.7 has never done. Both readings are defensible — "enemies" as everything you kill, or as the rank and file you fight on the way — and the second is implemented because the first breaks a proven property. Recorded here rather than settled quietly.
- **PROD-047:** Each map must carry statically placed pickups, averaging two per map across seeds. Each must stand on a cell the map's own verified witness stood on, outside both arenas and outside any span the player crosses committed. The average counts only these pickups: map one's starter cache is a separate guaranteed award required by [change 0003](changes/0003-game-core.md) so that a mini-boss is never met with the broken bottle, and map one therefore holds one more pre-placed pickup than the others. Each carries the same weapon-to-powerup split as a kill drop, and rolls its rarity twice keeping the better result, so a cache is worth crossing a map for. *(Added by [change 0005](changes/0005-visual-identity-and-loot-density.md).)*

## Presentation

*Added by [change 0005](changes/0005-visual-identity-and-loot-density.md).*

- **PROD-040:** The game must present a coherent cyberpunk-dystopian visual identity in a 2D art style. Each of the ten sub-themes must have its own palette and its own backdrop, so that a player can tell two sub-themes apart without reading the map name.
- **PROD-041:** The player character must be animated, and its animation must visibly distinguish standing, moving sideways, rising in a jump, falling from a jump, crouching, moving while crouched, firing a ranged weapon, and swinging a melee weapon. Weapon animation must compose over movement animation rather than replace it, because the weapon fires on its own cooldown and the player never stops to use it.
- **PROD-042:** The five enemy archetypes must be distinguishable from one another by silhouette, not by colour alone, and that identity must hold across the whole run, so a Swarm is recognisably a Swarm on every map. An enemy must read as tougher the more health it carries:
  - Across the whole archetype and map-index grid, the number of armour plates and protrusions it is drawn with must be non-decreasing in its health.
  - Within any one map, the size it is drawn at and the luminance it is drawn with must both be non-decreasing in its health as well.

  *(Size and luminance are scoped to one map because that is the only place a player can compare two enemies, and because a global ordering is not jointly satisfiable with the first clause. Measured: making drawn size monotone across the whole grid requires the five archetypes' heights to fall within 1.01x of each other — every enemy the same size — against the 1.78x spread that makes a Brute the broadest thing on a map. Luminance is additionally per-map because each sub-theme has its own palette (PROD-040). Both clauses were first written as whole-grid claims, and both were false: a map-1 Turret is drawn duller than a lighter map-2 Shooter, and a map-4 Swarm is drawn at 14.2 against a weaker map-1 Brute's 24.3.)*
- **PROD-043:** A boss must be drawn distinctly from a trash enemy, and a mini-boss from a main boss. *(Extends PROD-034, which requires only that a boss be drawn at all.)*
- **PROD-044:** A pickup lying on the ground must show whether it is a weapon or a powerup, and must show its rarity tier.
- **PROD-045:** The heads-up display must show remaining health, the equipped weapon, the powerups held and how many times each is stacked, and the current map index and sub-theme.
- **PROD-048:** The title screen and the run-ended screens must share the in-game visual identity.
