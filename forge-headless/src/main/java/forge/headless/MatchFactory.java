package forge.headless;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;

/** Shared match construction for the headless entry points. */
public final class MatchFactory {
    private MatchFactory() {
    }

    /**
     * Builds a constructed match between two AI players from .dck file paths.
     * @throws IllegalArgumentException if a deck fails to load
     */
    public static Match createTwoAiMatch(final String deckPath1, final String deckPath2, final String matchName) {
        final String[] paths = {deckPath1, deckPath2};
        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Deck deck = DeckSerializer.fromFile(new File(paths[i]));
            if (deck == null) {
                throw new IllegalArgumentException("Could not load deck: " + paths[i]);
            }
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("Ai(" + (i + 1) + ")-" + deck.getName(), i));
            players.add(rp);
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        return new Match(rules, players, matchName);
    }
}
