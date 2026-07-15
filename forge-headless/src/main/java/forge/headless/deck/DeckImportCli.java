package forge.headless.deck;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import forge.deck.CardPool;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.headless.HeadlessBootstrap;

/**
 * Deck import runner (Phase 4).
 *
 * Usage: java -cp forge-headless-...jar forge.headless.deck.DeckImportCli
 *            &lt;file.txt | archidekt-url | archidekt:ID | -&gt; [--name X] [--save]
 *
 * "-" reads a pasted decklist from stdin. --save writes a Forge .dck into the
 * profile's constructed decks folder, immediately usable by Main and the
 * play-mode server.
 */
public final class DeckImportCli {
    private DeckImportCli() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: DeckImportCli <file | archidekt-url | archidekt:ID | -> [--name X] [--save]");
            System.exit(2);
        }
        final String source = args[0];
        String name = null;
        boolean save = false;
        for (int i = 1; i < args.length; i++) {
            if ("--save".equals(args[i])) {
                save = true;
            } else if ("--name".equals(args[i]) && i + 1 < args.length) {
                name = args[++i];
            }
        }

        HeadlessBootstrap.boot();

        final String text;
        if (ArchidektImporter.looksLikeArchidekt(source)) {
            final ArchidektImporter.ArchidektDeck fetched = ArchidektImporter.fetch(source);
            text = fetched.decklistText();
            if (name == null) {
                name = fetched.name();
            }
            System.out.println("Fetched from Archidekt: " + fetched.name());
        } else if ("-".equals(source)) {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            if (name == null) {
                name = "Pasted Deck";
            }
        } else {
            final Path file = Paths.get(source);
            text = Files.readString(file, StandardCharsets.UTF_8);
            if (name == null) {
                name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            }
        }

        final DeckImporter.ImportResult result = DeckImporter.fromText(name, text);

        System.out.println("--- import report: " + name + " ---");
        for (final Map.Entry<DeckSection, CardPool> part : result.deck()) {
            System.out.println(part.getKey() + ": " + part.getValue().countAll() + " cards");
            for (final Map.Entry<forge.item.PaperCard, Integer> card : part.getValue()) {
                System.out.println("    " + card.getValue() + " " + card.getKey().getName()
                        + " [" + card.getKey().getEdition() + "]");
            }
        }
        System.out.println("resolved: " + result.resolvedCards() + " cards"
                + (result.isClean() ? " (clean import)" : ""));
        for (final String note : result.notes()) {
            System.out.println("note: " + note);
        }
        if (!result.isClean()) {
            System.out.println("UNRESOLVED (" + result.unresolved().size() + " lines):");
            for (final String line : result.unresolved()) {
                System.out.println("  " + line);
            }
        }

        if (save) {
            final String appData = System.getenv("APPDATA");
            final File out = new File(appData + File.separator + "Forge" + File.separator + "decks"
                    + File.separator + "constructed", sanitize(name) + ".dck");
            DeckSerializer.writeDeck(result.deck(), out);
            System.out.println("saved: " + out);
        }
        System.exit(result.isClean() ? 0 : 1);
    }

    private static String sanitize(final String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
