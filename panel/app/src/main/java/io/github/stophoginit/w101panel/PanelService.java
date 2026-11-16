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
    private enum Mode { EXPLORE, BATTLE, TARGET, WAIT }
    private Mode mode = Mode.EXPLORE;
    private LinearLayout target;
    private ImageView targetImage;
    private long lastHandSeen, fastUntil, lastTargetFrame;
    private boolean handGoneSinceCast;
    private HandReader.Hand lastRead;
    private boolean connected;
    private int statTick;
    private volatile boolean handWasUp;
    private int health = -1, mana = -1, potions = -1, maxHealth = -1;
    private int normalPips = -1, powerPips = -1;
    private boolean battleStats;   // show pips (we're in a battle)
    private long castUntil, noHandSince, shownAt;
    private HandReader.Hand shown;
    private TextView waitBanner;
    private static final long TARGET_TIMEOUT_MS = 40_000;

    private final Runnable pinger = new Runnable() {
        @Override public void run() {
            Bridge.ping(v -> main.post(() -> {
                connected = v != null;
                updateStatus();
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
                // Read stats as soon as a round's cards appear (pips change every round),
                // then every few snapshots.
                boolean newRound = h.n > 0 && !handWasUp;
                handWasUp = h.n > 0;
                if (newRound || statTick++ % 3 == 0) {
                    final boolean inBattle = h.n > 0;
                    Stats.read(frame, inBattle, st -> main.post(() -> onStats(st, inBattle)));
                }
                Bitmap small = Bitmap.createScaledBitmap(frame, tw, Math.round(tw * frame.getHeight() / (float) frame.getWidth()), true);
                frame.recycle();
                main.post(() -> { showHand(h); showFrame(small); });
            });
            long now = System.currentTimeMillis();
            main.postDelayed(this, now < fastUntil ? 250 : 700);
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
        waitBanner = new TextView(ctx);
        Style.lettering(waitBanner, unit * 0.3f, Style.GOLD_LIGHT);
        waitBanner.setText("✦ Casting…  (tap here for your cards) ✦");
        waitBanner.setVisibility(View.GONE);
        waitBanner.setOnClickListener(x -> { if (hand.n > 0) { setMode(Mode.BATTLE); render(hand); } });
        content.addView(explore);
        content.addView(battle);
        content.addView(target);
        content.addView(waitBanner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (int) (unit * 0.7f)));
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
        Style.lettering(status, unit * 0.3f, Style.GOLD_LIGHT);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.setPadding(unit / 4, 0, unit / 8, 0);
        status.setSingleLine(true);
        status.setText("…");
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
        TextView cards = battleButton("◀ CARDS", x -> { castUntil = 0; setMode(Mode.BATTLE); render(hand); });
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams((int) (unit * 2.0f), (int) (unit * 0.6f));
        cpl.setMargins(0, 0, unit / 8, 0);
        top.addView(cards, cpl);
        TextView back = battleButton("DONE", x -> setMode(Mode.WAIT));
        top.addView(back, new LinearLayout.LayoutParams((int) (unit * 1.6f), (int) (unit * 0.6f)));
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
                    setMode(Mode.WAIT);
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
            // The game takes a moment to re-deal; show the result straight away.
            lastRead = null;
            render(hand.without(index));
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
                    castUntil = System.currentTimeMillis() + 1500;
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
        long now = System.currentTimeMillis();
        boolean inBattle = h.n > 0;
        // Only trust a reading once two in a row agree (cards slide after a discard or cast).
        boolean stable = lastRead != null && lastRead.n == h.n && lastRead.targeting == h.targeting
                && (h.n == 0 || Math.abs(lastRead.left - h.left) <= 3);
        lastRead = h;
        if (!stable) return;

        if (inBattle) {
            noHandSince = 0;
            lastHandSeen = now;
            hand = h;
            if (h.targeting) {
                // A card is chosen (here or in the game) and the game wants a target.
                if (mode != Mode.TARGET) setMode(Mode.TARGET);
            } else if (mode == Mode.TARGET && now < castUntil) {
                // We just cast from the panel; the game hasn't caught up yet.
            } else {
                boolean changed = mode != Mode.BATTLE || shown == null || shown.n != h.n
                        || Math.abs(shown.left - h.left) > 3 || now - shownAt > 3000;
                if (mode != Mode.BATTLE) setMode(Mode.BATTLE);
                if (changed) render(h); // redraw only when the hand changed (less flicker)
            }
        } else {
            if (noHandSince == 0) noHandSince = now;
            boolean goneAWhile = now - noHandSince > 1200;
            if (mode == Mode.TARGET && goneAWhile && now >= castUntil) {
                setMode(Mode.WAIT); // the spell is being cast (it needed no target, or one was picked)
            } else if (mode == Mode.BATTLE && goneAWhile) {
                setMode(Mode.EXPLORE);
            } else if (mode == Mode.WAIT && now - lastHandSeen > TARGET_TIMEOUT_MS) {
                setMode(Mode.EXPLORE); // the battle is over
            }
        }
    }

    /** Shows hand h on the bottom screen. */
    private void render(HandReader.Hand h) {
        hand = h;
        shown = h;
        shownAt = System.currentTimeMillis();
        cardRow.removeAllViews();
        if (drawButton != null) drawButton.setAlpha(h.n < HandReader.MAX_HAND ? 1f : 0.45f);
        int m = unit / 24;
        for (int i = 0; i < h.n; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cardWidth() - 2 * m, cardHeight() - unit / 6);
            p.setMargins(m, 0, m, 0);
            cardRow.addView(cardView(i, h.cards[i]), p);
        }
    }

    private void onStats(Stats st, boolean inBattle) {
        if (st.health >= 0) health = st.health;
        if (st.mana >= 0) mana = st.mana;
        if (st.maxHealth > 0) maxHealth = st.maxHealth;
        battleStats = inBattle || mode == Mode.TARGET || mode == Mode.WAIT;
        if (inBattle && st.normalPips >= 0) { normalPips = st.normalPips; powerPips = st.powerPips; }
        // The potion bottles are only drawn outside battle.
        if (!inBattle && mode == Mode.EXPLORE && st.potions >= 0) potions = st.potions;
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        if (!connected) {
            status.setText("○ no game");
            status.setTextColor(0xFFB0B0B0);
            return;
        }
        StringBuilder b = new StringBuilder();
        if (health >= 0) {
            b.append("❤ ").append(health);
            if (maxHealth >= health) b.append("/").append(maxHealth);
            b.append("   ");
        }
        if (mana >= 0) b.append("💧 ").append(mana).append("   ");
        if (battleStats && normalPips >= 0) {
            for (int i = 0; i < normalPips; i++) b.append("⚪");
            for (int i = 0; i < powerPips; i++) b.append("🟡");
            if (normalPips + powerPips == 0) b.append("no pips");
        } else if (potions >= 0) {
            b.append("🧪 ").append(potions);
        }
        status.setText(b.length() > 0 ? b.toString().trim() : "● game");
        status.setTextColor(Style.GOLD_LIGHT);
    }

    private void setMode(Mode m) {
        mode = m;
        explore.setVisibility(m == Mode.EXPLORE ? View.VISIBLE : View.GONE);
        battle.setVisibility(m == Mode.BATTLE ? View.VISIBLE : View.GONE);
        target.setVisibility(m == Mode.TARGET ? View.VISIBLE : View.GONE);
        waitBanner.setVisibility(m == Mode.WAIT ? View.VISIBLE : View.GONE);
        title.setText(m == Mode.EXPLORE ? "✦ Spellbook ✦" : m == Mode.BATTLE ? "✦ Battle ✦"
                : m == Mode.TARGET ? "✦ Target ✦" : "✦ Battle ✦");
        if (m == Mode.TARGET) {
            lastHandSeen = System.currentTimeMillis();
            lastTargetFrame = 0;
            handGoneSinceCast = false;
            targetImage.setImageDrawable(null);
        }
    }

    private void showFrame(Bitmap small) {
        long now = System.currentTimeMillis();
        if (root == null || mode != Mode.TARGET || now - lastTargetFrame < 1500) { small.recycle(); return; }
        lastTargetFrame = now;
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
