package io.github.stophoginit.w101panel;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.widget.Toast;

/**
 * "W101 Play": one tap starts the panel on the bottom screen and then
 * Wizard101 in GameNative on the top screen.
 *
 * The game is started through GameNative's own launch link (the one its
 * "Copy launch link" menu gives you). The link is saved on the device in this
 * app's settings when you paste it in W101 Panel; nothing about your game or
 * device is built into the app.
 */
public class PlayActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    static final String PREFS = "panel";
    static final String KEY_LINK = "game_link";

    static String savedLink(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LINK, null);
    }

    /** Accepts only GameNative launch links: gamenative://run?appid=...&gamesource=... */
    static boolean validLink(String s) {
        if (s == null) return false;
        Uri u = Uri.parse(s.trim());
        return "gamenative".equals(u.getScheme()) && "run".equals(u.getHost())
                && u.getQueryParameter("appid") != null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Settings.canDrawOverlays(this) || !validLink(savedLink(this))) {
            Toast.makeText(this, "Finish setup first: paste the Wizard101 launch link", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class).putExtra("setup", true));
            finish();
            return;
        }
        if (PanelService.running) {
            launchGame();
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        Intent i = new Intent(this, PanelService.class).setAction(PanelService.ACTION_SHOW);
        if (resultCode == RESULT_OK && data != null) {
            i.putExtra(PanelService.EXTRA_CODE, resultCode);
            i.putExtra(PanelService.EXTRA_DATA, data);
        }
        startForegroundService(i);
        launchGame();
    }

    private void launchGame() {
        Intent game = new Intent(Intent.ACTION_VIEW, Uri.parse(savedLink(this).trim()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // The top screen is the Thor's main display; open the game there even
        // when W101 Play was tapped on the bottom screen.
        ActivityOptions opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        try {
            startActivity(game, opts.toBundle());
        } catch (SecurityException e) {
            tryPlain(game);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "GameNative didn't accept the launch link", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void tryPlain(Intent game) {
        try {
            startActivity(game);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't start GameNative", Toast.LENGTH_LONG).show();
        }
    }
}
