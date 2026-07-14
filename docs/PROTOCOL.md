# Project Cardinal — JSON Game Protocol (Phase 2 design)

*Status: DESIGN — recon verified 2026-07-14. Implementation not started.*

## The big discovery

Forge's LAN multiplayer already defines the exact protocol we need. We are **not designing a
protocol from scratch**; we are giving Forge's existing protocol a JSON/WebSocket transport
that non-JVM clients (Compose desktop today, Compose-iOS later) can speak.

Key existing machinery (all in `forge-gui/src/main/java/forge/gamemodes/net/`):

| Piece | What it does |
|---|---|
| `ProtocolMethod` (enum) | **The complete catalog: 47 server→client + 16 client→server calls.** Server→client includes every blocking question the engine asks a human (getChoices, order, confirm, chooseSingleEntityForEffect, assignCombatDamage, ...). Client→server are fire-and-forget inputs (passPriority, selectCard, concede, ...). |
| `GameProtocolHandler` | Dispatch: incoming call → reflective invoke on `IGuiGame` (client side) or `IGameController` (server side); replies matched by ID. |
| `ReplyPool` / `ReplyEvent` | Blocking-call plumbing: every question carries an id, the answer comes back as `ReplyEvent(id, value)`. |
| `DeltaSyncManager` / `DeltaPacket` | State sync: full `GameView` snapshot (`setGameView`), then incremental deltas — per-object maps of changed `TrackableProperty` → value, with composite int keys (4-bit type tag + 28-bit object id), sequence numbers, sampled checksums, and client-initiated `requestResync`. |
| `TrackableObject` tree | `GameView` / `PlayerView` / `CardView` / `CardStateView` / `StackItemView` — the engine's purpose-built, id-keyed, diffable view model. **This is our JSON document model, already maintained by the engine.** |

What is JVM-locked (and therefore replaced): the wire format — Java object serialization
over Netty (`CObjectOutputStream`, `CompatibleObjectEncoder`). Everything above the wire
format is transport-agnostic design we keep.

## Architecture

```
Compose client ── WebSocket, JSON text frames ── CardinalWsServer (Netty, in forge-headless)
                                                        │ implements IGuiGame (json bridge)
                                                        │ drives IGameController
                                                  Forge engine (same JVM)
```

- Netty is already a forge-gui dependency and natively supports WebSocket framing — no new
  transport dependency.
- JSON via **Gson** (new dependency, forge-headless only), used in manual/tree mode
  (JsonObject construction, no reflection) to stay AOT-friendly for the iOS engine build.
- Same server binds loopback for solo play and a LAN/Tailscale interface for multiplayer.

## Message envelope

```json
// server → client call (id present only when a reply is expected)
{ "t": "call", "id": 42, "m": "getChoices", "a": { ... } }
// client → server reply
{ "t": "reply", "id": 42, "v": ... }
// client → server input (fire-and-forget, mirrors Mode.CLIENT methods)
{ "t": "input", "m": "passPriority", "a": { ... } }
// state sync
{ "t": "gameView", "seq": 0, "state": { ...full trackable tree... } }
{ "t": "delta", "seq": 17, "changed": { "<key>": { "<Prop>": ... } }, "new": { ... }, "checksum": ... }
```

Method names (`m`) are the `ProtocolMethod` enum names verbatim — greppable in both codebases.

## JSON adaptations (the only places we deviate from mirroring the Java signatures)

| Java construct | JSON replacement |
|---|---|
| `FSerializableFunction<T,String>` display fn (getChoices) | Server pre-renders: choices sent as `[{ "id": ..., "label": "..." }]`. Client returns chosen ids. |
| `ITriggerEvent` (mouse event in selectCard/selectPlayer) | `{ "button": "left"\|"right" }` (that is all the engine consumes from it). |
| `FSkinProp` icon enums in dialogs | Icon name as string; client maps to its own art. |
| `TrackableObject` args (CardView etc.) | Id reference `{ "type": "card", "id": 123 }` — client already holds the object from state sync. |
| `CardPool` (sideboard) | `[{ "name": ..., "set": ..., "qty": ... }]` |
| `Map<TrackableProperty,Object>` delta payloads | Property enum name → JSON value, object refs id-substituted (same rule as args). |

## Implementation chunks (each independently testable)

1. **View JSON codec** — ✅ snapshot half DONE 2026-07-14 (`forge.headless.protocol.JsonViewCodec`,
   harness `forge.headless.SnapshotDump`). Real finished game: 175 objects (80 cards / 91 card
   states / 2 players / combat / game) in 15 ms, 77 KB compact. Values dispatch on runtime type
   (10 branches cover all 22 TrackableTypes); refs are `{"$ref": "type:id"}`; card states keyed
   `cardState:id:stateOrdinal` mirroring DeltaPacket. Remaining: `DeltaPacket` → JSON.
   **⚠️ Open question for chunk 3+: per-seat hidden-info filtering.** The snapshot contains
   full library order and both hands. Harmless for loopback solo play; MUST be resolved before
   any remote seat exists. Investigate how stock net play filters visibility before designing.
2. **Envelope + reply pool** — port `ReplyPool` semantics onto WebSocket frames.
3. **`CardinalWsServer`** — Netty WS endpoint hosting a match the way `forge.headless.Main`
   does, with one human seat whose `IGuiGame`/`PlayerControllerHuman` traffic goes over the
   socket instead of to a GUI.
4. **Scripted test client** — a dumb JSON client that mulligans to keep, passes priority
   forever, and loses on schedule. When that game completes over the socket, Phase 2's core
   is proven. (Same trick as Phase 1's AI-vs-AI proof: cheapest possible full-loop test.)
5. **Interactive surface** — implement the remaining blocking questions one at a time,
   driving coverage from real games (log any ProtocolMethod hit that is still unimplemented).

## Decisions log

- **Engine-side code stays Java** (consistency with the fork, checkstyle, upstream merges).
  Kotlin starts client-side in the Compose repo. (Revises the earlier "prefer Kotlin for the
  protocol" note in CLAUDE.md §2.)
- Forge's netty/Java-serialization net play remains untouched and functional — our WS server
  is additive, in forge-headless. Nothing in the upstream diff surface grows.
- Auth: none on loopback; multiplayer runs inside Tailscale (network-level auth). Revisit
  only if that assumption changes.
