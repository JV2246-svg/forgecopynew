package forge.headless.server;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.Match;
import forge.game.event.GameEventTurnPhase;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.server.DeltaSyncManager;
import forge.headless.MatchFactory;
import forge.headless.protocol.JsonViewCodec;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Per-connection handler: when the WebSocket handshake completes, starts an
 * AI-vs-AI game on a dedicated thread and streams JSON deltas collected at
 * every phase change. Client text frames are logged (no inputs consumed yet
 * in spectator mode).
 */
final class GameStreamHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final AtomicLong GAME_COUNTER = new AtomicLong();

    // serializeNulls: null delta values mean "reverted to default" (see JsonViewCodec)
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final String deckPath1;
    private final String deckPath2;

    GameStreamHandler(final String deckPath1, final String deckPath2) {
        this.deckPath1 = deckPath1;
        this.deckPath2 = deckPath2;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            final long gameNumber = GAME_COUNTER.incrementAndGet();
            System.out.println("Client connected: " + ctx.channel().remoteAddress() + " -> starting game " + gameNumber);
            final Thread gameThread = new Thread(() -> runGame(ctx.channel(), gameNumber), "cardinal-game-" + gameNumber);
            gameThread.setDaemon(true);
            gameThread.start();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
        // Spectator mode: no client inputs consumed yet (chunks 4-5)
        System.out.println("Client frame (ignored in spectator mode): " + frame.text());
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        System.out.println("Connection error: " + cause);
        ctx.close();
    }

    private void runGame(final Channel channel, final long gameNumber) {
        try {
            final Match match = MatchFactory.createTwoAiMatch(deckPath1, deckPath2, "CardinalGame" + gameNumber);
            final Game game = match.createGame();
            final DeltaSyncManager sync = new DeltaSyncManager();

            final JsonObject hello = new JsonObject();
            hello.addProperty("t", "hello");
            hello.addProperty("proto", 1);
            hello.addProperty("mode", "spectator");
            send(channel, hello);

            game.subscribeToEvents(new Object() {
                @Subscribe
                public void onPhase(final GameEventTurnPhase event) {
                    final DeltaPacket packet = sync.collectDeltas(game.getView());
                    if (!packet.isEmpty() && channel.isActive()) {
                        send(channel, JsonViewCodec.delta(packet));
                    }
                }
            });

            match.startGame(game);

            // Final state (game-over props) may have changed after the last phase event
            final DeltaPacket last = sync.collectDeltas(game.getView());
            if (!last.isEmpty()) {
                send(channel, JsonViewCodec.delta(last));
            }

            final JsonObject end = new JsonObject();
            end.addProperty("t", "end");
            end.addProperty("winner", game.getOutcome().isDraw() ? null
                    : game.getOutcome().getWinningLobbyPlayer().getName());
            send(channel, end);
            System.out.println("Game " + gameNumber + " finished; closing connection");
        } catch (final Exception e) {
            System.out.println("Game " + gameNumber + " failed: " + e);
        } finally {
            channel.close();
        }
    }

    private void send(final Channel channel, final JsonObject json) {
        final String text = gson.toJson(json);
        channel.writeAndFlush(new TextWebSocketFrame(text));
        if (text.getBytes(StandardCharsets.UTF_8).length > 10000) {
            System.out.println("Sent large frame: " + text.length() + " chars");
        }
    }
}
