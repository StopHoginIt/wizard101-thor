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

    static final class Hand {
        int n;              // 0 = not choosing spells
        float firstX;       // centre of card 0, reference px
        int left = -1;      // deck box left edge in screen px (to tell a settled layout from one still moving)
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
