package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.decoder.VideoDecoder;
import com.github.serezhka.airplay.server.internal.handler.video.VideoHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class VideoServer implements Runnable {

    private final FairPlaySession airPlay;

    private Thread thread;
    private Playback airPlayConsumer;

    @Getter
    private int port;

    public void start(Playback airPlayConsumer) throws InterruptedException {
        this.airPlayConsumer = airPlayConsumer;
        thread = new Thread(this);
        thread.start();
        synchronized (this) {
            wait();
        }
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
            airPlayConsumer = null;
        }
    }

    /** True while the accept thread is alive (mirror video path). */
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        var serverBootstrap = new ServerBootstrap();
        var bossGroup = eventLoopGroup();
        var workerGroup = eventLoopGroup();
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast("videoDiag", new VideoDiagHandler());
                            ch.pipeline().addLast("videoDecoder", new VideoDecoder());
                            ch.pipeline().addLast("videoHandler", new VideoHandler(airPlay, airPlayConsumer));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            var channelFuture = serverBootstrap.bind().sync();

            log.info("AirPlay video server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.debug("AirPlay video server interrupted");
        } finally {
            log.debug("AirPlay video server stopped");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return NettyEventLoops.create();
    }

    /**
     * Diagnostic tap for the mirror video TCP stream: reports connect /
     * disconnect, byte counts and a hex preview of the first chunks so the
     * next log run shows whether the sender reaches the data port at all.
     * Pure observer — never consumes or reorders the buffers.
     */
    private static final class VideoDiagHandler extends ChannelInboundHandlerAdapter {

        private long chunks;
        private long bytes;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            log.info("VIDEO data channel connected from {}",
                    ctx.channel().remoteAddress());
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            log.info("VIDEO data channel closed: chunks={} bytes={}",
                    chunks, bytes);
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf buf) {
                chunks++;
                int readable = buf.readableBytes();
                bytes += readable;
                if (chunks <= 5) {
                    int previewLen = Math.min(16, readable);
                    byte[] preview = new byte[previewLen];
                    buf.getBytes(buf.readerIndex(), preview);
                    log.info("VIDEO chunk #{} size={} head={}", chunks, readable, toHex(preview));
                } else if (chunks % 500 == 0) {
                    log.info("VIDEO chunks={} bytes={}", chunks, bytes);
                }
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("VIDEO data channel error: {}", String.valueOf(cause), cause);
            ctx.close();
        }

        private static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        }
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        // NIO only on Android (see NettyEventLoops).
        return NioServerSocketChannel.class;
    }
}
