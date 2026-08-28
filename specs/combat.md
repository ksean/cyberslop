# Combat, Weapons and Powerups

The player carries one weapon. Powerups are **player-owned and weapon-applied**: five slots of up
to three stacks each, re-applied to whatever weapon is held, never lost on a weapon swap.

## The firing tick

The equipped weapon fires on its own cooldown at the nearest valid target (PROD-021). Cooldown is
an accumulator in simulation time, so overshoot is never discarded and rates do not drift:

```
cooldownLeft -= dt
while (cooldownLeft <= 0) { fire(); cooldownLeft += resolved.cooldown }
```

## Weapon model

`combat.WeaponSpec` declares id, name, class, tier, damage, cooldown, range, projectile speed and
count, spread, pierce, knockback, crit chance (base 5 %), anchor (`Self` | `Cursor`), wind-up,
falloff, homing, on-hit effects, on-fire effects and fire pattern.

Class is load-bearing:

- **Melee** resolves an arc swing immediately: targets within reach and inside the arc, up to its
  pierce count; Mass Driver widens the arc's hitbox.
- **Ranged** spawns travelling projectiles that stop at terrain and obey falloff; the Railgun and
  Minigun declare a wind-up.
- **Psychic** projectiles and blasts pass through terrain.

Powerups apply through `DamagePipeline.resolve`, which turns the held weapon and the slots into
one `ResolvedWeapon` (damage, cooldown, projectile count, pierce, crit, chain, ricochet, homing,
hitbox and reach scale, knockback, stun, slow, blast, ignite, lifesteal, kill refund). Every
powerup resolves for every weapon; a field a pattern cannot use (extra projectiles on a blast)
simply has no effect there.

## Swapping on contact

Contact always resolves (PROD-030). A weapon on the ground is compared to the held one with the
player's current build:

```
score(w) = WeaponScore(w, slots, mapIndex)     // resolved damage × crowd factor, conditional terms
if score(ground) > score(held) swap, previous weapon → Scrap; else ground weapon → Scrap
```

Conditional terms resolve against a **reference target**: one enemy at 60 % of the current map's
trash health, 4 m away, unslowed, unstunned, full uptime, damage-over-time at full expected value.
Tier governs drop rarity only, never swapping.

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
| Ganglord SMG | Ranged | 2 | 4×3 | 0.75 | 16.0 | 20 m | Burst, 10° bloom |
| Riotbreaker Shotgun | Ranged | 2 | 6×5 | 1.50 | 20.0 | 20 m | 30° cone, falloff past 5 m |
| Vulture Rail Carbine | Ranged | 3 | 28 | 1.00 | 28.0 | 20 m | Pierce 2 |
| Ashfall Grenade Lobber | Ranged | 3 | 33 | 1.40 | 23.6 | 20 m | Blast 2.5 m at 60 % |
| Sable Corp Railgun | Ranged | 4 | 95 | 1.70 | 55.9 | 20 m | Infinite pierce, 0.4 s wind-up |
| "Debt Collector" Minigun | Ranged | 4 | 7 | 0.12 | 58.3 | 20 m | 0.6 s wind-up, 20° bloom |
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
| Red Market Siphon | 2 | Lifesteal (cap 4 HP/hit, 12 HP/s) | 2 % | 3.5 % | 4.5 % | add |
| Mass Driver | 2 | Hitbox / arc width | +25 % | +45 % | +60 % | mult |
| Overclock Coil | 3 | Cooldown reduction | 12 % | 21 % | 28 % | mult |
| Chill Protocol | 3 | Enemy speed, 2 s | −18 % | −30 % | −38 % | mult |
| Burn Rig | 3 | Ignite, % damage/s for 3 s | 15 % | 25 % | 32 % | add |
| Ricochet ROM | 3 | Bounces at 85 % | 1 | 2 | 3 | add |
| Seeker Daemon | 4 | Homing turn rate (°/s) | 90 | 160 | 210 | add |
| Arc Cascade | 4 | Chain targets | 1 @50 % | 2 @45 % | 3 @40 % | add |
| Brownout Charge | 4 | Expected stun-seconds | 0.048 | 0.090 | 0.132 | add |
| Fork Bomb | 5 | Effective projectile gain | +0.70 | +1.20 | +1.65 | add |
| Thermite Payload | 5 | On-hit blast, % of damage | 35 % | 45 % | 55 % | add |
| Killstreak Cache | 5 | On kill: chance to clear cooldown | 15 % | 25 % | 35 % | event |

Each run draws **8 of the 18** powerups, tier-weighted, as its drop pool, so duplicates are common
enough for stacking to happen.

## Damage formula and caps

```
raw   = base × (1 + Σ additive) × Π multiplicative × splitFactor
crit  = rand() < min(critChance, 0.75) ? (2.0 + Σ critBonus) : 1.0
splitFactor = (n + extraProjectiles × pct) / n        // n = the weapon's own projectile count
cooldown    = clamp(base × Π speedMults, max(0.08, base × 0.35), base × 2.0)
```

Caps, each tested: crit chance ≤ 75 %; cooldown floor `max(0.08 s, 0.35 × base)`; enemy speed
floor 40 %, multiple slows take the max rather than the product; bosses are immune to slows; live projectiles ≤ 60 per weapon and 300 per
scene (a performance bound); chain, ricochet, fork and blast each carry a per-activation target
set, blasts cannot trigger blasts, free recasts cannot recurse; lifesteal is capped per hit and per
second.

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

Scrap per displaced item by tier: 8, 20, 45, 100, 240.

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

## Known gaps

- `WeaponScore` and `expectedDps` order weapons differently, so an accepted optional weapon swap
  can lower single-target damage (measured: 381 of 3,243 accepted swaps across ten maps). Closing
  it is a balance change: recalibrate the score's crowd and conditional terms, or measure the loot
  floor in the units swaps are decided in.
- `WeaponScore` does not weigh lifesteal, seeking, slowing, reach, knockback, stun or kill refunds,
  so a guaranteed award can displace a slot whose unmeasured effect was worth keeping.
