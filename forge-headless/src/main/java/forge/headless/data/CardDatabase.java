package forge.headless.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * SQLite storage for the Scryfall card mirror (docs/DATA.md). Ingest goes to
 * a staging table which is atomically swapped in, so readers never see a
 * partially updated card list.
 */
public final class CardDatabase implements AutoCloseable {
    private static final int BATCH_SIZE = 1000;

    private final Connection conn;
    private PreparedStatement insert;
    private int batched;

    public CardDatabase(final Path dbFile) throws SQLException {
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (final Exception e) {
            throw new SQLException("Cannot create data dir " + dbFile.getParent(), e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)");
            s.execute(cardsDdl("cards"));
        }
    }

    private static String cardsDdl(final String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + "("
                + "scryfall_id TEXT PRIMARY KEY, oracle_id TEXT, name TEXT NOT NULL,"
                + "set_code TEXT, collector_number TEXT, released_at TEXT, layout TEXT,"
                + "mana_cost TEXT, cmc REAL, type_line TEXT, oracle_text TEXT,"
                + "colors TEXT, color_identity TEXT, rarity TEXT,"
                + "image_uri_normal TEXT, image_uri_back TEXT,"
                + "digital INTEGER, promo INTEGER, playable INTEGER)";
    }

    public String getMeta(final String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMeta(final String key, final String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** Prepares the staging table and the batched insert. */
    public void beginIngest() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS cards_staging");
            s.execute(cardsDdl("cards_staging"));
        }
        conn.setAutoCommit(false);
        insert = conn.prepareStatement("INSERT OR REPLACE INTO cards_staging VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        batched = 0;
    }

    // CHECKSTYLE:OFF long parameter list mirrors the row
    public void addCard(final String scryfallId, final String oracleId, final String name, final String setCode,
            final String collectorNumber, final String releasedAt, final String layout, final String manaCost,
            final Double cmc, final String typeLine, final String oracleText, final String colors,
            final String colorIdentity, final String rarity, final String imageNormal, final String imageBack,
            final boolean digital, final boolean promo) throws SQLException {
        insert.setString(1, scryfallId);
        insert.setString(2, oracleId);
        insert.setString(3, name);
        insert.setString(4, setCode);
        insert.setString(5, collectorNumber);
        insert.setString(6, releasedAt);
        insert.setString(7, layout);
        insert.setString(8, manaCost);
        if (cmc == null) {
            insert.setNull(9, java.sql.Types.REAL);
        } else {
            insert.setDouble(9, cmc);
        }
        insert.setString(10, typeLine);
        insert.setString(11, oracleText);
        insert.setString(12, colors);
        insert.setString(13, colorIdentity);
        insert.setString(14, rarity);
        insert.setString(15, imageNormal);
        insert.setString(16, imageBack);
        insert.setInt(17, digital ? 1 : 0);
        insert.setInt(18, promo ? 1 : 0);
        insert.setNull(19, java.sql.Types.INTEGER); // playable computed after ingest
        insert.addBatch();
        if (++batched % BATCH_SIZE == 0) {
            insert.executeBatch();
            conn.commit();
        }
    }

    /** Atomically replaces the live cards table with the staged one. */
    public void finishIngest() throws SQLException {
        insert.executeBatch();
        conn.commit();
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS cards");
            s.execute("ALTER TABLE cards_staging RENAME TO cards");
            s.execute("CREATE INDEX IF NOT EXISTS idx_cards_name ON cards(name)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_cards_set ON cards(set_code)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_cards_oracle ON cards(oracle_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_cards_released ON cards(released_at)");
        }
        conn.commit();
        conn.setAutoCommit(true);
        insert.close();
        insert = null;
    }

    /**
     * Computes the playable flag by joining card names against the engine's
     * card name set (front-face name matched for split/double-faced layouts).
     */
    public void markPlayable(final Set<String> engineNames) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS engine_names");
            s.execute("CREATE TEMP TABLE engine_names(name TEXT PRIMARY KEY)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO engine_names VALUES (?)")) {
            int n = 0;
            for (final String name : engineNames) {
                ps.setString(1, name);
                ps.addBatch();
                if (++n % BATCH_SIZE == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        try (Statement s = conn.createStatement()) {
            s.execute("UPDATE cards SET playable = EXISTS(SELECT 1 FROM engine_names e WHERE e.name = cards.name)"
                    + " OR EXISTS(SELECT 1 FROM engine_names e WHERE e.name ="
                    + " CASE WHEN instr(cards.name,' // ')>0 THEN substr(cards.name,1,instr(cards.name,' // ')-1) ELSE cards.name END)");
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    public long count(final String where) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM cards" + (where.isEmpty() ? "" : " WHERE " + where))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public Connection connection() {
        return conn;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
