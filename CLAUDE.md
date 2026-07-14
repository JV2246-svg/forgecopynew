# CLAUDE.md — Project Cardinal

> Drop this file at the root of the Forge fork. Claude Code loads it automatically each session.

---

## 1. What we are building

A **sleek, modern Magic: The Gathering client** built on top of the **Forge** rules engine — **desktop (Windows) first, iOS second**, from one Kotlin/Compose Multiplatform codebase.

> **Strategy decision (2026-07-14):** Jack chose desktop-first with Compose Multiplatform. Rationale: he develops on Windows and plays on Windows; Compose targets Windows/Mac/Linux AND iOS from one codebase; Kotlin interoperates natively with the Java engine. The iOS port later is honestly **moderate changes** (Compose-for-iOS + engine-on-device AOT), not "minor," but the entire protocol/data/UI-logic stack transfers. This consciously trades away "100% native SwiftUI" from the original plan. Jack has a Mac available for when iOS work begins.

Forge is an open-source MTG rules engine (GPL, Java, ~15 years old, not affiliated with Wizards of the Coast). It implements **99%+ of all MTG cards ever printed** — more than official Magic Online. Its own UI is the thing we're replacing.

We keep Forge's **engine**. We throw away Forge's **UI**.

### Requirements
- **Offline play vs. AI**, on desktop now, on the phone later. On iOS this forces the Java engine onto the device (see §4) — that risk is deferred, not deleted.
- **Online play with friends** via a self-hosted personal server.
- All MTG play modes: Constructed, Commander, Brawl, Draft, Sealed, Archenemis/Planechase/Vanguard, etc.
- **Scryfall** as the card data + image source.
- Deck import from external deckbuilding sites.
- A UI that is dramatically better than Forge's. Benchmark: MTG Arena / the official WotC app.

### Anti-goals (explicitly cut)
- **No Adventure mode. No Quest mode. No Planar Conquest.** Play modes only.
- **No monetization. Free forever.** (See §3 — this is a hard legal constraint, not a preference.)
- No App Store release. Desktop distribution is a plain download; iOS is TestFlight or sideload.

---

## 2. Who you are working with

**Jack.** Background: telematics / industrial access control. Strong with PC hardware, 3D modeling (Fusion 360, OpenSCAD), fabrication, and software configuration.

**He is technical, but he is not a software developer.** Calibrate accordingly:

- ✅ Explain *what is happening and why*, not just what to type. He wants to understand the machine.
- ✅ Work **phase by phase, step by step**. Do not dump the whole roadmap. Detail only the phase currently in flight.
- ✅ Give exact, runnable commands. Expect him to paste back errors — that is the loop.
- ❌ Do not condescend or over-explain fundamentals. He works with computers and software daily. He will tell you if he needs more.
- ❌ Do not assume prior Java, Maven, Xcode, or Swift knowledge. Introduce those as they come up.

**Known risk to name honestly:** Phase 6 (the Compose desktop client) is ~8–12 weeks for a professional and is where a first-time coder realistically hits a wall. Jack has chosen to proceed and learn. Support that, but do not pretend the wall isn't there. Kotlin is the language to introduce gradually from Phase 2 onward (protocol work can be done in Java or Kotlin — prefer Kotlin where practical so the learning starts early).

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
┌──────────────────────────────────────────┐
│  Compose Multiplatform client (Kotlin)   │
│  Windows/Mac/Linux now → iOS later       │
└──────────┬───────────────────────────────┘
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

**On desktop:** client and engine run in the **same JVM process** — the engine on a background thread exposing a loopback WebSocket. Trivially easy; no AOT compilation, no Phase-3-style risk at all.

**On iOS later:** same client code, but the engine must be AOT-compiled onto the device (the old Phase 3 spike — MobiVM vs GraalVM — now gates only the iOS port, nothing else).

**Why the WebSocket stays even in-process:** identical code paths for offline and online play, and the multiplayer server is the same engine on a plain JVM. Resist the temptation to have the desktop client call engine classes directly — that convenience would fork the codebase into desktop-only and iOS-only paths, which is exactly what we're avoiding.

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
| 3 | Scryfall data layer (bulk ingest → SQLite, image cache) | 2 wks | ⬜ |
| 4 | Deck import (paste / file / Archidekt) | 2 wks | ⬜ |
| 5 | Design system | 2–3 wks | ⬜ |
| 6 | **Compose Multiplatform desktop client v1** (Windows, offline vs AI) | 8–12 wks | ⬜ |
| 7 | Multiplayer + personal server (Tailscale) | 3 wks | ⬜ |
| 8 | **⚠️ Engine-on-iOS spike — GO/NO-GO for iOS port** (MobiVM vs GraalVM) | 3–5 wks | ⬜ |
| 9 | iOS port: Compose-for-iOS client + on-device engine | 4–8 wks | ⬜ |
| 10 | Polish, iPad, TestFlight | 3 wks | ⬜ |

**~6–9 months to a finished desktop app**, iOS on top of that. Longer for Jack; that's expected and fine.

### Sequencing notes (revised 2026-07-14 with desktop-first decision)
The old rule was "spike iOS AOT in week 2, because if it fails everything is worthless." **That rule is retired**: the desktop app has standalone value regardless of what iOS allows, so the AOT spike (old Phase 3) now sits at Phase 8, gating only the iOS port.

**But run the one-day kill shot opportunistically earlier.** Jack has a Mac. Some weekend while the desktop client is underway: Xcode + a hello-world MobiVM app for arm64 on a physical iPhone containing exactly:
```java
record Foo(int a, String b) {}
System.out.println(new Foo(1, "x"));
```
- **Prints?** → the iOS road is open; carry on with confidence.
- **Fails?** → we know years early, and plan the iOS port around the fallbacks below.

### Engine-on-iOS fallbacks (if records fail on MobiVM)
1. **De-record the engine.** 86 records → plain classes is mechanical and IDE-assisted. ~2–3 days. **Try this first.**
2. **GraalVM native-image.** Actively maintained, targets iOS, handles modern Java properly. It demands reflection config — but the engine has only six reflection sites, so that cost is near zero. **This may end up being the better path than MobiVM outright.** (Note: with a Compose/Kotlin-Native iOS app, embedding the engine as a GraalVM-built static library and talking to it over the loopback socket is the leading integration theory — to be validated in Phase 8.)

---

## 7. Current status & next action

**Phase 1 in progress** (started 2026-07-14, same day Phase 0 completed).

**Resolved:** Jack is on Windows 11 Pro x64 (16 cores — full build takes ~2.5 min). All desktop-track phases (0–7) proceed here. **Jack has a Mac** — needed only for the iOS spike/port (Phases 8–10) and available for the opportunistic kill-shot test. Jack is a long-time Forge player — no UI orientation needed. **Client tech: Compose Multiplatform (Kotlin), desktop-first** — decided 2026-07-14, see §1.

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

**Prereqs for the iOS track (Phase 8+, or the early kill-shot test):** Jack's Mac, Xcode, Apple Developer account ($99/yr), physical iPhone.

---

## 8. Working agreement

- One phase at a time. Chunk the steps *inside* the current phase. Do not race ahead.
- Explain the *why* behind each command.
- When something breaks, ask for the last ~30 lines — Maven and Xcode errors bury the real cause near the bottom.
- Be honest about risk and cost. Jack asked for a real build, not encouragement.
