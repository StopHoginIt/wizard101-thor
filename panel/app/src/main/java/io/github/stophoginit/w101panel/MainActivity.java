package io.github.stophoginit.w101panel;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Opening the app shows the panel. The first time, Android asks to allow
 * "Display over other apps"; each time the panel starts, Android asks to
 * allow screen capture, which the card helper uses to read your hand from the
 * top screen ("Cancel" still gives you the shortcut buttons).
 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private Button overlay;

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
        title.setText("W101 Panel");
        title.setTextSize(28);
        l.addView(title);

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, pad / 2, 0, pad / 2);
        info.setText("Wizard101 buttons and a card helper on the Thor's bottom screen.\n\n"
                + "1. Allow this app to display over other apps.\n"
                + "2. Open W101 Panel again while Wizard101 is running. Android asks to allow screen capture: "
                + "that lets the card helper see your hand. (Cancel still gives you the buttons.)\n\n"
                + "Needs W101Quick 1.7 or newer running the game.");
        l.addView(info);

        overlay = new Button(this);
        overlay.setText("Allow display over other apps");
        overlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        l.addView(overlay);

        Button stop = new Button(this);
        stop.setText("Remove panel");
        stop.setOnClickListener(v -> startService(new Intent(this, PanelService.class).setAction(PanelService.ACTION_STOP)));
        l.addView(stop);

        setContentView(l);
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
