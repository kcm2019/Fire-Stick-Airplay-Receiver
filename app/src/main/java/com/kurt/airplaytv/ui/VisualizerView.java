package com.kurt.airplaytv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import com.kurt.airplaytv.player.AudioLevels;

/**
 * Live spectrum bars fed by the decoded-PCM FFT tap. Runs its draw
 * loop only while attached and visible.
 */
public final class VisualizerView extends View {

    private static final int BARS = 28;
    private final float[] targets = new float[BARS];
    private final float[] levels = new float[BARS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            AudioLevels.snapshotBands(targets);
            for (int i = 0; i < BARS; i++) {
                float t = targets[i];
                levels[i] += (t - levels[i]) * (t > levels[i] ? 0.55f : 0.22f);
            }
            invalidate();
            handler.postDelayed(this, 33);
        }
    };

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xE6FFFFFF);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (!running) {
            running = true;
            handler.post(tick);
        }
    }

    private void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float gap = w * 0.012f;
        float barW = (w - gap * (BARS + 1)) / BARS;
        float minBar = h * 0.03f;
        for (int i = 0; i < BARS; i++) {
            float bh = minBar + levels[i] * (h - minBar);
            float left = gap + i * (barW + gap);
            float top = h - bh;
            float radius = barW / 2f;
            canvas.drawRoundRect(left, top, left + barW, h, radius, radius, paint);
        }
    }
}
