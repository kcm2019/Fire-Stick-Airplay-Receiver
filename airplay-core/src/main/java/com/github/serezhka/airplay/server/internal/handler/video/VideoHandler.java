package com.github.serezhka.airplay.server.internal.handler.video;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class VideoHandler extends ChannelInboundHandlerAdapter {

    private final FairPlaySession airPlay;
    private final Playback dataConsumer;

    /** Diagnostic counters for a stalled mirror session. */
    private long codecPackets;
    private long videoPackets;
    private long corruptPackets;

    /**
     * SPS/PPS (Annex-B) from the latest codec packet, awaiting its frame.
     * The sender's contract (both reference receivers rely on it): a codec
     * packet is always followed by an IDR packet with the SAME header
     * timestamp, and the parameter sets belong in-band ahead of that frame.
     */
    private byte[] pendingSpsPps;
    private long pendingSpsPpsTs;
    private boolean hasPendingSpsPps;

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("VIDEO handler error: {}", String.valueOf(cause), cause);
        ctx.close();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        VideoPacket packet = (VideoPacket) msg;
        try {
            if (packet.getPayloadType() == MirrorPacketLayout.PAYLOAD_VIDEO) {
                // Type 0 only: type 1 codec config is not AES-CTR-encrypted on the wire;
                // decrypting it advances the CTR counter and corrupts every later frame.
                // Type 5 is an unencrypted streaming-report plist — same trap.
                airPlay.decryptVideo(packet.getPayload());
                // Never feed partial/corrupt Annex-B to the consumer.
                if (!toAnnexB(packet.getPayload())) {
                    corruptPackets++;
                    if (corruptPackets <= 5) {
                        log.error("VIDEO corrupt Annex-B dropped ({} of {})", corruptPackets, videoPackets);
                    }
                    return;
                }
                byte[] frame = packet.getPayload();
                if (hasPendingSpsPps && packet.getTimestamp() == pendingSpsPpsTs) {
                    // Same timestamp as the codec packet: this is the IDR the
                    // config belongs to — deliver them as one unit (UxPlay's
                    // prepend). The decoder's first unit is then SPS+PPS+IDR,
                    // exactly like both reference receivers.
                    byte[] merged = new byte[pendingSpsPps.length + frame.length];
                    System.arraycopy(pendingSpsPps, 0, merged, 0, pendingSpsPps.length);
                    System.arraycopy(frame, 0, merged, pendingSpsPps.length, frame.length);
                    frame = merged;
                    hasPendingSpsPps = false;
                    pendingSpsPps = null;
                    if (codecPackets <= 3) {
                        log.info("VIDEO config paired with same-timestamp IDR ({} bytes)",
                                merged.length);
                    }
                }
                videoPackets++;
                if (videoPackets == 1 || videoPackets % 500 == 0) {
                    log.info("VIDEO frame #{} delivered to consumer ({} bytes)", videoPackets,
                            frame.length);
                }
                dataConsumer.onVideo(frame);
            } else if (packet.getPayloadType() == MirrorPacketLayout.PAYLOAD_CODEC) {
                codecPackets++;
                if (codecPackets <= 3) {
                    log.info("VIDEO codec-config packet #{} ({} bytes)", codecPackets,
                            packet.getPayload().length);
                }
                byte[] spsPps = prepareSpsPpsNALUnits(packet.getPayload());
                if (hasPendingSpsPps) {
                    // A previous config never met its frame (stream change):
                    // deliver it now so no parameter set is lost.
                    dataConsumer.onVideo(pendingSpsPps);
                }
                pendingSpsPps = spsPps;
                pendingSpsPpsTs = packet.getTimestamp();
                hasPendingSpsPps = true;
                // Also deliver the config as its own unit right away (RPiPlay
                // feeds it immediately as frame_type=0); the paired merged
                // unit then re-delivers it — decoders ignore redundant SPS.
                dataConsumer.onVideo(spsPps);
            }
        } catch (Exception e) {
            // Mirror NALs still arrive for a moment after HLS tears the video process down.
            // A stack trace per packet stalls the event loop and delays /play.
            if (e instanceof IllegalStateException && e.getMessage() != null
                    && e.getMessage().contains("not running")) {
                log.debug("Dropping video packet: {}", e.getMessage());
            } else {
                log.error(e.getMessage(), e);
            }
        }
    }

    /** @return false if length-prefixed NALs are invalid (likely bad decrypt) */
    static boolean toAnnexB(byte[] payload) {
        int idx = 0;
        while (idx + 4 <= payload.length) {
            int naluSize = (payload[idx + 3] & 0xFF)
                    | ((payload[idx + 2] & 0xFF) << 8)
                    | ((payload[idx + 1] & 0xFF) << 16)
                    | ((payload[idx] & 0xFF) << 24);
            if (naluSize <= 0 || idx + 4 + naluSize > payload.length) {
                log.error("Video packet contains corrupted NAL unit. It might be decrypt error");
                return false;
            }
            payload[idx] = 0;
            payload[idx + 1] = 0;
            payload[idx + 2] = 0;
            payload[idx + 3] = 1;
            idx += naluSize + 4;
        }
        return true;
    }

    private byte[] prepareSpsPpsNALUnits(byte[] payload) {
        ByteBuf payloadBuf = Unpooled.wrappedBuffer(payload);
        try {
            payloadBuf.readerIndex(6);

            short spsLen = (short) payloadBuf.readUnsignedShort();
            byte[] sequenceParameterSet = new byte[spsLen];
            payloadBuf.readBytes(sequenceParameterSet);

            payloadBuf.skipBytes(1); // pps count

            short ppsLen = (short) payloadBuf.readUnsignedShort();
            byte[] pictureParameterSet = new byte[ppsLen];
            payloadBuf.readBytes(pictureParameterSet);

            int spsPpsLen = spsLen + ppsLen + 8;
            log.debug("SPS PPS length: {}", spsPpsLen);
            byte[] spsPps = new byte[spsPpsLen];
            spsPps[0] = 0;
            spsPps[1] = 0;
            spsPps[2] = 0;
            spsPps[3] = 1;
            System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen);
            spsPps[spsLen + 4] = 0;
            spsPps[spsLen + 5] = 0;
            spsPps[spsLen + 6] = 0;
            spsPps[spsLen + 7] = 1;
            System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen);

            return spsPps;
        } finally {
            payloadBuf.release();
        }
    }
}
