# Product Specification

## Vision

Cyberslop is a cyberpunk-dystopian side-scrolling roguelite for the browser. The player crosses ten
procedurally generated maps of rising difficulty, left to right, fighting a mini-boss at each
midpoint and a main boss at each end. Four movement actions, one weapon that fires by itself, a
build of powerups collected on contact, and permadeath with persistent unlocks.

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
  weapon. Scrap earned during a run must be banked when the run ends: it increases both a spendable
  balance and a lifetime total. The lifetime total expands the pool of weapons available to later
  runs; spending the balance must never shrink that pool or erase an unlock.
- **PROD-032:** `Continue game` must resume an in-progress run only — never a run that has ended,
  and never a save the current build cannot read. Saves carry a format version and are refused
  rather than partially applied.
- **PROD-048:** The title screen and the run-ended screens must share the in-game visual identity.
- **PROD-081:** The title screen must always offer a keyboard-operable button named `Shop`. The
  shop must show the player's spendable Scrap, every permanent upgrade and its current rank,
  effect and next-rank price, and a `Back` button that returns to the same title screen without
  discarding a valid in-progress run. Every run-ended screen must offer `Return to title`, so Scrap
  banked by that run can be spent without reloading the page.
- **PROD-082:** Scrap spent in the shop must buy permanent character upgrades which apply to every
  later new or continued run. A purchase must be immediate, persistent and all-or-nothing; an
  unaffordable or maximum-rank purchase changes nothing. The catalog, prices, effects, unlock
  accounting and save migration are specified in [progression.md](progression.md).
- **PROD-091:** Pressing `Escape` during a map must pause the run and show a keyboard-operable menu
  centred over the dimmed game, with `Resume` and `Return to title` buttons. `Escape` also resumes.
  Pausing must freeze simulation and simulation-time presentation, clear held gameplay input, and
  never advance the run while focus changes. `Return to title` voluntarily ends the current run,
  banks all of its accumulated Scrap exactly once, clears its in-progress save, and shows the title
  with that Scrap available to the shop.

## Controls and gameplay

- **PROD-020:** A run must consist of ten procedurally generated maps of increasing difficulty. The
  player progresses by moving right; each map contains a mini-boss at its midpoint and a main boss
  at its end, and the main boss gates the map exit.
- **PROD-021:** The simulation must expose exactly four gameplay actions: left on `ArrowLeft` or
  `A`, right on `ArrowRight` or `D`, crouch on `ArrowDown` or `S`, and jump on `ArrowUp`, `W` or
  `Space`. Either binding for an action must have identical press, hold, release, buffering and
  focus-loss semantics. `Escape` controls the browser lifecycle pause in PROD-091 rather than
  entering the simulation input frame. The equipped weapon fires automatically on its own cooldown
  at the nearest valid target. There is no attack input.
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
- **PROD-033:** A player's `ArcSwing` melee attack must be visibly indicated for its whole active
  window by the same swept region used for direct-hit testing. In every rendered frame, an
  eligible enemy or boss whose combat body overlaps the visible swoosh has already taken that
  swing's direct hit, once; the swoosh must not advertise damaging space the swing does not cover.
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
- **PROD-069:** Every enemy body must hurt to touch: while the player overlaps a living
  rank-and-file enemy, health drains per second of contact at a rate the map index scales; a living
  mini-boss or main boss drains exactly three times that normal-enemy amount. Contact stacks with
  any swing or shot, never displaces the player, and never bypasses the fairness rules for
  committed spans and the boss's ground.
- **PROD-062:** A boss and a mini-boss must activate — move, attack and become vulnerable — as soon
  as the player is within its awareness radius, not on crossing a line; an engaged boss is free to
  leave its arena and pursue.
- **PROD-063:** Every enemy and boss attack must be animated: a visible wind-up before it can hurt,
  the swing or shot itself, and a muzzle flash for a shot.
- **PROD-064:** Maps must carry visible, survivable damaging hazards in the dystopian idiom — at
  least spike strips and burning barrels — that hurt per second of contact, placed off the proven
  route, at a density that rises across the run and is zero on map one.
- **PROD-094:** No lethal or damaging hazard may be placed in the exit corridor strictly beyond the
  main boss's gate, where entering completes the map. That corridor's safe floor must be visibly
  marked by an animated blue sparkling surface so the completion zone reads before the player
  crosses it; the animation is presentational and changes neither collision nor completion.
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
- **PROD-087:** Every mini-boss and main-boss encounter must receive a run-seeded combat profile
  which is reproducible on continue but varies between encounters. Every profile must expose at
  least one telegraphed melee attack and one telegraphed ranged attack from full health; profiles
  must vary in attack geometry and cadence, including single and rapid melee strikes and single,
  burst, spread and beam shots. The available profiles, their damage and a main boss's late-fight
  cadence must grow more dangerous from the early maps through the late maps as specified in
  [enemies.md](enemies.md).
- **PROD-092:** Every ranged mini-boss and main-boss attack must threaten through the complete
  visible view. A projectile continues until it hits terrain, hits the player or leaves the level;
  a beam extends in its locked direction to the first terrain face or the level boundary. Neither
  may expire at the former fixed eight-tile distance.
- **PROD-088:** Every engaged enemy must be able to continue pursuing across generated traversal
  hazards: a ground-bound rank-and-file enemy, mini-boss or main boss must jump a safe, reachable
  arc over pits, acid, spike strips and low obstructions instead of stopping at them, while a Flyer
  crosses them in flight. A fixed-looking Turret must unfold into a slower mobile form when it
  engages. An enemy must not launch a jump with no safe landing, enter another boss's protected
  arena ground, or gain permission to hurt a player during a committed crossing.

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
- **PROD-070:** Walking over a weapon with a different `WeaponId` must always equip it, whatever it
  is and whatever is held: the previous weapon converts to Scrap, and every powerup held is cleared
  and converts to Scrap. Walking over the same `WeaponId` already held instead converts the pickup
  to Scrap at that weapon's tier value; the equipped weapon and all of its powerup slots and stacks
  remain unchanged. A guaranteed award that pairs a weapon with a powerup resolves the weapon
  first, then resolves the powerup against the resulting build.
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
- **PROD-097:** A projectile weapon declared as a lobber must launch its projectile upward toward
  the aim point and let gravity bend the flight into a visible ballistic arc, rather than sending
  it directly along the line of sight. The Ashfall Grenade Lobber is a lobber, and the same rule
  applies to every future projectile pattern with positive gravity. Straight ranged and psychic
  shots, and enemy and boss shots, keep their declared trajectories.
- **PROD-098:** A player projectile must damage every eligible enemy or boss whose projectile-hit
  region its swept path intersects, subject to its pierce budget and intervening terrain. A fast
  shot must not pass harmlessly through a target merely because it crossed the complete target
  between two fixed simulation ticks; projectile-speed upgrades must not make a hit less reliable.
- **PROD-083:** The first time a browser profile collects each weapon or powerup, the collection
  must complete and then gameplay must pause for three seconds of active foreground time. A card
  centred over the game must show that item's usual picture, name and a brief, mechanically
  accurate description. The discovery must persist before the card is shown; collecting the same
  item in any later run must neither pause nor show the card. Several first discoveries from one
  pickup are shown one at a time in weapon-then-powerup order. See
  [progression.md](progression.md).
- **PROD-046:** One slain rank-and-file enemy in five drops something at every map index, three in
  ten of those a weapon. Mini-bosses and main bosses award loot on every death and are outside this
  rate.
- **PROD-047:** Each map carries statically placed pickups averaging two per map across seeds,
  each on a cell the map's own verified witness stood on, outside both arenas and outside any
  committed span, no two within twelve tiles where the map allows it. Each rolls its rarity twice
  keeping the better result. Map one's starter cache is a separate guaranteed award.
- **PROD-090:** Every weapon or powerup created by a rank-and-file, mini-boss or main-boss death
  must rest above safe standable ground at a height no grounded standing, running or crouching pose
  can contact, while the normal unassisted jump can. An airborne or over-hazard death must choose a
  deterministic nearby safe collection site; guaranteed loot must never become unreachable. Static
  map pickups and map one's starter cache remain walk-over pickups. Exact placement and contact are
  specified in [combat.md](combat.md).

## Presentation

- **PROD-040:** A coherent cyberpunk-dystopian 2D identity; each sub-theme has its own palette and
  backdrop so two sub-themes can be told apart without reading the map name.
- **PROD-041:** The player character is animated and visibly distinguishes standing, moving
  sideways, rising, falling, crouching, moving while crouched, firing a ranged weapon and swinging a
  melee weapon. Weapon animation composes over movement animation rather than replacing it.
- **PROD-066:** A player's `ArcSwing` melee attack must be drawn as a sweeping swoosh whose origin,
  locked direction, angular progress and resolved reach are the active direct-hit region's own;
  neither rendering nor the arm pose may substitute independent swing geometry. Every ranged or
  psychic activation must show a firing cue at the moment it happens: a muzzle flash at the barrel
  for a weapon that has one, an activation pulse at the weapon for one that does not (the Kessler
  dish). Enemy and boss attack cues remain governed by PROD-063.
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
- **PROD-093:** A living rank-and-file enemy with active burn damage must carry a visibly animated
  flame/ember indicator on its model; one with active bleed damage must carry a visibly animated
  falling-blood indicator. The two effects must remain distinguishable without motion or colour
  alone, coexist when both statuses are active, and disappear with the corresponding status or the
  enemy's death.
- **PROD-095:** The player character must briefly flash red whenever a positive damage event lowers
  current health. The flash is feedback only: it changes no damage, invulnerability, input, save or
  deterministic simulation state.
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
- **PROD-084:** A held weapon aimed left must be the horizontal mirror of its corresponding
  right-aimed appearance, not a 180-degree rotation: its top remains its top while its damaging end
  still follows the aim. The same rule applies at upward and downward angles on the left side.
- **PROD-085:** An acid pool must read as toxic liquid rather than a static block: every visible
  exposed surface must retain its bright liquid edge and show several differently phased bubbles
  rising and bursting. The animation is presentational only and changes no hazard geometry,
  timing or lethality.
- **PROD-096:** An active fire jet must read as fire rather than a straight light column: its outer
  flame and hot core form several pointed, laterally wavy tongues whose shapes animate. The solid
  tile immediately beneath every jet must visibly contain a ruptured pipe outlet, including while
  the jet is off, so the threat's location remains identifiable. The flame and pipe are
  presentational only and change no jet footprint, timing or lethality and no tile collision.
- **PROD-099:** A burning barrel must be topped by visibly animated, laterally wavy fire rather
  than a static triangular mark that reads as a spike. Its flame remains presentational and
  changes neither the barrel-and-flame damaging footprint nor its contact damage.
- **PROD-086:** Every positive Scrap award during active gameplay must produce one bold golden
  `+X` above the player's head, where `X` is the total awarded in that simulation tick. The label
  must rise a small visible distance and fade completely; it is feedback only and must not alter
  the award, simulation outcome or saved state.
- **PROD-089:** A mini-boss or main boss's silhouette must disclose its assigned attack profile
  without relying on colour: its melee implement must distinguish a heavy single strike, rapid
  strikes and a rush, and its ranged hardware must distinguish a single/burst barrel, a spread
  muzzle and a laser emitter. Mini-boss/main-boss scale and crown differences remain visible on
  every profile.
- **PROD-051:** An icon stays legible on every sub-theme: for every material colour and both ring
  colours, the drawn halo-and-line pair separates in Rec. 709 luminance from that palette's sky,
  backdrop and tile colours by at least 40 of 255, and no colour used for an item is used in the
  same frame for a hazard or a projectile.
