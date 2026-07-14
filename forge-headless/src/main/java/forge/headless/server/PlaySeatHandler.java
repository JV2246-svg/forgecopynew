package forge.headless.server;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.headless.MatchFactory;
import forge.player.GamePlayerUtil;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Play-mode connection handler (docs/PROTOCOL.md chunk 4): the connected
 * client occupies a real human seat (deck 1) against an AI (deck 2). The
 * seat is wired through HostedMatch exactly like a local GUI player, but
 * its IGuiGame is a JsonGuiGame speaking JSON over this WebSocket.
 *
 * Inbound frames: {"t":"reply","id":N,"v":...} answers a blocking call;
 * {"t":"input","m":"passPriority"|...} drives the game controller.
 */
final class PlaySeatHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final AtomicLong GAME_COUNTER = new AtomicLong();

    private final String humanDeckPath;
    private final String aiDeckPath;
    private JsonGuiGame gui;

    PlaySeatHandler(final String humanDeckPath, final String aiDeckPath) {
        this.humanDeckPath = humanDeckPath;
        this.aiDeckPath = aiDeckPath;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            final long gameNumber = GAME_COUNTER.incrementAndGet();
            System.out.println("Player connected: " + ctx.channel().remoteAddress() + " -> seat in game " + gameNumber);
            gui = new JsonGuiGame(ctx.channel());
            final Thread starter = new Thread(() -> startMatch(gameNumber), "cardinal-play-" + gameNumber);
            starter.setDaemon(true);
            starter.start();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    private void startMatch(final long gameNumber) {
        try {
            final RegisteredPlayer human = MatchFactory.registeredPlayerFromDeckFile(humanDeckPath);
            human.setPlayer(GamePlayerUtil.getGuiPlayer());
            final RegisteredPlayer ai = MatchFactory.registeredPlayerFromDeckFile(aiDeckPath);
            ai.setPlayer(GamePlayerUtil.createAiPlayer("Ai-" + ai.getDeck().getName(), 0));

            final HostedMatch hosted = GuiBase.getInterface().hostMatch();
            hosted.startMatch(GameType.Constructed, EnumSet.of(GameType.Constructed),
                    List.of(human, ai), human, gui);
            System.out.println("Game " + gameNumber + " started; human seat is live");
        } catch (final Exception e) {
            System.out.println("Game " + gameNumber + " failed to start: " + e);
            e.printStackTrace();
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
        if (gui == null) {
            return;
        }
        final JsonObject json = JsonParser.parseString(frame.text()).getAsJsonObject();
        final String type = json.has("t") ? json.get("t").getAsString() : "";
        switch (type) {
            case "reply":
                gui.handleReply(json.get("id").getAsInt(), json.get("v"));
                break;
            case "input":
                gui.handleInput(json);
                break;
            default:
                System.out.println("Unknown client frame: " + frame.text());
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        System.out.println("Connection error: " + cause);
        ctx.close();
    }
}
