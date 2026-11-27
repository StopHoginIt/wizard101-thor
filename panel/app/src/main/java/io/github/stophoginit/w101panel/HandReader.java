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

    // What the hint line asks for, told apart by the shape of its text: white pixels counted in
    // 10 px columns from x 740 to 1150 over five rows (y 421..433), in reference pixels.
    // Measured from a recording on the Thor (2026-11-26):
    //   NORMAL  "(mouse) to Select, (mouse) to Discard."
    //   ENCHANT "(mouse) non-grey spell to cast enchantment."
    //   TARGET  "(mouse) highlighted players to select target."
    // Same-prompt frames score about 20-25 against their own template, 200+ against the others.
    static final int PROMPT_UNKNOWN = -1, PROMPT_NORMAL = 0, PROMPT_ENCHANT = 1, PROMPT_TARGET = 2;
    private static final float[][] PROMPTS = {
        {0f, 0f, 0f, 0f, 0f, 0f, 18f, 0f, 0f, 13f, 8f, 8f, 2f, 16f, 15f, 16f, 16f, 6f, 13f, 2f, 0f, 0f, 1f, 15f, 0f, 0f, 0f, 8f, 10f, 8f, 12f, 8f, 9f, 10f, 9f, 16f, 13f, 13f, 9f, 0f, 0f},
        {4f, 14f, 0f, 0f, 17f, 8f, 17f, 8f, 4f, 15f, 10f, 8f, 8f, 9f, 0f, 9f, 15f, 13f, 16f, 2f, 1f, 12f, 11f, 0f, 6f, 12f, 6f, 9f, 2f, 5f, 12f, 11f, 9f, 14f, 16f, 9f, 14f, 11f, 10f, 12f, 10f},
        {3f, 12f, 0f, 0f, 23f, 8f, 11f, 15f, 19f, 12f, 19f, 18f, 14f, 13f, 7f, 16f, 2f, 9f, 20f, 12f, 13f, 16f, 13f, 10f, 3f, 8f, 10f, 11f, 0f, 10f, 14f, 4f, 13f, 7f, 6f, 0f, 10f, 12f, 13f, 14f, 9f},
    };
    private static final int PROMPT_X0 = 740, PROMPT_X1 = 1150, PROMPT_BIN = 10;
    private static final int[] PROMPT_ROWS = {421, 424, 427, 430, 433};

    static final class Hand {
        int n;              // 0 = not choosing spells
        float firstX;       // centre of card 0, reference px
        int left = -1;      // deck box left edge in screen px (to tell a settled layout from one still moving)
        boolean targeting;  // a card is chosen and the game wants a target (prompt == TARGET)
        int prompt = PROMPT_UNKNOWN; // what the hint line asks for
        boolean hover;      // a card is drawn enlarged (pointer on it, or a chosen enchantment)
        boolean unsure;     // the row is mid-animation: ignore this reading
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
    static boolean spellbookVisible(Frame screen) {
        float sx = screen.width() / REF_W, sy = screen.height() / REF_H;
        int maroon = 0, total = 0;
        for (int y = 950; y < 1040; y += 3) {
            for (int x = 1790; x < 1850; x += 3) {
                int c = screen.argb(Math.round(x * sx), Math.round(y * sy));
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                total++;
                if (r >= 60 && r <= 180 && g < 75 && b < 85 && r > 1.7f * g) maroon++;
            }
        }
        int gold = 0, gtotal = 0;
        for (int y = 980; y < 1012; y += 2) {
            for (int x = 1805; x < 1837; x += 2) {
                int c = screen.argb(Math.round(x * sx), Math.round(y * sy));
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

    /** Which prompt the hint line shows, by the shape of its text (nearest template). */
    private static int prompt(Frame screen, float sx, float sy) {
        int bins = (PROMPT_X1 - PROMPT_X0) / PROMPT_BIN;
        float[] v = new float[bins];
        for (int k = 0; k < bins; k++) {
            int x0 = Math.round((PROMPT_X0 + k * PROMPT_BIN) * sx), x1 = Math.round((PROMPT_X0 + (k + 1) * PROMPT_BIN) * sx);
            int cnt = 0;
            for (int yr : PROMPT_ROWS) {
                int y = Math.round(yr * sy);
                for (int x = x0; x < x1; x++) {
                    int c = screen.argb(x, y);
                    if (((c >> 16) & 0xFF) > 200 && ((c >> 8) & 0xFF) > 200 && (c & 0xFF) > 200) cnt++;
                }
            }
            v[k] = cnt / sx;
        }
        int best = PROMPT_UNKNOWN;
        float bestD = Float.MAX_VALUE, second = Float.MAX_VALUE;
        for (int p = 0; p < PROMPTS.length; p++) {
            float d = 0;
            for (int k = 0; k < bins; k++) d += Math.abs(v[k] - PROMPTS[p][k]);
            if (d < bestD) { second = bestD; bestD = d; best = p; } else if (d < second) second = d;
        }
        // Accept only a clear match (own prompt about 20-50, others 200+).
        return bestD < 110 && bestD < 0.6f * second ? best : PROMPT_UNKNOWN;
    }

    /**
     * Reads the hand. Only the deck box and the hint line are probed (a few hundred pixels);
     * the card pictures are copied only when cards is true.
     */
    static Hand read(Frame screen, boolean cards) {
        Hand h = new Hand();
        int W = screen.width(), H = screen.height();
        if (W < 100 || H < 100) return h;
        float sx = W / REF_W, sy = H / REF_H;
        int py = Math.round(DECK_PROBE_Y * sy);
        int need = Math.max(4, Math.round(12 * sx));
        int left = -1, run = 0;
        for (int x = Math.round(0.20f * W); x < Math.round(0.45f * W); x++) {
            if (deckRed(screen.argb(x, py))) {
                if (++run >= need) { left = x - run + 1; break; }
            } else {
                run = 0;
            }
        }
        if (left < 0) return h;
        // The deck box is tall: check the colour also holds a little above and below.
        if (!deckRed(screen.argb(left + need, Math.round((DECK_PROBE_Y - 30) * sy)))
                && !deckRed(screen.argb(left + need, Math.round((DECK_PROBE_Y + 30) * sy)))) {
            return h;
        }
        float leftRef = left / sx;
        float width = 2 * (ROW_CENTRE_X - leftRef);
        // A settled row of n cards measures about n - 0.1 here. A hovered card is drawn enlarged
        // and widens the row by 1.3 to 1.5 cards (measured), so the deck box sits further left.
        float raw = (width - DECK_TO_CARD - CARD_W) / CARD_PITCH + 1;
        float frac = raw - (float) Math.floor(raw);
        int n;
        if (frac >= 0.75f || frac <= 0.05f) {
            n = Math.round(raw);
        } else if (frac >= 0.1f && frac <= 0.55f) {
            n = Math.round(raw - 1.3f);
            h.hover = true;
        } else {
            h.unsure = true; // cards sliding in or out
            return h;
        }
        if (n < 1 || n > MAX_HAND) { h.unsure = raw < MAX_HAND + 2.5f; return h; }
        h.n = n;
        if (h.hover) {
            // Report where the settled row would be, so layouts compare and taps land on cards.
            leftRef = ROW_CENTRE_X - ((n - 1.1f) * CARD_PITCH + DECK_TO_CARD + CARD_W) / 2f;
            left = Math.round(leftRef * sx);
        }
        h.left = left;
        h.prompt = prompt(screen, sx, sy);
        h.targeting = h.prompt == PROMPT_TARGET;
        h.firstX = leftRef + DECK_TO_CARD + CARD_W / 2f;
        h.cards = new Bitmap[n];
        if (!cards || h.hover) return h;
        int cw = Math.round(CARD_W * sx);
        int top = Math.round(CARD_TOP * sy);
        int ch = Math.round((CARD_BOTTOM - CARD_TOP) * sy);
        for (int i = 0; i < n; i++) {
            int x0 = Math.round((h.firstX + i * CARD_PITCH - CARD_W / 2f) * sx);
            x0 = Math.max(0, Math.min(W - cw, x0));
            h.cards[i] = screen.crop(x0, Math.max(0, Math.min(H - ch, top)), cw, ch);
        }
        return h;
    }
}
