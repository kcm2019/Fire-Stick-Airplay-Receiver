package com.kurt.airplaytv.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed wrapper over the receiver's SharedPreferences. */
public final class ReceiverSettings {

    /** Full receiver: screen mirroring + audio. */
    public static final String MODE_MIRROR = "mirror";
    /** Audio-only receiver: advertised without screen/video features. */
    public static final String MODE_AUDIO = "audio";

    private static final String FILE = "receiver_settings";

    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PREFER_HEVC = "prefer_hevc";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_RESOLUTION = "resolution"; // "720p" | "1080p"
    private static final String KEY_HLS = "hls_enabled";

    private final SharedPreferences prefs;

    private ReceiverSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static ReceiverSettings get(Context context) {
        return new ReceiverSettings(
                context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    public SharedPreferences sharedPreferences() {
        return prefs;
    }

    // ---- Device name ----

    public String deviceName() {
        return prefs.getString(KEY_DEVICE_NAME, "Fire TV Stick");
    }

    public void setDeviceName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            trimmed = "Fire TV Stick";
        }
        prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply();
    }

    // ---- Receiver mode ----

    public String mode() {
        return prefs.getString(KEY_MODE, MODE_MIRROR);
    }

    public void setMode(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    public boolean isAudioOnly() {
        return MODE_AUDIO.equals(mode());
    }

    // ---- Video ----

    public boolean preferHevc() {
        return prefs.getBoolean(KEY_PREFER_HEVC, true);
    }

    public void setPreferHevc(boolean value) {
        prefs.edit().putBoolean(KEY_PREFER_HEVC, value).apply();
    }

    public String resolution() {
        return prefs.getString(KEY_RESOLUTION, "1080p");
    }

    public void setResolution(String resolution) {
        prefs.edit().putString(KEY_RESOLUTION, resolution).apply();
    }

    public int videoWidth() {
        return "720p".equals(resolution()) ? 1280 : 1920;
    }

    public int videoHeight() {
        return "720p".equals(resolution()) ? 720 : 1080;
    }

    public int videoFps() {
        return 30;
    }

    public boolean hlsEnabled() {
        return prefs.getBoolean(KEY_HLS, true);
    }

    public void setHlsEnabled(boolean value) {
        prefs.edit().putBoolean(KEY_HLS, value).apply();
    }

    // ---- General ----

    public boolean startOnBoot() {
        return prefs.getBoolean(KEY_START_ON_BOOT, true);
    }

    public void setStartOnBoot(boolean value) {
        prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply();
    }
}
