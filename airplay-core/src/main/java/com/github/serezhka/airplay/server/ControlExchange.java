package com.github.serezhka.airplay.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Android port: record replaced with a plain class (java.lang.Record is
// absent on API 25) and java.time.Instant replaced with millis.
public final class ControlExchange {

    private final long timestampMillis;
    private final String sessionId;
    private final String protocol;
    private final String method;
    private final String uri;
    private final Map<String, String> requestHeaders;
    private final byte[] requestBody;
    private final int responseStatus;
    private final Map<String, String> responseHeaders;
    private final byte[] responseBody;

    public ControlExchange(long timestampMillis,
                           String sessionId,
                           String protocol,
                           String method,
                           String uri,
                           Map<String, String> requestHeaders,
                           byte[] requestBody,
                           int responseStatus,
                           Map<String, String> responseHeaders,
                           byte[] responseBody) {
        this.timestampMillis = timestampMillis;
        this.sessionId = sessionId;
        this.protocol = protocol;
        this.method = method;
        this.uri = uri;
        this.requestHeaders = copyHeaders(requestHeaders);
        this.requestBody = requestBody == null ? new byte[0] : requestBody;
        this.responseStatus = responseStatus;
        this.responseHeaders = copyHeaders(responseHeaders);
        this.responseBody = responseBody == null ? new byte[0] : responseBody;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public String sessionId() {
        return sessionId;
    }

    public String protocol() {
        return protocol;
    }

    public String method() {
        return method;
    }

    public String uri() {
        return uri;
    }

    public Map<String, String> requestHeaders() {
        return requestHeaders;
    }

    public byte[] requestBody() {
        return requestBody;
    }

    public int responseStatus() {
        return responseStatus;
    }

    public Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    public byte[] responseBody() {
        return responseBody;
    }

    public String requestContentType() {
        return header(requestHeaders, "Content-Type");
    }

    public String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        String expected = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && expected.equals(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<>(headers);
    }
}
