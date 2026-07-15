# Project Cardinal â€” Design system (Phase 5)

*Status: ROUND 2, 2026-07-14. Round 1 (flat themable panels) REJECTED by Jack:
"blocky, not seamless/smooth." Direction now locked: **Forge's familiar
battlefield arrangement with a Hearthstone/Arena-grade facelift** â€” a facelift,
not a reinvention. Living companion: the interactive design lab artifact â€”
iterate there, record decisions here. This file is the contract the Compose
client implements.*

## Core principle: one physical object, not a grid of panels

The play screen reads as a **table** â€” wooden rim, felt top, soft sheen â€” and
every UI element floats on it as a physical thing: medallions, gems, coins,
one big golden button. Rules that make the "smooth" read:

- **Zero hard-edged rectangles.** No 1px-bordered panels. Radii are large
  and organic; depth comes from layered soft shadows + inner highlights,
  never from outlines.
- **Circular anchors.** Hero portraits, life gems, zone-count coins, mana
  crystals, the pass button, the log scroll â€” round is the default shape.
- **One accent metal.** Warm gold trim (#d9b565 family) on everything
  interactive; it is the "you can touch this" signal.
- **Soft motion.** Playable cards pulse gently; hand cards rise on hover;
  the pass button swells. Nothing snaps, everything eases.

## Layout contract (play screen â€” Forge's arrangement, kept)

```
  hero:opponentâ—‹         opponent lands+mana row
   (portrait,            opponent creatures row
    life gem,     ~~~~~ the river Â· turn medallion Â· phase pips ~~~~~
    zone coins)          player creatures row                 ( PASS )
  hero:youâ—‹              player lands+mana row                 big gold
   + mana crystals              hand, fanned over the table edge
```

- **Hero medallions** (left edge, opponent top / player bottom): circular
  portrait, life gem (player warm red / opponent cool blue), zone-count
  coins (deck/grave/hand), mana crystals under the player's.
- **The river**: a soft glowing midline, not a bar. Center turn medallion
  shows "Turn N Â· Phase" plus phase pips; right-click (long-press) opens
  stop/auto-pass settings. Replaces round 1's phase ladder.
- **The one big button** (right center, Hearthstone's signature): context-
  aware label â€” PASS / ATTACK / BLOCK / END TURN. The hand rests here all game.
- **Log/chat**: collapsed to a scroll medallion on the left edge; expands on
  hover/tap. No permanent rail.
- **Hand**: fanned arc over the bottom table edge; hover raises + zooms.
- **Battlefield rows**: lands+mana in the back row, creatures forward â€”
  combat happens across the river. Exactly how Forge players already read
  the board.

## Interaction states (all driven by existing protocol messages)

| State | Visual | Protocol trigger |
|---|---|---|
| Playable / priority | accent glow | `updateButtons` + playability from game view |
| Selectable | accent glow (weak: outline) | `setSelectables` / `setWeaklySelectable` |
| Selected | gold outline | client-side, confirmed by delta |
| Attacking | red glow + slight rise | combat view deltas |
| Blocking | blue glow | combat view deltas |
| Tapped | 90Â° rotation | `Tapped` property delta |
| Current phase | lit ladder entry | `Phase` property delta |
| Prompt | text in log rail header + buttons | `showPromptMessage` / `updateButtons` |

## Tokens (v2 names)

`roomVignette, tableRim, felt, riverGlow, goldTrim, goldBright, textWarm,
textDim, lifeYou, lifeOpp, manaCrystal, playableGlow (green), attackGlow (red),
blockGlow (blue), selectedOutline (gold), cardFrame, displayFont, uiFont`

Board *material* (felt vs parchment) is the only planned skin dimension â€”
one token swap, decided below. Round 1's three-theme system is retired.

## Invariant rules

- Card aspect ratio is exactly 63:88. Card images from Scryfall are rendered
  complete and uncropped, artist/copyright line visible (API terms).
- Life colors: player warm (red family gem), opponent cool (blue family gem).
- Attacker red / blocker blue / playable green / selected gold are game
  semantics â€” never reskinned.
- Type: serif display face (Palatino family until a licensed face is chosen)
  for names, life numbers, the medallion; system UI face for log/body.

## Open decisions (answer in the design lab, record here)

1. Board material: green felt (as mocked) vs parchment (library mockup vibe).
2. Opponent hand: fanned card backs at the top edge vs hand-count coin only.
3. Life gems on portraits (as mocked) vs also restoring the center life bar
   from Jack's original mockups.
4. The big button: one context-aware button (PASS/ATTACK/BLOCK/END TURN,
   as mocked) vs separate buttons.

## Baseline v0 (2026-07-14)

Captured from Jack's screenshot of a live match (mulligan decision, Human vs
Soderman) and reconstructed in the design lab. Region vocabulary for the
part-by-part rebuild:

1. Window chrome (Forge pill, Home/Deck Editor/match tabs)
2. Stack/Combat/Log/Dependencies tab stack
3. Dock (utility buttons)
4. Prompt panel (engine dialog + Keep/Mulligan/OK/Cancel)
5. Player sidebar (avatar, life, zone counts, mana pool) Ã—2
6. Phase-stop strip (UP/DR/M1/... â€” cyan = stop) Ã—2
7. Battlefield surface Ã—2
8. Hand panel
9. Card Detail panel
10. Card Picture panel
11. Global (background texture, palette, fonts) â€” last

Process: one region per round, Jack approves before the next; approved
values (colors/sizes/spacing) get recorded here per region.

## Screen inventory (whole-program plan)

Every screen gets the same loop: Jack screenshots Forge's version â†’ faithful
baseline reconstruction in the design lab â†’ facelift one region per round â†’
approved spec recorded here â†’ implemented in Compose (Phase 6). We copy
Forge's *design*, never its Swing code (incompatible toolkit, and it would
re-couple UI to engine internals, breaking the JSON-client architecture).

| # | Screen | Source | Status |
|---|---|---|---|
| S1 | Match screen | Forge match UI | ðŸ”µ baseline v0 done, region rounds next |
| S2 | Home / play setup (mode, decks, start) | Forge home | â¬œ awaiting screenshot |
| S3 | Deck editor + collection browser | Forge deck editor | ðŸ”µ base v0 done from Jack's 6 screenshots (editing state mocked; decks-list / commander / statistics / draw-order states inventoried). Regions: D1 filters+search, D2 catalog table, D3 current-deck panel, D4 format sub-tabs, D5/D6 shared right rail. |
| S4 | Deck import (paste / URL) | ours (no Forge equivalent this shape) | ðŸ”µ base v0 done (drawn in Forge's visual language; mirrors Phase 4 CLI: paste box, URL field, fidelity report, save) |
| S5 | Settings | Forge preferences | â¬œ awaiting screenshot |
| S6 | Draft / sealed flow | Forge limited UI | â¬œ later (with limited modes) |
| S7 | Multiplayer lobby | Forge net lobby | â¬œ Phase 7 |
| S8 | Achievements | Forge achievements | â¬œ OPTIONAL â€” Jack to decide if it ships |

Cut with their modes (never designed): Quest, Adventure, Planar Conquest
screens.

## Decisions log

- 2026-07-14 â€” Round 1 flat-panel design rejected (blocky).
- 2026-07-14 â€” Round 2 (Hearthstone-style table/medallions) ALSO rejected.
  **Both invented looks erased. New method locked by Jack: copy Forge's real
  match UI from screenshots as baseline v0, then rebuild it one region at a
  time, each change approved before the next.** Everything above this log
  describing round-2 visuals is superseded and kept only as history; the
  invariant rules (63:88 uncropped cards, fixed semantic colors) still hold.
