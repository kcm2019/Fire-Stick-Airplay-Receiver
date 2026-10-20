package com.kurt.airplaytv.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.kurt.airplaytv.R;
import com.kurt.airplaytv.player.AudioPipeline;
import com.kurt.airplaytv.player.NowPlaying;
import com.kurt.airplaytv.service.ReceiverService;

/**
 * Now-playing screen for audio streams, modeled on tvOS: the artwork
 * blurred full-screen behind a clean artwork + metadata layout, a slim
 * progress bar, and an optional live spectrum visualizer fed by the
 * decoded PCM. Pause is render-side only (the phone keeps playing) —
 * the same model as the video player's pause.
 */
public final class AudioActivity extends Activity implements NowPlaying.Listener {

    private static volatile AudioActivity instance;

    /** Called by the service when video takes the screen over. */
    public static void closeIfShowing() {
        AudioActivity a = instance;
        if (a != null) {
            a.runOnUiThread(a::finish);
        }
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ImageView bgArtworkView;
    private ImageView artworkView;
    private View artworkPlaceholder;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private VisualizerView visualizer;
    private Button btnVisualizer;
    private View progressRow;
    private ProgressBar progressBar;
    private TextView elapsedView;
    private TextView remainingView;
    private View overlay;
    private Button btnPlayPause;
    private boolean paused;
    private Bitmap currentArtwork;

    private final Runnable hideOverlayRunnable = () -> {
        if (!isFinishing()) {
            overlay.setVisibility(View.GONE);
        }
    };

    private TextView statsView;
    private Button btnStats;
    private long statsLastAudioBytes;
    private long statsLastTimeMs;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (statsView != null && statsView.getVisibility() == View.VISIBLE) {
                refreshStats();
            }
            uiHandler.postDelayed(this, 500);
        }
    };

    private void refreshStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        double dt = statsLastTimeMs > 0 ? (now - statsLastTimeMs) / 1000.0 : 0;
        double mbps = dt > 0 ? (com.kurt.airplaytv.player.Stats.audioBytes
                - statsLastAudioBytes) * 8 / dt / 1_000_000.0 : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Audio · ").append(com.kurt.airplaytv.player.Stats.audioFormat);
        sb.append(String.format("\nBitrate: %.2f Mbps · Packets: %d", mbps,
                com.kurt.airplaytv.player.Stats.audioPackets));
        String title = NowPlaying.getTitle();
        if (title != null) {
            sb.append("\nTrack: ").append(title);
        }
        long start = com.kurt.airplaytv.player.Stats.sessionStartMs;
        if (start > 0) {
            sb.append("\nSession: ").append(
                    com.kurt.airplaytv.player.Stats.formatDuration(now - start));
        }
        statsView.setText(sb.toString());
        statsLastAudioBytes = com.kurt.airplaytv.player.Stats.audioBytes;
        statsLastTimeMs = now;
    }

    private void applyStatsVisibility() {
        boolean on = prefs().getBoolean("stats_enabled", false);
        if (statsView != null) {
            statsView.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (btnStats != null) {
            btnStats.setText(on ? "Stats: On" : "Stats: Off");
        }
        statsLastTimeMs = 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_audio);
        bgArtworkView = findViewById(R.id.bgArtwork);
        artworkView = findViewById(R.id.artwork);
        artworkPlaceholder = findViewById(R.id.artworkPlaceholder);
        titleView = findViewById(R.id.trackTitle);
        artistView = findViewById(R.id.trackArtist);
        albumView = findViewById(R.id.trackAlbum);
        visualizer = findViewById(R.id.visualizer);
        progressRow = findViewById(R.id.progressRow);
        progressBar = findViewById(R.id.progressBar);
        elapsedView = findViewById(R.id.elapsedTime);
        remainingView = findViewById(R.id.remainingTime);
        overlay = findViewById(R.id.audioOverlay);
        btnPlayPause = findViewById(R.id.btnAudioPlayPause);
        btnPlayPause.setOnClickListener(v -> {
            togglePause();
            showOverlay();
        });
        btnVisualizer = findViewById(R.id.btnAudioVisualizer);
        applyVisualizerPref();
        btnVisualizer.setOnClickListener(v -> {
            boolean on = !prefs().getBoolean("audio_visualizer", true);
            prefs().edit().putBoolean("audio_visualizer", on).apply();
            applyVisualizerPref();
            showOverlay();
        });
        statsView = findViewById(R.id.audioStatsView);
        btnStats = findViewById(R.id.btnAudioStats);
        btnStats.setOnClickListener(v -> {
            boolean on = !prefs().getBoolean("stats_enabled", false);
            prefs().edit().putBoolean("stats_enabled", on).apply();
            applyStatsVisibility();
            showOverlay();
        });
        applyStatsVisibility();
        findViewById(R.id.btnAudioBrightDown).setOnClickListener(v -> {
            stepBrightness(-10);
            showOverlay();
        });
        findViewById(R.id.btnAudioBrightUp).setOnClickListener(v -> {
            stepBrightness(10);
            showOverlay();
        });
        findViewById(R.id.btnAudioStop).setOnClickListener(v -> {
            setRenderPaused(false);
            finish();
        });
        applyBrightness();
        NowPlaying.setListener(this);
        refresh();
        overlay.setVisibility(View.VISIBLE);
        showOverlay();
        uiHandler.post(progressTicker);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        NowPlaying.setListener(null);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        uiHandler.removeCallbacks(progressTicker);
        super.onDestroy();
    }

    @Override
    public void onNowPlayingChanged() {
        refresh();
    }

    @Override
    public void onAudioStopped() {
        finish();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("player_display", MODE_PRIVATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBrightness();
    }

    /** Same shared brightness setting as the video player's menu. */
    private void applyBrightness() {
        int pct = prefs().getInt("brightness_pct", -1);
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = pct < 0
                ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : pct / 100f;
        getWindow().setAttributes(lp);
    }

    private void stepBrightness(int delta) {
        int cur = prefs().getInt("brightness_pct", -1);
        if (cur < 0) {
            cur = 50;
        }
        int next = Math.max(10, Math.min(100, cur + delta));
        prefs().edit().putInt("brightness_pct", next).apply();
        applyBrightness();
    }

    private void applyVisualizerPref() {
        boolean on = prefs().getBoolean("audio_visualizer", true);
        visualizer.setVisibility(on ? View.VISIBLE : View.GONE);
        btnVisualizer.setText(on ? "Visualizer: On" : "Visualizer: Off");
    }

    private void refresh() {
        String title = NowPlaying.getTitle();
        String artist = NowPlaying.getArtist();
        String album = NowPlaying.getAlbum();
        titleView.setText(title != null ? title : "AirPlay Audio");
        artistView.setText(artist != null ? artist : "");
        albumView.setText(album != null ? album : "");
        byte[] art = NowPlaying.getArtwork();
        if (art != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(art, 0, art.length);
            if (bmp != null && bmp != currentArtwork) {
                currentArtwork = bmp;
                artworkView.setImageBitmap(bmp);
                artworkView.setVisibility(View.VISIBLE);
                artworkPlaceholder.setVisibility(View.GONE);
                bgArtworkView.setImageBitmap(blurred(bmp));
                return;
            }
            if (bmp != null) {
                return;
            }
        }
        artworkView.setVisibility(View.GONE);
        artworkPlaceholder.setVisibility(View.VISIBLE);
        bgArtworkView.setImageBitmap(null);
    }

    /** Cheap blur: crush the artwork to a few pixels and stretch it back. */
    private static Bitmap blurred(Bitmap src) {
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(src, 18, 18, true);
            return Bitmap.createScaledBitmap(tiny, 480, 270, true);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateProgress() {
        double duration = NowPlaying.getDuration();
        if (duration <= 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        double position = NowPlaying.positionEstimate(!paused);
        progressRow.setVisibility(View.VISIBLE);
        progressBar.setProgress((int) (1000 * Math.min(1, position / duration)));
        elapsedView.setText(formatTime(position));
        remainingView.setText("-" + formatTime(Math.max(0, duration - position)));
    }

    private static String formatTime(double seconds) {
        int s = (int) seconds;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private void togglePause() {
        paused = !paused;
        setRenderPaused(paused);
        btnPlayPause.setText(paused ? "Resume" : "Pause");
    }

    private void setRenderPaused(boolean value) {
        ReceiverService service = ReceiverService.instance();
        AudioPipeline ap = service != null ? service.audioPipeline() : null;
        if (ap != null) {
            ap.setRenderPaused(value);
        }
    }

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        int sec = prefs().getInt("overlay_timeout_sec", 10);
        if (sec > 0) {
            uiHandler.postDelayed(hideOverlayRunnable, sec * 1000L);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY
                    || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                togglePause();
                showOverlay();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK && overlay.getVisibility() == View.VISIBLE) {
                overlay.setVisibility(View.GONE);
                return true;
            }
            boolean wasHidden = overlay.getVisibility() != View.VISIBLE;
            showOverlay();
            if (wasHidden && getCurrentFocus() == null) {
                btnPlayPause.requestFocus();
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
