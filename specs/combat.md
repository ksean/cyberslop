# Combat, Weapons and Powerups

The player carries one weapon. Powerups are **player-owned and weapon-applied**: five slots of up
to three stacks each, applied to the weapon held — and emptied whenever a pickup equips a
different weapon (PROD-070). A build is made around one weapon identity and survives picking up
another copy of that same weapon.

## The firing tick

The equipped weapon fires on its own cooldown at the nearest valid target (PROD-021). Cooldown is
an accumulator in simulation time, so overshoot is never discarded and rates do not drift:

```
cooldownLeft -= dt
while (cooldownLeft <= 0) { fire(); cooldownLeft += resolved.cooldown }
```

## Weapon model

`combat.WeaponSpec` declares id, name, class, tier, damage, cooldown, range, projectile speed and
count, spread, burst interval, pierce, knockback, crit chance (base 5 %), anchor (`Self` |
`Cursor`), wind-up, falloff, homing, on-hit effects, on-fire effects and fire pattern.

**Spread and burst are alternatives (PROD-075).** A multi-projectile weapon with a `spread`
fires its projectiles at once, fanned evenly across the spread angle around the aim with the
outermost on its edges (five pellets at 30° sit at −15°, −7.5°, 0°, 7.5°, 15°) — a shotgun, a
nailgun. A weapon with a `burstIntervalSeconds > 0` is a **machine gun**: the trigger fires its
first round and queues the rest, one every interval, each leaving the muzzle where it is *then*
along the aim recorded when the trigger fell, so the rounds trail one another in a straight line
whatever the target does in the meantime. A burst weapon declares no spread. Every round is a
whole projectile of the build that pulled the trigger (`LiveProjectile.weapon`, PROD-070) and
draws its own muzzle flash; Fork Bomb's extra projectiles join the burst rather than a fan. The
Minigun's cooldown is already one round every 0.12 s, so it declares no interval and no spread:
its rounds — Fork Bomb's included — leave together along the aim: an interval on it could not
fit inside its 0.042 s cooldown floor, and a Fork Bomb round that left a tick later would only
land a tick later on the same line. The
registry keeps `burstIntervalSeconds × (maxProjectiles − 1)` below the weapon's cooldown floor
(`0.35 × cooldown`, maxProjectiles counting three Fork Bomb stacks), so a burst is always spent
before the next trigger; the trigger tick nevertheless discards any pending rounds so a burst can
never carry a stale build. The pending burst is simulation state and is in the digest (P-40).

Class is load-bearing:

- **Melee** `ArcSwing` weapons sweep a live sector over their declared linger window: every target
  body the visible sector covers takes one direct hit from that activation; Mass Driver widens the
  sector and Ranger Optics extends its reach. Meatgrinder Halo keeps its separately specified ring
  pattern.
- **Ranged** spawns travelling projectiles that stop at terrain and obey falloff; the Railgun and
  Minigun declare a wind-up.
- **Psychic** projectiles and blasts pass through terrain.

Powerups apply through `DamagePipeline.resolve`, which turns the held weapon and the slots into
one `ResolvedWeapon` (damage, cooldown, projectile count, pierce, crit, chain, ricochet, homing,
hitbox and reach scale, knockback, stun, slow, blast, ignite, lifesteal, kill refund). Every
powerup resolves for every weapon; a field a pattern cannot use (extra projectiles on a blast)
simply has no effect there.

## Hitbox-faithful player arc swings (PROD-033, PROD-066)

An `ArcSwing` activation creates one gameplay-owned active swing. Its aim direction is locked when
the weapon triggers; its resolved reach, arc and `lingerSeconds` are likewise snapshotted from the
build that triggered it. During each simulation tick of that window, the origin follows the
player's combat centre and progress advances monotonically from zero to one. At progress `p`, the
active and visible footprint is the closed circular sector from the trailing angular edge
`−arc / 2` through `−arc / 2 + p × arc`, from the origin through the resolved reach. Boundaries are
inside. Actor movement is resolved before the active sector is tested, and that test completes
before the same state may be composed into a frame.

Targets are areas, not aim points. Every enemy and boss exposes one canonical **combat body** used
by both direct melee collision and presentation: a closed disc centred on its combat centre and
large enough to contain its damaging body silhouette. Glow, a health bar, a held implement and an
attack effect are not body. A direct hit occurs when that disc intersects the current sector. This
includes tangency at the reach or either angular edge. A target that enters any still-visible part
of the sector later in the window is hit then; one that never intersects it is not. The swing keeps
the set of targets it has directly hit, so no target takes its direct damage more than once per
activation. Secondary shock, chain and blast effects remain separate hits with their own geometry.

Every eligible target whose combat body intersects the visible sector is hit: an `ArcSwing` has no
direct-target or pierce limit. `pierce` remains a projectile capacity, so Spike Driver has no direct
effect on an `ArcSwing`; this is preferable to drawing a swoosh through a second target after an
invisible target budget was spent. Every registered `ArcSwing.lingerSeconds` remains below that
weapon's resolved cooldown floor, so two active swings from the same weapon cannot overlap.

The active swing is future-affecting simulation state — locked geometry, progress and already-hit
targets — and therefore participates in the determinism digest. It ends exactly when its visible
window ends: there is no cosmetic swoosh afterimage with no matching hit region. Meatgrinder Halo's
`Orbit`, enemy strikes and boss attack modules are outside this player-weapon rule and retain their
own geometry and timing.

## Weapon pickup

Contact always resolves (PROD-030, PROD-070). If a weapon on the ground has a different `WeaponId`
from the held weapon, it is **always taken**: it becomes the held weapon, the previous weapon
converts to Scrap at its tier value, and every powerup slot is cleared, each converting to Scrap at
its tier value. If its `WeaponId` matches the held weapon, the pickup itself converts to Scrap at
that weapon's tier value instead: the loadout, including every powerup identity and stack, remains
unchanged and no held item pays additional Scrap. There is no score comparison or refusal; exact
weapon identity selects between these two resolutions, while tier supplies the Scrap value. A
player's shot carries the build that fired it: a pickup while it is in flight changes nothing about
where it lands or what landing does. A guaranteed award that carries both a weapon and a powerup —
a mini-boss from map 4, every main boss — resolves the weapon first and the powerup second. A
different weapon therefore empties the build before the guaranteed powerup lands; a matching
weapon converts to Scrap and the guaranteed powerup resolves by the normal stacking and
displacement rules against the preserved build.

`WeaponScore` still decides powerup displacement (below) and still resolves conditional terms
against a **reference target**: one enemy at 60 % of the current map's trash health, 4 m away,
unslowed, unstunned, full uptime, damage-over-time at full expected value. It no longer decides
weapon pickups.

A powerup arriving at a full build follows PROD-028: a guaranteed award is never refused and
displaces the slot whose loss costs least damage; an optional award displaces a slot only when doing
so raises the weapon score without lowering damage; whatever is displaced converts to Scrap.

## Weapon registry

Tiers: T1 Street · T2 Scav · T3 Chromed · T4 Blacksite · T5 Ascended. DPS is
`damage × projectiles ÷ cooldown`, single target, crit-free. Reach is in metres (1 m = 16 px).
Melee is the high-risk class (PROD-065): every melee weapon reaches at least 2 m — beyond any
enemy swing's 1.5 tiles — and within each tier the melee mean DPS, bottle excluded, exceeds the
ranged mean.

| Weapon | Class | T | Dmg | CD | DPS | Reach / range | Mechanic |
|---|---|---|---|---|---|---|---|
| Broken Bottle | Melee | 1 | 8 | 2.00 | 4.0 | 2.2 m, 70° | Starting weapon |
| Rustline Machete | Melee | 1 | 17 | 1.40 | 12.1 | 2.3 m, 80° | Bleed |
| Corpo Riot Baton | Melee | 2 | 17 | 1.10 | 15.5 | 2.2 m, 90° | Knockback, 0.3 s stun |
| Chrome Fang | Melee | 2 | 13×2 | 1.20 | 21.7 | 2.0 m, 35° | Two-hit combo |
| Static Lash | Melee | 3 | 24 | 0.90 | 26.7 | 4.0 m, 60° | Shocks one extra target |
| Gutterjack Cleaver | Melee | 3 | 42 | 1.30 | 32.3 | 2.4 m, 75° | Executes under 15 % HP |
| Kill-Switch Katana | Melee | 4 | 40 | 0.65 | 61.5 | 2.8 m, 50° | Dash-strike: the hitbox lunges 3 m, 0.2 s i-frames; the player does not move |
| Chromewreck Maul | Melee | 4 | 100 | 1.60 | 62.5 | 3.6 m, 100° | Shockwave, heavy knockback |
| Meatgrinder Halo | Melee | 5 | 40 | 0.35 | 114.3 | 2.8 m ring | Persistent saw ring |
| Scrapline Zip Pistol | Ranged | 1 | 7 | 0.80 | 8.8 | 20 m | Single slug |
| Tenement Nailgun | Ranged | 1 | 4×2 | 0.70 | 11.4 | 20 m | 12° spread, pierce 1 |
| Ganglord SMG | Ranged | 2 | 4×3 | 0.75 | 16.0 | 20 m | Machine gun: 3-round burst, 0.05 s apart, straight |
| Riotbreaker Shotgun | Ranged | 2 | 6×5 | 1.50 | 20.0 | 20 m | 30° cone, falloff past 5 m |
| Vulture Rail Carbine | Ranged | 3 | 28 | 1.00 | 28.0 | 20 m | Pierce 2 |
| Ashfall Grenade Lobber | Ranged | 3 | 33 | 1.40 | 23.6 | 20 m | Blast 2.5 m at 60 % |
| Sable Corp Railgun | Ranged | 4 | 95 | 1.70 | 55.9 | 20 m | Infinite pierce, 0.4 s wind-up |
| "Debt Collector" Minigun | Ranged | 4 | 7 | 0.12 | 58.3 | 20 m | Machine gun: 0.6 s wind-up, one straight round per 0.12 s |
| Kessler Orbital Uplink | Ranged | 5 | 120 | 1.20 | 100.0 | 30 m | Target-anchored strike, 0.35 s delay |
| Neural Spike | Psychic | 1 | 10 | 1.10 | 9.1 | 16 m | Slow orb, weak seek |
| Migraine Loop | Psychic | 2 | 13 | 0.85 | 15.3 | 12 m | Blast through terrain |
| Wetware Screamer | Psychic | 2 | 9×2 | 1.00 | 18.0 | 16 m | Two homing orbs |
| Ghostwire Tether | Psychic | 3 | 18 | 0.70 | 25.7 | 8 m | Chains 3, −25 % per jump |
| Blackbox Chorus | Psychic | 3 | 36 | 1.20 | 30.0 | 10 m | Pull, then crush |
| Synapse Hemorrhage | Psychic | 4 | 44 | 0.80 | 55.0 | 14 m | ×2 vs slowed or stunned |
| Null-Ego Singularity | Psychic | 5 | 55×2 | 1.00 | 110.0 | 6 m | Two orbiting orbs, pull + pierce |
| Voice of the Dead Net | Psychic | 5 | 95 | 1.05 | 90.5 | 12 m | Chains 8, no decay; 40 % free recast on kill |

## Powerup registry

| Powerup | T | Effect | Stack 1 | Stack 2 | Stack 3 | Combines |
|---|---|---|---|---|---|---|
| Fracture Lens | 1 | Crit chance | +8 % | +14 % | +18 % | add |
| Kinetic Damper | 1 | Knockback | +60 % | +110 % | +150 % | mult |
| Ranger Optics | 1 | Range / arc reach | +20 % | +35 % | +50 % | mult |
| Guillotine Codec | 1 | Crit multiplier (base ×2.0) | +0.50 | +0.85 | +1.10 | add |
| Hollowpoint Firmware | 2 | Damage | +25 % | +45 % | +60 % | add |
| Spike Driver | 2 | Pierce | +1 | +2 | +3 | add |
| Red Market Siphon | 2 | Lifesteal on every hit (cap 4 HP/hit; 12 HP budget refilling at 12 HP/s) | 2 % | 3.5 % | 4.5 % | add |
| Mass Driver | 2 | Hitbox / arc width | +25 % | +45 % | +60 % | mult |
| Overclock Coil | 3 | Cooldown reduction | 12 % | 21 % | 28 % | mult |
| Chill Protocol | 3 | Enemy speed, 2 s | −18 % | −30 % | −38 % | mult |
| Burn Rig | 3 | Ignite, % damage/s for 3 s | 15 % | 25 % | 32 % | add |
| Ricochet ROM | 3 | Projectiles bounce off terrain, 85 % damage per bounce | 1 | 2 | 3 | add |
| Seeker Daemon | 4 | Homing turn rate (°/s) | 90 | 160 | 210 | add |
| Arc Cascade | 4 | Chain targets | 1 @50 % | 2 @45 % | 3 @40 % | add |
| Brownout Charge | 4 | Expected stun-seconds | 0.048 | 0.090 | 0.132 | add |
| Fork Bomb | 5 | Effective projectile gain | +0.70 | +1.20 | +1.65 | add |
| Thermite Payload | 5 | On-hit blast, % of damage | 35 % | 45 % | 55 % | add |
| Killstreak Cache | 5 | On kill: chance to clear cooldown | 15 % | 25 % | 35 % | event |

Each run draws **8 of the 18** powerups, tier-weighted, as its drop pool, so duplicates are common
enough for stacking to happen.

**Life steal (PROD-073).** Red Market Siphon heals the player by its fraction of every point of
damage the held weapon **actually deals** to an enemy or a boss — a swing, a projectile landing,
a blast, a chain jump, splash; overkill past zero health steals nothing — before the per-hit cap
of `LIFESTEAL_CAP = 4 HP` and a **budget** of `LIFESTEAL_PER_SECOND = 12 HP` that refills at
12 HP/s and is spent by each heal, so sustained healing never exceeds 12 HP/s and a burst can
bank at most one second of it (a full budget then a full second's refill is 24 HP inside one
second, by design: a token bucket, not a sliding window). A heal is what the player gains: it
never passes max health and spends only what it gave. Damage over time (burn, bleed) heals
nothing: it is not a hit. It is the build that fired a projectile that heals when it lands
(PROD-070). A player killed earlier in a tick is not brought back by a hit landing later in it.
The budget is simulation state and is in the digest.

**Bounce (PROD-074).** Ricochet ROM gives a projectile `bounces` (1, 2, 3 by stack). A ranged
projectile that would be spent on terrain instead **reflects** while it has bounces left: the axis
it entered the solid tile along is reversed (a floor or ceiling reverses `vy`, a wall `vx`; a
corner entered on both axes reverses both), the projectile is put back at its position before the
step, its damage is multiplied by `BOUNCE_DAMAGE = 0.85`, its bounce count falls by one, and its
lifetime and pierce carry on. Psychic projectiles pass through terrain and never bounce; enemy
and boss shots never bounce. A bounced projectile that meets an enemy hits it as any projectile
does, and the last bounce leaves its impact tracer where it finally stops (PROD-071). The bounce
count is on `LiveProjectile` and in the digest.

## Damage formula and caps

```
raw   = base × (1 + Σ additive) × Π multiplicative × splitFactor
crit  = rand() < min(critChance, 0.75) ? (2.0 + Σ critBonus) : 1.0
splitFactor = (n + extraProjectiles × pct) / n        // n = the weapon's own projectile count
cooldown    = clamp(base × Π speedMults, max(0.08, base × 0.35), base × 2.0)
```

Caps, each tested: crit chance ≤ 75 %; cooldown floor `max(0.08 s, 0.35 × base)`; enemy speed
floor 40 %, multiple slows take the max rather than the product; bosses are immune to slows; live projectiles ≤ 60 per weapon and 300 per
scene (a performance bound); chain, fork and blast each carry a per-activation target
set, blasts cannot trigger blasts, free recasts cannot recurse; a projectile bounces at most its
bounce count times and never against an enemy; lifesteal is capped per hit and by its budget
(12 HP, refilling at 12 HP/s, never more than one second banked).

## Drops and rarity

Tier weight at map `L` interpolates linearly between the two endpoint rows and is renormalised;
the same weights apply to weapons and powerups.

| Map | T1 | T2 | T3 | T4 | T5 |
|---|---|---|---|---|---|
| 1 | 62 | 25 | 9 | 3 | 1 |
| 10 | 34 | 26 | 20 | 13 | 7 |

| Source | Chance | Yields |
|---|---|---|
| Rank-and-file kill | 20 %, flat (PROD-046) | 30 % weapon / 70 % powerup |
| Static drop, mean 2 per map (PROD-047) | 100 % | same split; rarity rolled twice, keeping the better |
| Starter cache, map 1 before the midpoint | 100 % | weapon, tier ≥ T1 |
| Mini-boss | 100 % | weapon (tier ≥ T2); plus a powerup from map 4 |
| Main boss | 100 % | weapon (tier ≥ T3, +2 tier shifts) + powerup (tier ≥ T2) + Scrap |

### Jump-required death drops (PROD-090)

Loot created by any death — an optional rank-and-file weapon or powerup and every guaranteed
mini-boss or main-boss award — uses a death-drop site. Its resting height is
`DEATH_DROP_RISE = 2 × TILE_SIZE = 32 px` above the top of its collection surface. Contact keeps
the existing strict radial test: the player's centre must be less than
`PICKUP_REACH = TILE_SIZE = 16 px` from either icon's **simulation resting position**. On flat
ground a standing player's centre is 13 px above the surface, leaving a 19 px vertical separation
even at exact horizontal alignment; running or crouching under the item therefore cannot take it.
A normal held jump from rest, stepped through the shipping `MovementModel`, must bring the player
within reach before landing. No airborne-state or jump-input flag gates collection: the geometry
alone creates the requirement.

The primary candidate preserves the slain actor's centre x and projects it onto the nearest safe
standable surface below. A site is valid only when its supporting pose is player-reachable without
lethal contact, both resting icon centres (the powerup remains one tile right of the weapon in a
paired award) are clear of blocking and lethal terrain, every collision-free grounded standing or
crouching pose near either icon remains outside pickup reach, and a normal jump from that support
reaches at least one icon without touching blocking or lethal terrain. If the projection is invalid
— including a Flyer over a pit, a ground enemy over a hazard, a boss killed during a leap, a low
ceiling, a sealed platform or adjacent raised ground that would turn the drop back into a walk-over
— player-reachable safe standable candidates are ordered by horizontal distance from the death,
then vertical distance, column and row; the first valid candidate wins. This search consumes no
randomness. A valid generated map must supply a candidate; loot is never discarded for lack of one.

The position is chosen once when the death is resolved. The item has no falling physics, and the
visual hover remains presentation-only around that resting position (P-52). Static map pickups and
the map-one starter cache do not use this placement rule, so their positions and grounded contact
remain unchanged. Drop chance, weapon/powerup split, rarity, guaranteed contents, collection order
and loot RNG draws are also unchanged.

Scrap per converted weapon or powerup slot by the converted item's tier: 8, 20, 45, 100, 240. A
same-weapon pickup pays exactly one weapon value and no values for the powerups it preserves.

## Verified properties

- **P-14** Weapon registry: ≥ 20 weapons, all three classes; `minDPS(T) ≥ 1.05 × maxDPS(T−1)`;
  min, mean and max DPS strictly increasing by tier; every entry has a finite score against the
  reference target.
- **P-15** Powerup registry: ≥ 15 entries, each with a tier and a scalar magnitude whose stack
  curve is never super-linear (`v(2) ≤ 2·v(1)`, `v(3) ≤ 3·v(1)`); every weapon × every powerup at
  every stack count resolves to a `ResolvedWeapon` with finite, positive damage and cooldown;
  interpolated tier weights are strictly decreasing in tier at every map index.
- **P-16** Cooldown fidelity (simulation.md).
- **P-37** Melee class: every melee weapon's reach is ≥ 2 m and greater than the enemy swing
  reach; in every tier that holds both classes, mean melee DPS excluding the Broken Bottle exceeds
  mean ranged DPS; the bottle's DPS stays below map 1's required rate.
- **P-25** Kill drop rate is 0.20 at every map index, three in ten of them weapons; static drops
  average 2.0 ± 0.15 per map over a seed cohort, each count in {1, 2, 3}.
- **P-44** Boss attack choice (enemies.md).
- **P-45** Life steal and bounce (a projectile step is walked in pieces of at most half a tile,
  so a 24 px-per-tick shot fired 4 px short of a one-tile wall is stopped by it, or reflected off
  it with a bounce left): with Red Market Siphon a melee swing, a projectile landing, a
  Migraine Loop blast, a Ghostwire Tether jump and Thermite splash each heal exactly the fraction
  of the damage dealt, capped at 4 HP per hit; ten seconds of ceaseless hits heal at most eleven
  seconds of budget (132 HP); a hit that would deal 120 to an enemy with 1 HP steals from 1; a
  player 1 HP short spends 1 HP of budget; a burn tick heals nothing; healing never exceeds max
  health; without the powerup nothing heals.
  With Ricochet ROM at one stack a projectile fired into the floor reflects with `vy` reversed,
  `vx` kept and 85 % of its damage, keeps its remaining lifetime and pierce, and is spent on its
  second terrain contact; at three stacks it survives three; a wall reverses `vx`; a bounced
  projectile still damages an enemy it meets; a psychic projectile never bounces; an enemy shot
  never bounces; the bounce count is in the digest.
- **P-46** Burst fire: the Ganglord SMG's trigger tick spawns one projectile and the next two
  follow exactly three ticks (0.05 s) apart — ticks 4 and 7 after a tick-1 trigger — each along the aim of the trigger tick (a target that moved between rounds
  does not bend them), each leaving the muzzle of its own tick, each with a muzzle flash; the
  Minigun declares no spread and no interval; a spread weapon (Riotbreaker Shotgun) still fans
  all its projectiles at once; Fork Bomb extends the burst; every burst weapon satisfies
  `interval × (count + 3 − 1) < 0.35 × cooldown`; a burst pending at the next trigger is
  discarded, a round of it due on the trigger tick included; the pending burst is in the digest;
  the registry DPS column is unchanged.
- **P-42** Weapon pickup: collecting a weapon with a different `WeaponId` — including one of lower
  tier and lower score than the held one — equips it; the previous weapon's Scrap and the Scrap of
  every cleared slot are paid; the slots are empty afterwards; and a powerup collected next lands
  in the emptied build. Collecting the same `WeaponId` leaves the weapon and every slot and stack
  unchanged, removes the pickup, and pays exactly the pickup weapon's tier value. A paired boss
  award resolves in weapon-then-powerup order: its different weapon is equipped before its powerup,
  while its matching weapon is scrapped before its guaranteed powerup resolves against the
  preserved build. Both weapon outcomes are reported as collections for discovery.
- **P-63** Hitbox-faithful player arc swings: pure sector/body fixtures include a body wholly
  inside, tangent to the resolved reach, tangent to both angular edges, and one epsilon outside
  each boundary; the first four intersect and the outside fixtures do not. For every registered
  `ArcSwing`, with Ranger Optics and Mass Driver at zero and three stacks, collision and the active
  swing state use the same origin, locked direction, scaled reach, arc and progress, and its linger
  window is shorter than the cooldown floor. Integration fixtures prove that a stationary target,
  a target entering an already visible portion on a later tick and three simultaneously
  overlapping targets each take one direct hit; remaining in the sector never deals a second
  direct hit; leaving before the sweep reaches the body deals none; Spike Driver neither limits nor
  enlarges the sector. Movement, collision and composition use the same tick snapshot. Mutating
  active progress, geometry or any already-hit identity changes the digest; presentation-only
  styling does not. At opening, midpoint and final progress, the composed fan has the state's exact
  origin, angular bounds and outer reach, every boundary stroke lies inside it, the held-weapon
  pose follows its leading angle, and every damaging body primitive lies inside that target's
  combat disc; changing actor count does not change the fan's batch count.
- **P-64** Jump-required death loot: on a flat fixture, rank-and-file weapon and powerup drops and
  mini-boss/main-boss awards all rest exactly two tiles above the safe surface, with paired icons at
  the same y. Exhaustive grounded standing, running and crouching approaches under both icon
  positions collect nothing, while a normal held jump from rest through `MovementModel` collects
  the item. Fixtures with adjacent raised ground, a low ceiling and a sealed nearer platform reject
  sites that permit grounded contact, block the collecting jump or are player-inaccessible; an
  airborne Flyer over a lethal gap and a boss killed during a leap select the nearest valid site in
  the specified stable order, outside solid and lethal cells, and the same death state always
  selects the same position. Static drops and the starter cache retain their generated positions
  and grounded collection. A pressure-harness
  reference player takes a guaranteed award through that real jump/contact path before using its
  loadout, never by inventory injection. Seeded drop occurrence, contents and RNG state are
  unchanged apart from the new positions; those positions remain covered by P-40's ground-item
  digest, and P-52's hover changes neither them nor contact.

## Known gaps

- Every different-weapon pickup is taken (PROD-070), so an optional pickup can lower the player's
  damage and wipe their build; the loot floor accounts for guaranteed pickups but no test bounds
  how far an optional one can set a player back. Whether the drop table should stop rolling weapons
  below the held tier is a balance decision not yet taken.
- `WeaponScore` does not weigh lifesteal, bounce, seeking, slowing, reach, knockback, stun or kill
  refunds, so a guaranteed award can displace a slot whose unmeasured effect was worth keeping.
- A projectile landing applies neither crit, falloff nor Thermite Payload's on-hit blast (only
  instant patterns and swings do). Pre-existing; recorded here rather than changed under PROD-073.
- The Railgun's and Minigun's declared wind-ups are not paid: `AutoFire` fires on the cooldown
  alone, while `WeaponScore` charges the wind-up on every activation. Pre-existing; surfaced by
  gate 4 and not changed under PROD-075.
- The "≤ 60 live projectiles per weapon" cap is not implemented; only the 300-per-scene cap is,
  now for enemy shots as well as the player's. Pre-existing; surfaced by gate 4.
