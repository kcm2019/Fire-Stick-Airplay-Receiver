package com.github.serezhka.airplay.server.internal.packet;

import lombok.Data;

@Data
public class VideoPacket {

    private final int payloadType;
    private final int payloadSize;

    /** Raw timestamp from the 128-byte packet header (bytes 8-15). */
    private final long timestamp;

    private final byte[] payload;
}
