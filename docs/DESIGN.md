# Project Cardinal — Design system (Phase 5)

*Status: BASELINE PHASE, 2026-07-14. Method locked by Jack: copy Forge's real
UI from screenshots as faithful baselines, then rebuild one region per round,
each change approved before the next. Living companion: the interactive design
lab artifact — iterate there, record decisions here. This file is the contract
the Compose client implements.*

## Interaction states (all driven by existing protocol messages)

| State | Visual (final look TBD in facelift rounds) | Protocol trigger |
|---|---|---|
| Playable / priority | accent highlight | `updateButtons` + playability from game view |
| Selectable | accent highlight (weak variant) | `setSelectables` / `setWeaklySelectable` |
| Selected | gold outline | client-side, confirmed by delta |
| Attacking | red highlight | combat view deltas |
| Blocking | blue highlight | combat view deltas |
| Tapped | 90-degree rotation | `Tapped` property delta |
| Current phase | lit phase indicator | `Phase` property delta |
| Prompt | prompt panel text + buttons | `showPromptMessage` / `updateButtons` |

## Invariant rules

- Card aspect ratio is exactly 63:88. Card images from Scryfall are rendered
  complete and uncropped, artist/copyright line visible (API terms).
- Life colors: player warm (red family), opponent cool (blue family).
- Attacker red / blocker blue / playable green / selected gold are game
  semantics — never reskinned.

## Baseline S1 · Match screen (2026-07-14)

Captured from Jack's screenshot of a live match (mulligan decision, Human vs
Soderman) and reconstructed in the design lab. Region vocabulary:

1. Window chrome (Forge pill, Home/Deck Editor/match tabs)
2. Stack/Combat/Log/Dependencies tab stack
3. Dock (utility buttons)
4. Prompt panel (engine dialog + Keep/Mulligan/OK/Cancel)
5. Player sidebar (avatar, life, zone counts, mana pool) x2
6. Phase-stop strip (UP/DR/M1/... — cyan = stop) x2
7. Battlefield surface x2
8. Hand panel
9. Card Detail panel
10. Card Picture panel
11. Global (background texture, palette, fonts) — last

Process: one region per round, Jack approves before the next; approved
values (colors/sizes/spacing) get recorded here per region.

## Screen inventory (whole-program plan)

Every screen gets the same loop: Jack screenshots Forge's version → faithful
baseline reconstruction in the design lab → facelift one region per round →
approved spec recorded here → implemented in Compose (Phase 6). We copy
Forge's *design*, never its Swing code (incompatible toolkit, and it would
re-couple UI to engine internals, breaking the JSON-client architecture).

| # | Screen | Source | Status |
|---|---|---|---|
| S1 | Match screen | Forge match UI | 🔵 base v0 done, region rounds next |
| S2 | Home / play setup (mode, decks, start) | Forge home | 🔵 base v0 done from Jack's screenshot. Regions: H1 mode sidebar (cut modes drop in Cardinal), H2 variants, H3 player seats, H4 deck picker, H5 start bar. |
| S3 | Deck editor + collection browser | Forge deck editor | 🔵 base v0 done from Jack's 6 screenshots (editing state mocked; decks-list / commander / statistics / draw-order states inventoried). Regions: D1 filters+search, D2 catalog table, D3 current-deck panel, D4 format sub-tabs, D5/D6 shared right rail. |
| S4 | Deck import (paste / URL) | ours (no Forge equivalent this shape) | 🔵 base v0 done (drawn in Forge's visual language; mirrors Phase 4 CLI: paste box, URL field, fidelity report, save) |
| S5 | Settings | Forge preferences | ⬜ awaiting screenshot |
| S6 | Draft / sealed flow | Forge limited UI | ⬜ later (with limited modes) |
| S7 | Multiplayer lobby | Forge net lobby | ⬜ Phase 7 |
| S8 | Achievements | Forge achievements | ⬜ OPTIONAL — Jack to decide if it ships |

Cut with their modes (never designed): Quest, Adventure, Planar Conquest
screens.

## Decisions log

- 2026-07-14 — Round 1 (flat themable panels) rejected by Jack: blocky.
- 2026-07-14 — Round 2 (Hearthstone-style table/medallions) also rejected.
  Both invented looks erased. Method locked: copy Forge's real UI, rebuild
  region by region with approval gates. Invariant rules survive the reset.
- 2026-07-14 — S1/S3/S4/S2 baselines built (S2 note: Quest/Puzzle/Gauntlets
  entries leave the sidebar in Cardinal along with their modes).
