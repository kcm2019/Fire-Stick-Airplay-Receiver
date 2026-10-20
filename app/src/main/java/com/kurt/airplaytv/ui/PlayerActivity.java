package com.kurt.airplaytv.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.kurt.airplaytv.R;
import com.kurt.airplaytv.player.HlsPlayer;
import com.kurt.airplaytv.player.PlayerSurface;
import com.kurt.airplaytv.player.VideoPipeline;
import com.kurt.airplaytv.service.ReceiverService;
import com.kurt.airplaytv.settings.ReceiverSettings;

/**
 * Fullscreen playback surface host. Mirroring and HLS/URL playback get
 * DEDICATED SurfaceViews: a Surface's buffer queue accepts exactly one
 * MEDIA producer, so sharing one surface between the mirror MediaCodec
 * and an HLS MediaPlayer made the decoder fail to configure
 * ("native_window_api_connect: already connected" — black screen,
 * v1.0.9 root cause).
 *
 * The video surface is sized to the stream's aspect ratio (letterboxed)
 * unless the user enables stretch-to-fill in the in-player settings.
 * The info/controls overlay shows for 10 s on connect and reappears on
 * remote interaction, then auto-hides.
 */
public final class PlayerActivity extends Activity {

    private static final String TAG = "PlayerActivity";
    private static final String PREFS = "player_display";
    private static final String KEY_STRETCH = "stretch_to_fill";

    /** The running activity (at most one), for mode hand-off callbacks. */
    private static volatile PlayerActivity instance;

    /** Receiver service calls this when a mirroring session starts. */
    public static void mirrorTookOver() {
        PlayerActivity a = instance;
        if (a != null) {
            a.runOnUiThread(() -> {
                a.releaseHls();
                a.showMirrorSurface();
                a.showOverlay(a.overlayTimeoutMs());
            });
        }
    }

    /** Receiver service calls this when AirPlay video (/play) is requested. */
    public static void hlsRequested() {
        PlayerActivity a = instance;
        if (a != null) {
            a.runOnUiThread(() -> {
                a.sessionActive = true;
                a.showHlsSurface();
                a.maybeStartHls();
                a.showOverlay(a.overlayTimeoutMs());
            });
        }
    }

    /** Receiver service calls this with the decoded stream's dimensions. */
    public static void videoSizeChanged(int width, int height) {
        PlayerActivity a = instance;
        if (a != null && width > 0 && height > 0) {
            a.runOnUiThread(() -> a.onVideoSize(width, height));
        }
    }

    /** Receiver service calls this when the video session ends. */
    public static void playbackStopped() {
        PlayerActivity a = instance;
        if (a != null) {
            a.runOnUiThread(a::blackout);
        }
    }

    private SurfaceView mirrorSurfaceView;
    private SurfaceView hlsSurfaceView;
    private View overlay;
    private View settingsPanel;
    private TextView statusView;
    private TextView titleView;
    private Button btnPlayPause;
    private Button btnDisplayMode;
    private Button btnFade;
    private android.widget.TextView brightnessLabel;
    private MediaPlayer hlsPlayer;
    private boolean hlsSurfaceReady;
    private boolean hlsActive;
    /** True while a mirroring session is on screen (idle = overlay stays). */
    private boolean sessionActive;
    private TextView statsView;
    private Button btnStats;
    private long statsLastRendered;
    private long statsLastBytes;
    private long statsLastAudioBytes;
    private long statsLastTimeMs;
    private boolean paused;
    private int videoWidth;
    private int videoHeight;
    private boolean stretch;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean polling;

    private final Runnable hideOverlayRunnable = () -> {
        // With no device connected there is nothing to watch — the
        // overlay (status + controls) stays up instead of fading to black.
        if (!sessionActive && !hlsActive) {
            return;
        }
        if (settingsPanel.getVisibility() != View.VISIBLE) {
            overlay.setVisibility(View.GONE);
        }
    };

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            ReceiverService service = ReceiverService.instance();
            if (service != null) {
                statusView.setText(service.status());
            }
            if (statsView != null && statsView.getVisibility() == View.VISIBLE) {
                refreshStats();
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");
        com.kurt.airplaytv.log.LogBuffer.append(Log.INFO, TAG, "onCreate");
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        stretch = prefs.getBoolean(KEY_STRETCH, false);

        mirrorSurfaceView = findViewById(R.id.playerSurface);
        hlsSurfaceView = findViewById(R.id.hlsSurface);
        overlay = findViewById(R.id.playerOverlay);
        settingsPanel = findViewById(R.id.settingsPanel);
        statusView = findViewById(R.id.playerStatus);
        titleView = findViewById(R.id.playerTitle);
        titleView.setText(ReceiverSettings.get(this).deviceName());
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnDisplayMode = findViewById(R.id.btnDisplayMode);
        updateDisplayModeLabel();

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopPlayback());
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        btnDisplayMode.setOnClickListener(v -> toggleDisplayMode());
        btnFade = findViewById(R.id.btnFade);
        btnFade.setText("Menu fade: " + fadeLabel());
        btnFade.setOnClickListener(v -> {
            cycleFadeTimeout();
            showOverlay(overlayTimeoutMs());
        });
        brightnessLabel = findViewById(R.id.brightnessLabel);
        findViewById(R.id.btnBrightnessDown).setOnClickListener(v -> {
            stepBrightness(-10);
            showOverlay(overlayTimeoutMs());
        });
        findViewById(R.id.btnBrightnessUp).setOnClickListener(v -> {
            stepBrightness(10);
            showOverlay(overlayTimeoutMs());
        });
        applyBrightness();
        statsView = findViewById(R.id.statsView);
        btnStats = findViewById(R.id.btnStats);
        btnStats.setOnClickListener(v -> {
            boolean on = !getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("stats_enabled", false);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("stats_enabled", on).apply();
            applyStatsVisibility();
            showOverlay(overlayTimeoutMs());
        });
        applyStatsVisibility();
        findViewById(R.id.btnCloseSettings).setOnClickListener(v -> closeSettings());

        mirrorSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "Mirror surface created");
                com.kurt.airplaytv.log.LogBuffer.append(Log.INFO, TAG, "Mirror surface created");
                applyAspect(mirrorSurfaceView);
                PlayerSurface.onCreated(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "Mirror surface destroyed");
                com.kurt.airplaytv.log.LogBuffer.append(Log.INFO, TAG, "Mirror surface destroyed");
                PlayerSurface.onDestroyed();
            }
        });
        hlsSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "HLS surface created");
                com.kurt.airplaytv.log.LogBuffer.append(Log.INFO, TAG, "HLS surface created");
                hlsSurfaceReady = true;
                applyAspect(hlsSurfaceView);
                maybeStartHls();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "HLS surface destroyed");
                com.kurt.airplaytv.log.LogBuffer.append(Log.INFO, TAG, "HLS surface destroyed");
                hlsSurfaceReady = false;
                releaseHls();
            }
        });
        showOverlay(overlayTimeoutMs());
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBrightness();
        polling = true;
        uiHandler.post(statusPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        uiHandler.removeCallbacks(statusPoll);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        uiHandler.removeCallbacks(hideOverlayRunnable);
        releaseHls();
        super.onDestroy();
    }

    // ---- overlay & controls ----

    private void showOverlay(long hideAfterMs) {
        overlay.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        if (hideAfterMs > 0 && settingsPanel.getVisibility() != View.VISIBLE) {
            uiHandler.postDelayed(hideOverlayRunnable, hideAfterMs);
        }
    }

    private void applyStatsVisibility() {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("stats_enabled", false);
        if (statsView != null) {
            statsView.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (btnStats != null) {
            btnStats.setText(on ? "Stats: On" : "Stats: Off");
        }
        statsLastTimeMs = 0; // re-baseline the rate math
    }

    /** Compose the stats readout; rates come from deltas since last poll. */
    private void refreshStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        double dt = statsLastTimeMs > 0 ? (now - statsLastTimeMs) / 1000.0 : 0;
        StringBuilder sb = new StringBuilder();
        long start = com.kurt.airplaytv.player.Stats.sessionStartMs;
        if (com.kurt.airplaytv.player.Stats.videoFramesFed > 0) {
            double fps = dt > 0 ? (com.kurt.airplaytv.player.Stats.videoFramesRendered
                    - statsLastRendered) / dt : 0;
            double mbps = dt > 0 ? (com.kurt.airplaytv.player.Stats.videoBytes
                    - statsLastBytes) * 8 / dt / 1_000_000.0 : 0;
            sb.append("Mirroring · ").append(com.kurt.airplaytv.player.Stats.videoCodec);
            if (com.kurt.airplaytv.player.Stats.videoWidth > 0) {
                sb.append("\nResolution: ").append(com.kurt.airplaytv.player.Stats.videoWidth)
                        .append("×").append(com.kurt.airplaytv.player.Stats.videoHeight)
                        .append(" · decoder mode ").append(com.kurt.airplaytv.player.Stats.decoderMode);
            }
            sb.append(String.format("\nFPS: %.1f · Bitrate: %.2f Mbps", fps, mbps));
            sb.append("\nFrames: ").append(com.kurt.airplaytv.player.Stats.videoFramesFed)
                    .append(" fed · ").append(com.kurt.airplaytv.player.Stats.videoFramesRendered)
                    .append(" shown · ").append(com.kurt.airplaytv.player.Stats.videoFramesDropped)
                    .append(" dropped");
        } else if (hlsActive) {
            double[] info = com.kurt.airplaytv.player.HlsPlayer.info();
            sb.append("Video (HLS)");
            sb.append(String.format("\nPosition: %s / %s",
                    com.kurt.airplaytv.player.Stats.formatDuration((long) (info[0] > 0 ? info[1] * 1000 : 0)),
                    com.kurt.airplaytv.player.Stats.formatDuration((long) (info[0] * 1000))));
        } else if (com.kurt.airplaytv.player.Stats.audioPackets > 0) {
            double mbps = dt > 0 ? (com.kurt.airplaytv.player.Stats.audioBytes
                    - statsLastAudioBytes) * 8 / dt / 1_000_000.0 : 0;
            sb.append("Audio · ").append(com.kurt.airplaytv.player.Stats.audioFormat);
            sb.append(String.format("\nBitrate: %.2f Mbps · Packets: %d", mbps,
                    com.kurt.airplaytv.player.Stats.audioPackets));
        } else {
            sb.append("No active stream");
        }
        if (start > 0) {
            sb.append("\nSession: ").append(
                    com.kurt.airplaytv.player.Stats.formatDuration(now - start));
        }
        statsView.setText(sb.toString());
        statsLastRendered = com.kurt.airplaytv.player.Stats.videoFramesRendered;
        statsLastBytes = com.kurt.airplaytv.player.Stats.videoBytes;
        statsLastAudioBytes = com.kurt.airplaytv.player.Stats.audioBytes;
        statsLastTimeMs = now;
    }

    /** Configured menu fade timeout (Settings → "Menu fade"); 0 = never. */
    long overlayTimeoutMs() {
        int sec = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_timeout_sec", 10);
        return sec <= 0 ? 0 : sec * 1000L;
    }

    private String fadeLabel() {
        int sec = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_timeout_sec", 10);
        return sec <= 0 ? "Never" : sec + "s";
    }

    /** Window brightness override in % (pref "brightness_pct"; -1 = system). */
    private int brightnessPct() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("brightness_pct", -1);
    }

    void applyBrightness() {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        int pct = brightnessPct();
        lp.screenBrightness = pct < 0
                ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : pct / 100f;
        getWindow().setAttributes(lp);
        if (brightnessLabel != null) {
            brightnessLabel.setText(pct < 0 ? "Auto" : pct + "%");
        }
    }

    private void stepBrightness(int delta) {
        int cur = brightnessPct();
        if (cur < 0) {
            cur = 50;
        }
        int next = Math.max(10, Math.min(100, cur + delta));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("brightness_pct", next).apply();
        applyBrightness();
    }

    private void cycleFadeTimeout() {
        int[] options = {5, 10, 15, 20, 30, 0};
        int current = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_timeout_sec", 10);
        int next = options[0];
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                next = options[(i + 1) % options.length];
                break;
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("overlay_timeout_sec", next).apply();
        btnFade.setText("Menu fade: " + fadeLabel());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY
                    || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                togglePlayPause();
                showOverlay(overlayTimeoutMs());
                return true;
            }
            // Any remote interaction brings the info/controls back.
            boolean wasHidden = overlay.getVisibility() != View.VISIBLE;
            showOverlay(overlayTimeoutMs());
            if (wasHidden && settingsPanel.getVisibility() != View.VISIBLE
                    && getCurrentFocus() == null) {
                // Give the D-pad a visible orange starting point.
                btnPlayPause.requestFocus();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void togglePlayPause() {
        if (hlsActive && hlsPlayer != null) {
            try {
                if (hlsPlayer.isPlaying()) {
                    hlsPlayer.pause();
                    paused = true;
                } else {
                    hlsPlayer.start();
                    paused = false;
                }
            } catch (Exception ignored) {
            }
        } else {
            ReceiverService service = ReceiverService.instance();
            VideoPipeline vp = service != null ? service.videoPipeline() : null;
            if (vp != null) {
                paused = !vp.isRenderPaused();
                vp.setRenderPaused(paused);
            }
        }
        btnPlayPause.setText(paused ? "Resume" : "Pause");
    }

    private void stopPlayback() {
        releaseHls();
        blackout();
    }

    private void openSettings() {
        uiHandler.removeCallbacks(hideOverlayRunnable);
        overlay.setVisibility(View.VISIBLE);
        settingsPanel.setVisibility(View.VISIBLE);
        btnDisplayMode.requestFocus();
    }

    private void closeSettings() {
        settingsPanel.setVisibility(View.GONE);
        findViewById(R.id.btnSettings).requestFocus();
        showOverlay(overlayTimeoutMs());
    }

    private void toggleDisplayMode() {
        stretch = !stretch;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_STRETCH, stretch).apply();
        updateDisplayModeLabel();
        applyAspect(mirrorSurfaceView);
        applyAspect(hlsSurfaceView);
    }

    private void updateDisplayModeLabel() {
        btnDisplayMode.setText(stretch
                ? "Display: Stretch (fill screen)"
                : "Display: Fit (keep aspect ratio)");
    }

    // ---- surfaces, aspect, blackout ----

    private void onVideoSize(int width, int height) {
        sessionActive = true;
        videoWidth = width;
        videoHeight = height;
        applyAspect(hlsActive ? hlsSurfaceView : mirrorSurfaceView);
    }

    /** Sizes a video surface to the stream aspect (fit) or full screen (stretch). */
    private void applyAspect(SurfaceView view) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (stretch || videoWidth <= 0 || videoHeight <= 0) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
        } else {
            Point screen = new Point();
            getWindowManager().getDefaultDisplay().getSize(screen);
            double scale = Math.min((double) screen.x / videoWidth,
                    (double) screen.y / videoHeight);
            lp.width = Math.max(2, (int) Math.round(videoWidth * scale));
            lp.height = Math.max(2, (int) Math.round(videoHeight * scale));
        }
        lp.gravity = android.view.Gravity.CENTER;
        view.setLayoutParams(lp);
    }

    private void showMirrorSurface() {
        hlsActive = false;
        hlsSurfaceView.setVisibility(View.GONE);
        mirrorSurfaceView.setVisibility(View.VISIBLE);
        applyAspect(mirrorSurfaceView);
    }

    private void showHlsSurface() {
        hlsSurfaceView.setVisibility(View.VISIBLE);
        hlsSurfaceView.bringToFront();
        mirrorSurfaceView.setVisibility(View.GONE);
        overlay.bringToFront();
        if (settingsPanel.getVisibility() == View.VISIBLE) {
            settingsPanel.bringToFront();
        }
    }

    /** Disconnect/stop: clear the last frame — the screen goes black. */
    private void blackout() {
        sessionActive = false;
        hlsActive = false;
        videoWidth = 0;
        videoHeight = 0;
        mirrorSurfaceView.setVisibility(View.GONE);
        hlsSurfaceView.setVisibility(View.GONE);
        statusView.setText("Disconnected");
        showOverlay(4_000);
    }

    // ---- HLS / URL playback (own surface, never the mirror one) ----

    private String pendingHlsUrl;

    private void maybeStartHls() {
        String url = HlsPlayer.consumePendingUrl();
        if (url != null) {
            pendingHlsUrl = url;
        }
        if (pendingHlsUrl == null || hlsPlayer != null || !hlsSurfaceReady) {
            return; // surface creation retries when it becomes ready
        }
        String u = pendingHlsUrl;
        pendingHlsUrl = null;
        if (HlsPlayer.recentlyDrmFailed()) {
            // The phone retries DRM /play requests; don't churn the
            // player/surface for each one — keep the explanation up.
            statusView.setText("This video is DRM-protected (Apple FairPlay)"
                    + " and can't play on third-party receivers.");
            showOverlay(20_000);
            return;
        }
        startHls(u);
    }

    private void startHls(String url) {
        try {
            SurfaceHolder holder = hlsSurfaceView.getHolder();
            hlsPlayer = new MediaPlayer();
            MediaPlayer mp = hlsPlayer;
            HlsPlayer.attach(mp, holder.getSurface());
            mp.setSurface(holder.getSurface());
            mp.setOnPreparedListener(p -> {
                hlsActive = true;
                HlsPlayer.setPrepared();
                videoWidth = p.getVideoWidth();
                videoHeight = p.getVideoHeight();
                applyAspect(hlsSurfaceView);
                double seek = HlsPlayer.consumePendingSeek();
                if (seek >= 0) {
                    try {
                        p.seekTo((int) (seek * 1000));
                    } catch (Exception ignored) {
                    }
                }
                p.start();
                paused = false;
                btnPlayPause.setText("Pause");
            });
            mp.setOnCompletionListener(p -> {
                statusView.setText("Finished");
                releaseHls();
                blackout();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (what == -38) {
                    // Invalid-state noise (a seek/duration call raced the
                    // player's state machine). Not a playback failure —
                    // releasing here killed /play before it could start.
                    Log.w(TAG, "HLS state warning -38 (ignored)");
                    return true;
                }
                if (extra == -1007) {
                    // MALFORMED: the segments can't even be parsed — the
                    // signature of FairPlay-encrypted HLS (Apple TV app,
                    // purchased movies). No third-party receiver can play
                    // it; say so once, calmly, instead of error-looping
                    // while the phone retries and falls back.
                    HlsPlayer.noteDrmFailure();
                    Log.w(TAG, "HLS content is DRM-protected (malformed segments)");
                    com.kurt.airplaytv.log.LogBuffer.append(Log.WARN, TAG,
                            "DRM-protected content cannot be played (FairPlay)");
                    releaseHls();
                    hlsSurfaceView.setVisibility(View.GONE);
                    statusView.setText("This video is DRM-protected (Apple FairPlay)"
                            + " and can't play on third-party receivers.");
                    showOverlay(20_000);
                    return true;
                }
                Log.w(TAG, "HLS error " + what + "/" + extra);
                com.kurt.airplaytv.log.LogBuffer.append(Log.WARN, TAG, "HLS error " + what + "/" + extra);
                statusView.setText("Playback error");
                // A MediaPlayer in error state keeps its surface connection,
                // which blocked the mirror decoder in v1.0.9. Release now.
                releaseHls();
                blackout();
                return true;
            });
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "HLS start failed", e);
            statusView.setText("Playback failed");
            releaseHls();
        }
    }

    private void releaseHls() {
        if (hlsPlayer != null) {
            HlsPlayer.detach(hlsPlayer);
            try {
                hlsPlayer.stop();
            } catch (Exception ignored) {
            }
            hlsPlayer.release();
            hlsPlayer = null;
        }
        hlsActive = false;
    }
}
