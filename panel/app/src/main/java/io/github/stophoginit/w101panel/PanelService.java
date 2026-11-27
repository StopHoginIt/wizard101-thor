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
    private volatile boolean cardsOn = true;      // the card view (remembered; header toggle)
    private volatile boolean wantTargetFrames;     // TARGET mode: stream small pictures of the battle
    private volatile int targetW = 960;
    private TextView cardsButton, battleHint;
    private FrameLayout targetFrame;
    private volatile long lastClickAt;             // uptime of the last click sent to the game
    private HandReader.Hand lastPics;              // newest reading that carried card pictures
    private long sawCardsAt;                       // last reading with cards, even an unsteady one
    private int prompt = HandReader.PROMPT_NORMAL; // last prompt read clearly for this hand
    private final FrameLayout[] cardViews = new FrameLayout[HandReader.MAX_HAND];
    private final ImageView[] cardImages = new ImageView[HandReader.MAX_HAND];
    private static final long FAST_MS = 66;        // about 15 frames a second in battle
    private static final long SLOW_MS = 250;

    // TARGET: after casting a card, the bottom screen shows the battle so you can tap who it's for.
    private enum Mode { EXPLORE, BATTLE, TARGET, WAIT }
    private Mode mode = Mode.EXPLORE;
    private LinearLayout target;
    private ImageView targetImage;
    private long lastHandSeen;
    private HandReader.Hand lastRead;
    private boolean connected;
    private int health = -1, mana = -1, potions = -1, maxHealth = -1;
    private int normalPips = -1, powerPips = -1;
    private boolean battleStats;   // show pips (we're in a battle)
    private long castUntil, noHandSince, shownAt, lastBattlePing;
    private int bookPolls;
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

    // Capture-thread state for the frame listener.
    private int cN = -1, cLeft = -1, cropNext;
    private boolean pendingCrop, provisional;
    private long pendingSince;
    private long cropAt, statsAt, targetAt, handGoneSince = 1;

    /**
     * Runs on the capture thread for every frame handed over (about 15 a second in battle,
     * 2.5 a second otherwise). Probing the hand costs a few hundred pixel reads.
     *
     * Card pictures are copied only when the hand changed, and only from a clean frame: not
     * while the game wants a target or a card to enchant (it dims and highlights cards then),
     * and not within 0.45 s of a click (the pointer may still be over a card, which the game
     * draws enlarged). Two frames get pictures, so the steady reading has them.
     */
    private final Capture.Listener frameListener = f -> {
        long now = f.time;
        HandReader.Hand h = HandReader.read(f, false);
        final boolean book = h.n == 0 && HandReader.spellbookVisible(f);
        boolean layoutChanged = !h.unsure && (h.n != cN || Math.abs(h.left - cLeft) > 3);
        if (!h.unsure) { cN = h.n; cLeft = h.left; }
        if (layoutChanged) { pendingCrop = true; pendingSince = now; }
        // A clean frame: no card enlarged, the hint line shows the normal prompt, no recent click.
        boolean clean = cardsOn && h.n > 0 && !h.hover && h.prompt == HandReader.PROMPT_NORMAL
                && now - lastClickAt >= 450;
        if (clean && (pendingCrop || provisional)) { cropNext = 2; pendingCrop = false; provisional = false; }
        else if (pendingCrop && cardsOn && h.n > 0 && !h.hover && now - pendingSince > 1000) {
            // Still no clean frame after a second (e.g. the game greys cards for an enchant
            // choice): use this one for now and replace it as soon as a clean frame comes.
            cropNext = 2; pendingCrop = false; provisional = true;
            clean = true;
        }
        if (clean && cropNext > 0) {
            h = HandReader.read(f, true);
            cropAt = now;
            cropNext--;
        }
        // A new round: cards back after being gone for a moment (pips change every round).
        boolean newRound = h.n > 0 && handGoneSince > 0 && now - handGoneSince >= 1500;
        if (h.n > 0) handGoneSince = 0; else if (!h.unsure && handGoneSince == 0) handGoneSince = now;
        boolean inBattle = h.n > 0;
        if (newRound || now - statsAt >= (inBattle ? 1000 : 2000)) {
            statsAt = now;
            final boolean roundStart = newRound;
            Stats.read(f.full(), inBattle, st -> main.post(() -> onStats(st, inBattle, roundStart)));
        }
        Bitmap small = null;
        if (wantTargetFrames && now - targetAt >= 120) {
            targetAt = now;
            small = f.scaled(targetW);
        }
        final HandReader.Hand hf = h;
        final Bitmap sf = small;
        main.post(() -> { showHand(hf, book); if (sf != null) showFrame(sf); });
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
                capture.setListener(frameListener);
                capture.setInterval(SLOW_MS);
            } catch (Exception e) {
                capture = null;
            }
        }
        if (root == null) show();
        else if (collapsed && ACTION_SHOW.equals(intent != null ? intent.getAction() : null)) toggle();
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
        cardsOn = getSharedPreferences("panel", MODE_PRIVATE).getBoolean("cards", true);

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
        targetW = Math.min(960, pictureSize()[0]);
        waitBanner = new TextView(ctx);
        Style.lettering(waitBanner, unit * 0.3f, Style.GOLD_LIGHT);
        waitBanner.setText("✦ Casting…  (tap here for your cards) ✦");
        waitBanner.setVisibility(View.GONE);
        waitBanner.setOnClickListener(x -> { if (hand.n > 0) { setMode(Mode.BATTLE); render(hand, true); } });
        content.addView(explore);
        content.addView(battle);
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

        cardsButton = tileButton(null, "", v -> setCardsOn(!cardsOn));
        updateCardsButton();
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams((int) (unit * 1.9f), h);
        cbp.setMargins(0, 0, unit / 10, 0);
        bar.addView(cardsButton, cbp);

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

        battleHint = new TextView(ctx);
        Style.lettering(battleHint, unit * 0.24f, Style.GOLD_LIGHT);
        battleHint.setText(HINT_CARDS);
        v.addView(battleHint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (unit * 0.42f)));

        cardRow = new LinearLayout(ctx);
        cardRow.setGravity(Gravity.CENTER);
        int m = unit / 24;
        for (int i = 0; i < HandReader.MAX_HAND; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cardWidth() - 2 * m, cardHeight() - unit / 6);
            p.setMargins(m, 0, m, 0);
            cardViews[i] = cardView(i);
            cardViews[i].setVisibility(View.GONE);
            cardRow.addView(cardViews[i], p);
        }
        v.addView(cardRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cardHeight()));

        // The live battle picture, shown under the cards only while the game wants a target, so
        // the cards never move (and an enchantment's card choice stays right there).
        targetFrame = targetPicture();
        targetFrame.setVisibility(View.GONE);
        v.addView(targetFrame);

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

    private static final String HINT_CARDS = "Tap a card to cast  ·  hold to discard";
    private static final String HINT_TARGET = "Tap who the spell is for  ·  or tap another card";
    private static final String HINT_ENCHANT = "Tap the spell to enchant";

    /** The battle picture's size: what's left of the bottom screen under the cards and buttons. */
    private int[] pictureSize() {
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int used = (int) (unit * 0.62f) + 2 * (unit / 10)          // header, panel padding
                + unit / 10 + (int) (unit * 0.42f) + cardHeight()   // battle padding, hint, cards
                + (int) (unit * 0.75f) + unit / 8                   // PASS / DRAW / FLEE
                + unit / 12 + unit / 4;                              // picture margin, spare
        int rim = Math.max(3, unit / 24);
        int h = Math.max(unit * 2, screenH - used) - 2 * rim;
        int w = Math.min(targetImageWidth() - 2 * rim, Math.round(h * 16f / 9f));
        h = Math.round(w * 9f / 16f);
        return new int[] {w, h};
    }

    private FrameLayout targetPicture() {
        FrameLayout f = new FrameLayout(ctx);
        int rim = Math.max(3, unit / 24);
        f.setBackground(Style.frame(0xFF000000, 0xFF000000, unit * 0.08f, rim));
        f.setPadding(rim, rim, rim, rim);
        targetImage = new ImageView(ctx);
        targetImage.setScaleType(ImageView.ScaleType.FIT_XY);
        f.addView(targetImage);
        int[] size = pictureSize();
        targetImage.setOnTouchListener((iv, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float nx = e.getX() / Math.max(1, iv.getWidth());
                float ny = e.getY() / Math.max(1, iv.getHeight());
                if (nx >= 0 && nx <= 1 && ny >= 0 && ny <= 1) {
                    iv.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    clickGame(new float[] {nx, ny}, false);
                    castUntil = System.currentTimeMillis() + 600; // the game still shows targeting for a moment
                    setMode(Mode.BATTLE);
                }
            }
            return true;
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(size[0] + 2 * rim, size[1] + 2 * rim);
        fp.gravity = Gravity.CENTER_HORIZONTAL;
        fp.setMargins(0, unit / 12, 0, 0);
        f.setLayoutParams(fp);
        return f;
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

    private FrameLayout cardView(int index) {
        FrameLayout f = new FrameLayout(ctx);
        int rim = Math.max(3, unit / 24);
        f.setBackground(Style.frame(0xFF000000, 0xFF000000, unit * 0.08f, rim));
        f.setPadding(rim, rim, rim, rim);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        cardImages[index] = img;
        f.addView(img);
        final Runnable discard = () -> {
            if (index >= hand.n) return;
            f.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            f.setTag("done");
            clickGame(hand.cardPoint(index), true);
            // The game takes a moment to re-deal; show the result straight away.
            castUntil = System.currentTimeMillis() + 450;
            lastRead = null;
            render(hand.without(index), true);
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
                if (v.getTag() == null && index < hand.n) {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    // Select the card in the game. The panel follows what the game does next
                    // (target picture, enchant choice, or the hand going away) as soon as it sees it.
                    clickGame(hand.cardPoint(index), false);
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
        updateInterval();
    }

    private void setCardsOn(boolean on) {
        cardsOn = on;
        getSharedPreferences("panel", MODE_PRIVATE).edit().putBoolean("cards", on).apply();
        updateCardsButton();
        if (!on && mode != Mode.EXPLORE) setMode(Mode.EXPLORE);
        lastRead = null;
        updateInterval();
    }

    private void updateCardsButton() {
        if (cardsButton != null) cardsButton.setText(cardsOn ? "🃏 Cards ✓" : "🃏 Cards ✕");
    }

    /** Stream fast while cards are shown in a battle; otherwise a slow look every 0.4 s. */
    private void updateInterval() {
        if (capture == null) return;
        long now = System.currentTimeMillis();
        boolean battleOn = mode != Mode.EXPLORE || now - sawCardsAt < 3000
                || (lastHandSeen > 0 && now - lastHandSeen < TARGET_TIMEOUT_MS && bookPolls == 0);
        capture.setInterval(cardsOn && !collapsed && battleOn ? FAST_MS : SLOW_MS);
    }

    private void showHand(HandReader.Hand h, boolean book) {
        if (root == null) return;
        if (h.unsure) return; // the row is sliding: no information either way
        if (h.n > 0) {
            if (System.currentTimeMillis() - sawCardsAt > 3000 && capture != null) capture.poke();
            sawCardsAt = System.currentTimeMillis();
        }
        if (!cardsOn || collapsed) trackBattle(h, book);
        else showHandCards(h, book);
        updateInterval();
    }

    /**
     * The card view. Frames arrive about 15 times a second in battle; a reading counts once two in
     * a row agree (about 0.15 s). The panel never guesses ahead of the game, except for a discard
     * (the rest of the hand closes up at once): it shows the target picture only when the game
     * asks for a target, so an enchantment keeps the hand up for picking the card to enchant.
     */
    private void showHandCards(HandReader.Hand h, boolean book) {
        long now = System.currentTimeMillis();
        // The world HUD's spellbook button is hidden for a whole battle; two sightings in a row
        // mean the fight is over, so stop waiting for another round right away.
        bookPolls = book ? bookPolls + 1 : 0;
        if (bookPolls >= 2 && (lastHandSeen > 0 || mode != Mode.EXPLORE)) {
            lastHandSeen = 0;
            battleStats = false;
            Bridge.send("w101 explore");
            if (mode != Mode.EXPLORE) setMode(Mode.EXPLORE);
            return;
        }
        boolean stable = lastRead != null && sameLayout(lastRead, h);
        lastRead = h;
        if (hasPictures(h)) lastPics = h;
        if (!stable) return;
        // Frames between picture copies carry none: use the newest pictures of this same hand.
        if (h.n > 0 && !hasPictures(h) && lastPics != null && sameLayout(lastPics, h)) h.cards = lastPics.cards;

        if (h.n > 0 || (lastHandSeen > 0 && now - lastHandSeen < TARGET_TIMEOUT_MS && mode != Mode.EXPLORE)) {
            // Tell the launcher a battle is on, so the stick doesn't turn with the right mouse
            // button (a right-click there discards a card).
            if (now - lastBattlePing > 1000) { Bridge.send("w101 battle"); lastBattlePing = now; }
        }
        if (now < castUntil) return; // we just clicked; give the game a moment to catch up
        if (h.n > 0) {
            noHandSince = 0;
            lastHandSeen = now;
            // The cards stay put; the battle picture shows under them while the game wants a target.
            // The prompt reads "unknown" while an enlarged card covers it: keep the last one.
            boolean newHand = shown == null || !sameLayout(shown, h);
            if (h.prompt != HandReader.PROMPT_UNKNOWN) prompt = h.prompt;
            else if (newHand && !h.hover) prompt = HandReader.PROMPT_NORMAL;
            Mode want = prompt == HandReader.PROMPT_TARGET ? Mode.TARGET : Mode.BATTLE;
            if (mode != want) setMode(want);
            battleHint.setText(prompt == HandReader.PROMPT_TARGET ? HINT_TARGET
                    : prompt == HandReader.PROMPT_ENCHANT ? HINT_ENCHANT : HINT_CARDS);
            boolean layout = shown == null || !sameLayout(shown, h);
            if (layout && hasPictures(h)) render(h, true);           // a new hand, with clean pictures
            else if (!layout && hasPictures(h) && h.cards != shown.cards) render(h, false);
            else if (!layout) hand = h;                              // same hand: keep tap positions fresh
        } else {
            if (noHandSince == 0) noHandSince = now;
            if ((mode == Mode.BATTLE || mode == Mode.TARGET) && now - noHandSince > 700) {
                setMode(Mode.WAIT); // the spell is being cast, or the round is playing out
            } else if (mode == Mode.WAIT && now - lastHandSeen > TARGET_TIMEOUT_MS) {
                setMode(Mode.EXPLORE); // the battle is over
            }
        }
    }

    private static boolean sameLayout(HandReader.Hand a, HandReader.Hand b) {
        return a.n == b.n && (a.n == 0 || Math.abs(a.left - b.left) <= 3);
    }

    private static boolean hasPictures(HandReader.Hand h) {
        return h.cards.length > 0 && h.cards[0] != null;
    }

    /**
     * Battle tracking without the card view: a battle runs from the first hand until the world
     * HUD's spellbook button is seen twice in a row (or 40 s after the last hand). While it runs
     * the launcher is told, so the left stick doesn't right-click (that discards a card), and the
     * header shows pips instead of potions.
     */
    private void trackBattle(HandReader.Hand h, boolean book) {
        long now = System.currentTimeMillis();
        bookPolls = book ? bookPolls + 1 : 0;
        if (bookPolls >= 2 && lastHandSeen > 0) {
            lastHandSeen = 0;
            battleStats = false;
            Bridge.send("w101 explore");
            updateStatus();
            return;
        }
        boolean stable = lastRead != null && sameLayout(lastRead, h);
        lastRead = h;
        if (!stable) return;
        if (h.n > 0) { lastHandSeen = now; hand = h; }
        boolean battleOn = h.n > 0 || (lastHandSeen > 0 && now - lastHandSeen < TARGET_TIMEOUT_MS && bookPolls == 0);
        if (battleOn && now - lastBattlePing > 1000) { Bridge.send("w101 battle"); lastBattlePing = now; }
    }

    /** Shows hand h on the bottom screen (the card views are reused; only pictures change). */
    private void render(HandReader.Hand h, boolean layout) {
        hand = h;
        shown = h;
        shownAt = System.currentTimeMillis();
        if (drawButton != null) drawButton.setAlpha(h.n < HandReader.MAX_HAND ? 1f : 0.45f);
        for (int i = 0; i < HandReader.MAX_HAND; i++) {
            boolean on = i < h.n;
            if (layout) cardViews[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (on && i < h.cards.length && h.cards[i] != null) cardImages[i].setImageBitmap(h.cards[i]);
        }
    }

    private void onStats(Stats st, boolean inBattle, boolean roundStart) {
        long now = System.currentTimeMillis();
        if (st.health >= 0) health = st.health;
        if (st.mana >= 0) mana = st.mana;
        if (st.maxHealth > 0) maxHealth = st.maxHealth;
        // In a battle from the first hand until 40 s after the last one (the end of the fight).
        battleStats = inBattle || (lastHandSeen > 0 && now - lastHandSeen < TARGET_TIMEOUT_MS);
        if (inBattle && st.normalPips >= 0) {
            // Pips are set when a round starts and can't go up until the next one, so keep the
            // round's best reading: a reading taken while the nameplate is covered or fading
            // can't knock the count down.
            if (roundStart || normalPips < 0) { normalPips = 0; powerPips = 0; }
            if (st.normalPips + 2 * st.powerPips > normalPips + 2 * powerPips) {
                normalPips = st.normalPips;
                powerPips = st.powerPips;
            }
        }
        // The potion bottles are only drawn outside battle.
        if (!battleStats && st.potions >= 0) potions = st.potions;
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
        battle.setVisibility(m == Mode.BATTLE || m == Mode.TARGET ? View.VISIBLE : View.GONE);
        targetFrame.setVisibility(m == Mode.TARGET ? View.VISIBLE : View.GONE);
        waitBanner.setVisibility(m == Mode.WAIT ? View.VISIBLE : View.GONE);
        title.setText(m == Mode.EXPLORE ? "✦ Spellbook ✦" : m == Mode.BATTLE ? "✦ Battle ✦"
                : m == Mode.TARGET ? "✦ Target ✦" : "✦ Battle ✦");
        wantTargetFrames = m == Mode.TARGET;
        if (m == Mode.TARGET) {
            lastHandSeen = System.currentTimeMillis();
            if (capture != null) capture.poke(); // the last picture stays until a fresh one arrives
        }
    }

    /** The live target picture (about 8 a second while the game wants a target). */
    private void showFrame(Bitmap small) {
        if (root == null || mode != Mode.TARGET) return;
        targetImage.setImageBitmap(small);
    }

    private void pressBattleButton(String which) {
        float x = which.equals("pass") ? HandReader.PASS_X : which.equals("draw") ? HandReader.DRAW_X : HandReader.FLEE_X;
        clickGame(new float[] {x / HandReader.REF_W, HandReader.BUTTON_Y / HandReader.REF_H}, false);
    }

    private void clickGame(float[] p, boolean right) {
        Bridge.send(String.format(Locale.US, "w101 click %.4f %.4f%s", p[0], p[1], right ? " r" : ""));
        lastClickAt = android.os.SystemClock.uptimeMillis();
        // Look again right away, so the bottom screen follows the game.
        if (capture != null) capture.poke();
    }
}
