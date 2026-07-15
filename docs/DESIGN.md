# Project Cardinal — Design system (Phase 5)

*Status: ROUND 2, 2026-07-14. Round 1 (flat themable panels) REJECTED by Jack:
"blocky, not seamless/smooth." Direction now locked: **Forge's familiar
battlefield arrangement with a Hearthstone/Arena-grade facelift** — a facelift,
not a reinvention. Living companion: the interactive design lab artifact —
iterate there, record decisions here. This file is the contract the Compose
client implements.*

## Core principle: one physical object, not a grid of panels

The play screen reads as a **table** — wooden rim, felt top, soft sheen — and
every UI element floats on it as a physical thing: medallions, gems, coins,
one big golden button. Rules that make the "smooth" read:

- **Zero hard-edged rectangles.** No 1px-bordered panels. Radii are large
  and organic; depth comes from layered soft shadows + inner highlights,
  never from outlines.
- **Circular anchors.** Hero portraits, life gems, zone-count coins, mana
  crystals, the pass button, the log scroll — round is the default shape.
- **One accent metal.** Warm gold trim (#d9b565 family) on everything
  interactive; it is the "you can touch this" signal.
- **Soft motion.** Playable cards pulse gently; hand cards rise on hover;
  the pass button swells. Nothing snaps, everything eases.

## Layout contract (play screen — Forge's arrangement, kept)

```
  hero:opponent○         opponent lands+mana row
   (portrait,            opponent creatures row
    life gem,     ~~~~~ the river · turn medallion · phase pips ~~~~~
    zone coins)          player creatures row                 ( PASS )
  hero:you○              player lands+mana row                 big gold
   + mana crystals              hand, fanned over the table edge
```

- **Hero medallions** (left edge, opponent top / player bottom): circular
  portrait, life gem (player warm red / opponent cool blue), zone-count
  coins (deck/grave/hand), mana crystals under the player's.
- **The river**: a soft glowing midline, not a bar. Center turn medallion
  shows "Turn N · Phase" plus phase pips; right-click (long-press) opens
  stop/auto-pass settings. Replaces round 1's phase ladder.
- **The one big button** (right center, Hearthstone's signature): context-
  aware label — PASS / ATTACK / BLOCK / END TURN. The hand rests here all game.
- **Log/chat**: collapsed to a scroll medallion on the left edge; expands on
  hover/tap. No permanent rail.
- **Hand**: fanned arc over the bottom table edge; hover raises + zooms.
- **Battlefield rows**: lands+mana in the back row, creatures forward —
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
| Tapped | 90° rotation | `Tapped` property delta |
| Current phase | lit ladder entry | `Phase` property delta |
| Prompt | text in log rail header + buttons | `showPromptMessage` / `updateButtons` |

## Tokens (v2 names)

`roomVignette, tableRim, felt, riverGlow, goldTrim, goldBright, textWarm,
textDim, lifeYou, lifeOpp, manaCrystal, playableGlow (green), attackGlow (red),
blockGlow (blue), selectedOutline (gold), cardFrame, displayFont, uiFont`

Board *material* (felt vs parchment) is the only planned skin dimension —
one token swap, decided below. Round 1's three-theme system is retired.

## Invariant rules

- Card aspect ratio is exactly 63:88. Card images from Scryfall are rendered
  complete and uncropped, artist/copyright line visible (API terms).
- Life colors: player warm (red family gem), opponent cool (blue family gem).
- Attacker red / blocker blue / playable green / selected gold are game
  semantics — never reskinned.
- Type: serif display face (Palatino family until a licensed face is chosen)
  for names, life numbers, the medallion; system UI face for log/body.

## Open decisions (answer in the design lab, record here)

1. Board material: green felt (as mocked) vs parchment (library mockup vibe).
2. Opponent hand: fanned card backs at the top edge vs hand-count coin only.
3. Life gems on portraits (as mocked) vs also restoring the center life bar
   from Jack's original mockups.
4. The big button: one context-aware button (PASS/ATTACK/BLOCK/END TURN,
   as mocked) vs separate buttons.

## Decisions log

- 2026-07-14 — Round 1 flat-panel design rejected (blocky).
- 2026-07-14 — Round 2 (Hearthstone-style table/medallions) ALSO rejected.
  **Both invented looks erased. New method locked by Jack: copy Forge's real
  match UI from screenshots as baseline v0, then rebuild it one region at a
  time, each change approved before the next.** Everything above this log
  describing round-2 visuals is superseded and kept only as history; the
  invariant rules (63:88 uncropped cards, fixed semantic colors) still hold.
