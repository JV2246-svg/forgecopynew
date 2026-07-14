# CLAUDE.md — Project Cardinal

> Drop this file at the root of the Forge fork. Claude Code loads it automatically each session.

---

## 1. What we are building

A **sleek, modern iOS (iPhone-first) Magic: The Gathering client**, built on top of the **Forge** rules engine.

Forge is an open-source MTG rules engine (GPL, Java, ~15 years old, not affiliated with Wizards of the Coast). It implements **99%+ of all MTG cards ever printed** — more than official Magic Online. It runs on Windows/Mac/Linux/Android. **There is no working iOS version.**

We keep Forge's **engine**. We throw away Forge's **UI**.

### Requirements
- **Offline play vs. AI on the phone.** Non-negotiable. This forces the Java engine onto the device (see §4).
- **Online play with friends** via a self-hosted personal server.
- All MTG play modes: Constructed, Commander, Brawl, Draft, Sealed, Archenemis/Planechase/Vanguard, etc.
- **Scryfall** as the card data + image source.
- Deck import from external deckbuilding sites.
- A UI that is dramatically better than Forge's. Benchmark: MTG Arena / the official WotC app.

### Anti-goals (explicitly cut)
- **No Adventure mode. No Quest mode. No Planar Conquest.** Play modes only.
- **No monetization. Free forever.** (See §3 — this is a hard legal constraint, not a preference.)
- No App Store release. Distribution is TestFlight or sideload.

---

## 2. Who you are working with

**Jack.** Background: telematics / industrial access control. Strong with PC hardware, 3D modeling (Fusion 360, OpenSCAD), fabrication, and software configuration.

**He is technical, but he is not a software developer.** Calibrate accordingly:

- ✅ Explain *what is happening and why*, not just what to type. He wants to understand the machine.
- ✅ Work **phase by phase, step by step**. Do not dump the whole roadmap. Detail only the phase currently in flight.
- ✅ Give exact, runnable commands. Expect him to paste back errors — that is the loop.
- ❌ Do not condescend or over-explain fundamentals. He works with computers and software daily. He will tell you if he needs more.
- ❌ Do not assume prior Java, Maven, Xcode, or Swift knowledge. Introduce those as they come up.

**Known risk to name honestly:** Phase 7 (the SwiftUI client) is ~8–12 weeks for a professional and is where a first-time coder realistically hits a wall. Jack has chosen to proceed and learn. Support that, but do not pretend the wall isn't there.

---

## 3. Hard constraints (do not relitigate)

**GPL.** Forge is GPL. Any fork is GPL. Source must ship. Cannot be closed or commercialized.

**WotC Fan Content Policy.** Fan content must be **free**. Selling is not permitted without written consent, and the policy carries an indemnification clause. A *paid* MTG client is the most enforcement-attractive category possible — it competes directly with Arena/MTGO. Forge and XMage have survived 15+ years precisely because nobody monetizes them.

**Scryfall API terms.** Data may **not** be paywalled. Required: real `User-Agent` + `Accept` headers, ≤10 req/sec, **use bulk data files** for mass ingest rather than hammering the API, cache aggressively. Images may not be cropped, distorted, or have the artist/copyright line obscured.

> A monetized version was considered and **abandoned** — it was blocked independently by all four of: the Fan Content Policy, Scryfall's terms, the GPL, and App Store rules. Do not resurrect it.

**Moxfield.** No official public API; the public GitHub org is effectively empty. All "Moxfield API" wrappers are unofficial and some rely on Cloudflare-bypass tooling. **Do not architect around Moxfield.** Build universal decklist paste/file import as the primary path, add **Archidekt** (real public API), and treat Moxfield public-URL import as best-effort only.

---

## 4. Architecture

**Keep Forge's brain. Delete its face.**

```
┌─────────────────────────────┐
│  SwiftUI client (iPhone)    │  ← 100% native. Real iOS polish.
└──────────┬──────────────────┘
           │ JSON over WebSocket   ← ONE protocol, TWO transports
     ┌─────┴─────┐
     │           │
ws://127.0.0.1   ws://your-server
(offline vs AI)  (multiplayer w/ friends)
     │           │
┌────┴───────────┴────────────┐
│  Forge engine, headless     │
│  forge-core → game → ai     │
└─────────────────────────────┘
```

The engine runs on a background thread **inside the app** and exposes a loopback WebSocket. The client points at `localhost` for solo play, or at a home server for multiplayer. Identical protocol both ways.

**Why this matters:** no Java↔Swift FFI bridging, no separate online/offline code paths, and the multiplayer server is the same engine running on a plain JVM (no iOS toolchain needed server-side).

**Multiplayer networking:** put Jack + friends on a **Tailscale/WireGuard mesh**. Zero port-forwarding, no exposed ports. (Forge's own net play uses TCP 36743 and has no matchmaking — it's explicitly designed for playing with people you know.)

---

## 5. Repo recon — VERIFIED FINDINGS

*These were established by direct inspection of the source. Trust them; don't re-derive.*

### ✅ The engine is completely clean
Grepped every source file in the engine modules for Swing / AWT / libGDX imports:

| Module | GUI imports |
|---|---|
| `forge-core` | **0** |
| `forge-game` | **0** |
| `forge-ai` | **0** |

Dependency graph is perfectly layered: `core → game → ai → gui → platform-specific UIs`. **The engine has zero knowledge that a UI exists.** The headless architecture is not a refactor — it is already true.

### ✅ Reflection is nearly absent — AOT compilation is viable
There are **exactly six** reflection sites in the whole engine, and **zero** `Class.forName` calls.

Card scripts dispatch through **`ApiType`** — an enum holding **hard `.class` literals**:
```java
Abandon(AbandonEffect.class),
Animate(AnimateEffect.class),
...
```
Same pattern for `TriggerType`, `ReplacementType`, `Keyword`. The six reflection sites all call `getConstructor().newInstance()` on a class object obtained from one of those enums.

**Why this is the whole ballgame:** those are real class references in the bytecode constant pool, not strings. An **AOT tree-shaker can see them.** The "reflection will fight the AOT compiler" risk is largely dead.

### ⚠️ `forge-gui-ios` exists — and it is a zombie
The module is present and wired to RoboVM, **but it has never shipped.** Evidence:
- `robovm.properties` timestamped **December 2014**.
- `robovm.xml` declares `<arch>thumbv7</arch>` — **32-bit ARMv7**. Apple killed 32-bit apps with iOS 11 (2017). This config cannot produce a binary that runs on any modern iPhone.
- Bundled `libgdx.a`, `libObjectAL.a`, `libgdx-freetype.a` are ancient prebuilt blobs that will not link for arm64.
- Forge's own CONTRIBUTING.md says: *"RoboVM (optional: for iOS releases) (TBD: Current status of support by libgdx)"* — **the maintainers themselves don't know if it works.**

It has been *mechanically* kept compiling (targets Java 17, imports modern interfaces) but nobody has built or run it in ~12 years.

**But it is still a gift:**
- `Main.java` reveals the platform seam: **`IDeviceAdapter`** — clipboard, storage paths, network status, screen size. A small, bounded contract.
- `robovm.xml` already contains a `forceLinkClasses` list — a head start on AOT config.

**Crucially: we do not need libGDX at all.** The engine never imports it. Delete every `.a` blob and the OpenGLES/OpenAL frameworks. The scariest part of the 2014 config evaporates.

### 🚨 THE ACTUAL BLOCKER — Java 17 records
The engine uses **86 `record` declarations** (a Java 16 feature) and 50 arrow-switches.

**MobiVM's Java class library is a fork of an older Android libcore.** The maintainer's experimental repo (`robovmx/robovmx`) lists *"Libcore 10 — migrating to Android 10–13 runtime"* as an **ongoing, unmerged experiment.**

So the single make-or-break question for this entire project is:

> **Does MobiVM's runtime provide `java.lang.Record`?**

Not answerable from documentation. **Must be answered empirically.** See Phase 3, Step 1.

### Third-party engine dependencies
`guava` (large — tree-shaking matters), `commons-lang3`, `commons-text`, `jgrapht-core`, `commons-math3`, `tinylog`, `rssreader`, `sentry`, `testng`.

- **Exclude `io.sentry`** — crash reporting, network calls, privacy. Not wanted.
- **Exclude `rssreader`** — only used for a changelog feed.
- Confirm `testng` is test-scope only.

### Asset cut list — ~275 MB deleted before writing a line of code
| Path | Size | Verdict |
|---|---|---|
| `forge-gui/res/cardsfolder` | 132 MB | **KEEP** — 33,290 card scripts |
| `forge-gui/res/adventure` | **154 MB** | CUT |
| `forge-gui/res/languages` | 55 MB | CUT all but English |
| `forge-gui/res/quest` | 39 MB | CUT |
| `forge-gui/res/skins` | 18 MB | CUT — building our own look |
| `forge-gui/res/music` | 17 MB | CUT |
| `forge-gui/res/conquest` | 6.4 MB | CUT |

Ships ~150 MB of card data. Card art pulled from Scryfall on demand and cached.

### Build facts
- **Java 17** exactly.
- Build: `mvn -U -B clean install -DskipTests`
- Desktop entry point: `forge.view.Main`
- Output: `forge-gui-desktop/target/*-jar-with-dependencies.jar`
- ⚠️ CONTRIBUTING.md's `-P windows-linux` flag is **misleading** — that profile lives in `forge-installer` and is not needed for a normal build.

---

## 6. Phase plan

| # | Phase | Est. | Status |
|---|---|---|---|
| **0** | **Env setup + build Forge from source** | 1 hr | ✅ **DONE 2026-07-14** |
| **1** | **Headless engine bootstrap (strip UI, Adventure, Quest)** | 1–2 wks | 🔵 **IN PROGRESS** |
| 2 | JSON game protocol over the engine's view/controller seam | 3–5 wks | ⬜ |
| **3** | **⚠️ MobiVM spike — GO/NO-GO GATE** | 3–5 wks | ⬜ |
| 4 | Scryfall data layer (bulk ingest → SQLite, image cache) | 2 wks | ⬜ |
| 5 | Deck import (paste / file / Archidekt) | 2 wks | ⬜ |
| 6 | Design system | 2–3 wks | ⬜ |
| 7 | SwiftUI client v1 (offline vs AI) | 8–12 wks | ⬜ |
| 8 | Multiplayer + personal server (Tailscale) | 3 wks | ⬜ |
| 9 | Polish, iPad, TestFlight | 3 wks | ⬜ |

**~6–9 months** at professional pace. Longer for Jack; that's expected and fine.

### ‼️ Sequencing rule
**Phase 3 is the riskiest thing in the project and should be spiked EARLY — before Phase 2 and before any UI work.** If the engine cannot AOT-compile for iOS, everything downstream is worthless. Find out in week 2, not month 5.

**Phase 3, Step 1 is a one-day kill shot:** build a hello-world MobiVM app for arm64 on a physical iPhone containing exactly:
```java
record Foo(int a, String b) {}
System.out.println(new Foo(1, "x"));
```
- **Prints?** → road is open.
- **Fails?** → stop, go to fallbacks. Four months saved.

### Phase 3 fallbacks (if records fail)
1. **De-record the engine.** 86 records → plain classes is mechanical and IDE-assisted. ~2–3 days. **Try this first.**
2. **GraalVM native-image via Gluon Substrate.** Actively maintained, targets iOS, handles modern Java properly. It demands reflection config — but the engine has only six reflection sites, so that cost is near zero. **This may end up being the better path than MobiVM outright.**

---

## 7. Current status & next action

**Phase 1 in progress** (started 2026-07-14, same day Phase 0 completed).

**Resolved:** Jack is on Windows 11 Pro x64 (16 cores — full build takes ~2.5 min). Phases 0–2 proceed here; **Mac access must be arranged before Phase 3.** Jack is a long-time Forge player — no UI orientation needed.

**Phase 1 progress:**
- ✅ Proven headless: engine plays full AI-vs-AI games from the console (first via desktop jar's `sim` mode, then via our own module).
- ✅ New Maven module **`forge-headless`** on branch `cardinal`: depends only on `forge-gui` (the non-Swing shared layer) → core/game/ai. Entry point `forge.headless.Main`, platform stub `HeadlessGuiBase` (33 no-op methods). **Does not call `Sentry.init`** — note: stock desktop `Main.java` phones home to sentry.asgardsrealm.net on every launch with a hardcoded DSN.
- Run: `java -jar forge-headless\target\forge-headless-*-jar-with-dependencies.jar <deck1.dck> <deck2.dck> [n]` with cwd = `forge-gui` (or `-Dforge.assets.dir=`).
- ⬜ Next: strip sentry/rssreader deps from the headless classpath; asset cut list (§5); decide fork/remote strategy for the `cardinal` branch.

**Learned along the way:**
- `IGuiBase` is only 33 methods; a ready headless stub existed in `forge-gui/tools/java/ForgeMatrixWriter.java`.
- The engine resolves `res/` via `GuiBase.getInterface().getAssetsDir()` read in a **static initializer** (`ForgeConstants.ASSETS_DIR`) — the GUI stub must be installed before any engine class loads.
- Desktop jar must run with cwd = `forge-gui` in dev (hardcoded `../forge-gui/` assets path).
- `TimeLimitedCodeBlock` lives in forge-gui-desktop; forge-headless inlines its own timeout runner.

**Prereqs for Phase 3:** Mac, Xcode, Apple Developer account ($99/yr), physical iPhone.

---

## 8. Working agreement

- One phase at a time. Chunk the steps *inside* the current phase. Do not race ahead.
- Explain the *why* behind each command.
- When something breaks, ask for the last ~30 lines — Maven and Xcode errors bury the real cause near the bottom.
- Be honest about risk and cost. Jack asked for a real build, not encouragement.
