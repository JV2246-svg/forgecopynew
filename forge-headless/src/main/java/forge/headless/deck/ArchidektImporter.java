package forge.headless.deck;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.headless.data.ScryfallBulkSync;

/**
 * Archidekt deck import via its public per-deck API (Phase 4; the only deck
 * site in our plan with a real API - docs in CLAUDE.md section 3). Fetches
 * /api/decks/{id}/ and renders the deck as MTGA-style decklist text, which
 * then flows through the same DeckImporter pipeline as pasted lists.
 *
 * Category handling: categories with includedInDeck=false (Maybeboard etc.)
 * are skipped; the premier category maps to the Commander section; a category
 * named Sideboard maps to the sideboard; everything else is maindeck.
 */
public final class ArchidektImporter {
    private static final Pattern DECK_ID = Pattern.compile("archidekt\\.com/(?:api/)?decks/(\\d+)");

    private ArchidektImporter() {
    }

    /** A fetched deck: its Archidekt name plus decklist text for DeckImporter. */
    public record ArchidektDeck(String name, String decklistText) {
    }

    public static boolean looksLikeArchidekt(final String source) {
        return source.contains("archidekt.com") || source.matches("archidekt:\\d+");
    }

    public static ArchidektDeck fetch(final String urlOrId) throws IOException, InterruptedException {
        final String id = extractId(urlOrId);
        final HttpRequest request = HttpRequest.newBuilder(URI.create("https://archidekt.com/api/decks/" + id + "/"))
                .header("User-Agent", ScryfallBulkSync.USER_AGENT)
                .header("Accept", "application/json")
                .build();
        final HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Archidekt API returned HTTP " + response.statusCode() + " for deck " + id);
        }

        final JsonObject deck = JsonParser.parseString(response.body()).getAsJsonObject();
        final String name = deck.get("name").getAsString();

        final Map<String, Boolean> categoryIncluded = new HashMap<>();
        final Map<String, Boolean> categoryPremier = new HashMap<>();
        if (deck.has("categories")) {
            for (final JsonElement e : deck.getAsJsonArray("categories")) {
                final JsonObject cat = e.getAsJsonObject();
                categoryIncluded.put(cat.get("name").getAsString(),
                        !cat.has("includedInDeck") || cat.get("includedInDeck").getAsBoolean());
                categoryPremier.put(cat.get("name").getAsString(),
                        cat.has("isPremier") && cat.get("isPremier").getAsBoolean());
            }
        }

        final StringBuilder main = new StringBuilder();
        final StringBuilder side = new StringBuilder();
        final StringBuilder commander = new StringBuilder();

        for (final JsonElement e : deck.getAsJsonArray("cards")) {
            final JsonObject entry = e.getAsJsonObject();
            final String category = firstCategory(entry);
            if (category != null && !categoryIncluded.getOrDefault(category, true)) {
                continue; // Maybeboard-style category
            }
            final JsonObject card = entry.getAsJsonObject("card");
            final String cardName = card.getAsJsonObject("oracleCard").get("name").getAsString();
            final String set = card.getAsJsonObject("edition").get("editioncode").getAsString().toUpperCase();
            final String collector = card.get("collectorNumber").getAsString();
            final int qty = entry.get("quantity").getAsInt();

            final String line = qty + " " + cardName + " (" + set + ") " + collector + "\n";
            if (category != null && categoryPremier.getOrDefault(category, false)) {
                commander.append(line);
            } else if ("Sideboard".equalsIgnoreCase(category)) {
                side.append(line);
            } else {
                main.append(line);
            }
        }

        final StringBuilder text = new StringBuilder();
        if (commander.length() > 0) {
            text.append("Commander\n").append(commander).append('\n');
        }
        text.append("Main\n").append(main);
        if (side.length() > 0) {
            text.append("\nSideboard\n").append(side);
        }
        return new ArchidektDeck(name, text.toString());
    }

    private static String firstCategory(final JsonObject entry) {
        if (!entry.has("categories") || entry.get("categories").isJsonNull()) {
            return null;
        }
        final JsonArray categories = entry.getAsJsonArray("categories");
        return categories.size() == 0 ? null : categories.get(0).getAsString();
    }

    private static String extractId(final String urlOrId) {
        if (urlOrId.matches("\\d+")) {
            return urlOrId;
        }
        final Matcher m = DECK_ID.matcher(urlOrId);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalArgumentException("Not an Archidekt deck URL or id: " + urlOrId);
    }
}
