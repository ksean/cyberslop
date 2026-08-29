# Product Specification

## Vision

Cyberslop is a cyberpunk-dystopian side-scrolling roguelite for the browser. The player crosses ten
procedurally generated maps of rising difficulty, left to right, fighting a mini-boss at each
midpoint and a main boss at each end. Four keys, one weapon that fires by itself, a build of
powerups collected by walking over them, and permadeath with persistent unlocks.

## Runtime

- **PROD-001:** A player must need only a modern web browser to play a deployed build.
- **PROD-002:** The game must run client-side as a Kotlin/WebAssembly application with no native
  install, runtime or extension.
- **PROD-003:** Supported browsers must provide WebAssembly garbage collection: Chrome/Chromium
  119+, Firefox 120+, Safari 18.2+.
- **PROD-004:** Every player-facing control must be operable with a keyboard alone and expose an
  accessible name to assistive technology. The game requires no pointing device.

## Title screen and run lifecycle

- **PROD-010:** Opening the game URL must show the title `Cyberslop` and a button named `New game`.
  A button named `Continue game` must be present exactly when a valid in-progress save exists.
- **PROD-031:** Death must end the run and return the player to the first map with the starting
  weapon. Scrap earned during a run must persist and expand the pool of weapons and powerups
  available to later runs.
- **PROD-032:** `Continue game` must resume an in-progress run only — never a run that has ended,
  and never a save the current build cannot read. Saves carry a format version and are refused
  rather than partially applied.
- **PROD-048:** The title screen and the run-ended screens must share the in-game visual identity.

## Controls and gameplay

- **PROD-020:** A run must consist of ten procedurally generated maps of increasing difficulty. The
  player progresses by moving right; each map contains a mini-boss at its midpoint and a main boss
  at its end, and the main boss gates the map exit.
- **PROD-021:** The only controls must be left, right, crouch and jump on the arrow keys. The
  equipped weapon fires automatically on its own cooldown at the nearest valid target. There is no
  attack input.
- **PROD-022:** Aiming must require no player input and no configuration.
- **PROD-023:** A run must begin with a broken bottle melee weapon that swings every two seconds.
- **PROD-024:** Every map presented must be completable: the generator holds a witness — an input
  sequence which, replayed through the game's own movement model, transits the mini-boss arena and
  reaches the boss arena without contacting a lethal hazard. A map without a verified witness must
  not be shown. See [completability.md](completability.md).
- **PROD-025:** Each map must have a distinct sub-theme consistent with the setting and must vary in
  geometry, hazard mix and enemy population rather than in decoration alone.
- **PROD-026:** Maps must include platform traversal, acid pits and timed fire jets. A fire-jet
  crossing must always be survivable from a proven safe standing position.
- **PROD-033:** A melee attack must be visibly indicated at the moment it resolves, showing the
  direction and extent of the swing.
- **PROD-034:** A boss and a mini-boss must be visibly rendered while alive, showing remaining
  health.
- **PROD-035:** Once the main boss is defeated, nothing may obstruct the player's path from the
  arena to the end of the map.
- **PROD-036:** The main boss's exit gate must stand while the boss lives and open on its death.
  Nothing the player or their automatic weapon does may seal the player in; a boss that is not yet
  engaged must be invulnerable and inert.

## Enemies and hazards

- **PROD-060:** An enemy within engagement range of the player must act on the player rather than
  patrol: a melee enemy pursues, a ranged enemy holds range and shoots. An engaged enemy is not
  confined to its patrol zone.
- **PROD-061:** A melee enemy must close on the player and attack with a telegraphed swing. A
  ranged enemy must keep its distance when the player closes, but no enemy may move faster than
  the player runs.
- **PROD-069:** A rank-and-file enemy's body must hurt to touch: while the player overlaps a living
  enemy, health drains per second of contact at a rate the map index scales, in addition to any
  swing or shot, never displacing the player, and never faster than the fairness rules for
  committed spans and the boss's ground allow. Bosses and mini-bosses hurt only through their
  attacks.
- **PROD-062:** A boss and a mini-boss must activate — move, attack and become vulnerable — as soon
  as the player is within its awareness radius, not on crossing a line; an engaged boss is free to
  leave its arena and pursue.
- **PROD-063:** Every enemy and boss attack must be animated: a visible wind-up before it can hurt,
  the swing or shot itself, and a muzzle flash for a shot.
- **PROD-064:** Maps must carry visible, survivable damaging hazards in the dystopian idiom — at
  least spike strips and burning barrels — that hurt per second of contact, placed off the proven
  route, at a density that rises across the run and is zero on map one.
- **PROD-068:** Difficulty pressure must rise across the run and be measured: over a seed cohort,
  the population's threat score rises strictly in cohort mean from map to map, and a reference bot
  replaying each map's route with the guaranteed loadout takes gross incoming damage per hundred
  tiles that, averaged over the early, middle and late thirds of the run, is strictly increasing —
  while that loadout still survives the route and wins the boss fight on every map the loot floor
  covers.
- **PROD-072:** A boss whose current phase holds both melee and ranged attacks must choose by
  distance: the further the player stands from it, the more often it opens with a ranged attack;
  the nearer, the more often with a melee one. The choice is made when an attack begins and holds
  for that attack; it never shortens a telegraph or removes a dodge.

## Weapons, powerups and loot

- **PROD-027:** At least twenty distinct weapons across melee, ranged and psychic classes, spanning
  a range of power, with the three classes mechanically distinct.
- **PROD-028:** At least fifteen distinct powerups including attack speed, damage, target seeking
  and target slowing. At most five distinct powerups may be active at once; each stacks up to three
  times with increasing but never super-linear strength. A powerup arriving at a full build
  displaces the slot whose loss costs least, measured against the held weapon, whenever the swap
  raises the build's weapon score without lowering its damage; what is displaced converts to Scrap.
  A guaranteed award — a boss or mini-boss drop, or map one's starter cache — is never refused and
  displaces whichever slot costs least damage to lose.
- **PROD-065:** Melee must be the high-risk, high-reward class: every melee weapon reaches at
  least two metres, further than any enemy swing, and within each rarity tier the melee weapons'
  mean damage per second exceeds the ranged weapons' (the starting bottle excluded).
- **PROD-029:** Stronger weapons and powerups must be rarer: drop weight strictly decreasing in
  rarity tier at every map index.
- **PROD-030:** Weapons and powerups resolve on contact with no additional input, and contact always
  resolves: a powerup that is not taken converts to Scrap.
- **PROD-070:** Walking over a weapon must always equip it, whatever it is and whatever is held:
  the previous weapon converts to Scrap, and every powerup held is cleared and converts to Scrap.
  A guaranteed award that pairs a weapon with a powerup applies the weapon first, so the powerup
  lands on the new weapon.
- **PROD-073:** A powerup must exist that steals life: every point of damage the held weapon
  deals to an enemy or boss — by swing, projectile, blast, chain or splash — heals the player by a
  fraction of it, capped per hit and by a budget that refills at a fixed rate per second, never
  above maximum health.
- **PROD-074:** A powerup must exist that makes the weapon's projectiles bounce: a projectile
  that would stop against the floor (or any other terrain face) instead reflects off it, up to a
  number of bounces the powerup sets, and goes on to hit what it meets.
- **PROD-075:** A machine-gun weapon must fire the rounds of one activation one after another
  along one straight line — the aim held when the trigger fell — never as a fan; a weapon whose
  activation is already a single round (the Minigun) satisfies this by its cadence, and any
  extra round a powerup adds to it leaves along the same line. Spread stays the mechanic of
  weapons that are spread weapons by nature (a shotgun, a nailgun).
- **PROD-046:** One slain rank-and-file enemy in five drops something at every map index, three in
  ten of those a weapon. Mini-bosses and main bosses award loot on every death and are outside this
  rate.
- **PROD-047:** Each map carries statically placed pickups averaging two per map across seeds,
  each on a cell the map's own verified witness stood on, outside both arenas and outside any
  committed span, no two within twelve tiles where the map allows it. Each rolls its rarity twice
  keeping the better result. Map one's starter cache is a separate guaranteed award.

## Presentation

- **PROD-040:** A coherent cyberpunk-dystopian 2D identity; each sub-theme has its own palette and
  backdrop so two sub-themes can be told apart without reading the map name.
- **PROD-041:** The player character is animated and visibly distinguishes standing, moving
  sideways, rising, falling, crouching, moving while crouched, firing a ranged weapon and swinging a
  melee weapon. Weapon animation composes over movement animation rather than replacing it.
- **PROD-066:** A melee swing must be drawn as a sweeping swoosh along the arc it covered, at the
  reach the hit test used. Every ranged or psychic activation must show a firing cue at the moment
  it happens: a muzzle flash at the barrel for a weapon that has one, an activation pulse at the
  weapon for one that does not (the Kessler dish). Player and enemies alike.
- **PROD-071:** Every ranged or psychic attack must also show where it went, not only that it
  fired: a travelling projectile is drawn as a visible body with a tracer along its motion, and an
  attack that resolves instantly draws its hit geometry — a beam onto a strike point, a chain
  through the targets struck, a ring at a blast's radius — at the moment it resolves. Player,
  enemies and bosses alike.
- **PROD-067:** A crouching character must be drawn crouching — limbs the same length as when
  standing, hips dropped, knees bent, torso forward — not as a shrunken standing figure.
- **PROD-042:** The five enemy archetypes are distinguishable by silhouette, not colour alone, on
  every map. Across the whole archetype × map grid, armour plates and protrusions are non-decreasing
  in health; within one map, drawn size and luminance are also non-decreasing in health.
- **PROD-043:** A boss is drawn distinctly from a trash enemy, and a mini-boss from a main boss.
- **PROD-076:** An enemy or boss that takes a hit must flash red briefly at the moment of the hit;
  the flash never hides a boss's telegraph colour.
- **PROD-077:** Every living enemy below full health shows a health bar above it, as bosses
  already do; an enemy at full health shows none.
- **PROD-044:** A pickup on the ground shows the specific item it is and its rarity tier.
- **PROD-045:** The HUD shows remaining health, the equipped weapon, the powerups held with their
  stack counts, and the current map index and sub-theme.
- **PROD-049:** Every weapon and powerup has its own icon, recognisable as the object it names; no
  two items share an icon; the same icon is drawn on the ground, in the player's hand and in the
  HUD.
- **PROD-050:** A drop on the ground is ringed in a fixed colour by kind — a weapon in red, a
  powerup in blue — and the ring is drawn only around a drop: never around the weapon in the
  player's hand. Kind is also readable with colour removed: a powerup's icon sits inside a module
  casing, a weapon's does not.
- **PROD-078:** Inside the ring, an item is drawn in the colours of what it is made of, in the
  game's worn dystopian register: wood is brown, metal is dull silver-grey, glass is dim, an
  energy or psychic part glows; every metal and wooden stroke wide enough to carry one bears a
  visible streak of rust or grain, and every item carries at least one such cue of wear, so the
  weapon reads as aged, on the ground, in the hand and in the HUD alike.
- **PROD-079:** A drop — a weapon or a powerup — hovers: it rises and falls a small, visible
  distance about its resting position, continuously, rather than lying still. The hover is
  presentation only: where the player must stand to pick the item up does not move.
- **PROD-080:** A projectile is drawn with a glow, a body and a bright core, and a two-tone
  tracer, in a colour that tells the player what fired it: a ranged weapon's shot is hot
  brass-orange, a psychic weapon's violet, and an enemy's shot stays in the map's hazard colour
  with its own brighter core.
- **PROD-051:** An icon stays legible on every sub-theme: for every material colour and both ring
  colours, the drawn halo-and-line pair separates in Rec. 709 luminance from that palette's sky,
  backdrop and tile colours by at least 40 of 255, and no colour used for an item is used in the
  same frame for a hazard or a projectile.
