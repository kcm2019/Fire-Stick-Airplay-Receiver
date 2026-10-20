package com.kurt.airplaytv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.kurt.airplaytv.R;
import com.kurt.airplaytv.ReceiverApp;
import com.kurt.airplaytv.service.ReceiverService;
import com.kurt.airplaytv.settings.ReceiverSettings;

/**
 * iPadOS Settings-style home screen: large title, grouped cards, chevron
 * rows and switches. DPAD-friendly for the Fire TV remote.
 */
public final class MainActivity extends Activity {

    private LinearLayout root;
    private TextView statusView;
    private TextView statusDetailView;
    private ReceiverSettings settings;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean polling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = ReceiverSettings.get(this);
        root = findViewById(R.id.root);

        // Make sure the receiver is running whenever the UI opens.
        ReceiverApp.ensureReceiverRunning(this);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
        polling = true;
        uiHandler.post(statusPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        uiHandler.removeCallbacks(statusPoll);
    }

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            ReceiverService service = ReceiverService.instance();
            if (service != null && statusView != null) {
                statusView.setText(service.status());
            }
            uiHandler.postDelayed(this, 1500);
        }
    };

    // ---- screen construction ----

    private void rebuild() {
        root.removeAllViews();
        addTitle();
        addStatusCard();
        addReceiverSection();
        addVideoSection();
        addGeneralSection();
        addFooter();
    }

    private void addTitle() {
        TextView title = new TextView(this);
        title.setText("AirPlay Receiver");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = rowParams(24, 0, 24, 0);
        lp.setMargins(dp(32), dp(8), dp(32), dp(4));
        title.setLayoutParams(lp);
        root.addView(title);
    }

    private void addStatusCard() {
        LinearLayout card = card();
        LinearLayout row = baseRow();
        LinearLayout texts = texts();
        TextView name = rowTitle(settings.deviceName());
        statusView = rowSummary("Starting…");
        statusDetailView = rowSummary(modeDescription());
        statusDetailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        texts.addView(name);
        texts.addView(statusView);
        texts.addView(statusDetailView);
        row.addView(texts);
        card.addView(row);
        root.addView(card);
    }

    private void addReceiverSection() {
        addSectionHeader("Receiver");
        LinearLayout card = card();

        navRow(card, "Device Name", settings.deviceName(), v -> showRenameDialog());
        divider(card);
        navRow(card, "Receiver Mode", modeDescription(), v -> showModeDialog());
        divider(card);
        infoRow(card, "On This Network",
                "iPhone, iPad, and Mac on this Wi-Fi network will see “" + settings.deviceName() + "”.");

        root.addView(card);
    }

    private void addVideoSection() {
        addSectionHeader("Video");
        LinearLayout card = card();

        navRow(card, "Mirroring Resolution", settings.resolution(), v -> showResolutionDialog());
        divider(card);
        switchRow(card, "Prefer HEVC", "Ask Apple devices for H.265 when offered", settings.preferHevc(),
                (v, checked) -> settings.setPreferHevc(checked));
        divider(card);
        switchRow(card, "Video URLs", "Play video links sent by apps (HLS)", settings.hlsEnabled(),
                (v, checked) -> settings.setHlsEnabled(checked));

        root.addView(card);
    }

    private void addGeneralSection() {
        addSectionHeader("General");
        LinearLayout card = card();

        switchRow(card, "Start on Boot", "Begin advertising when the Stick powers on",
                settings.startOnBoot(), (v, checked) -> settings.setStartOnBoot(checked));
        divider(card);
        navRow(card, "Restart Receiver", "Apply changes and re-announce on the network", v -> {
            ReceiverService service = ReceiverService.instance();
            if (service != null) {
                service.restartReceiver("manual");
            }
        });
        divider(card);
        navRow(card, "View Logs", "Recent activity and full failure details",
                v -> startActivity(new android.content.Intent(this, LogsActivity.class)));

        root.addView(card);
    }

    private void addFooter() {
        TextView how = new TextView(this);
        how.setText(getString(R.string.how_to_connect));
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        how.setTextColor(getColor(R.color.text_secondary));
        how.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams lp = rowParams(24, 0, 24, 0);
        lp.setMargins(dp(32), dp(24), dp(32), dp(8));
        how.setLayoutParams(lp);
        root.addView(how);

        TextView drm = new TextView(this);
        drm.setText(getString(R.string.drm_note));
        drm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        drm.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams lp2 = rowParams(24, 0, 24, 0);
        lp2.setMargins(dp(32), dp(0), dp(32), dp(8));
        drm.setLayoutParams(lp2);
        root.addView(drm);
    }

    // ---- dialogs ----

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setText(settings.deviceName());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine();
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Device Name")
                .setMessage("This is the name your Apple devices will see.")
                .setView(padDialog(input))
                .setPositiveButton("Done", (d, w) -> {
                    settings.setDeviceName(input.getText().toString());
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showModeDialog() {
        String[] items = {"Mirroring + Audio", "Audio Only"};
        int checked = settings.isAudioOnly() ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Receiver Mode")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    settings.setMode(which == 1 ? ReceiverSettings.MODE_AUDIO : ReceiverSettings.MODE_MIRROR);
                    d.dismiss();
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showResolutionDialog() {
        String[] items = {"1080p (Recommended)", "720p (Lower bandwidth)"};
        int checked = "720p".equals(settings.resolution()) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Mirroring Resolution")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    settings.setResolution(which == 1 ? "720p" : "1080p");
                    d.dismiss();
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String modeDescription() {
        if (settings.isAudioOnly()) {
            return "Audio Only — appears as a speaker in AirPlay";
        }
        return "Mirroring + Audio — appears as a screen in AirPlay";
    }

    // ---- row builders (iPadOS grouped-list look) ----

    private void addSectionHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(getColor(R.color.text_secondary));
        header.setAllCaps(true);
        LinearLayout.LayoutParams lp = rowParams(24, 0, 24, 0);
        lp.setMargins(dp(32), dp(22), dp(32), dp(8));
        header.setLayoutParams(lp);
        root.addView(header);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.card_bg));
        LinearLayout.LayoutParams lp = rowParams(24, 0, 24, 0);
        lp.setMargins(dp(24), 0, dp(24), 0);
        card.setLayoutParams(lp);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        return card;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(rowParams(24, 12, 24, 12));
        return row;
    }

    private LinearLayout texts() {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return texts;
    }

    private TextView rowTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTextColor(getColor(R.color.text_primary));
        return tv;
    }

    private TextView rowSummary(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setPadding(0, dp(2), 0, 0);
        return tv;
    }

    private TextView chevron() {
        TextView tv = new TextView(this);
        tv.setText("›");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTextColor(getColor(R.color.text_secondary));
        return tv;
    }

    private void navRow(LinearLayout card, String title, String summary, View.OnClickListener onClick) {
        LinearLayout row = baseRow();
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_bg));
        LinearLayout texts = texts();
        texts.addView(rowTitle(title));
        if (summary != null) {
            texts.addView(rowSummary(summary));
        }
        row.addView(texts);
        row.addView(chevron());
        row.setOnClickListener(onClick);
        card.addView(row);
    }

    private void infoRow(LinearLayout card, String title, String summary) {
        LinearLayout row = baseRow();
        LinearLayout texts = texts();
        texts.addView(rowTitle(title));
        texts.addView(rowSummary(summary));
        row.addView(texts);
        card.addView(row);
    }

    private void switchRow(LinearLayout card, String title, String summary, boolean checked,
                           CompoundButton.OnCheckedChangeListener onToggle) {
        LinearLayout row = baseRow();
        row.setFocusable(true);
        LinearLayout texts = texts();
        texts.addView(rowTitle(title));
        texts.addView(rowSummary(summary));
        row.addView(texts);
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setFocusable(true);
        sw.setOnCheckedChangeListener(onToggle);
        row.addView(sw);
        // Whole row toggles the switch for remote-friendliness.
        row.setClickable(true);
        row.setBackground(getDrawable(R.drawable.row_bg));
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
    }

    private void divider(LinearLayout card) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(64), 0, dp(8), 0);
        divider.setLayoutParams(lp);
        card.addView(divider);
    }

    private View padDialog(View inner) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), dp(0));
        wrap.addView(inner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private LinearLayout.LayoutParams rowParams(int l, int t, int r, int b) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
