package forge.headless;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.Match;
import forge.headless.protocol.JsonViewCodec;

/**
 * Chunk-1 test harness (docs/PROTOCOL.md): plays one quiet AI-vs-AI game,
 * then serializes the finished game's view tree to JSON.
 *
 * Usage: java -cp forge-headless-...jar forge.headless.SnapshotDump
 *            &lt;deck1.dck&gt; &lt;deck2.dck&gt; [out.json]
 */
public final class SnapshotDump {
    private SnapshotDump() {
    }

    public static void main(final String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: SnapshotDump <deck1.dck> <deck2.dck> [out.json]");
            System.exit(2);
        }

        HeadlessBootstrap.boot();

        final Match match = MatchFactory.createTwoAiMatch(args[0], args[1], "SnapshotMatch");
        final Game game = match.createGame();
        match.startGame(game);

        final long started = System.currentTimeMillis();
        final JsonObject snapshot = JsonViewCodec.snapshot(game.getView());
        final long encodeMs = System.currentTimeMillis() - started;

        final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        final String pretty = gson.toJson(snapshot);

        final String outPath = args.length > 2 ? args[2] : "snapshot.json";
        try (Writer w = new FileWriter(outPath, StandardCharsets.UTF_8)) {
            w.write(pretty);
        }

        final Map<String, Integer> countsByType = new java.util.TreeMap<>();
        for (final Map.Entry<String, JsonElement> e : snapshot.getAsJsonObject("objects").entrySet()) {
            final String type = e.getKey().substring(0, e.getKey().indexOf(':'));
            countsByType.merge(type, 1, Integer::sum);
        }

        System.out.println("--- snapshot stats ---");
        System.out.println("encode time: " + encodeMs + " ms");
        System.out.println("objects by type: " + countsByType);
        System.out.println("pretty JSON size: " + Files.size(Paths.get(outPath)) + " bytes -> " + outPath);
        System.out.println("compact JSON size: " + new Gson().toJson(snapshot).getBytes(StandardCharsets.UTF_8).length + " bytes");
        System.exit(0);
    }
}
