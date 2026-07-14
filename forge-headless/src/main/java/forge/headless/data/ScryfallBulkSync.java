package forge.headless.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

/**
 * Scryfall bulk-data sync (docs/DATA.md): one API call to check freshness,
 * then a streaming download + ingest when the bulk file changed. This is the
 * Scryfall-sanctioned way to mirror card data - the per-card API is never
 * crawled.
 */
public final class ScryfallBulkSync {
    public static final String BULK_INDEX_URL = "https://api.scryfall.com/bulk-data";
    public static final String USER_AGENT = "ProjectCardinal/0.1 (+https://github.com/JV2246-svg/forgecopynew)";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Gson gson = new Gson();

    /** Result of a sync attempt. */
    public record SyncResult(boolean updated, String updatedAt, long cardCount) {
    }

    /**
     * Checks the bulk index and ingests the given bulk type if newer than the
     * locally recorded version (or if force is set).
     */
    public SyncResult sync(final CardDatabase db, final String bulkType, final boolean force) throws Exception {
        final JsonObject entry = findBulkEntry(bulkType);
        final String updatedAt = entry.get("updated_at").getAsString();
        final String localVersion = db.getMeta("bulk_updated_at_" + bulkType);

        if (!force && updatedAt.equals(localVersion)) {
            return new SyncResult(false, updatedAt, db.count(""));
        }

        final String downloadUri = entry.get("download_uri").getAsString();
        final long size = entry.get("size").getAsLong();
        System.out.printf("Downloading %s (%s, %.1f MB)...%n", bulkType, updatedAt, size / 1048576.0);

        final long count = ingest(db, downloadUri);

        db.setMeta("bulk_updated_at_" + bulkType, updatedAt);
        db.setMeta("bulk_type", bulkType);
        return new SyncResult(true, updatedAt, count);
    }

    private JsonObject findBulkEntry(final String bulkType) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(BULK_INDEX_URL))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build();
        final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("bulk-data index returned HTTP " + response.statusCode());
        }
        final JsonArray entries = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data");
        for (final JsonElement e : entries) {
            final JsonObject entry = e.getAsJsonObject();
            if (bulkType.equals(entry.get("type").getAsString())) {
                return entry;
            }
        }
        throw new IOException("bulk type not found in index: " + bulkType);
    }

    private long ingest(final CardDatabase db, final String downloadUri) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUri))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .build();
        final HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("bulk download returned HTTP " + response.statusCode());
        }
        InputStream in = response.body();
        if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
            in = new GZIPInputStream(in, 65536);
        }

        long count = 0;
        db.beginIngest();
        try (JsonReader reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                final JsonObject card = gson.fromJson(reader, JsonObject.class);
                ingestCard(db, card);
                if (++count % 20000 == 0) {
                    System.out.println("  ingested " + count + " cards...");
                }
            }
            reader.endArray();
        }
        db.finishIngest();
        return count;
    }

    private void ingestCard(final CardDatabase db, final JsonObject card) throws Exception {
        String imageNormal = null;
        String imageBack = null;
        if (card.has("image_uris")) {
            imageNormal = str(card.getAsJsonObject("image_uris"), "normal");
        } else if (card.has("card_faces")) {
            final JsonArray faces = card.getAsJsonArray("card_faces");
            if (faces.size() > 0 && faces.get(0).getAsJsonObject().has("image_uris")) {
                imageNormal = str(faces.get(0).getAsJsonObject().getAsJsonObject("image_uris"), "normal");
            }
            if (faces.size() > 1 && faces.get(1).getAsJsonObject().has("image_uris")) {
                imageBack = str(faces.get(1).getAsJsonObject().getAsJsonObject("image_uris"), "normal");
            }
        }

        db.addCard(
                str(card, "id"),
                str(card, "oracle_id"),
                str(card, "name"),
                str(card, "set"),
                str(card, "collector_number"),
                str(card, "released_at"),
                str(card, "layout"),
                str(card, "mana_cost"),
                card.has("cmc") && !card.get("cmc").isJsonNull() ? card.get("cmc").getAsDouble() : null,
                str(card, "type_line"),
                str(card, "oracle_text"),
                joined(card, "colors"),
                joined(card, "color_identity"),
                str(card, "rarity"),
                imageNormal,
                imageBack,
                bool(card, "digital"),
                bool(card, "promo"));
    }

    private static String str(final JsonObject obj, final String key) {
        final JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(final JsonObject obj, final String key) {
        final JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static String joined(final JsonObject obj, final String key) {
        final JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final JsonElement item : e.getAsJsonArray()) {
            sb.append(item.getAsString());
        }
        return sb.toString();
    }
}
