# Project Cardinal — Design system (Phase 5)

*Status: FIRST CUT 2026-07-14, distilled from Jack's three reference mockups.
Living companion: the interactive design lab artifact (theme switcher + token
swatches) — iterate there, record decisions here. This file is the contract
the Compose client implements.*

## Core principle: one layout contract, themable surfaces

Jack's three reference mockups (warm library / clean slate / neon night) are
the same interface wearing three skins. The design system therefore separates:

- **Layout contract** — where things live and how they behave. Fixed.
- **Theme tokens** — every color, glow, texture and display face. Swappable.
  Themes are data, not code; `Slate` / `Library` / `Neon` are the launch set.

## Layout contract (play screen)

```
┌ plaque:opponent ──────┬──────────────────────────────┬─ plaque/stack ─┐
│ log / chat rail       │   opponent battlefield       │  phase ladder  │
│ (left, ~13% width)    │   (lands+mana row, creatures)│  UPKEEP..END   │
│                       ├──────────────────────────────┤  PASS TURN     │
│                       │   life divider  20 ♥ vs ◆ 16 │  [ATTACK]      │
│                       ├──────────────────────────────┤                │
│                       │   player battlefield         │                │
│                       │   (creatures, lands+mana row)│                │
├ plaque:player ────────┴───────── hand (fanned) ──────┴─ utility ──────┘
```

- **Player plaques** (corners): avatar, life badge, deck/graveyard/hand counts.
- **Life divider**: both totals, segmented commander/poison pips, life bars.
  This is the visual center of gravity — game state readable in one glance.
- **Phase ladder** (right): all phases listed vertically, current phase lit.
  Doubles as the stop/skip configuration surface (right-click a phase to
  toggle auto-pass — replaces Forge's buried prefs).
- **Hand**: fanned arc, bottom center, overlapping the table edge. Hover
  raises + zooms a card.
- **Battlefield rows**: lands+mana furthest from the divider, creatures
  nearest — combat happens across the divider.
- **Log/chat rail** (left): tabs for log / chat / settings.

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

## Theme tokens (v1 names)

`ground, table, tableEdge, panel, panelEdge, text, textDim, accent, accentSoft,
lifeYou, lifeOpp, button, buttonEdge, buttonText, primary, primaryEdge,
primaryText, divider, glow, cardFrame, textureOpacity, headFont`

Values for the three launch themes live in the design lab artifact (and will
land here as final hex once iterated). Working default: **Slate**.

## Rules that hold across every theme

- Card aspect ratio is exactly 63:88. Card images from Scryfall are rendered
  complete and uncropped, artist/copyright line visible (API terms).
- Life colors: player warm (red-orange family), opponent cool (blue family) —
  consistent across themes so the divider reads instantly.
- Attacker red / blocker blue never change with theme (game semantics, not skin).
- One glow color per theme (`accent`) for "you may act on this"; semantic
  combat colors are separate and fixed.
- Type: one display face per theme (plaques, life numbers, phase ladder), one
  UI face shared by all themes for body/log text.

## Open decisions (answer in the design lab, record here)

1. Default theme — working answer Slate; Library and Neon ship as skins.
2. Hand: fanned arc (pretty, overlaps board) vs flat strip (more board visible).
3. Log/chat rail: permanent vs collapsible.
4. ATTACK button: contextual (combat only, per neon mockup) vs always-visible-disabled.
5. Avatar art source: Scryfall art crops w/ artist credit vs custom set.
