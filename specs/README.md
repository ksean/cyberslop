# Specifications

This directory is the source of truth for what Cyberslop is and how it is built. `README.md` at the
repository root is a short human introduction and is not normative; `plan.md` is a living work
plan and is not a source of requirements.

Requirement words **must**, **should** and **may** mean mandatory, recommended and optional.
Requirements carry stable IDs (`PROD-nnn`, `ENG-nnn`) and verified properties carry stable IDs
(`P-nn`) so that specifications, code comments and tests can cite one another. Specifications
define required behaviour; where the code does not yet meet a specification, the gap is an open
entry in `tasks.md`. Specifications carry no history; history lives in git.

## Documents

| Document | Covers |
|---|---|
| [product.md](product.md) | Player-facing requirements: runtime, controls, run structure, content, presentation |
| [engineering.md](engineering.md) | Technology, architecture, code quality, verification layers, review process |
| [simulation.md](simulation.md) | The movement model, its constants and measured envelope, assists, determinism |
| [completability.md](completability.md) | The completability guarantee, the two reachability analyses, hazards, enemy placement, failure handling |
| [generation.md](generation.md) | The generation pipeline, the ten sub-themes, the difficulty curve and score |
| [combat.md](combat.md) | Weapons, powerups, the damage formula and its caps, drops and rarity |
| [progression.md](progression.md) | Persistent Scrap, the title-screen shop, permanent upgrades and first-pickup discovery cards |
| [enemies.md](enemies.md) | Enemy archetypes and behaviour, mini-bosses and bosses, balance calibration, the loot floor |
| [hazards.md](hazards.md) | Map hazards: acid, fire jets, spike traps, burning barrels and their contact rules |
| [presentation.md](presentation.md) | The draw list, character rigs and animation, enemy looks, palettes, icons, camera, browser rendering |
| [iconography.md](iconography.md) | The intended silhouette of every weapon and powerup icon |

## Change workflow

Every functional change has two phases:

1. **Specify.** Update the relevant specification documents and add the implementation work to
   `tasks.md`.
2. **Implement.** Only after the user explicitly approves implementation. Record the approval in
   `tasks.md`, then work test-first: failing test, smallest passing change, `./scripts/check.sh`.

Approval to draft or discuss a specification is not approval to implement. A user may grant
implementation approval in advance, in the request that asks for the plan; when they do, record
that in `tasks.md` and proceed.
