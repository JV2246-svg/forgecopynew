package forge.headless;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Chunk-4 verification client (docs/PROTOCOL.md): occupies the human seat on
 * a play-mode CardinalWsServer and plays the dumbest legal game of Magic:
 * every blocking question is answered null (server substitutes the default),
 * and every button prompt is answered OK if enabled, else Cancel. Net
 * effect: keep opening hand, never play anything, pass every priority,
 * decline all combat - and lose politely.
 *
 * Exits when afterGameEnd arrives. Success = the whole IGuiGame round-trip
 * works over JSON.
 *
 * Usage: java -cp forge-headless-...jar forge.headless.ScriptedClient
 *            [ws://127.0.0.1:17171/game]
 */
public final class ScriptedClient {
    private ScriptedClient() {
    }

    public static void main(final String[] args) throws Exception {
        final String uri = args.length > 0 ? args[0] : "ws://127.0.0.1:17171/game";
        final int timeoutSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 600;
        final boolean trace = args.length > 2 && "--trace".equals(args[2]);
        final CountDownLatch done = new CountDownLatch(1);
        final Map<String, Integer> callCounts = new TreeMap<>();
        final long[] totals = {0, 0, 0}; // frames, deltas, replies sent
        final Gson gson = new Gson();

        final WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            private final java.util.ArrayDeque<Integer> selectables = new java.util.ArrayDeque<>();

            @Override
            public CompletionStage<?> onText(final WebSocket ws, final CharSequence data, final boolean last) {
                buffer.append(data);
                if (last) {
                    final String text = buffer.toString();
                    buffer.setLength(0);
                    try {
                        handle(ws, text);
                    } catch (final Exception e) {
                        System.out.println("Error handling frame: " + e + " frame=" + trim(text));
                    }
                }
                ws.request(1);
                return null;
            }

            private void handle(final WebSocket ws, final String text) {
                totals[0]++;
                final JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                final String type = json.has("t") ? json.get("t").getAsString() : "?";

                if ("delta".equals(type)) {
                    totals[1]++;
                    return;
                }
                if (!"call".equals(type)) {
                    return;
                }

                final String method = json.get("m").getAsString();
                callCounts.merge(method, 1, Integer::sum);
                if (trace) {
                    System.out.println("<< " + trim(text));
                }

                if (json.has("id")) {
                    // Blocking question: answer null, server picks the default
                    final JsonObject reply = new JsonObject();
                    reply.addProperty("t", "reply");
                    reply.addProperty("id", json.get("id").getAsInt());
                    reply.add("v", com.google.gson.JsonNull.INSTANCE);
                    ws.sendText(gson.toJson(reply), true);
                    totals[2]++;
                    return;
                }

                switch (method) {
                    case "updateButtons": {
                        final JsonArray a = json.getAsJsonArray("a");
                        final boolean okEnabled = a.get(3).getAsBoolean();
                        final boolean cancelEnabled = a.get(4).getAsBoolean();
                        if (okEnabled) {
                            sendInput(ws, "selectButtonOk");
                        } else if (cancelEnabled) {
                            sendInput(ws, "selectButtonCancel");
                        } else if (!selectables.isEmpty()) {
                            // Mandatory selection (e.g. discard to hand size):
                            // no button works, so pick the next offered card
                            final JsonObject sel = new JsonObject();
                            sel.addProperty("t", "input");
                            sel.addProperty("m", "selectCard");
                            final JsonObject arg = new JsonObject();
                            arg.addProperty("card", selectables.poll());
                            sel.add("a", arg);
                            ws.sendText(gson.toJson(sel), true);
                        }
                        break;
                    }
                    case "setSelectables": {
                        final JsonArray cards = json.getAsJsonArray("a").get(0).getAsJsonArray();
                        selectables.clear();
                        for (final var card : cards) {
                            final String ref = card.getAsJsonObject().get("$ref").getAsString();
                            selectables.add(Integer.parseInt(ref.substring(ref.indexOf(':') + 1)));
                        }
                        break;
                    }
                    case "clearSelectables":
                        selectables.clear();
                        break;
                    case "finishGame":
                    case "afterGameEnd":
                        done.countDown();
                        break;
                    default:
                        // fire-and-forget UI call; nothing to do
                }
            }

            private void sendInput(final WebSocket ws, final String method) {
                final JsonObject input = new JsonObject();
                input.addProperty("t", "input");
                input.addProperty("m", method);
                ws.sendText(gson.toJson(input), true);
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

        final boolean finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (final Exception ignored) {
            // channel may already be closed by the server
        }

        System.out.println("--- scripted game stats ---");
        System.out.println("finished: " + finished);
        System.out.println("total frames: " + totals[0] + " (deltas: " + totals[1] + ", replies sent: " + totals[2] + ")");
        System.out.println("calls by method: " + callCounts);
        System.exit(finished ? 0 : 1);
    }

    private static String trim(final String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
