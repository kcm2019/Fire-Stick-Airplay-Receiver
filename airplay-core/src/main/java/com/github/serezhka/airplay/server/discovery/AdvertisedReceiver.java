package com.github.serezhka.airplay.server.discovery;

import java.util.EnumSet;
import java.util.Set;

/**
 * Receiver advertisement profile (mDNS TXT + {@code GET /info}).
 * <p>
 * {@link #ADVERTISED} is only what this server implements.
 * With HLS off, {@link AirPlayFeature#VIDEO_PLAYBACK} is removed.
 * FairPlay video decrypt stays: that is the mirror stream, not HLS.
 */
public final class AdvertisedReceiver {

    /**
     * Screen mirroring, FairPlay, legacy pairing, RAOP, and the codecs we decode.
     * Video playback ({@code /play}, volume, HLS, play queue) is included and
     * dropped as a group when {@code airplay.hls-enabled} is false.
     */
    private static final Set<AirPlayFeature> ADVERTISED = EnumSet.of(
            AirPlayFeature.VIDEO,
            AirPlayFeature.VIDEO_FAIRPLAY,
            AirPlayFeature.VIDEO_VOLUME_CONTROL,
            AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
            AirPlayFeature.SCREEN,
            AirPlayFeature.AUDIO,
            AirPlayFeature.FPSAP_V2_5_AES_GCM,
            AirPlayFeature.AUTHENTICATION_4,
            AirPlayFeature.AUDIO_FORMAT_2,
            AirPlayFeature.AUDIO_FORMAT_3,
            AirPlayFeature.AUDIO_FORMAT_4,
            AirPlayFeature.SUPPORTS_LEGACY_PAIRING,
            AirPlayFeature.RAOP,
            AirPlayFeature.SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE);

    public static final long FEATURES = features(true);
    public static final int FEATURES_LO = (int) FEATURES;
    public static final int FEATURES_HI = (int) (FEATURES >>> 32);
    public static final String FEATURES_TXT = featuresTxt(true);

    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;
    public static final String MDNS_FLAGS = "0x44";
    public static final int VV = 2;

    private AdvertisedReceiver() {
    }

    public static String airTunesServerHeader() {
        return "AirTunes/" + SOURCE_VERSION;
    }

    public static Set<AirPlayFeature> selected(boolean hlsEnabled) {
        return selected(hlsEnabled, false, false);
    }

    /**
     * Android port: receiver-mode aware selection.
     * Audio-only drops every screen/video feature so senders present this
     * receiver as an audio target (speaker-style), not a mirroring target.
     * {@code preferHevc} adds the screen multi-codec feature used to
     * negotiate HEVC mirroring.
     */
    public static Set<AirPlayFeature> selected(boolean hlsEnabled, boolean audioOnly, boolean preferHevc) {
        EnumSet<AirPlayFeature> selected = EnumSet.copyOf(ADVERTISED);
        if (!hlsEnabled) {
            selected.removeAll(AirPlayFeature.VIDEO_PLAYBACK);
        }
        if (audioOnly) {
            selected.remove(AirPlayFeature.VIDEO);
            selected.remove(AirPlayFeature.VIDEO_FAIRPLAY);
            selected.remove(AirPlayFeature.VIDEO_VOLUME_CONTROL);
            selected.remove(AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS);
            selected.remove(AirPlayFeature.SCREEN);
            selected.remove(AirPlayFeature.SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE);
            selected.removeAll(AirPlayFeature.VIDEO_PLAYBACK);
        } else if (preferHevc) {
            selected.add(AirPlayFeature.SUPPORTS_SCREEN_MULTI_CODEC);
        }
        return selected;
    }

    public static int featuresLo(boolean hlsEnabled) {
        return (int) features(hlsEnabled);
    }

    public static int featuresHi(boolean hlsEnabled) {
        return (int) (features(hlsEnabled) >>> 32);
    }

    public static long features(boolean hlsEnabled) {
        return AirPlayFeature.maskOf(selected(hlsEnabled));
    }

    /** Android port: mode-aware feature mask. */
    public static long features(boolean hlsEnabled, boolean audioOnly, boolean preferHevc) {
        return AirPlayFeature.maskOf(selected(hlsEnabled, audioOnly, preferHevc));
    }

    public static String featuresTxt(boolean hlsEnabled) {
        return String.format("0x%X,0x%X", featuresLo(hlsEnabled), featuresHi(hlsEnabled));
    }

    /** Android port: mode-aware TXT features string. */
    public static String featuresTxt(boolean hlsEnabled, boolean audioOnly, boolean preferHevc) {
        long features = features(hlsEnabled, audioOnly, preferHevc);
        return String.format("0x%X,0x%X", (int) features, (int) (features >>> 32));
    }
}
