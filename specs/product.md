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
- **PROD-028:** At least fifteen distinct powerups must be available, including attack speed, damage, target seeking and target slowing. At most five distinct powerups may be active at once, and each may stack up to three times with increasing but never super-linear strength. A powerup arriving at a full build must displace the slot whose loss costs the least, measured against the weapon the build is feeding, whenever that swap raises the build's weapon score without lowering its damage; what it displaces converts to Scrap. An award the run guarantees — a boss or mini-boss drop, or map one's starter cache — must never be refused, and displaces whichever slot costs least damage to lose. *(The two measures named are the ones the game can compare. Lifesteal, seeking, slowing, reach, knockback, stun and kill refunds are carried by real powerups and are in neither, so a swap can give one up; closing that means extending the weapon score, which is a balance change and is `plan.md` §12 question 8.)* *(Amended by [change 0005](changes/0005-visual-identity-and-loot-density.md): collecting must never be able to make a player worse off. Contact cannot be declined (PROD-030), so at PROD-046's rate a build fills with whatever the route hands over, and a guaranteed award arriving afterwards was being thrown away — which put a real player below the loadout the guaranteed-loot floor bounds them to.)*
- **PROD-029:** Stronger weapons and powerups must be rarer. Drop weight must be strictly decreasing in rarity tier at every map index.
- **PROD-030:** Weapons and powerups must resolve on contact when the player walks over them, with no additional input. Contact must always resolve: a pickup that is not taken must convert to Scrap.
- **PROD-031:** Death must end the run and return the player to the first map with the starting weapon. Scrap earned during a run must persist and expand the pool of weapons and powerups available to later runs.
- **PROD-032:** `Continue game` must resume an in-progress run only. It must never resume a run that has ended, and must not be offered when no valid saved run exists.
- **PROD-046:** One slain rank-and-file enemy in five must drop something, at every map index. Three in ten of those drops must be a weapon and seven in ten a powerup. Mini-bosses and main bosses are not covered by this rate: each awards loot on every death, which [change 0003](changes/0003-game-core.md) requires and which the guaranteed-loot floor is computed from. *(Added by [change 0005](changes/0005-visual-identity-and-loot-density.md). Supersedes the 1.5%-to-3% ramp recorded in `plan.md` §6.7 and the 3%-to-6% ramp that was implemented; the two disagreed with each other.)*
  > **This narrows the request it came from, and the owner confirmed it on 2026-08-27.** The instruction was "1 in 5 enemies slain", with no exception. Applying it to bosses would put every guaranteed award behind a four-in-five chance of nothing, which the loot floor is computed from and `plan.md` §6.7 has never done. Both readings were put to the owner with that consequence stated, and rank-and-file was chosen.
- **PROD-047:** Each map must carry statically placed pickups, averaging two per map across seeds. Each must stand on a cell the map's own verified witness stood on, outside both arenas and outside any span the player crosses committed. The average counts only these pickups: map one's starter cache is a separate guaranteed award required by [change 0003](changes/0003-game-core.md) so that a mini-boss is never met with the broken bottle, and map one therefore holds one more pre-placed pickup than the others. Each carries the same weapon-to-powerup split as a kill drop, and rolls its rarity twice keeping the better result, so a cache is worth crossing a map for. No two may stand within twelve tiles of each other where the map offers any legal placement that far apart, so two pickups read as two finds rather than one pile. *(Added by [change 0005](changes/0005-visual-identity-and-loot-density.md).)*

## Presentation

*Added by [change 0005](changes/0005-visual-identity-and-loot-density.md).*

- **PROD-040:** The game must present a coherent cyberpunk-dystopian visual identity in a 2D art style. Each of the ten sub-themes must have its own palette and its own backdrop, so that a player can tell two sub-themes apart without reading the map name.
- **PROD-041:** The player character must be animated, and its animation must visibly distinguish standing, moving sideways, rising in a jump, falling from a jump, crouching, moving while crouched, firing a ranged weapon, and swinging a melee weapon. Weapon animation must compose over movement animation rather than replace it, because the weapon fires on its own cooldown and the player never stops to use it.
- **PROD-042:** The five enemy archetypes must be distinguishable from one another by silhouette, not by colour alone, and that identity must hold across the whole run, so a Swarm is recognisably a Swarm on every map. An enemy must read as tougher the more health it carries:
  - Across the whole archetype and map-index grid, the number of armour plates and protrusions it is drawn with must be non-decreasing in its health.
  - Within any one map, the size it is drawn at and the luminance it is drawn with must both be non-decreasing in its health as well.

  *(Size and luminance are scoped to one map because that is the only place a player can compare two enemies, and because a global ordering is not jointly satisfiable with the first clause. Measured: making drawn size monotone across the whole grid requires the five archetypes' heights to fall within 1.01x of each other — every enemy the same size — against the 1.78x spread that makes a Brute the broadest thing on a map. Luminance is additionally per-map because each sub-theme has its own palette (PROD-040). Both clauses were first written as whole-grid claims, and both were false: a map-1 Turret is drawn duller than a lighter map-2 Shooter, and a map-4 Swarm is drawn at 14.2 against a weaker map-1 Brute's 24.3.)*
- **PROD-043:** A boss must be drawn distinctly from a trash enemy, and a mini-boss from a main boss. *(Extends PROD-034, which requires only that a boss be drawn at all.)*
- **PROD-044:** A pickup lying on the ground must show whether it is a weapon or a powerup, and must show its rarity tier. *(Amended by [change 0006](changes/0006-weapon-and-pickup-iconography.md): showing the kind is no longer sufficient. A pickup must be drawn as the specific item it is, per PROD-049, so that a player can tell one weapon from another before crossing a map to reach it — contact is irrevocable under PROD-030, so the decision to walk into a pickup is the only decision the player gets.)*
- **PROD-045:** The heads-up display must show remaining health, the equipped weapon, the powerups held and how many times each is stacked, and the current map index and sub-theme.
- **PROD-048:** The title screen and the run-ended screens must share the in-game visual identity.

## Item identity

*Added by [change 0006](changes/0006-weapon-and-pickup-iconography.md).*

- **PROD-049:** Every weapon and every powerup must have its own icon, recognisable as the object it names — a broken bottle as a bottle, a shotgun as a shotgun. No two items may share an icon. An item's icon must be the same wherever the game draws that item: lying on the ground, held by the player, and beside its name in the heads-up display.
- **PROD-050:** A weapon's icon must be outlined in red and a powerup's in blue, in fixed colours that do not vary with the sub-theme. Kind must additionally be readable with colour removed: a powerup's icon must be drawn inside a module casing and a weapon's must not.
  > **Colour alone is not enough here, and that is a measurement rather than a caution.** Two of the ten palettes carry an `accent` within an RGB distance of 13 of these outlines — Reactor Core's `#ff3b30` against the red, Server Stacks' `#3b82f6` against the blue — and `accent` is the colour of projectiles, fire-jet plumes, the exit marker and the player's own trim. On those two maps a red-outlined drop is the same colour as four other things on screen. No choice of red and blue avoids it, because the ten accents span the colour wheel by design (PROD-040).
- **PROD-051:** An item's icon must remain legible on every sub-theme. As drawn, it must be separated in Rec. 709 luminance from that palette's sky, backdrop and tile colours by at least 40 of a 255 range, and it must not be drawn in a colour the same frame uses for a hazard or a projectile.
  > **The separation is required of the drawn result, not of the outline colour, and that distinction is load-bearing.** Measured over all ten palettes and all nine of their background colours: the outline alone is worth **2.0** at its worst — a red at luma 91.2 against `ArcologyVault.tileBody` at 89.3 — and a near-black halo alone is worth **0.1**, against Reactor Core's sky. Drawn as a pair, so that at least one of the two lines separates from whatever is behind it, the worst case is **45.8**. No single colour satisfies this requirement on ten palettes; a pair does. See [`plan.md` §16.3](../plan.md#163-red-blue-and-the-two-themes-that-fight-them).
