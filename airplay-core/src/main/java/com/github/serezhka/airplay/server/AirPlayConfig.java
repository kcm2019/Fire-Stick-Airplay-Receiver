package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName;
    private int width;
    private int height;
    private int fps;
    private boolean hlsEnabled;
    private boolean mdnsLoopback;
    /** Android port: advertise as an audio-only receiver (no screen/video features). */
    private boolean audioOnly;
    /** Android port: advertise screen multi-codec (HEVC mirroring) support. */
    private boolean preferHevc;
}
