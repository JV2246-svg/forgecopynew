package forge.headless.deck;

import java.util.ArrayList;
import java.util.List;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;

/**
 * Universal decklist import (Phase 4): thin orchestration around forge-core's
 * battle-tested DeckRecognizer, which already understands plain lists, MTGA
 * exports ("4 Name (SET) 123"), sideboard/commander section headers, and
 * set/collector-number hints. Anything the recognizer can't resolve against
 * the engine's card database is reported, not silently dropped.
 */
public final class DeckImporter {
    private DeckImporter() {
    }

    /** Outcome of an import: the (possibly partial) deck plus a fidelity report. */
    public record ImportResult(Deck deck, int resolvedCards, List<String> unresolved, List<String> notes) {
        public boolean isClean() {
            return unresolved.isEmpty();
        }
    }

    public static ImportResult fromText(final String deckName, final String text) {
        final DeckRecognizer recognizer = new DeckRecognizer();
        // Seed the parse in Main: without a leading section header the recognizer
        // parks commander-eligible cards (any instant/sorcery qualifies via
        // Oathbreaker signature spells) in the Commander section. Pasted lists
        // without headers are maindecks by convention; explicit headers in the
        // text still switch sections normally.
        final List<DeckRecognizer.Token> tokens = recognizer.parseCardList(("Main\n" + text).split("\\r?\\n"));

        final Deck deck = new Deck(deckName);
        final List<String> unresolved = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        int resolved = 0;

        for (final DeckRecognizer.Token token : tokens) {
            if (token.isCardTokenForDeck()) {
                deck.getOrCreate(token.getTokenSection()).add(token.getCard(), token.getQuantity());
                resolved += token.getQuantity();
                continue;
            }
            switch (token.getType()) {
                case UNKNOWN_CARD:
                    unresolved.add(describe(token));
                    break;
                case UNSUPPORTED_CARD:
                    unresolved.add(describe(token) + " (unsupported)");
                    break;
                case DECK_NAME:
                    notes.add("decklist declares name: " + token.getText());
                    break;
                default:
                    // section markers, placeholders, comments - structural, not content
            }
        }
        return new ImportResult(deck, resolved, unresolved, notes);
    }

    private static String describe(final DeckRecognizer.Token token) {
        return token.getQuantity() > 0 ? token.getQuantity() + " " + token.getText() : token.getText();
    }
}
