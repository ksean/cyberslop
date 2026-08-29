# Persistent Progression and Discovery

Persistent progression belongs to the browser profile, not to one run. A run accumulates Scrap;
ending it banks that Scrap once. The profile owns the balance the shop can spend, the lifetime
total that opens the weapon pool, permanent upgrade ranks and the item ids already introduced to
the player (PROD-031, PROD-081..083).

## Profile and Scrap

The platform-independent profile contains:

- `spendableScrap`, a non-negative balance;
- `lifetimeScrap`, the amount ever banked, which never decreases;
- one rank for each catalog upgrade;
- the sets of discovered `WeaponId`s and `PowerupId`s.

A new profile starts with both Scrap counters at zero, every upgrade at rank zero, and only the
Broken Bottle discovered: it is in the player's hand from the start rather than first encountered
as a pickup. When a run ends in death or victory, its run Scrap is added once to both counters.
Scrap in an in-progress run is neither spendable nor part of the lifetime total until that run
ends. Every positive change to that in-progress counter produces the transient `+X` feedback in
presentation.md; banking, migration and shop balance changes happen outside active gameplay and do
not. Buying an upgrade subtracts only from `spendableScrap`, so it cannot reduce
`unlockedWeapons = min(8 + floor(lifetimeScrap / 400), 26)` or relock an item.

The profile is the canonical source of persistent state and is saved immediately after banking a
run, buying an upgrade or recording a discovery. A valid in-progress run is stored separately; a
profile write must not create, replace or clear one. A shop purchase made while `Continue game` is
available applies when that run is continued. Current health remains the same absolute value when
a health rank is bought; the higher maximum applies immediately and the next map entry restores
to that higher maximum.

The profile format is versioned and rejects a malformed record as a whole. Migration preserves
existing players: a legacy metadata record containing one Scrap integer, or the meta-Scrap field
of a version-2 in-progress save, becomes both counters with upgrade ranks zero and the Broken
Bottle discovered. If both legacy sources exist, the greater non-negative Scrap value wins, so an
older run snapshot cannot roll back already banked progression. The version-2 run itself remains
resumable after migration; unknown versions or invalid fields are still refused (PROD-032).

## Shop

`Shop` is always present on the title screen, including at zero Scrap and alongside
`Continue game`. It opens a DOM screen in the title screen's visual identity. The balance, current
rank out of five, current total effect and price of the next rank are readable without colour and
exposed as text to assistive technology. Each available purchase and `Back` are real buttons in a
predictable keyboard order. A maximum-rank row says `Max rank` and cannot be bought; an
unaffordable row remains visible with its price but cannot be bought.

Death and victory bank the run before drawing their end screen. That screen offers `Return to
title`; using it shows the title with the new balance available to `Shop` and must not start or save
a replacement run. The existing `New game` action may remain as a shortcut, but it does not replace
the route through the title and shop.

All three tracks have five ranks and use the same prices for ranks 1 through 5: **100, 250, 500,
1,000 and 2,000 Scrap**.

| Upgrade | Effect per rank | Rank-five total | Applies to |
|---|---:|---:|---|
| Reinforced Chassis | +10 % maximum health | +50 % | `Balance.playerMaxHealth(mapIndex)` before a run starts or a map restores health |
| Black-Market Firmware | +5 % weapon damage | +25 % | the resolved player weapon hit, before its blast, chain, ignite and life-steal consequences |
| Reactive Dermal Weave | −5 % incoming non-lethal damage | −25 % | enemy and boss attacks, enemy contact, spike strips and burning barrels |

Bonuses combine only within their own formula: maximum health and weapon damage multiply their
unupgraded values by `1 + rate × rank`; non-lethal incoming damage multiplies by
`1 − 0.05 × rank`. The weave never changes acid, void or fire-jet contact: those hazards remain
lethal. Registry DPS, rarity, powerup displacement and the map verifier continue to use the
unupgraded baseline, so no generated route ever requires a purchase and a purchase never changes
which item is judged better than another.

A purchase checks the current saved balance and rank as one transition. On success it subtracts
exactly the displayed price, raises exactly that track by one and persists before the screen is
redrawn. Double activation, insufficient Scrap, an unknown upgrade or a rank already at five
returns the unchanged profile; the balance can never become negative.

## First-pickup discovery cards

Every registered weapon and powerup has one authored discovery entry: its id, display name, the
same icon geometry and materials used on the ground, in the hand and in the HUD, and one
present-tense sentence of at most 140 characters describing its defining mechanic. The picture is
large enough to recognise and has no ground-drop kind ring or rarity pips. Copy must agree with the
actual registry rather than with a generic class description. For example:

- **Riotbreaker Shotgun:** `Fires five projectiles at once in a 30° spread.`
- **Red Market Siphon:** `Heals you for a fraction of the damage dealt by every weapon hit.`

Pickup resolution is not delayed or undone. A contacted powerup counts as collected for discovery
whether it is applied, displaces a slot or converts to Scrap. After a tick collects an id absent
from the profile, the id is added and persisted, then a discovery card is queued. A paired award
queues its weapon first and its powerup second. Duplicate ids in the same resolution queue only
once. An id already in the profile produces no card and no pause, including after starting a new
run or reloading the page. Clearing the browser's site data creates a new profile and therefore
resets discoveries.

While a card is visible, the simulation executes no ticks, but rendering continues with the card
centred above a dimmed game frame. Its name and description are also announced through the game's
live region. One card remains for **3.0 seconds accumulated only while the page is visible and its
window focused**; background time does not consume the interval. The next queued card then starts
its own interval, or play resumes. Gameplay bindings received during the pause are ignored, and
held/latched input is cleared both when the first card opens and when the last closes, so a held
direction or Space cannot move or jump on resume.

## Verified properties

- **P-56** Profile and shop: banking `n` Scrap once raises both counters by `n`; buying all five
  ranks of one track charges exactly `100 + 250 + 500 + 1000 + 2000`, never lowers lifetime Scrap
  or the unlocked weapon count, and a repeated, unaffordable, unknown or rank-six purchase is
  unchanged. Each rank produces exactly its specified health, weapon-damage and
  non-lethal-damage multiplier; lethal hazards stay lethal. The current profile round-trips
  byte-for-byte, the legacy integer and a version-2 run migrate without losing Scrap or the run,
  and malformed or unknown versions are rejected without partial state.
- **P-57** Discovery: the discovery registry is total over every weapon and powerup id, uses each
  item's icon, and has a non-blank sentence no longer than 140 characters. A first weapon pickup
  records and queues it after equipping; applied, displaced and scrapped first powerups each record
  and queue; the same id in a fresh run queues nothing; a first paired award queues exactly weapon
  then powerup. During each three-second active interval simulation tick count and digest do not
  move, background time does not expire it, the card is rendered and announced, and input held or
  pressed during the interval is absent on the first resumed tick.
