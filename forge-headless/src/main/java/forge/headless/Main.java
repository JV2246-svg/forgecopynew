package forge.headless;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;

/**
 * Headless Forge entry point: boots the engine with no UI and no telemetry,
 * then plays AI-vs-AI games from the command line.
 *
 * Usage: java -jar forge-headless.jar &lt;deck1.dck&gt; &lt;deck2.dck&gt; [numGames]
 * The assets directory (containing res/) defaults to the working directory;
 * override with -Dforge.assets.dir=&lt;path&gt;.
 */
public final class Main {
    private Main() {
    }

    public static void main(final String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java -jar forge-headless.jar <deck1.dck> <deck2.dck> [numGames]");
            System.out.println("Deck arguments are paths to .dck files.");
            System.out.println("Assets dir (folder containing res/) defaults to the working directory;");
            System.out.println("override with -Dforge.assets.dir=<path>.");
            System.exit(2);
        }

        HeadlessBootstrap.boot();

        final List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Deck deck = DeckSerializer.fromFile(new File(args[i]));
            if (deck == null) {
                System.err.println("Could not load deck: " + args[i]);
                System.exit(1);
            }
            final RegisteredPlayer rp = new RegisteredPlayer(deck);
            rp.setPlayer(GamePlayerUtil.createAiPlayer("Ai(" + (i + 1) + ")-" + deck.getName(), i));
            players.add(rp);
        }

        final int nGames = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));

        final Match match = new Match(rules, players, "HeadlessMatch");
        for (int i = 0; i < nGames; i++) {
            playGame(match, i, rules.getSimTimeout());
        }

        System.exit(0);
    }

    private static void playGame(final Match match, final int gameIndex, final int timeoutSeconds) {
        final Game game = match.createGame();
        final long started = System.currentTimeMillis();

        try {
            runWithTimeout(() -> match.startGame(game), timeoutSeconds, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            System.out.println("Game exceeded " + timeoutSeconds + "s - scoring as draw");
        } catch (final Exception | StackOverflowError e) {
            e.printStackTrace();
        } finally {
            // no-op when the game already ended normally
            game.setGameOver(GameEndReason.Draw);
        }

        final long elapsed = System.currentTimeMillis() - started;

        final List<GameLogEntry> log = game.getGameLog().getLogEntries(null);
        Collections.reverse(log);
        for (final GameLogEntry entry : log) {
            System.out.println(entry);
        }

        if (game.getOutcome().isDraw()) {
            System.out.printf("%nGame Result: Game %d ended in a Draw! Took %d ms.%n", gameIndex + 1, elapsed);
        } else {
            System.out.printf("%nGame Result: Game %d ended in %d ms. %s has won!%n",
                    gameIndex + 1, elapsed, game.getOutcome().getWinningLobbyPlayer().getName());
        }
    }

    private static void runWithTimeout(final Runnable task, final long timeout, final TimeUnit unit)
            throws TimeoutException, InterruptedException, ExecutionException {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> future = executor.submit(task);
        executor.shutdown();
        try {
            future.get(timeout, unit);
        } catch (final TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }
}
