package io.github.stophoginit.w101panel;

import android.graphics.Bitmap;

/**
 * Finds the spell cards on a screenshot of the top screen.
 *
 * During spell selection Wizard101 draws one centred row: the deck box
 * ("Cards 4 of 11", dark red) followed by up to 7 cards. Measured on the Thor
 * at 1920x1080 and scaled for other sizes:
 *   deck box left .. first card left 99 px, card 83 x 123 px, pitch 94.5 px,
 *   row centred at x 958, cards from y 477 to 600.
 * The deck box's left edge therefore tells how many cards there are.
 * The PASS / DRAW / FLEE buttons sit at y 663, x 695 / 958 / 1228.
 */
final class HandReader {
    static final float REF_W = 1920f, REF_H = 1080f;
    static final float DECK_TO_CARD = 99f, CARD_W = 83f, CARD_PITCH = 94.5f;
    static final float ROW_CENTRE_X = 958f, CARD_TOP = 477f, CARD_BOTTOM = 600f;
    static final float DECK_PROBE_Y = 530f;
    static final float BUTTON_Y = 663f, PASS_X = 695f, DRAW_X = 958f, FLEE_X = 1228f;
    static final int MAX_HAND = 7;
    // The hint line above the cards. "(mouse) to Select, (mouse) to Discard." starts at x 802;
    // while choosing a target it reads "(mouse) highlighted players to select target." from x 748.
    static final float HINT_Y = 427f, HINT_TARGET_MAX_LEFT = 775f;

    static final class Hand {
        int n;              // 0 = not choosing spells
        float firstX;       // centre of card 0, reference px
        int left = -1;      // deck box left edge in screen px (to tell a settled layout from one still moving)
        boolean targeting;  // a card is chosen and the game wants a target
        Bitmap[] cards = new Bitmap[0];

        /** Card i's centre as fractions of the screen. */
        /** The hand the game shows after card i is discarded: the rest close up and re-centre. */
        Hand without(int i) {
            Hand o = new Hand();
            o.n = n - 1;
            o.firstX = firstX + CARD_PITCH / 2f;
            o.cards = new Bitmap[o.n];
            for (int k = 0, j = 0; k < n; k++) if (k != i) o.cards[j++] = cards[k];
            return o;
        }

        float[] cardPoint(int i) {
            return new float[] {(firstX + i * CARD_PITCH) / REF_W, ((CARD_TOP + CARD_BOTTOM) / 2f) / REF_H};
        }
    }

    private HandReader() {}

    private static boolean deckRed(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r > 70 && r > g * 1.8f && r > b * 1.8f;
    }

    /**
     * True when the spellbook button (bottom-right of the world HUD) is on screen. The game hides it
     * for the whole of a battle, so seeing it means the fight is over. Measured at 1920x1080: a maroon
     * cover over x 1790..1850, y 950..1040 with a gold swirl around (1821, 996). World screenshots
     * score about 52% maroon and 139 gold pixels; battle screenshots score 0 and 0.
     */
    static boolean spellbookVisible(Bitmap screen) {
        float sx = screen.getWidth() / REF_W, sy = screen.getHeight() / REF_H;
        int maroon = 0, total = 0;
        for (int y = 950; y < 1040; y += 3) {
            for (int x = 1790; x < 1850; x += 3) {
                int c = screen.getPixel(Math.min(screen.getWidth() - 1, Math.round(x * sx)), Math.min(screen.getHeight() - 1, Math.round(y * sy)));
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                total++;
                if (r >= 60 && r <= 180 && g < 75 && b < 85 && r > 1.7f * g) maroon++;
            }
        }
        int gold = 0, gtotal = 0;
        for (int y = 980; y < 1012; y += 2) {
            for (int x = 1805; x < 1837; x += 2) {
                int c = screen.getPixel(Math.round(x * sx), Math.round(y * sy));
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                gtotal++;
                if (r > 150 && g > 90 && b < 90 && r > b + 80) gold++;
            }
        }
        lastMaroonPct = Math.round(100f * maroon / total);
        lastGoldPct = Math.round(100f * gold / gtotal);
        return maroon >= total * 0.3f && gold > gtotal / 20; // the book scores about 52% and 12%
    }

    /** The last spellbook scores, in percent (for the panel's log). */
    static volatile int lastMaroonPct, lastGoldPct;

    private static boolean targeting(Bitmap screen, float sx, float sy) {
        int y = Math.round(HINT_Y * sy), first = -1, count = 0;
        for (int x = Math.round(700 * sx); x < Math.round(1300 * sx); x++) {
            int c = screen.getPixel(x, y);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (r > 200 && g > 200 && b > 200) {
                if (first < 0) first = x;
                count++;
            }
        }
        return first >= 0 && first / sx < HINT_TARGET_MAX_LEFT && count / sx > 150;
    }

    static Hand read(Bitmap screen) {
        Hand h = new Hand();
        int W = screen.getWidth(), H = screen.getHeight();
        if (W < 100 || H < 100) return h;
        float sx = W / REF_W, sy = H / REF_H;
        int py = Math.round(DECK_PROBE_Y * sy);
        int need = Math.max(4, Math.round(12 * sx));
        int left = -1, run = 0;
        for (int x = Math.round(0.20f * W); x < Math.round(0.45f * W); x++) {
            if (deckRed(screen.getPixel(x, py))) {
                if (++run >= need) { left = x - run + 1; break; }
            } else {
                run = 0;
            }
        }
        if (left < 0) return h;
        // The deck box is tall: check the colour also holds a little above and below.
        if (!deckRed(screen.getPixel(left + need, Math.round((DECK_PROBE_Y - 30) * sy)))
                && !deckRed(screen.getPixel(left + need, Math.round((DECK_PROBE_Y + 30) * sy)))) {
            return h;
        }
        float leftRef = left / sx;
        float width = 2 * (ROW_CENTRE_X - leftRef);
        int n = Math.round((width - DECK_TO_CARD - CARD_W) / CARD_PITCH) + 1;
        if (n < 1 || n > MAX_HAND) return h;
        h.n = n;
        h.left = left;
        h.targeting = targeting(screen, sx, sy);
        h.firstX = leftRef + DECK_TO_CARD + CARD_W / 2f;
        h.cards = new Bitmap[n];
        int cw = Math.round(CARD_W * sx);
        int top = Math.round(CARD_TOP * sy);
        int ch = Math.round((CARD_BOTTOM - CARD_TOP) * sy);
        for (int i = 0; i < n; i++) {
            int x0 = Math.round((h.firstX + i * CARD_PITCH - CARD_W / 2f) * sx);
            x0 = Math.max(0, Math.min(W - cw, x0));
            h.cards[i] = Bitmap.createBitmap(screen, x0, Math.max(0, Math.min(H - ch, top)), cw, ch);
        }
        return h;
    }
}
