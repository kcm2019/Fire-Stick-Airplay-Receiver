package com.github.serezhka.airplay.server.internal;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * Event-loop factory, NIO only.
 *
 * Android port notes: Netty is pinned to 4.1 because Netty 4.2's
 * PlatformDependent0 static initializer calls
 * java.lang.invoke.MethodHandles.lookup() unguarded, and that class only
 * exists on Android API 26+ — on Fire OS 6 (API 25) Netty 4.2 dies with
 * NoClassDefFoundError before any server can start. The native epoll
 * transport is unusable on Android (bionic libc), so the epoll branches
 * from upstream are removed here and in the server channel selectors.
 */
final class NettyEventLoops {

    private NettyEventLoops() {
    }

    static EventLoopGroup create() {
        return new NioEventLoopGroup();
    }
}
