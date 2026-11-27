package io.github.stophoginit.w101panel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Opening the app shows the panel. The first time (or with a long press on the
 * panel's title) it shows setup instead: allow "Display over other apps", and
 * paste Wizard101's GameNative launch link so W101 Play can start everything
 * with one tap. Each time the panel starts, Android asks to allow screen
 * capture, which the panel uses to notice battles ("Cancel" still gives you
 * the buttons).
 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private Button overlay;
    private TextView linkState;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Settings.canDrawOverlays(this) && !getIntent().getBooleanExtra("setup", false)) {
            if (PanelService.running) {
                startPanel(null, 0);
            } else {
                MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
            }
            return;
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("W101 Panel setup");
        title.setTextSize(28);
        l.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, pad / 2, 0, pad / 2);
        info.setText("Wizard101 buttons on the Thor's bottom screen.\n\n"
                + "1. Allow this app to display over other apps.");
        l.addView(info);

        overlay = new Button(this);
        overlay.setText("Allow display over other apps");
        overlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        l.addView(overlay);

        TextView step2 = new TextView(this);
        step2.setTextSize(16);
        step2.setPadding(0, pad, 0, pad / 2);
        step2.setText("2. For one-tap start (the W101 Play icon): in GameNative, open Wizard101's menu "
                + "and choose \"Copy launch link\", then come back and tap Paste. "
                + "The link stays on this device only.");
        l.addView(step2);

        linkState = new TextView(this);
        linkState.setTextSize(15);
        l.addView(linkState);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = new Button(this);
        paste.setText("Paste launch link");
        paste.setOnClickListener(v -> pasteLink());
        row.addView(paste);
        Button clear = new Button(this);
        clear.setText("Forget link");
        clear.setOnClickListener(v -> {
            getSharedPreferences(PlayActivity.PREFS, MODE_PRIVATE).edit().remove(PlayActivity.KEY_LINK).apply();
            showLinkState();
        });
        row.addView(clear);
        l.addView(row);

        TextView step3 = new TextView(this);
        step3.setTextSize(16);
        step3.setPadding(0, pad, 0, pad / 2);
        step3.setText("3. Start with W101 Play, or open W101 Panel while Wizard101 is running. "
                + "Long-press the panel's title to come back here.");
        l.addView(step3);

        Button stop = new Button(this);
        stop.setText("Remove panel");
        stop.setOnClickListener(v -> startService(new Intent(this, PanelService.class).setAction(PanelService.ACTION_STOP)));
        l.addView(stop);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);
        showLinkState();
    }

    private void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        String s = text != null ? text.toString().trim() : null;
        if (!PlayActivity.validLink(s)) {
            Toast.makeText(this, "The clipboard doesn't hold a GameNative launch link (gamenative://run?...)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PlayActivity.PREFS, MODE_PRIVATE).edit().putString(PlayActivity.KEY_LINK, s).apply();
        Toast.makeText(this, "Saved. W101 Play is ready.", Toast.LENGTH_SHORT).show();
        showLinkState();
    }

    private void showLinkState() {
        linkState.setText(PlayActivity.validLink(PlayActivity.savedLink(this))
                ? "Launch link: saved ✓" : "Launch link: not set");
    }

    @Override protected void onResume() {
        super.onResume();
        if (overlay != null) overlay.setEnabled(!Settings.canDrawOverlays(this));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            startPanel(resultCode == RESULT_OK ? data : null, resultCode);
        }
    }

    private void startPanel(Intent captureData, int resultCode) {
        Intent i = new Intent(this, PanelService.class).setAction(PanelService.ACTION_SHOW);
        if (captureData != null) {
            i.putExtra(PanelService.EXTRA_CODE, resultCode);
            i.putExtra(PanelService.EXTRA_DATA, captureData);
        }
        startForegroundService(i);
        finish();
    }
}
