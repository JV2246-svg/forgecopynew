package forge.headless.server;

import forge.headless.HeadlessBootstrap;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Chunk-3 WebSocket game server (docs/PROTOCOL.md): accepts WS connections on
 * /game and streams an AI-vs-AI match as JSON delta frames (the first delta
 * carries the full world in "new"). One game per connection; the connection
 * closes after the end frame.
 *
 * This is the spectator milestone: no human seat yet. The IGuiGame bridge
 * (blocking questions over the socket) is chunks 4-5.
 *
 * Usage: java -cp forge-headless-...jar forge.headless.server.CardinalWsServer
 *            &lt;deck1.dck&gt; &lt;deck2.dck&gt; [port]
 */
public final class CardinalWsServer {
    public static final int DEFAULT_PORT = 17171;
    public static final String PATH = "/game";

    private CardinalWsServer() {
    }

    public static void main(final String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.out.println("Usage: CardinalWsServer <deck1.dck> <deck2.dck> [port]");
            System.exit(2);
        }
        final int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;

        HeadlessBootstrap.boot();

        final EventLoopGroup boss = new NioEventLoopGroup(1);
        final EventLoopGroup workers = new NioEventLoopGroup();
        try {
            final ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new WebSocketServerProtocolHandler(PATH, null, true))
                                    .addLast(new GameStreamHandler(args[0], args[1]));
                        }
                    });
            final Channel server = bootstrap.bind(port).sync().channel();
            System.out.println("CardinalWsServer listening on ws://127.0.0.1:" + port + PATH);
            server.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }
}
