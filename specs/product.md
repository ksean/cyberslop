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
- **PROD-103:** Death must leave the current map visible for a four-second death sequence before
  showing the `You died` screen. Starting from the pose visible on the lethal tick, the player
  collapses into a prone pose over the first two seconds and remains prone for the other two. An
  acid/poison-pit death animates poison bubbles on the player; a fire-hazard or laser-beam death
  animates flame; and a spike, broken-glass, projectile or melee-attack death animates bleeding. The complete
  cause, timing, input and fallback rules are specified in [simulation.md](simulation.md) and
  [presentation.md](presentation.md).
- **PROD-032:** `Continue game` must resume an in-progress run only — never a run that has ended,
  and never a save the current build cannot read. Saves carry a format version and are refused
  rather than partially applied.
- **PROD-100:** Exiting a cleared map into the next map must carry the player's current health
  forward as the same absolute value. Clearing or entering a map must not heal, refill or reset
  health. The player's unupgraded maximum health is 100 on every map and is changed only by
  permanent upgrades; advancing the map index must not change it. A new run begins at its full
  upgraded maximum.
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
  at the nearest valid target when one is available. There is no attack input. Ranged-class target
  selection follows PROD-116.
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
- **PROD-112:** Every engaged rank-and-file archetype may enter, cross and fight within the
  mini-boss arena and its twenty-tile approach when its ordinary pursuit movement takes it there.
  Rank-and-file swings, projectiles and contact drain work normally on that ground. Initial
  population remains outside both arenas, and the main-boss approach, arena and exit corridor
  retain their rank-and-file movement and damage protection.
- **PROD-061:** A melee enemy must close on the player and attack with a telegraphed swing. A
  ranged enemy must keep its distance when the player closes, but no enemy may move faster than
  the player runs.
- **PROD-069:** Every enemy body must hurt to touch: while the player overlaps a living
  rank-and-file enemy, health drains per second of contact at a rate the map index scales; a living
  mini-boss or main boss drains exactly three times that normal-enemy amount. Contact stacks with
  any swing or shot, never displaces the player, and never bypasses the fairness rules for
  committed spans and the main boss's protected ground.
- **PROD-062:** A boss and a mini-boss must activate — move, attack and become vulnerable — as soon
  as the player is within its awareness radius, not on crossing a line; an engaged boss is free to
  leave its arena and pursue.
- **PROD-063:** Every enemy and boss attack must be animated: a visible wind-up before it can hurt,
  the swing or shot itself, and a muzzle flash for a shot.
- **PROD-064:** Maps must carry visible, survivable damaging hazards in the dystopian idiom — at
  least spike strips, broken-glass patches and burning barrels — that hurt per second of contact,
  placed off the proven route, at a density that rises across the run. The shared damaging-hazard
  placement target must scale linearly from `7/3` hazards per 100 tiles on map 1 to `7` on map 10,
  so map 10's target density is exactly 300 % of map 1's. Acid and fire-jet availability and
  proposal frequency remain separate, theme-driven terrain rules.
- **PROD-113:** Map 1 is the unscaled reference for enemy health and enemy damage. For map `L` from
  1 through 10, the health of each rank-and-file archetype, mini-boss and main boss must equal its
  map-1 counterpart times `1 + 4(L - 1) / 9`, and each damage amount from the same enemy contact or
  attack source must equal its map-1 counterpart times `1 + 6(L - 1) / 9`. Map 10 enemies must
  therefore have exactly 500 % of map-1 health and deal exactly 700 % of map-1 damage. Both scales
  are linear between those endpoints. The existing rank-and-file population-density curve remains
  unchanged at a linear 4 to 9 enemies per 100 tiles from maps 1 through 10. Damaging hazards retain
  their separate damage scale specified in [hazards.md](hazards.md).
- **PROD-114:** Spike traps and burning-barrel bodies must use colours from the current map's
  palette. The building-window colour is the map's canonical theme colour, and every filled spike
  blade and barrel drum must use that exact colour. Supports, bands and other structural details
  may use contrasting colours from the same palette. Each spike blade must be a filled shape rather
  than an outline made from line segments. The fire above a barrel must retain the fixed warm
  outer-flame and hot-core colours used by fire elsewhere in the game.
- **PROD-094:** No lethal or damaging hazard may occupy the main boss's gate column or the exit
  corridor strictly beyond it, where entering completes the map. Nothing damaging may therefore
  sit on top of the wall behind the boss. The corridor's safe floor must be visibly marked by an
  animated blue sparkling surface so the completion zone reads before the player crosses it; the
  animation is presentational and changes neither collision nor completion.
- **PROD-106:** A generated rank-and-file enemy's complete initial patrol span must remain outside
  a 22-tile horizontal exclusion zone on both sides of the player's map-start column. No normal
  enemy may therefore begin a map within the player's initial awareness radius; population targets
  and all other placement protections remain in force.
- **PROD-108:** Broken glass must be a non-blocking, survivable ground hazard which drains health
  at `0.5 × hazardDamage(mapIndex)` per second of player overlap. It must be generated in small
  patches under the normal damaging-hazard placement and confirmation rules and presented as low,
  rusty, jagged shards visibly distinct from spike strips and ordinary floor.
- **PROD-068:** Difficulty pressure must rise across the run and be measured: over a seed cohort,
  the population's threat score rises strictly in cohort mean from map to map. A no-dodge pressure
  probe with non-terminal measurement health must replay each complete route and record uncapped
  gross incoming damage per hundred tiles; its early-, middle- and late-run averages must be
  strictly increasing. Separately, an unupgraded player with only guaranteed equipment must survive
  the route and win the boss fight on map 1 using a deterministic policy which responds to live
  telegraphs through the four player controls and avoids damage from at least 90 % of counted enemy
  and boss attack activations over the seed cohort. Contact drain and hazards are not attacks and
  remain ordinary survival pressure.
- **PROD-072:** A boss whose current phase holds both melee and ranged attacks must choose by
  distance: the further the player stands from it, the more often it opens with a ranged attack;
  the nearer, the more often with a melee one. The choice is made when an attack begins and holds
  for that attack; it never shortens a telegraph or removes a dodge.
- **PROD-104:** When a mini-boss or main boss begins a melee attack, it must sometimes charge
  forward in the direction locked by that attack's telegraph. Conditional on a melee attack, the
  charge probability must scale linearly from 50 % on map 1 to 90 % on map 10. A charged attack
  must damage an otherwise hittable player anywhere along the attack geometry's actual swept
  path, without adding damage opportunities or bypassing the attack's telegraph, declared dodge,
  terrain safety or committed-span fairness.
- **PROD-105:** A rank-and-file melee enemy must progress both its swing wind-up and its swing
  cooldown twice as quickly while the player's centre is within that enemy's swing reach. Leaving
  reach must immediately restore ordinary timer progression without resetting either timer. This
  close-range cadence must not increase the swing's damage or affect ranged enemies, mini-bosses
  or main bosses.
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
- **PROD-111:** A Bolt, Burst or Scatter round from either boss rank must test its visible-radius
  body over the complete path it travels during a fixed tick against the player's current-stance
  collision body. On the first contact not suppressed by committed-span fairness, the round must
  deal its declared damage exactly once, stop at that contact and trigger the player's red hurt
  flash. Fixed-tick endpoints and an ordinary jump outside a committed crossing must never turn a
  visible projectile-body contact into a miss; committed crossings and their landing grace retain
  their existing protection.
- **PROD-088:** Every engaged enemy must be able to continue pursuing across generated traversal
  hazards: a ground-bound rank-and-file enemy, mini-boss or main boss must jump a safe, reachable
  arc over pits, acid, spike strips, broken glass and low obstructions instead of stopping at them,
  while a Flyer crosses them in flight. A fixed-looking Turret must unfold into a slower mobile form when it
  engages. No enemy may launch a jump with no safe landing or gain permission to hurt a player
  during a committed crossing; a rank-and-file enemy must not enter the main boss's protected
  ground.

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
- **PROD-107:** A player grenade launcher must lead its selected enemy or boss when that target is
  moving: at trigger time its ballistic solution must aim for the constant-velocity future point
  implied by the target's most recently completed fixed-tick movement. A stationary target keeps
  the current-point solution, target selection remains based on current positions, and a grenade
  never retargets after launch.
- **PROD-109:** Every player melee weapon, including the Broken Bottle and Meatgrinder Halo, must
  have exactly 1.5 times its previous declared base damage. Cooldown, reach, attack geometry,
  fixed status magnitudes and all non-melee weapon damage remain unchanged.
- **PROD-098:** A player projectile must damage every eligible enemy or boss whose projectile-hit
  region its swept path intersects, subject to its pierce budget and intervening terrain. A fast
  shot must not pass harmlessly through a target merely because it crossed the complete target
  between two fixed simulation ticks; projectile-speed upgrades must not make a hit less reliable.
- **PROD-101:** A player attack fired by a ranged-class weapon must be bounded by the visible
  camera view. A travelling shot is spent when it first reaches the view's edge and no direct or
  secondary part of that ranged activation may damage an enemy or boss wholly off-screen. Melee,
  psychic, enemy and boss attacks retain their existing boundaries.
- **PROD-116:** A player's ranged-class weapon must aim at the closest visible eligible enemy,
  mini-boss or main boss, regardless of its distance from the player. Visibility is the canonical
  combat body's positive-area overlap with the current gameplay viewport under PROD-101, and
  distance is measured from the player's current weapon position to the target's current combat
  centre. No fixed auto-aim range may exclude an on-screen target. If no eligible target is
  visible, the weapon aims in the player's facing direction rather than selecting an off-screen
  target. Melee, psychic, enemy and boss targeting remain unchanged.
- **PROD-083:** The first time a browser profile collects each weapon or powerup, the collection
  must complete and then gameplay must pause for three seconds of active foreground time. A card
  centred over the game must show that item's usual picture, name and a brief, mechanically
  accurate description. The discovery must persist before the card is shown; collecting the same
  item in any later run must neither pause nor show the card. Several first discoveries from one
  pickup are shown one at a time in weapon-then-powerup order. If the collection tick also kills
  the player, the discovery still persists but the death sequence takes precedence and suppresses
  that discovery card, so neither overlay obscures or delays the four-second terminal sequence. See
  [progression.md](progression.md).
- **PROD-046:** One slain rank-and-file enemy in five drops something at every map index, three in
  ten of those a weapon. Mini-bosses and main bosses award loot on every death and are outside this
  rate.
- **PROD-110:** Every slain rank-and-file enemy must independently have a one-in-eight chance to
  drop one bowl of ramen. The bowl rests on safe ground and is collectible by walking over it,
  unlike weapon and powerup death drops. Collecting it removes it, restores 5 % of the player's
  current maximum health without exceeding that maximum, and briefly flashes the player green.
  It must read as a worn bowl with wavy noodles above its rim and two angled chopsticks
  emerging from the right side. Exact roll isolation, placement, collection and presentation are
  specified in [combat.md](combat.md) and [presentation.md](presentation.md).
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

- **PROD-040:** A coherent cyberpunk-dystopian 2D identity; every map's backdrop must depict a
  decayed, surveilled or industrial future rather than a generic modern skyline. Each sub-theme
  has its own palette, silhouette vocabulary and seeded structural detail so two sub-themes can be
  told apart without reading the map name or relying on colour alone. A themed distant landscape
  must sit behind the three building depths at exactly 0.024 times the camera rate — five times
  slower than the 0.12 far-building layer — while the existing building rates and their
  far-to-near ordering remain unchanged. Each landscape takes its identity and restrained colour
  cues from that map's building-window colour; ordinary details remain tiny at this implied
  distance while large landforms may span a substantial part of the horizon. It must be a designed
  scene rather than a sparse repeating silhouette: each screen-width composition has an authored
  landmark, supporting masses, environmental storytelling, surface texture and atmosphere, with
  multiple seeded outline variants and a fixed depth-ordered tonal hierarchy. Exact motifs, depth
  rates, composition quotas and procedural constraints are specified in
  [presentation.md](presentation.md).
- **PROD-041:** The player character is animated and visibly distinguishes standing, moving
  sideways, rising, falling, crouching, moving while crouched, firing a ranged weapon and swinging a
  melee weapon. Weapon animation composes over movement animation rather than replacing it.
- **PROD-066:** A player's `ArcSwing` melee attack must be drawn as a sweeping swoosh whose origin,
  locked direction, angular progress and resolved reach are the active direct-hit region's own;
  neither rendering nor the arm pose may substitute independent swing geometry. Every ranged or
  psychic activation must show a firing cue at the moment it happens: a muzzle flash at the barrel
  for a weapon that has one, an activation pulse at the weapon for one that does not (the Kessler
  dish). Enemy and boss attack cues remain governed by PROD-063.
- **PROD-115:** When a player's melee activation either has no `ArcSwing` swoosh or belongs to a
  weapon with a native chain or extra-target mechanic, an activation that damages no enemy or boss
  must briefly show how far its first contact could have reached. The miss indicator must end at
  the exact resolved first-contact range in the attack's locked direction; it must not depict the
  possible distance of later chain jumps or create damaging space. Static Lash is explicitly
  covered by this rule. Existing swooshes and successful-hit effects remain unchanged.
- **PROD-102:** During active gameplay, each activation of the player's melee weapon must produce
  one short swing sound, each firing event of the player's ranged weapon one short fire sound, and
  each firing event of the player's psychic weapon one subtle warp-like sound distinct from both.
  A simultaneous spread is one firing event; each later round in a time-separated burst is another.
  Contact that resolves one ground item containing a weapon, a powerup, both, or ramen must produce
  one small pickup pulse, including when the resolved item converts to Scrap, displaces something
  or is capped by full health.
  These sounds duplicate visible events, require no downloaded audio assets, and may never affect
  simulation, input, saving or progression. Enemy and boss attacks remain unsonified by this basic
  set.
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
- **PROD-050:** A drop on the ground is ringed by kind and rarity. A powerup has a fixed blue ring;
  a weapon has a tier-coded ring — T1 white, T2 green, T3 gold, T4 purple and T5 red. The T4 ring
  has a restrained coloured glow and the T5 ring has a visibly stronger glow; T1–T3 and powerups
  have no coloured ring glow. The ring and any glow are drawn only around a drop: never around the
  weapon in the player's hand or in the HUD. Kind and rarity remain readable with colour removed:
  a powerup's icon sits inside a module casing while a weapon's does not, and tier pips remain.
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
- **PROD-051:** An icon stays legible on every sub-theme: for every material colour and all six ring
  colours, the drawn halo-and-line pair separates in Rec. 709 luminance from that palette's sky,
  backdrop and tile colours by at least 40 of 255, and no colour used for an item is used in the
  same frame for a hazard or a projectile.
