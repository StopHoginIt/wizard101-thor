package io.github.stophoginit.w101panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The bottom-screen panel: a Wizard101-styled overlay on the Thor's second
 * display. Outside battle it shows shortcut tiles; while you choose spells it
 * shows your hand (read from the top screen) with Pass / Draw / Flee.
 * The window never takes focus, so the controller keeps driving the game, and
 * touches below it reach GameNative's touchpad.
 */
public class PanelService extends Service {
    static final String ACTION_STOP = "stop";
    static final String ACTION_SHOW = "show";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static volatile boolean running;

    // icon, label, launcher action
    private static final String[][] TILES = {
            {"🎒", "Backpack", "backpack"}, {"📖", "Spells", "spells"}, {"📜", "Quests", "quests"},
            {"🗺", "Map", "map"}, {"🧪", "Potion", "potion"}, {"🐾", "Pet", "pet"},
            {"📍", "Mark", "mark"}, {"🌀", "Recall", "recall"}, {"🏠", "Home", "home"},
            {"👥", "Friends", "friends"}, {"💬", "Chat", "chat"}, {"⚙", "Menu", "menu"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private Context ctx;
    private int unit, screenW;

    private LinearLayout root, body;
    private TextView title, status, hideButton, closeButton;
    private LinearLayout explore, battle, cardRow;
    private TextView drawButton;
    private boolean collapsed;

    private Capture capture;
    private HandReader.Hand hand = new HandReader.Hand();
    private boolean polling;

    // TARGET: after casting a card, the bottom screen shows the battle so you can tap who it's for.
    private enum Mode { EXPLORE, BATTLE, TARGET }
    private Mode mode = Mode.EXPLORE;
    private LinearLayout target;
    private ImageView targetImage;
    private long lastHandSeen, fastUntil;
    private boolean handGoneSinceCast;
    private static final long TARGET_TIMEOUT_MS = 40_000;

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

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (capture == null || !capture.alive() || collapsed) { polling = false; return; }
            final int tw = targetImageWidth();
            capture.snapshot(frame -> {
                HandReader.Hand h = HandReader.read(frame);
                Bitmap small = Bitmap.createScaledBitmap(frame, tw, Math.round(tw * frame.getHeight() / (float) frame.getWidth()), true);
                frame.recycle();
                main.post(() -> { showHand(h); showFrame(small); });
            });
            long now = System.currentTimeMillis();
            main.postDelayed(this, now < fastUntil ? 250 : mode == Mode.TARGET ? 400 : 700);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (intent != null && intent.hasExtra(EXTRA_DATA) && (capture == null || !capture.alive())) {
            try {
                Intent data = intent.getParcelableExtra(EXTRA_DATA);
                capture = new Capture(this, intent.getIntExtra(EXTRA_CODE, 0), data);
            } catch (Exception e) {
                capture = null;
            }
        }
        if (root == null) show();
        else if (collapsed && ACTION_SHOW.equals(intent != null ? intent.getAction() : null)) toggle();
        startPolling();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (capture != null) capture.release();
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

    private Display targetDisplay() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    // ---------------------------------------------------------------- layout

    private void show() {
        Display display = targetDisplay();
        ctx = createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = ctx.getSystemService(WindowManager.class);
        DisplayMetrics dmx = ctx.getResources().getDisplayMetrics();
        screenW = dmx.widthPixels;
        unit = Math.max(88, Math.min(dmx.widthPixels, dmx.heightPixels) / 9);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = unit / 10;
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(Style.panelBackground(unit * 0.25f));

        root.addView(header());

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        FrameLayout content = new FrameLayout(ctx);
        explore = exploreView();
        battle = battleView();
        battle.setVisibility(View.GONE);
        target = targetView();
        target.setVisibility(View.GONE);
        content.addView(explore);
        content.addView(battle);
        content.addView(target);
        body.addView(content);
        root.addView(body);

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

    private LinearLayout header() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int h = (int) (unit * 0.62f);

        title = new TextView(ctx);
        Style.lettering(title, unit * 0.32f, Style.INK);
        title.setShadowLayer(0, 0, 0, 0);
        title.setText("✦ Spellbook ✦");
        title.setBackground(Style.parchment(unit * 0.15f));
        title.setPadding(unit / 4, 0, unit / 4, 0);
        bar.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h));

        status = new TextView(ctx);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, unit * 0.24f);
        status.setPadding(unit / 4, 0, unit / 4, 0);
        status.setText("…");
        status.setTextColor(0xFFB0B0B0);
        bar.addView(status, new LinearLayout.LayoutParams(0, h, 1f));

        hideButton = tileButton(null, "▲ Hide", v -> toggle());
        bar.addView(hideButton, new LinearLayout.LayoutParams((int) (unit * 1.8f), h));

        // Remove only on a long press, so a stray tap can't close the panel.
        closeButton = tileButton(null, "✕", v -> {});
        final Runnable close = this::stopSelf;
        closeButton.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                main.postDelayed(close, 800);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                main.removeCallbacks(close);
                if (e.getAction() == MotionEvent.ACTION_UP && e.getEventTime() - e.getDownTime() < 800) {
                    Toast.makeText(ctx, "Hold ✕ to remove the panel", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams((int) (unit * 0.8f), h);
        cp.setMargins(unit / 10, 0, 0, 0);
        bar.addView(closeButton, cp);
        return bar;
    }

    private LinearLayout exploreView() {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, unit / 10, 0, 0);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(ctx);
            for (int c = 0; c < 6; c++) {
                final String[] t = TILES[r * 6 + c];
                TextView b = tileButton(t[0], t[1], x -> Bridge.key(t[2]));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, (int) (unit * 1.15f), 1f);
                int m = unit / 16;
                p.setMargins(m, m, m, m);
                row.addView(b, p);
            }
            v.addView(row);
        }
        return v;
    }

    private LinearLayout battleView() {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(0, unit / 10, 0, 0);

        TextView hint = new TextView(ctx);
        Style.lettering(hint, unit * 0.24f, Style.GOLD_LIGHT);
        hint.setText("Tap a card to cast  ·  hold to discard");
        v.addView(hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (unit * 0.42f)));

        cardRow = new LinearLayout(ctx);
        cardRow.setGravity(Gravity.CENTER);
        v.addView(cardRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cardHeight()));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.CENTER);
        String[][] bs = {{"PASS", "pass"}, {"DRAW", "draw"}, {"FLEE", "flee"}};
        for (String[] b : bs) {
            final String which = b[1];
            TextView t = battleButton(b[0], x -> pressBattleButton(which));
            if (which.equals("draw")) drawButton = t;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) (unit * 2.4f), (int) (unit * 0.75f));
            p.setMargins(unit / 5, unit / 8, unit / 5, 0);
            buttons.addView(t, p);
        }
        v.addView(buttons);
        return v;
    }

    private int targetImageWidth() { return Math.max(320, screenW - unit / 2); }

    private LinearLayout targetView() {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(0, unit / 12, 0, 0);

        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = new TextView(ctx);
        Style.lettering(hint, unit * 0.26f, Style.GOLD_LIGHT);
        hint.setText("Tap who the spell is for");
        top.addView(hint, new LinearLayout.LayoutParams(0, (int) (unit * 0.6f), 1f));
        TextView back = battleButton("BACK", x -> setMode(hand.n > 0 ? Mode.BATTLE : Mode.EXPLORE));
        top.addView(back, new LinearLayout.LayoutParams((int) (unit * 1.8f), (int) (unit * 0.6f)));
        v.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout f = new FrameLayout(ctx);
        int rim = Math.max(3, unit / 24);
        f.setBackground(Style.frame(0xFF000000, 0xFF000000, unit * 0.08f, rim));
        f.setPadding(rim, rim, rim, rim);
        targetImage = new ImageView(ctx);
        targetImage.setScaleType(ImageView.ScaleType.FIT_XY);
        f.addView(targetImage);
        int w = targetImageWidth() - 2 * rim;
        int h = Math.round(w * 9f / 16f);
        targetImage.setOnTouchListener((iv, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float nx = e.getX() / Math.max(1, iv.getWidth());
                float ny = e.getY() / Math.max(1, iv.getHeight());
                if (nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1) {
                    iv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    clickGame(new float[] {nx, ny}, false);
                }
            }
            return true;
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(w + 2 * rim, h + 2 * rim);
        fp.setMargins(0, unit / 12, 0, 0);
        v.addView(f, fp);
        return v;
    }

    private int cardWidth() { return (screenW - unit) / HandReader.MAX_HAND; }

    private int cardHeight() {
        return Math.round(cardWidth() * (HandReader.CARD_BOTTOM - HandReader.CARD_TOP) / HandReader.CARD_W) + unit / 6;
    }

    // ---------------------------------------------------------------- widgets

    private TextView tileButton(String icon, String label, View.OnClickListener onClick) {
        TextView b = new TextView(ctx);
        b.setText(icon != null ? icon + "\n" + label : label);
        Style.lettering(b, unit * 0.26f, Style.GOLD_LIGHT);
        if (icon != null) b.setLineSpacing(unit * 0.05f, 1f);
        final float radius = unit * 0.18f;
        final int rim = Math.max(3, unit / 22);
        b.setBackground(Style.tile(false, radius, rim));
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setBackground(Style.tile(true, radius, rim));
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onClick.onClick(v);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setBackground(Style.tile(false, radius, rim));
            }
            return true;
        });
        return b;
    }

    private TextView battleButton(String label, View.OnClickListener onClick) {
        TextView b = new TextView(ctx);
        b.setText(label);
        Style.lettering(b, unit * 0.36f, Style.YELLOW);
        final float radius = unit * 0.12f;
        final int rim = Math.max(3, unit / 20);
        b.setBackground(Style.battleButton(false, true, radius, rim));
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setBackground(Style.battleButton(true, true, radius, rim));
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onClick.onClick(v);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setBackground(Style.battleButton(false, true, radius, rim));
            }
            return true;
        });
        return b;
    }

    private View cardView(int index, Bitmap bmp) {
        FrameLayout f = new FrameLayout(ctx);
        int rim = Math.max(3, unit / 24);
        f.setBackground(Style.frame(0xFF000000, 0xFF000000, unit * 0.08f, rim));
        f.setPadding(rim, rim, rim, rim);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(bmp);
        f.addView(img);
        final Runnable discard = () -> {
            f.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            f.setTag("done");
            clickGame(hand.cardPoint(index), true);
            f.setVisibility(View.GONE); // the game takes a moment; don't wait for it
        };
        f.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setTag(null);
                v.setScaleX(0.94f);
                v.setScaleY(0.94f);
                main.postDelayed(discard, 450);
            } else if (e.getAction() == MotionEvent.ACTION_UP) {
                main.removeCallbacks(discard);
                v.setScaleX(1f);
                v.setScaleY(1f);
                if (v.getTag() == null) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    clickGame(hand.cardPoint(index), false);
                    setMode(Mode.TARGET);
                }
            } else if (e.getAction() == MotionEvent.ACTION_CANCEL) {
                main.removeCallbacks(discard);
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            return true;
        });
        return f;
    }

    // ---------------------------------------------------------------- behaviour

    private void toggle() {
        collapsed = !collapsed;
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        status.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        closeButton.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        title.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        hideButton.setText(collapsed ? "▼ Spellbook" : "▲ Hide");
        hideButton.getLayoutParams().width = (int) (unit * (collapsed ? 2.6f : 1.8f));
        hideButton.requestLayout();
        lp.width = collapsed ? WindowManager.LayoutParams.WRAP_CONTENT : WindowManager.LayoutParams.MATCH_PARENT;
        wm.updateViewLayout(root, lp);
        if (!collapsed) startPolling();
    }

    private void startPolling() {
        if (polling || capture == null) return;
        polling = true;
        main.post(poll);
    }

    private void showHand(HandReader.Hand h) {
        if (root == null) return;
        HandReader.Hand old = hand;
        hand = h;
        boolean inBattle = h.n > 0;
        long now = System.currentTimeMillis();
        if (inBattle) {
            lastHandSeen = now;
            // Leave the target view only when a new round's cards appear (the hand went away
            // after the cast and came back); while targeting, the cards may stay on screen.
            if (mode != Mode.TARGET || handGoneSinceCast) setMode(Mode.BATTLE);
        } else if (mode == Mode.TARGET) {
            handGoneSinceCast = true;
            if (now - lastHandSeen > TARGET_TIMEOUT_MS) setMode(Mode.EXPLORE); // the battle is over
        } else if (mode == Mode.BATTLE) {
            setMode(Mode.EXPLORE);
        }
        cardRow.removeAllViews();
        if (inBattle) {
            if (drawButton != null) drawButton.setAlpha(h.n < HandReader.MAX_HAND ? 1f : 0.45f);
            int m = unit / 24;
            for (int i = 0; i < h.n; i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cardWidth() - 2 * m, cardHeight() - unit / 6);
                p.setMargins(m, 0, m, 0);
                cardRow.addView(cardView(i, h.cards[i]), p);
            }
        }
        if (old != null && old != h) for (Bitmap b : old.cards) if (b != null && !b.isRecycled()) b.recycle();
    }

    private void setMode(Mode m) {
        mode = m;
        explore.setVisibility(m == Mode.EXPLORE ? View.VISIBLE : View.GONE);
        battle.setVisibility(m == Mode.BATTLE ? View.VISIBLE : View.GONE);
        target.setVisibility(m == Mode.TARGET ? View.VISIBLE : View.GONE);
        title.setText(m == Mode.EXPLORE ? "✦ Spellbook ✦" : m == Mode.BATTLE ? "✦ Battle ✦" : "✦ Target ✦");
        if (m == Mode.TARGET) {
            lastHandSeen = System.currentTimeMillis();
            handGoneSinceCast = false;
        }
    }

    private void showFrame(Bitmap small) {
        if (root == null || mode != Mode.TARGET) { small.recycle(); return; }
        android.graphics.drawable.Drawable d = targetImage.getDrawable();
        targetImage.setImageBitmap(small);
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && b != small && !b.isRecycled()) b.recycle();
        }
    }

    private void pressBattleButton(String which) {
        float x = which.equals("pass") ? HandReader.PASS_X : which.equals("draw") ? HandReader.DRAW_X : HandReader.FLEE_X;
        clickGame(new float[] {x / HandReader.REF_W, HandReader.BUTTON_Y / HandReader.REF_H}, false);
    }

    private void clickGame(float[] p, boolean right) {
        Bridge.send(String.format(Locale.US, "w101 click %.4f %.4f%s", p[0], p[1], right ? " r" : ""));
        // Look again quickly for a couple of seconds, so the bottom screen follows the game.
        fastUntil = System.currentTimeMillis() + 2000;
        main.removeCallbacks(poll);
        polling = false;
        main.postDelayed(this::startPolling, 250);
    }
}
