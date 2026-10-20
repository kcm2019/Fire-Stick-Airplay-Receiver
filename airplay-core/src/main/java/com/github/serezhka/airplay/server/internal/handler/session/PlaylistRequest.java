package com.github.serezhka.airplay.server.internal.handler.session;

import io.netty.channel.ChannelHandlerContext;

import java.util.Map;

// Android port: record replaced with a plain class (java.lang.Record is absent on API 25).
public final class PlaylistRequest {

    private final ChannelHandlerContext context;
    private final String protocol;
    private final String method;
    private final String uri;
    private final Map<String, String> requestHeaders;
    private final byte[] requestBody;
    private final boolean keepAlive;

    public PlaylistRequest(ChannelHandlerContext context, String protocol, String method, String uri,
                           Map<String, String> requestHeaders, byte[] requestBody, boolean keepAlive) {
        this.context = context;
        this.protocol = protocol;
        this.method = method;
        this.uri = uri;
        this.requestHeaders = requestHeaders;
        this.requestBody = requestBody;
        this.keepAlive = keepAlive;
    }

    public ChannelHandlerContext context() { return context; }
    public String protocol() { return protocol; }
    public String method() { return method; }
    public String uri() { return uri; }
    public Map<String, String> requestHeaders() { return requestHeaders; }
    public byte[] requestBody() { return requestBody; }
    public boolean keepAlive() { return keepAlive; }
}
