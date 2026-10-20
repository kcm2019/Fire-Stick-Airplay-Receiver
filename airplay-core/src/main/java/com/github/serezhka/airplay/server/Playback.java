package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;

public interface Playback {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    void onAudioSrcDisconnect();

    default void onPlaylist(String playlistUri) {
    }

    default void onPlaylistContent(String playlistUri, String content) {
    }

    default void onPlaylistRemoved() {
    }

    default void onPause() {
    }

    default void onResume() {
    }

    default void onSeek(double positionSeconds) {
    }

    /** Linear volume in {@code [0, 1]}. RTSP dB is converted by the control layer. */
    default void onVolume(double volumeLinear) {
    }

    /** Now-playing metadata from a DMAP SET_PARAMETER (audio streams). */
    default void onAudioMetadata(String title, String artist, String album) {
    }

    /** Album artwork (JPEG/PNG bytes) from an image SET_PARAMETER. */
    default void onAudioArtwork(byte[] imageBytes) {
    }

    /** Track progress in seconds (RTSP "progress:" parameter), audio streams. */
    default void onAudioProgress(double positionSeconds, double durationSeconds) {
    }

    default double volume() {
        return 1.0;
    }

    default void onControlExchange(ControlExchange exchange) {
    }

    default Playback.Info info() {
        return Playback.Info.idle();
    }

    /** Server registers one observer. The player calls {@link Observer#onEnded()} at VOD end. */
    default void setObserver(Observer observer) {
    }

    interface Observer {
        void onEnded();

        /** First picture is on the display. Until then the sender stays on {@code loading}. */
        default void onPresented() {
        }

        /** A seek has a picture. Tell the sender {@code playing} at the real position. */
        default void onSeekDisplayed() {
        }

        Observer NONE = new Observer() {
            @Override
            public void onEnded() {
            }
        };
    }

    /**
     * {@code rate}: {@code 0} paused, {@code 1} playing ({@code /playback-info}, {@code /rate}).
     * Android port: record replaced with a plain class (java.lang.Record is absent on API 25).
     */
    final class Info {
        private final double duration;
        private final double position;
        private final double rate;

        public Info(double duration, double position, double rate) {
            this.duration = duration;
            this.position = position;
            this.rate = rate;
        }

        public Info(double duration, double position) {
            this(duration, position, 1);
        }

        public double duration() { return duration; }
        public double position() { return position; }
        public double rate() { return rate; }

        public static Info idle() {
            return new Info(0, 0, 1);
        }
    }
}
