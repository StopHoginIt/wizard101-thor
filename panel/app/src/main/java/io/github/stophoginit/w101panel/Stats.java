package io.github.stophoginit.w101panel;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Reads health and mana from the globes in the bottom-left corner of the top
 * screen, and counts the potion bottles next to them (shown outside battle).
 *
 * The numbers are yellow lettering with a dark outline. Each one is cut out,
 * reduced to the yellow areas the outline fully encloses (so a yellow globe
 * behind doesn't count), turned into black-on-white text and read with ML
 * Kit's on-device text recognition (nothing leaves the Thor).
 * Positions measured at 1920x1080:
 *   health x 20..150, y 922..975   mana x 118..205, y 972..1015
 *   potions: bottles from x 240, every 45 px, at y 1025
 * In battle, while choosing spells, your nameplate sits in the bottom-right:
 *   health "current/max" in red at x 1525..1690, y 1008..1038
 *   pips in a row from x 1535, every 23.6 px, at y 1051 (white = 1, gold with your school sign = 2)
 */
final class Stats {
    interface Listener { void onStats(Stats s); }

    int health = -1, mana = -1, potions = -1;
    int maxHealth = -1;          // from your battle nameplate ("7,647/7,931")
    int normalPips = -1, powerPips = -1;

    private static final float REF_W = 1920f, REF_H = 1080f;
    private static final int[][] REGIONS = { // x0, y0, x1, y1
            {20, 922, 150, 975},   // health
            {118, 972, 205, 1015}, // mana
    };
    private static TextRecognizer recognizer;

    private Stats() {}

    /** Cuts what's needed out of the screenshot (which the caller may then recycle) and reads it. */
    static void read(Bitmap screen, boolean inBattle, Listener l) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        float sx = screen.getWidth() / REF_W, sy = screen.getHeight() / REF_H;
        Stats s = new Stats();
        s.potions = countPotions(screen, sx, sy);
        int n = inBattle ? REGIONS.length + 1 : REGIONS.length;
        Bitmap[] crops = new Bitmap[n];
        for (int i = 0; i < REGIONS.length; i++) crops[i] = textOnly(screen, REGIONS[i], sx, sy, false);
        if (inBattle) {
            crops[REGIONS.length] = textOnly(screen, PLATE_HEALTH, sx, sy, true);
            countPips(screen, sx, sy, s);
        }
        readNext(s, crops, 0, l);
    }

    private static final int[] PLATE_HEALTH = {1525, 1008, 1690, 1038};

    private static void readNext(Stats s, Bitmap[] crops, int i, Listener l) {
        if (i == crops.length) { l.onStats(s); return; }
        recognizer.process(InputImage.fromBitmap(crops[i], 0))
                .addOnSuccessListener(t -> {
                    if (i == 0) s.health = number(t.getText());
                    else if (i == 1) s.mana = number(t.getText());
                    else s.maxHealth = maxOf(t.getText());
                })
                .addOnCompleteListener(t -> {
                    crops[i].recycle();
                    readNext(s, crops, i + 1, l);
                });
    }

    /** "7,647/7,931" -> 7931; -1 unless it looks right. */
    static int maxOf(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) return -1;
        int cur = number(text.substring(0, slash)), max = number(text.substring(slash + 1));
        return (cur > 0 && max >= cur && max < 100000) ? max : -1;
    }

    /** Digits only, allowing for the usual look-alikes. */
    static int number(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) b.append(c);
            else if (c == 'O' || c == 'o' || c == 'D' || c == 'Q') b.append('0');
            else if (c == 'l' || c == 'I' || c == '|' || c == 'i') b.append('1');
            else if (c == 'S' || c == 's') b.append('5');
            else if (c == 'B') b.append('8');
            else if (c == 'Z' || c == 'z') b.append('2');
            else if (c == 'g') b.append('9');
        }
        if (b.length() == 0 || b.length() > 6) return -1;
        try { return Integer.parseInt(b.toString()); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean yellowText(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r > 170 && g > 150 && b < 150 && Math.abs(r - g) < 70;
    }

    /** Black text on white, three times larger, with a margin: what text recognition likes best. */
    private static boolean redText(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r > 170 && g < 110 && b < 110;
    }

    private static Bitmap textOnly(Bitmap screen, int[] r, float sx, float sy, boolean red) {
        int x0 = Math.round(r[0] * sx), y0 = Math.round(r[1] * sy);
        int w = Math.max(1, Math.round((r[2] - r[0]) * sx)), h = Math.max(1, Math.round((r[3] - r[1]) * sy));
        x0 = Math.max(0, Math.min(screen.getWidth() - w, x0));
        y0 = Math.max(0, Math.min(screen.getHeight() - h, y0));
        int[] px = new int[w * h];
        screen.getPixels(px, 0, w, x0, y0, w, h);
        // Yellow that reaches the edge of the cut-out is background (a globe), not lettering.
        boolean[] yel = new boolean[w * h], outside = new boolean[w * h];
        for (int i = 0; i < px.length; i++) yel[i] = red ? redText(px[i]) : yellowText(px[i]);
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (yel[i] && !outside[i]) { outside[i] = true; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (yel[i] && !outside[i]) { outside[i] = true; queue[tail++] = i; }
            }
        }
        if (red) tail = 0; // the nameplate's lettering has no same-coloured background to remove
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int j : next) {
                if (j >= 0 && yel[j] && !outside[j]) { outside[j] = true; queue[tail++] = j; }
            }
        }
        for (int i = 0; i < px.length; i++) px[i] = (yel[i] && (red || !outside[i])) ? Color.BLACK : Color.WHITE;
        Bitmap bw = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        int scale = 3, m = 12;
        Bitmap out = Bitmap.createBitmap(w * scale + 2 * m, h * scale + 2 * m, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        Bitmap big = Bitmap.createScaledBitmap(bw, w * scale, h * scale, false);
        c.drawBitmap(big, m, m, null);
        bw.recycle();
        big.recycle();
        return out;
    }

    private static void countPips(Bitmap screen, float sx, float sy, Stats s) {
        int normal = 0, power = 0;
        int cy = Math.round(1051 * sy);
        for (int k = 0; k < 9; k++) {
            int cx = Math.round((1535 + 23.6f * k) * sx);
            int w = 0, p = 0;
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    int x = cx + Math.round(dx * sx), y = cy + Math.round(dy * sy);
                    if (x < 0 || y < 0 || x >= screen.getWidth() || y >= screen.getHeight()) continue;
                    int c = screen.getPixel(x, y);
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    if (r > 215 && g > 195 && b > 170) w++;
                    else if ((r > 190 && g > 150 && b < 130) || (r > 150 && g < 90 && b < 90)) p++;
                }
            }
            int area = 17 * 11;
            if (w >= area * 0.32f) normal++;
            else if (p >= area * 0.27f) power++;
            else break;
        }
        s.normalPips = normal;
        s.powerPips = power;
    }

    private static boolean potionPurple(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return b > 150 && r > 90 && g < r && b > g + 50;
    }

    private static int countPotions(Bitmap screen, float sx, float sy) {
        int y = Math.round(1025 * sy), n = 0;
        for (int k = 0; k < 6; k++) {
            int cx = Math.round((240 + 45 * k) * sx);
            int hits = 0;
            for (int dx = -6; dx <= 6; dx += 3) {
                for (int dy = -6; dy <= 6; dy += 3) {
                    int x = cx + Math.round(dx * sx), yy = y + Math.round(dy * sy);
                    if (x >= 0 && x < screen.getWidth() && yy >= 0 && yy < screen.getHeight()
                            && potionPurple(screen.getPixel(x, yy))) hits++;
                }
            }
            if (hits >= 12) n++; else break;
        }
        return n;
    }
}
