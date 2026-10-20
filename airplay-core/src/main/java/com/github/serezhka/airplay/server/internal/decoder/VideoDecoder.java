package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout;
import com.github.serezhka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class VideoDecoder extends ReplayingDecoder<VideoDecoder.DecoderState> {

    public enum DecoderState {
        READ_HEADER,
        READ_PAYLOAD
    }

    public VideoDecoder() {
        super(DecoderState.READ_HEADER);
    }

    private int payloadSize;
    private short payloadType;
    // private short payloadOption;
    private long timestamp;

    /** Diagnostic: log the first packets of every type so a stalled mirror leaves a trail. */
    private long packetsSeen;
    private long skippedPacketsLogged;
    private long decodedPayloadBytes;
    private long lastPeriodicLogAt;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state()) {
            case READ_HEADER:
                ByteBuf headerBuf = in.readSlice(128);
                payloadSize = (int) headerBuf.readUnsignedIntLE();
                payloadType = (short) (headerBuf.readUnsignedShortLE() & 0xff);
                // payloadOption = (short) headerBuf.readUnsignedShortLE();
                timestamp = headerBuf.readLongLE();
                checkpoint(DecoderState.READ_PAYLOAD);
            case READ_PAYLOAD:
                if (payloadType == MirrorPacketLayout.PAYLOAD_VIDEO
                        || payloadType == MirrorPacketLayout.PAYLOAD_CODEC) {
                    ByteBuf payloadBuf = in.readSlice(payloadSize);
                    byte[] payloadBytes = new byte[payloadSize];
                    payloadBuf.readBytes(payloadBytes);
                    checkpoint(DecoderState.READ_HEADER);
                    packetsSeen++;
                    decodedPayloadBytes += payloadSize;
                    if (packetsSeen <= 10 || packetsSeen % 500 == 0) {
                        log.info("VIDEO packet type={} size={} (packets={})", payloadType, payloadSize, packetsSeen);
                    }
                    out.add(new VideoPacket(payloadType, payloadSize, timestamp, payloadBytes));
                } else {
                    decodedPayloadBytes += payloadSize;
                    long now = System.currentTimeMillis();
                    if (skippedPacketsLogged < 10 || payloadSize > 200_000
                            || now - lastPeriodicLogAt > 5_000) {
                        log.info("VIDEO packet skipped: type={} size={} bytes (totalDecoded={})",
                                payloadType, payloadSize, decodedPayloadBytes);
                        skippedPacketsLogged++;
                        lastPeriodicLogAt = now;
                    } else {
                        log.debug("Video packet with type: {}, length: {} bytes is skipped", payloadType, payloadSize);
                    }
                    in.skipBytes(payloadSize);
                    checkpoint(DecoderState.READ_HEADER);
                }
                break;
            default:
                throw new Error("Shouldn't reach here.");
        }
    }
}
