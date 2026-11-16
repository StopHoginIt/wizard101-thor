package io.github.stophoginit.w101panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shows a strip of Wizard101 shortcut buttons along the top of the second
 * (bottom) screen. The window never takes focus, so the controller keeps
 * driving the game, and touches outside the strip reach whatever is below it
 * (GameNative's touchpad).
 */
public class PanelService extends Service {
    static final String ACTION_STOP = "stop";
    static volatile boolean running;

    // label, launcher action
    private static final String[][] ROW1 = {
            {"Backpack", "backpack"}, {"Spells", "spells"}, {"Quests", "quests"},
            {"Map", "map"}, {"Potion", "potion"}, {"Pet", "pet"},
    };
    private static final String[][] ROW2 = {
            {"Mark", "mark"}, {"Recall", "recall"}, {"Home", "home"},
            {"Friends", "friends"}, {"Chat", "chat"}, {"Esc", "menu"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout root;
    private LinearLayout body;
    private TextView status;
    private WindowManager.LayoutParams lp;
    private boolean collapsed;
    private int unit; // button height in px

    private final Runnable pinger = new Runnable() {
        @Override public void run() {
            Bridge.ping(v -> main.post(() -> {
                if (status == null) return;
                status.setText(v != null ? "● game" : "○ no game");
                status.setTextColor(v != null ? 0xFF7CE38B : 0xFFB0B0B0);
            }));
            main.postDelayed(this, 3000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, notification());
        if (root == null) show();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacks(pinger);
        if (wm != null && root != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        root = null;
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("panel", "Panel", NotificationManager.IMPORTANCE_MIN));
        Intent stop = new Intent(this, PanelService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "panel")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("W101 Panel is on the bottom screen")
                .addAction(new Notification.Action.Builder(null, "Stop", pi).build())
                .build();
    }

    /** The bottom screen: the first display that isn't the default one; the default display as a fallback. */
    private Display targetDisplay() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private void show() {
        Display display = targetDisplay();
        Context ctx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = ctx.getSystemService(WindowManager.class);
        DisplayMetrics dmx = ctx.getResources().getDisplayMetrics();
        unit = Math.max(dp(ctx, 44), Math.min(dmx.widthPixels, dmx.heightPixels) / 9);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(row(ctx, ROW1));
        body.addView(row(ctx, ROW2));
        root.addView(body);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status = new TextView(ctx);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.28f);
        status.setPadding(unit / 4, 0, unit / 4, 0);
        status.setText("…");
        bar.addView(status);
        TextView handle = button(ctx, "▲ hide", v -> toggle());
        bar.addView(handle, new LinearLayout.LayoutParams(unit * 2, (int) (unit * 0.6f)));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        wm.addView(root, lp);
        running = true;
        main.post(pinger);
    }

    private void toggle() {
        collapsed = !collapsed;
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        status.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        TextView handle = (TextView) ((LinearLayout) root.getChildAt(1)).getChildAt(1);
        handle.setText(collapsed ? "▼ panel" : "▲ hide");
        lp.width = collapsed ? WindowManager.LayoutParams.WRAP_CONTENT : WindowManager.LayoutParams.MATCH_PARENT;
        wm.updateViewLayout(root, lp);
    }

    private LinearLayout row(Context ctx, String[][] items) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] it : items) {
            final String action = it[1];
            TextView b = button(ctx, it[0], v -> Bridge.key(action));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, unit, 1f);
            int m = unit / 16;
            p.setMargins(m, m, m, m);
            r.addView(b, p);
        }
        return r;
    }

    private TextView button(Context ctx, String label, View.OnClickListener onClick) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * 0.3f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC2A1F4A);
        bg.setStroke(2, 0xFFB08CFF);
        bg.setCornerRadius(unit * 0.2f);
        b.setBackground(bg);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                bg.setColor(0xFF6B4FBB);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onClick.onClick(v);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                bg.setColor(0xCC2A1F4A);
            }
            return true;
        });
        return b;
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
}
