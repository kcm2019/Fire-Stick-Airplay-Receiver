package com.kurt.airplaytv.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kurt.airplaytv.R;
import com.kurt.airplaytv.ReceiverApp;
import com.kurt.airplaytv.log.LogBuffer;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * In-app log viewer: recent session log (protocol core + service lifecycle,
 * including full stack traces on failures) and the last recorded crash.
 */
public final class LogsActivity extends Activity {

    private TextView logView;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(getColor(R.color.window_bg));
        rootLayout.setPadding(dp(32), dp(20), dp(32), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Logs");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(getColor(R.color.text_primary));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refresh());
        header.addView(refresh);

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> {
            LogBuffer.clear();
            crashFile().delete();
            refresh();
        });
        header.addView(clear);
        rootLayout.addView(header);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setTextColor(getColor(R.color.text_primary));
        logView.setPadding(0, dp(12), 0, dp(12));
        scrollView.addView(logView);
        rootLayout.addView(scrollView);

        setContentView(rootLayout);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private File crashFile() {
        return new File(getFilesDir(), ReceiverApp.LAST_CRASH_FILE);
    }

    private String readCrashFile() {
        File file = crashFile();
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void refresh() {
        StringBuilder text = new StringBuilder();
        String crash = readCrashFile();
        if (crash != null && !crash.isEmpty()) {
            text.append("—— Last crash ——\n").append(crash).append("\n\n");
        }
        text.append("—— Session log ——\n");
        List<String> lines = LogBuffer.snapshot();
        if (lines.isEmpty()) {
            text.append("(no log entries yet)");
        } else {
            for (String line : lines) {
                text.append(line).append('\n');
            }
        }
        logView.setText(text.toString());
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
