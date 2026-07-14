package forge.headless;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Chunk-3 verification client (docs/PROTOCOL.md): connects to a running
 * CardinalWsServer with the JDK's built-in WebSocket client (deliberately NOT
 * netty - proves the stream is consumable by a foreign client), tallies the
 * received frames, and exits when the end frame arrives.
 *
 * Usage: java -cp forge-headless-...jar forge.headless.SpectateClient
 *            [ws://127.0.0.1:17171/game]
 */
public final class SpectateClient {
    private SpectateClient() {
    }

    public static void main(final String[] args) throws Exception {
        final String uri = args.length > 0 ? args[0] : "ws://127.0.0.1:17171/game";
        final CountDownLatch done = new CountDownLatch(1);
        final Map<String, Integer> frameCounts = new TreeMap<>();
        final long[] totals = {0L, 0L}; // frames, bytes
        final String[] winner = {null};
        final long[] firstDeltaNewCount = {-1};

        final WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(final WebSocket ws, final CharSequence data, final boolean last) {
                buffer.append(data);
                if (last) {
                    final String text = buffer.toString();
                    buffer.setLength(0);
                    handle(text);
                }
                ws.request(1);
                return null;
            }

            private void handle(final String text) {
                totals[0]++;
                totals[1] += text.getBytes(StandardCharsets.UTF_8).length;
                final JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                final String type = json.has("t") ? json.get("t").getAsString() : "?";
                frameCounts.merge(type, 1, Integer::sum);
                if ("delta".equals(type) && firstDeltaNewCount[0] < 0) {
                    firstDeltaNewCount[0] = json.getAsJsonObject("new").size();
                }
                if ("end".equals(type)) {
                    winner[0] = json.get("winner").isJsonNull() ? "(draw)" : json.get("winner").getAsString();
                    done.countDown();
                }
            }

            @Override
            public void onError(final WebSocket ws, final Throwable error) {
                System.out.println("WebSocket error: " + error);
                done.countDown();
            }
        };

        System.out.println("Connecting to " + uri);
        final WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(uri), listener).join();

        final boolean finished = done.await(5, TimeUnit.MINUTES);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        System.out.println("--- spectate stats ---");
        System.out.println("finished: " + finished);
        System.out.println("frames by type: " + frameCounts);
        System.out.println("objects in first delta's 'new' table: " + firstDeltaNewCount[0]);
        System.out.println("total frames: " + totals[0] + ", total bytes: " + totals[1]);
        System.out.println("winner: " + winner[0]);
        System.exit(finished && winner[0] != null ? 0 : 1);
    }
}
