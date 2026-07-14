package forge.headless.data;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import forge.StaticData;
import forge.headless.HeadlessBootstrap;
import forge.item.PaperCard;

/**
 * Manual runner for the Scryfall sync (docs/DATA.md).
 *
 * Usage: java -cp forge-headless-...jar forge.headless.data.ScryfallSyncCli [--force] [--no-engine]
 *
 * DB location: %LOCALAPPDATA%\Cardinal\scryfall.db (override -Dcardinal.data.dir).
 * With engine assets reachable (run from forge-gui, or -Dforge.assets.dir), the
 * engine boots afterwards and the playable flag is computed by name join.
 */
public final class ScryfallSyncCli {
    private static final String BULK_TYPE = "default_cards";

    private ScryfallSyncCli() {
    }

    public static void main(final String[] args) throws Exception {
        boolean force = false;
        boolean useEngine = true;
        for (final String arg : args) {
            if ("--force".equals(arg)) {
                force = true;
            } else if ("--no-engine".equals(arg)) {
                useEngine = false;
            }
        }

        final Path dataDir = dataDir();
        System.out.println("Data dir: " + dataDir);

        try (CardDatabase db = new CardDatabase(dataDir.resolve("scryfall.db"))) {
            final ScryfallBulkSync sync = new ScryfallBulkSync();
            final long start = System.currentTimeMillis();
            final ScryfallBulkSync.SyncResult result = sync.sync(db, BULK_TYPE, force);
            if (!result.updated()) {
                System.out.println("Already current (" + result.updatedAt() + "), " + result.cardCount() + " cards.");
            } else {
                System.out.printf("Ingested %d cards in %.1f s (bulk of %s)%n",
                        result.cardCount(), (System.currentTimeMillis() - start) / 1000.0, result.updatedAt());
            }

            if (useEngine) {
                System.out.println("Booting engine to compute playable flags...");
                HeadlessBootstrap.boot();
                final Set<String> engineNames = new HashSet<>();
                for (final PaperCard pc : StaticData.instance().getCommonCards().getUniqueCards()) {
                    engineNames.add(pc.getName());
                }
                System.out.println("Engine knows " + engineNames.size() + " unique card names");
                db.markPlayable(engineNames);
            }

            printStats(db);

            for (final String arg : args) {
                if ("--test-image".equals(arg)) {
                    testImage(db, dataDir);
                }
            }
        }
        System.exit(0);
    }

    /** Fetches one real card image through the cache as a smoke test. */
    private static void testImage(final CardDatabase db, final Path dataDir) throws Exception {
        try (Statement s = db.connection().createStatement();
             ResultSet rs = s.executeQuery("SELECT scryfall_id, name, set_code, image_uri_normal FROM cards"
                     + " WHERE name='Lightning Bolt' AND image_uri_normal IS NOT NULL ORDER BY released_at DESC LIMIT 1")) {
            if (!rs.next()) {
                System.out.println("image test: no candidate card found");
                return;
            }
            final CardImageCache cache = new CardImageCache(dataDir.resolve("images"));
            final long start = System.currentTimeMillis();
            final Path first = cache.get(rs.getString(1), rs.getString(4));
            final long fetchMs = System.currentTimeMillis() - start;
            final long cachedStart = System.currentTimeMillis();
            final Path second = cache.get(rs.getString(1), rs.getString(4));
            System.out.printf("image test: %s (%s) -> %s (%d bytes, fetch %d ms, cached hit %d ms)%n",
                    rs.getString(2), rs.getString(3), first.getFileName(),
                    java.nio.file.Files.size(first), fetchMs, System.currentTimeMillis() - cachedStart);
            if (!first.equals(second)) {
                System.out.println("image test: WARNING cache miss on second get");
            }
        }
    }

    private static Path dataDir() {
        final String override = System.getProperty("cardinal.data.dir");
        if (override != null) {
            return Paths.get(override);
        }
        final String localAppData = System.getenv("LOCALAPPDATA");
        final String base = localAppData != null ? localAppData : System.getProperty("user.home") + File.separator + ".local";
        return Paths.get(base, "Cardinal");
    }

    private static void printStats(final CardDatabase db) throws Exception {
        System.out.println("--- card database stats ---");
        System.out.println("printings: " + db.count(""));
        System.out.println("paper (non-digital): " + db.count("digital=0"));
        try (Statement s = db.connection().createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(DISTINCT oracle_id) FROM cards")) {
                rs.next();
                System.out.println("unique cards (oracle ids): " + rs.getLong(1));
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(DISTINCT set_code) FROM cards")) {
                rs.next();
                System.out.println("sets: " + rs.getLong(1));
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT set_code, MIN(released_at) r, COUNT(*) FROM cards WHERE released_at > date('now')"
                            + " GROUP BY set_code ORDER BY r LIMIT 5")) {
                System.out.println("upcoming sets (future released_at):");
                while (rs.next()) {
                    System.out.printf("  %s releases %s (%d cards indexed so far)%n",
                            rs.getString(1), rs.getString(2), rs.getLong(3));
                }
            }
        }
        final long playable = db.count("playable=1");
        final long notPlayable = db.count("playable=0");
        if (playable + notPlayable > 0) {
            System.out.printf("playable in engine: %d printings (%.1f%%); not yet scripted: %d%n",
                    playable, 100.0 * playable / (playable + notPlayable), notPlayable);
        }
    }
}
