package io.github.stophoginit.w101panel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup screen: grant "Display over other apps", then start or stop the panel. */
public class MainActivity extends Activity {
    private TextView info;
    private Button overlay, start, stop;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Opening the app starts the panel straight away once it's allowed, so it
        // also works from the Thor's bottom-screen app picker and from adb.
        // If the panel is already up but hidden, this brings it back.
        if (Settings.canDrawOverlays(this) && !getIntent().getBooleanExtra("setup", false)) {
            startForegroundService(new Intent(this, PanelService.class).setAction(PanelService.ACTION_SHOW));
            finish();
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

        info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, pad / 2, 0, pad / 2);
        l.addView(info);

        overlay = new Button(this);
        overlay.setText("Allow display over other apps");
        overlay.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        l.addView(overlay);

        start = new Button(this);
        start.setText("Show panel on bottom screen");
        start.setOnClickListener(v -> {
            startForegroundService(new Intent(this, PanelService.class));
            finish();
        });
        l.addView(start);

        stop = new Button(this);
        stop.setText("Remove panel");
        stop.setOnClickListener(v -> {
            startService(new Intent(this, PanelService.class).setAction(PanelService.ACTION_STOP));
            refresh();
        });
        l.addView(stop);

        setContentView(l);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean allowed = Settings.canDrawOverlays(this);
        overlay.setEnabled(!allowed);
        start.setEnabled(allowed);
        info.setText(allowed
                ? "Shortcut buttons for Wizard101 on the Thor's bottom screen. They need W101Quick 1.7 or newer to be running the game."
                : "First allow this app to display over other apps (Android asks once).");
    }
}
