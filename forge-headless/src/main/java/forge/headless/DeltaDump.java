package forge.headless;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IntSummaryStatistics;
import java.util.List;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.server.DeltaSyncManager;
import forge.headless.protocol.JsonViewCodec;
import forge.player.GamePlayerUtil;

/**
 * Chunk-2 test harness (docs/PROTOCOL.md): plays one quiet AI-vs-AI game
 * while collecting a real DeltaPacket at every phase change (on the game
 * thread, as DeltaSyncManager requires) and encoding each to JSON.
 *
 * Writes the first packet (initial world, everything in "new") and a
 * mid-game sample packet, then prints size statistics.
 *
 * Usage: java -cp forge-headless-...jar forge.headless.DeltaDump
 *            &lt;deck1.dck&gt; &lt;deck2.dck&gt; [outDir]
 */
public final class DeltaDump {
    private DeltaDump() {
    }

    /** Collects and encodes a delta on every phase-change game event. */
    public static final class PhaseCollector {
        private static final int MID_SAMPLE_INDEX = 25;

        private final Game game;
        private final DeltaSyncManager sync = new DeltaSyncManager();
        // serializeNulls is required: a null property in a delta means
        // "reverted to default" and must survive onto the wire
        private final Gson gson = new GsonBuilder().serializeNulls().create();
        private final List<Integer> sizes = new ArrayList<>();
        private JsonObject firstPacket;
        private JsonObject midPacket;
        private JsonObject lastPacket;
        private int emptyPackets;
        private long encodeNanos;

        PhaseCollector(final Game game) {
            this.game = game;
        }

        @Subscribe
        public void onPhase(final GameEventTurnPhase event) {
            final DeltaPacket packet = sync.collectDeltas(game.getView());
            if (packet.isEmpty()) {
                emptyPackets++;
                return;
            }
            final long start = System.nanoTime();
            final JsonObject json = JsonViewCodec.delta(packet);
            encodeNanos += System.nanoTime() - start;
            sizes.add(gson.toJson(json).getBytes(StandardCharsets.UTF_8).length);
            if (firstPacket == null) {
                firstPacket = json;
            } else if (sizes.size() == MID_SAMPLE_INDEX) {
                midPacket = json;
            }
            lastPacket = json;
        }
    }

    public static void main(final String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: DeltaDump <deck1.dck> <deck2.dck> [outDir]");
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

        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        final Match match = new Match(rules, players, "DeltaMatch");
        final Game game = match.createGame();

        final PhaseCollector collector = new PhaseCollector(game);
        game.subscribeToEvents(collector);
        match.startGame(game);

        final String outDir = args.length > 2 ? args[2] : ".";
        final Gson pretty = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
        writeJson(pretty, collector.firstPacket, outDir + File.separator + "delta-first.json");
        writeJson(pretty, collector.midPacket != null ? collector.midPacket : collector.lastPacket,
                outDir + File.separator + "delta-sample.json");

        final IntSummaryStatistics stats = collector.sizes.stream().mapToInt(Integer::intValue).summaryStatistics();
        System.out.println("--- delta stats ---");
        System.out.println("packets: " + stats.getCount() + " non-empty, " + collector.emptyPackets + " empty (skipped)");
        System.out.printf("compact bytes: first=%d avg=%.0f max=%d min=%d total=%d%n",
                collector.sizes.isEmpty() ? 0 : collector.sizes.get(0),
                stats.getAverage(), stats.getMax(), stats.getMin(), stats.getSum());
        System.out.printf("encode time: %.1f ms total across all packets%n", collector.encodeNanos / 1_000_000.0);
        System.out.println("wrote delta-first.json (initial world) and delta-sample.json (mid-game)");
        System.exit(0);
    }

    private static void writeJson(final Gson gson, final JsonObject json, final String path) throws IOException {
        if (json == null) {
            return;
        }
        try (Writer w = new FileWriter(path, StandardCharsets.UTF_8)) {
            w.write(gson.toJson(json));
        }
    }
}
