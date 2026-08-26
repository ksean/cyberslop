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
