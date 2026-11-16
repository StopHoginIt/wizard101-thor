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
 */
final class Stats {
    interface Listener { void onStats(Stats s); }

    int health = -1, mana = -1, potions = -1;

    private static final float REF_W = 1920f, REF_H = 1080f;
    private static final int[][] REGIONS = { // x0, y0, x1, y1
            {20, 922, 150, 975},   // health
            {118, 972, 205, 1015}, // mana
    };
    private static TextRecognizer recognizer;

    private Stats() {}

    /** Cuts what's needed out of the screenshot (which the caller may then recycle) and reads it. */
    static void read(Bitmap screen, Listener l) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        float sx = screen.getWidth() / REF_W, sy = screen.getHeight() / REF_H;
        Stats s = new Stats();
        s.potions = countPotions(screen, sx, sy);
        Bitmap[] crops = new Bitmap[REGIONS.length];
        for (int i = 0; i < REGIONS.length; i++) crops[i] = textOnly(screen, REGIONS[i], sx, sy);
        readNext(s, crops, 0, l);
    }

    private static void readNext(Stats s, Bitmap[] crops, int i, Listener l) {
        if (i == crops.length) { l.onStats(s); return; }
        recognizer.process(InputImage.fromBitmap(crops[i], 0))
                .addOnSuccessListener(t -> {
                    int v = number(t.getText());
                    if (i == 0) s.health = v; else s.mana = v;
                })
                .addOnCompleteListener(t -> {
                    crops[i].recycle();
                    readNext(s, crops, i + 1, l);
                });
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
    private static Bitmap textOnly(Bitmap screen, int[] r, float sx, float sy) {
        int x0 = Math.round(r[0] * sx), y0 = Math.round(r[1] * sy);
        int w = Math.max(1, Math.round((r[2] - r[0]) * sx)), h = Math.max(1, Math.round((r[3] - r[1]) * sy));
        x0 = Math.max(0, Math.min(screen.getWidth() - w, x0));
        y0 = Math.max(0, Math.min(screen.getHeight() - h, y0));
        int[] px = new int[w * h];
        screen.getPixels(px, 0, w, x0, y0, w, h);
        // Yellow that reaches the edge of the cut-out is background (a globe), not lettering.
        boolean[] yel = new boolean[w * h], outside = new boolean[w * h];
        for (int i = 0; i < px.length; i++) yel[i] = yellowText(px[i]);
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
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int j : next) {
                if (j >= 0 && yel[j] && !outside[j]) { outside[j] = true; queue[tail++] = j; }
            }
        }
        for (int i = 0; i < px.length; i++) px[i] = (yel[i] && !outside[i]) ? Color.BLACK : Color.WHITE;
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
