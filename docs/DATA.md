# Project Cardinal — Scryfall data layer (Phase 3 design)

*Status: DESIGN + first implementation 2026-07-14.*

## Two streams of "new cards"

| Stream | Source | Cadence | Gives us |
|---|---|---|---|
| Card knowledge | Scryfall **bulk data** (`default_cards`) | Daily file; preview cards appear during spoiler season with future `released_at` | Names, oracle text, sets, collector numbers, **image URIs**, legalities — for deck builder, search, rendering |
| Card playability | Forge `cardsfolder` scripts | Upstream Card-Forge merges (they script new sets around spoiler season) | The rules implementation. **A card is playable only when Forge scripts it.** |

The gap between the streams is embraced, not hidden: at ingest we join Scryfall names
against the engine's card database and store a computed **`playable`** flag. Deck builder
shows unplayable cards with a "not yet supported by engine" badge; the flag flips
automatically on the sync after an upstream merge adds the script.

## Update methodology (Scryfall-compliant)

1. One GET to `https://api.scryfall.com/bulk-data` (real `User-Agent` + `Accept` headers).
   Response lists current bulk files with `updated_at` + `download_uri` + size.
2. Compare `updated_at` with the `meta` table. Unchanged → done (one request total).
3. Changed → stream-download the JSON (Gson JsonReader, constant memory), ingest into
   `cards_staging`, then in one transaction: drop `cards`, rename staging, update `meta`.
   The app never sees a partially updated database.
4. Trigger points: app launch (throttled to once/day) + manual "check for card updates".

Bulk type: **`default_cards`** (every printing, English-preferred) — needed because images
and collector numbers are per-printing. ~100k rows, ~450 MB JSON streamed, ingests in
minutes, SQLite file ~150-200 MB.

## Images

- **Never bulk-downloaded.** Per-card, on demand, from the card's `image_uris` in the DB.
- Compliant headers, client-side cap ≤10 req/s (we run well under).
- Disk cache keyed by scryfall id + face; Scryfall image URIs carry a version query param,
  stored so a changed URI invalidates the entry. Cache is effectively permanent.
- Per Scryfall/WotC terms: images shown uncropped, artist/copyright line unobscured.

## Storage

SQLite via `org.xerial:sqlite-jdbc` (single file, `%LOCALAPPDATA%\Cardinal\scryfall.db`,
override with `-Dcardinal.data.dir`). Schema v1:

```sql
meta(key TEXT PRIMARY KEY, value TEXT)
cards(
  scryfall_id TEXT PRIMARY KEY, oracle_id TEXT, name TEXT NOT NULL,
  set_code TEXT, collector_number TEXT, released_at TEXT, layout TEXT,
  mana_cost TEXT, cmc REAL, type_line TEXT, oracle_text TEXT,
  colors TEXT, color_identity TEXT, rarity TEXT,
  image_uri_normal TEXT, image_uri_back TEXT,
  digital INTEGER, promo INTEGER, playable INTEGER
)
-- indexes: name, set_code, oracle_id, released_at
```

Legalities/prices deferred to a later pass (kept out of v1 to stay lean).

## Where this layer lives (architecture note)

Server-side Java (forge-headless), beside the engine — the client asks the backend for
card data/images, identical shape on desktop (same JVM) and iOS (AOT'd with the engine).
**Known iOS risk, scoped to Phase 8:** sqlite-jdbc ships prebuilt natives per platform;
iOS needs either a custom-built sqlite native or moving this layer into the Kotlin client
via SQLDelight (multiplatform). The schema and sync logic port either way; nothing in the
protocol changes.

## Implemented (see forge.headless.data)

- `CardDatabase` — schema, staging ingest, atomic swap, queries.
- `ScryfallBulkSync` — bulk-data check + streaming download/ingest + playable-flag join.
- `CardImageCache` — on-demand fetch, compliant headers, rate cap, disk cache.
- `ScryfallSyncCli` — manual runner: `java -cp ... forge.headless.data.ScryfallSyncCli`
  (boots the engine when `res/` is reachable to compute `playable`; `--force` re-ingests).
