package io.github.stophoginit.w101panel;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Look and feel modelled on Wizard101's own UI: deep indigo/violet panels with
 * gold trim, and brown battle buttons with gold frames and yellow lettering
 * (like PASS / DRAW / FLEE). Everything is drawn with shapes; no game art is used.
 */
final class Style {
    private Style() {}

    static final int GOLD = 0xFFE3B34A;
    static final int GOLD_LIGHT = 0xFFFFE08A;
    static final int GOLD_DARK = 0xFF7A5414;
    static final int INDIGO = 0xFF241848;
    static final int VIOLET = 0xFF3E2A7A;
    static final int VIOLET_PRESSED = 0xFF6B4FBB;
    static final int BROWN = 0xFF3A2108;
    static final int BROWN_LIGHT = 0xFF6A3F12;
    static final int YELLOW = 0xFFFFE24A;
    static final int PARCHMENT = 0xFFF1DFB0;
    static final int INK = 0xFF2B1A0A;

    static final Typeface TITLE = Typeface.create(Typeface.SERIF, Typeface.BOLD);

    /** Two-tone frame: dark outer edge, gold rim, gradient fill. */
    static Drawable frame(int fillTop, int fillBottom, float radius, int rimPx) {
        GradientDrawable outer = new GradientDrawable();
        outer.setColor(GOLD_DARK);
        outer.setCornerRadius(radius);

        GradientDrawable rim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {GOLD_LIGHT, GOLD, GOLD_DARK});
        rim.setCornerRadius(radius - 1);

        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {fillTop, fillBottom});
        fill.setCornerRadius(Math.max(0, radius - rimPx));

        LayerDrawable ld = new LayerDrawable(new Drawable[] {outer, rim, fill});
        int d = Math.max(1, rimPx / 3);
        ld.setLayerInset(1, d, d, d, d);
        ld.setLayerInset(2, rimPx, rimPx, rimPx, rimPx);
        return ld;
    }

    /** Violet tile with gold rim (menus, shortcuts). */
    static Drawable tile(boolean pressed, float radius, int rim) {
        return pressed ? frame(VIOLET_PRESSED, VIOLET, radius, rim) : frame(VIOLET, INDIGO, radius, rim);
    }

    /** Brown battle button with gold rim. */
    static Drawable battleButton(boolean pressed, boolean enabled, float radius, int rim) {
        if (!enabled) return frame(0xFF2A2018, 0xFF1A140E, radius, rim);
        return pressed ? frame(BROWN_LIGHT, BROWN, radius, rim) : frame(BROWN, 0xFF241404, radius, rim);
    }

    /** The strip's background: translucent indigo with a gold top edge. */
    static Drawable panelBackground(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xE61C1238, 0xE6120B26});
        g.setCornerRadius(radius);
        g.setStroke(3, GOLD);
        return g;
    }

    /** Parchment ribbon for titles and hints. */
    static Drawable parchment(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {PARCHMENT, 0xFFD9BE82});
        g.setCornerRadius(radius);
        g.setStroke(3, GOLD_DARK);
        return g;
    }

    /** Lettering with a dark outline, like the game's button text. */
    static void lettering(TextView t, float px, int color) {
        t.setTypeface(TITLE);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        t.setShadowLayer(Math.max(2f, px / 10f), 0, px / 18f, Color.BLACK);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
    }
}
