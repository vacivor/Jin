package io.jin.web.adapter;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.jin.web.HttpHandler;
import io.jin.web.HttpMethod;
import io.jin.web.HttpRequest;
import io.jin.web.HttpResponse;
import io.jin.web.JinServer;

import java.util.HashMap;
import java.util.Map;

public class NettyJinServer implements JinServer {
    private final int port;
    private final HttpHandler handler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;

    public NettyJinServer(int port, HttpHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(1_048_576))
                                    .addLast(new JinNettyHandler(handler));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            channelFuture = bootstrap.bind(port).sync();
            System.out.println("Jin running on Netty at http://localhost:" + port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (channelFuture != null) {
                channelFuture.channel().close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
        }
    }

    private static final class JinNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final HttpHandler handler;

        private JinNettyHandler(HttpHandler handler) {
            this.handler = handler;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
            HttpMethod method = HttpMethod.fromToken(msg.method().name());
            if (method == null) {
                FullHttpResponse methodNotAllowed = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.METHOD_NOT_ALLOWED,
                        Unpooled.copiedBuffer("Method Not Allowed: " + msg.method().name(), CharsetUtil.UTF_8)
                );
                methodNotAllowed.headers().set("Content-Type", "text/plain; charset=UTF-8");
                methodNotAllowed.headers().set("Content-Length", methodNotAllowed.content().readableBytes());
                ctx.writeAndFlush(methodNotAllowed);
                return;
            }
            String[] uriParts = msg.uri().split("\\?", 2);
            String path = uriParts[0];
            String query = uriParts.length == 2 ? uriParts[1] : null;
            String body = msg.content().toString(CharsetUtil.UTF_8);
            Map<String, String> headers = new HashMap<>();
            msg.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));

            HttpRequest request = new HttpRequest(method, path, query, headers, body);
            HttpResponse response = handler.handle(request);

            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.getStatus()),
                    Unpooled.copiedBuffer(response.getBody(), CharsetUtil.UTF_8)
            );
            response.getHeaders().forEach(nettyResponse.headers()::set);
            if (!nettyResponse.headers().contains("Content-Type")) {
                nettyResponse.headers().set("Content-Type", "text/plain; charset=UTF-8");
            }
            nettyResponse.headers().set("Content-Length", nettyResponse.content().readableBytes());

            ctx.writeAndFlush(nettyResponse);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
