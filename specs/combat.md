# Combat, Weapons and Powerups

The player carries one weapon. Powerups are **player-owned and weapon-applied**: five slots of up
to three stacks each, applied to the weapon held — and emptied whenever a pickup equips a
different weapon (PROD-070). A build is made around one weapon identity and survives picking up
another copy of that same weapon.

## The firing tick

The equipped weapon fires on its own cooldown using its class-specific target acquisition
(PROD-021, PROD-116). Cooldown is an accumulator in simulation time, so overshoot is never
discarded and rates do not drift:

```
cooldownLeft -= dt
while (cooldownLeft <= 0) { fire(); cooldownLeft += resolved.cooldown }
```

### Visible ranged target acquisition (PROD-116)

On every fixed tick while the equipped weapon is `WeaponClass.Ranged`, its aim target is the
closest eligible combat target whose canonical combat body is visible in the supplied gameplay
viewport. Eligible targets are living rank-and-file enemies and undefeated vulnerable mini-bosses
and main bosses. Visibility is exactly PROD-101's positive-area overlap between the interiors of
the combat body and viewport: partial overlap counts, while mere edge tangency does not.

Closest means the smallest squared Euclidean distance from the player's current weapon origin,
before an `Anchor.Cursor` pattern relocates its effect, to the target's current combat centre.
Exact distance ties use stable `CombatTargetId` order. The 22-tile awareness/legacy auto-aim radius
is not applied, so a target anywhere in an unusually wide or tall visible view remains eligible.
When none is visible, aim falls back to the player's current facing direction and never snaps to an
off-screen target.

This selection supplies straight, spread, lobbed, homing and cursor-anchored ranged patterns. A
trigger snapshots its selected aim under each pattern's existing rules: in particular, delayed
burst rounds retain the trigger tick's locked direction or lob intercept rather than selecting
again. PROD-101 still bounds every ranged result to the visible view. `WeaponClass.Melee`,
`WeaponClass.Psychic`, enemy and boss target acquisition keep their existing range and eligibility
rules.

## Weapon model

`combat.WeaponSpec` declares id, name, class, tier, damage, cooldown, range, projectile speed and
count, spread, burst interval, pierce, knockback, crit chance (base 5 %), anchor (`Self` |
`Cursor`), wind-up, falloff, homing, on-hit effects, on-fire effects and fire pattern. A
`FirePattern.Projectile` also declares gravity and lifetime; positive gravity identifies a lobbed
projectile, while zero gravity identifies an ordinary straight projectile.

**Spread and burst are alternatives (PROD-075).** A multi-projectile weapon with a `spread`
fires its projectiles at once, fanned evenly across the spread angle around the aim with the
outermost on its edges (five pellets at 30° sit at −15°, −7.5°, 0°, 7.5°, 15°) — a shotgun, a
nailgun. A weapon with a `burstIntervalSeconds > 0` is a **machine gun**: the trigger fires its
first round and queues the rest, one every interval, each leaving the muzzle where it is *then*
along the aim recorded when the trigger fell, so the rounds trail one another in a straight line
whatever the target does in the meantime. If a future lobbed weapon uses a burst, the recorded
aim is the stationary or led intercept point selected on the trigger tick rather than merely a
direction: each delayed round leaves the muzzle of its own tick on a newly solved arc toward that
unchanged point. A burst weapon declares no spread.
Every round is a
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
- **Ranged** spawns travelling projectiles that stop at terrain and obey falloff. Most travel
  straight; a positive-gravity projectile follows the ballistic-lob rule below. The Railgun and
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

## Miss-range feedback for exceptional melee attacks (PROD-115)

A player-melee activation requires fallback range feedback when either its fire pattern is not an
`ArcSwing` or its weapon declares a native chain or extra-target effect. Static Lash qualifies
because its native Shock can continue from a direct strike to one extra target, even though its
primary attack remains an `ArcSwing`. A powerup which does not give that weapon a functioning
melee-chain path does not make it qualify.

The fallback is emitted only when the complete activation has dealt no damage to any enemy or boss,
directly or secondarily. An immediate pattern therefore knows the result on its firing tick; an
`ArcSwing` cannot be called a miss until its complete live window has ended, because a target may
enter its already visible sector on a later tick. At that boundary the feedback snapshots the
attack's final origin, locked direction and exact resolved first-contact reach. For Static Lash the
reach is its direct 4 m reach after Ranger Optics, not the distance a successful Shock could travel
afterward. For a non-directional melee pattern the locked aim direction is still the direction of
the one-dimensional range ruler; it does not narrow or otherwise reinterpret that pattern's hit
region.

This snapshot is presentation-only. It adds no collision test, target, damage, status, chain jump
or persistence and is excluded from the determinism digest. The ordinary swoosh, ring or successful
chain presentation is neither replaced nor delayed.

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
ranged mean. PROD-109 multiplies every player melee weapon's base direct damage by exactly 1.5;
cooldown and every non-damage field are unchanged. Adjacent tier DPS bands may overlap after this
class-wide premium, while minimum, mean and maximum DPS still rise strictly by tier.

| Weapon | Class | T | Dmg | CD | DPS | Reach / range | Mechanic |
|---|---|---|---|---|---|---|---|
| Broken Bottle | Melee | 1 | 12 | 2.00 | 6.0 | 2.2 m, 70° | Starting weapon |
| Rustline Machete | Melee | 1 | 25.5 | 1.40 | 18.2 | 2.3 m, 80° | Bleed |
| Corpo Riot Baton | Melee | 2 | 25.5 | 1.10 | 23.2 | 2.2 m, 90° | Knockback, 0.3 s stun |
| Chrome Fang | Melee | 2 | 19.5×2 | 1.20 | 32.5 | 2.0 m, 35° | Two-hit combo |
| Static Lash | Melee | 3 | 36 | 0.90 | 40.0 | 4.0 m, 60° | Shocks one extra target |
| Gutterjack Cleaver | Melee | 3 | 63 | 1.30 | 48.5 | 2.4 m, 75° | Executes under 15 % HP |
| Kill-Switch Katana | Melee | 4 | 60 | 0.65 | 92.3 | 2.8 m, 50° | Dash-strike: the hitbox lunges 3 m, 0.2 s i-frames; the player does not move |
| Chromewreck Maul | Melee | 4 | 150 | 1.60 | 93.8 | 3.6 m, 100° | Shockwave, heavy knockback |
| Meatgrinder Halo | Melee | 5 | 60 | 0.35 | 171.4 | 2.8 m ring | Persistent saw ring |
| Scrapline Zip Pistol | Ranged | 1 | 7 | 0.80 | 8.8 | 20 m | Single slug |
| Tenement Nailgun | Ranged | 1 | 4×2 | 0.70 | 11.4 | 20 m | 12° spread, pierce 1 |
| Ganglord SMG | Ranged | 2 | 4×3 | 0.75 | 16.0 | 20 m | Machine gun: 3-round burst, 0.05 s apart, straight |
| Riotbreaker Shotgun | Ranged | 2 | 6×5 | 1.50 | 20.0 | 20 m | 30° cone, falloff past 5 m |
| Vulture Rail Carbine | Ranged | 3 | 28 | 1.00 | 28.0 | 20 m | Pierce 2 |
| Ashfall Grenade Lobber | Ranged | 3 | 33 | 1.40 | 23.6 | 20 m | Ballistic lob, 600 px/s² gravity; blast 2.5 m at 60 % |
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

### Lobbed projectiles (PROD-097, PROD-107)

Target acquisition first chooses the nearest visible eligible combat target under PROD-116 by its
current centre. Each rank-and-file enemy and boss also exposes an **aiming velocity**: its actual
combat-centre displacement during the most recently completed active fixed tick, divided by `dt`.
Actual walk, flight, leap, knockback and charge displacement all count. The velocity is zero when
the actor did not move during that tick and for a newly spawned actor with no completed movement
tick. This is future-affecting state under P-40.

At the trigger tick, a positive-gravity projectile snapshots an intercept rather than blindly
using the current centre when the selected target is moving. Let `p` be that centre, `v` its aiming
velocity, `dt` the fixed simulation step, `m` the round's muzzle, `s` its resolved projectile speed
and `g` its declared positive gravity. For each candidate whole-tick flight length `N`, the solver
uses the constant-velocity future point `pN = p + v × N × dt` and displacement
`(dxN, dyN) = pN - m`, then accepts the smallest `N` within the projectile's lifetime for which

```
N × dt >= max(length(dxN, dyN) / s, MIN_LOB_SECONDS)
vy0 = dyN / (N × dt) - g × dt × (N + 1) / 2
vy0 <= -MIN_LOB_UP_SPEED
```

where `MIN_LOB_SECONDS = 0.40 s` and `MIN_LOB_UP_SPEED = 120 px/s`; screen y increases downward.
It sets `vx0 = dxN / (N × dt)`. Each tick thereafter first applies any homing turn, then adds
`g × dt` to `vy`, then performs the swept terrain-and-actor move specified below. In an unobstructed,
non-homing fixture where the target continues at `v`, this semi-implicit update places projectile
and target centres together after exactly `N` ticks. A stationary target is the exact existing
`v = 0` solution. If no moving-target solution fits the lifetime, the solver falls back to the
stationary solution at `p`. If that stationary point itself cannot be reached within the declared
lifetime — possible because PROD-116 does not cap the distance of a visible target — the solver
keeps `p` as the selected aim and computes the smallest later whole-tick hypothetical arc that
satisfies the same nominal-speed and minimum-upward-speed constraints. The live grenade's declared
lifetime is not extended: it may expire or meet a PROD-101 view edge before reaching `p`. Target
selection and the player's aim direction never fall back to a nearer point. Ashfall declares
`gravity = 600 px/s²`; every other current player projectile declares zero.

The initial upward velocity is gameplay state, not a drawn offset: ceilings and walls can stop the
grenade, and an obstruction below a clear arc is passed over. Gravity continues after a Ricochet
ROM reflection. Ranger Optics' resolved speed participates in the flight-time bound; simultaneous
Fork Bomb rounds each use the same snapshotted intercept; spread, if a future lobber declares it, rotates the
solved initial velocity around the base arc. Seeker Daemon may steer a lob after launch and does
not suppress gravity. Later target motion never bends a launched grenade. A future lobbed burst
keeps the intercept selected on its trigger tick, and each delayed round solves from its then-current
muzzle to that unchanged point. `LiveProjectile` carries gravity and the determinism digest includes
it; a pending lobbed burst also carries its snapshotted intercept. Zero-gravity player projectiles
and all enemy and boss attacks retain their existing aim rules exactly. Damage, falloff, crit,
blast and all other landing rules are unchanged by this requirement, including the pre-existing
projectile-landing gaps recorded below.

### Swept projectile hits (PROD-098)

A player projectile tests the complete segment travelled by each movement piece, not only its
position after the tick. The existing projectile contact geometry is unchanged: a rank-and-file
enemy is contacted when the swept projectile-radius disc reaches its combat centre, and a boss is
contacted when that disc reaches the boss's existing body radius. Segment/disc tangency is a hit.
Movement remains divided into pieces no longer than half a tile for terrain, but hit reliability
does not depend on that implementation bound, projectile speed or fixed-step endpoint placement.

Contacts in one piece resolve by increasing first-intersection distance along the segment; exact
ties use stable `CombatTargetId` order. Each projectile carries the identities it has already hit
and may damage each target at most once, even when several movement pieces or later ticks remain
inside that target. Every accepted contact applies damage and consumes the existing pierce budget.
A spent projectile stops at its first disallowed continuation; a piercing projectile continues
from the contact along the remainder of the same movement piece. Terrain still wins at its actual
blocking face: no target whose first intersection lies beyond that face is hit. Ricochet begins a
new reflected segment but does not clear prior target identities.

This is collision correction only. It changes neither projectile speed nor radius, target
selection, damage, pierce caps, terrain reflection, homing, falloff, crit or on-hit behavior. In
particular, Ranger Optics may still raise the Sable Railgun from 1,400 px/s to 2,100 px/s, but that
larger per-tick displacement cannot skip a target. The already-hit identity set is future-affecting
`LiveProjectile` state and participates in P-40's digest.

### Visible-view ranged boundary (PROD-101)

Each fixed tick supplies the simulation with the immutable world rectangle of the current camera
view. The rectangle's interior is active space and all four edges are terminating boundaries. This
is a gameplay input for ranged attacks, not a canvas clipping rule.

Every travelling projectile fired by a `WeaponClass.Ranged` build is spent when its centre's swept
path first contacts an edge of that tick's rectangle. Its swept movement is clipped to that view
edge before actor collision is resolved beyond it, including when one tick would otherwise carry a
fast shot from visible space through an off-screen target. Reaching a view edge consumes the
projectile: a
lob cannot leave above the view and fall back in, Ricochet ROM cannot reflect from the edge, and
homing cannot turn a spent shot back into view. Terrain or a visible target that is reached earlier
still resolves normally; exact same-distance contact at the view edge resolves the boundary first.
A projectile already outside because the camera moved is spent before it can deal damage.

An enemy or boss is visible for this rule exactly when the interior of its canonical combat body
overlaps the viewport interior; mere tangency at an edge is off-screen. Every damage or status
application attributable to a ranged activation requires that visibility at the moment it
resolves. The gate therefore covers travelling-projectile contact, Kessler's target-anchored
strike, blast and chain targets, and other direct or immediate on-hit consequences. A burn or
bleed already applied while its target was visible continues under its normal duration if that
target later leaves the view, and life steal continues to count only damage actually dealt.

Ranged auto-aim uses this same visibility test under PROD-116, so it never selects a wholly
off-screen target and never applies the legacy 22-tile range cap to one that is visible.
`WeaponClass.Melee` and `WeaponClass.Psychic` activations are not view-bounded. Enemy and boss
projectiles and beams retain their terrain, player and level boundaries from PROD-092.

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
| Rank-and-file ramen | 12.5 %, flat and independent (PROD-110) | one grounded bowl of ramen |
| Static drop, mean 2 per map (PROD-047) | 100 % | same split; rarity rolled twice, keeping the better |
| Starter cache, map 1 before the midpoint | 100 % | weapon, tier ≥ T1 |
| Mini-boss | 100 % | weapon (tier ≥ T2); plus a powerup from map 4 |
| Main boss | 100 % | weapon (tier ≥ T3, +2 tier shifts) + powerup (tier ≥ T2) + Scrap |

### Grounded ramen drops (PROD-110)

Every resolved rank-and-file death consumes exactly one draw from its map's dedicated `ramen`
stream. Exactly one of the stream's eight equiprobable outcomes creates one bowl; mini-boss and
main-boss deaths consume no ramen draw. This roll is independent of PROD-046's weapon/powerup roll,
so either, neither or both drops can result from the same enemy, and adding ramen does not change
combat, cache, weapon/powerup occurrence or rarity draws.

A ramen bowl uses a grounded death site rather than PROD-090's raised death-drop site. The primary
candidate preserves the slain enemy's centre x, projects it onto the nearest safe support below,
and puts its pickup point at the centre of the clear cell immediately above that support. The site
must be on a player-reachable support, outside blocking, lethal and damaging-hazard cells, and
collectible from a collision-free grounded pose. If that projection is invalid, safe reachable
sites are ordered by horizontal distance from the death, then vertical distance, column and row;
the first valid site wins. Placement consumes no randomness. The chosen position is fixed, has no
falling physics and is within the existing strict `PICKUP_REACH` contact radius of an ordinary
grounded walk-over pose. A weapon or powerup produced by the same death independently keeps its
raised PROD-090 position.

Contact removes the bowl and heals exactly `0.05 × maxHealth` as calculated from the map-independent
baseline and permanent upgrades at that tick, capped at `maxHealth`. Fractional health is retained. The
bowl is still consumed and starts its green feedback when the player is already at full health. It
does not enter the loadout, award Scrap or create a first-discovery card; it emits the ordinary one
per-item `PickupPulse`. Its presence, position and payload, and the ramen stream state, are
rule-bearing deterministic state. Its green feedback timer is presentation-only.

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

- **P-14** Weapon registry: ≥ 20 weapons and all three classes; minimum, mean and maximum DPS are
  strictly increasing by tier, though adjacent tier bands may overlap; every entry has a finite
  score against the reference target.
- **P-15** Powerup registry: ≥ 15 entries, each with a tier and a scalar magnitude whose stack
  curve is never super-linear (`v(2) ≤ 2·v(1)`, `v(3) ≤ 3·v(1)`); every weapon × every powerup at
  every stack count resolves to a `ResolvedWeapon` with finite, positive damage and cooldown;
  interpolated tier weights are strictly decreasing in tier at every map index.
- **P-16** Cooldown fidelity (simulation.md).
- **P-37** Melee class: every melee weapon's reach is ≥ 2 m and greater than the enemy swing
  reach; in every tier that holds both classes, mean melee DPS excluding the Broken Bottle exceeds
  mean ranged DPS; the bottle's 6.0 DPS meets map 1's required rate.
- **P-86** Player-melee damage: the registered base direct damage of Broken Bottle, Rustline
  Machete, Corpo Riot Baton, Chrome Fang, Static Lash, Gutterjack Cleaver, Kill-Switch Katana,
  Chromewreck Maul and Meatgrinder Halo is exactly 1.5 times its pre-PROD-109 value. Projectile
  count, cooldown, reach, arc, linger, wind-up, knockback, status magnitudes, execute threshold and
  other mechanics are byte-for-byte-equivalent controls. Damage fractions subsequently derived
  from the direct hit use the larger base; fixed bleed, stun, execute and knockback values do not
  receive a second multiplier.
- **P-25** Kill drop rate is 0.20 at every map index, three in ten of them weapons; static drops
  average 2.0 ± 0.15 per map over a seed cohort, each count in {1, 2, 3}.
- **P-87** Ramen drop and collection: over each map index a seeded cohort's rank-and-file death
  sequence consumes one `ramen` draw per death and produces exactly the stream's one-in-eight
  successes, while mini-boss and main-boss deaths consume none. Forcing any combination of ramen
  and PROD-046 outcomes proves neither roll changes the other and both drops coexist when both
  succeed. A flat-ground death places the ramen's pickup point in the clear cell directly above the
  support at the enemy's centre x; a grounded approach collects it without a jump. Airborne,
  over-hazard, blocked and unreachable projections choose the first safe reachable fallback in the
  declared stable order without consuming RNG, while a simultaneous weapon or powerup remains at
  its jump-required site. At 40/100 health the bowl raises health to 45, at 98/100 it raises health
  to 100, and at 100/100 it remains 100; all three remove exactly that bowl, start heal feedback
  and emit one `PickupPulse`, without changing the loadout, Scrap or discoveries. Ramen items,
  positions and RNG state change P-40's digest; heal feedback does not.
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
- **P-71** Ballistic lob: Ashfall's registry pattern is the only current player projectile with
  positive gravity, exactly `600 px/s²`; every current straight ranged and psychic projectile and
  every enemy or boss projectile remains at zero. For same-height, higher and lower targets on
  both sides of the player, the whole-tick solver produces an initial `vy <= -120 px/s`, applies
  exactly `gravity × dt` downward per tick after any homing turn, crosses an apex, and reaches the
  snapshotted stationary point on tick `N` in an unobstructed non-homing fixture. A moving target
  does not bend an already launched grenade. A low obstruction beneath the swept arc is cleared, while a
  terrain cell intersected by the arc stops or reflects it under the existing bounce rule.
  Ranger Optics changes the nominal flight bound, Fork Bomb rounds share the intercept, and Seeker
  Daemon and Ricochet ROM retain gravity. A Zip Pistol control keeps its existing constant-velocity
  path exactly. Changing live gravity or a pending lob aim point changes P-40's digest;
  changing only its drawn marks does not.
- **P-83** Moving-target lob lead: horizontal, vertical and diagonal constant-velocity enemy and
  boss fixtures expose their most recently completed actual movement and an unobstructed Ashfall
  grenade meets each continuing target centre on the solver's selected whole tick. A stationary
  target exactly preserves P-71's launch; a newly spawned or stopped target reports zero velocity;
  walk, flight, leap, knockback and boss charge displacement update the snapshot. If the moving
  intercept exceeds lifetime the stationary fallback is used; if that point is also beyond the
  live lifetime, its later hypothetical arc is used without extending the projectile. PROD-116
  target selection remains based on current centres, simultaneous Fork Bomb rounds share one
  intercept, and changing target motion after launch does not bend the grenade. Zip Pistol retains
  its straight trajectory and enemy/boss-shot controls retain their prior aim. Changing an actor's
  aiming velocity or a pending lob intercept changes P-40's digest.
- **P-72** Swept player-projectile collision: fixtures place a rank-and-file enemy wholly between
  the Sable Railgun's start and end positions for one tick at its base 1,400 px/s and at the
  Ranger-Optics maximum 2,100 px/s; both take exactly one hit. Tangency hits and one epsilon of
  clearance misses. Three non-overlapping targets crossed in one tick resolve in geometric travel
  order and only as far as the existing pierce budget permits; reversing insertion order does not
  change that result. A target overlapped across multiple movement pieces or ticks takes only one
  hit from that projectile. A wall before the target blocks it, a target before the wall is hit,
  and a Ricochet ROM reflection cannot hit an already struck target again. The same outcomes hold
  at fixed-step boundaries on JVM and Wasm. Mutating a live projectile's already-hit identities
  changes P-40's digest.
- **P-75** Visible-view ranged boundary: straight, maximum-speed, lobbed, homing, piercing and
  terrain-bouncing ranged projectiles are spent at the first left, right, top or bottom viewport
  edge and cannot re-enter; a target before that edge is hit and one wholly beyond it is not,
  including when both lie in one tick's swept segment. A wall before the edge still blocks or
  reflects normally, while an exact boundary tie spends the shot. Kessler, blast, chain, ignite
  and other immediate ranged consequences exclude wholly off-screen targets but include a combat
  body with positive-area overlap inside the view; an already-applied status may keep ticking after
  its target leaves. Psychic and melee player attacks and enemy and boss shots are unchanged
  controls. Equal initial state, input tape and viewport tape produce equal outcomes on JVM and
  Wasm, while changing the viewport tape can change them.
- **P-97** Visible ranged auto-aim: with a ranged weapon equipped, a fixture containing a closer
  wholly off-screen enemy and a farther visible enemy beyond 22 tiles aims and fires toward the
  visible enemy. Among several visible living enemies and vulnerable undefeated bosses it chooses
  the smallest firing-origin-to-current-centre squared distance; reversing insertion order does
  not change the result, and an exact distance tie uses stable `CombatTargetId` order. A combat
  body with positive-area viewport overlap is eligible while mere edge tangency is not. With no
  visible eligible target the aim follows the player's facing direction and no off-screen target
  is selected. Straight, spread, Ashfall lob and Kessler cursor-anchor fixtures consume this same
  selection; an Ashfall target beyond its live lifetime produces the later hypothetical arc without
  extending that lifetime or throwing, and a timed burst keeps its trigger-locked aim if the target
  or viewport later changes. Melee and psychic controls retain their existing 22-tile selection
  rule, and enemy and boss aim is unchanged. Equal state, input and viewport produce equal target
  identity and aim on JVM and Wasm; changing only the viewport may change them.
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
