package io.github.stophoginit.w101panel;

import android.graphics.Bitmap;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * One captured frame of the top screen, read straight from the capture buffer (no full copy).
 * Positions are in the game picture: if the capture surface had another shape than 16:9, the
 * letterbox bars are already left out. A Frame is only valid inside {@link Capture.Listener#onFrame};
 * copy what you need ({@link #crop}, {@link #full}, {@link #scaled}) before returning.
 */
final class Frame {
    private final ByteBuffer buf;
    private final int rowStride, pixelStride, ox, oy, w, h, imageH;
    final long time;
    private boolean valid = true;

    Frame(Image img, long time) {
        Image.Plane p = img.getPlanes()[0];
        buf = p.getBuffer();
        rowStride = p.getRowStride();
        pixelStride = p.getPixelStride();
        int iw = img.getWidth(), ih = img.getHeight();
        imageH = ih;
        int cx = 0, cy = 0, cw = iw, ch = ih;
        if (iw * 9 < ih * 16 - 16) { ch = Math.round(iw * 9f / 16f); cy = (ih - ch) / 2; }
        else if (iw * 9 > ih * 16 + 16) { cw = Math.round(ih * 16f / 9f); cx = (iw - cw) / 2; }
        ox = cx; oy = cy; w = cw; h = ch;
        this.time = time;
    }

    int width() { return w; }
    int height() { return h; }

    void invalidate() { valid = false; }

    /** The pixel at (x, y) of the game picture as 0xAARRGGBB (the capture is RGBA bytes). */
    int argb(int x, int y) {
        if (!valid) throw new IllegalStateException("frame used after its callback");
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int i = (oy + y) * rowStride + (ox + x) * pixelStride;
        int r = buf.get(i) & 0xFF, g = buf.get(i + 1) & 0xFF, b = buf.get(i + 2) & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A copy of a rectangle of the game picture. */
    Bitmap crop(int x0, int y0, int cw, int ch) {
        x0 = Math.max(0, Math.min(w - 1, x0));
        y0 = Math.max(0, Math.min(h - 1, y0));
        cw = Math.max(1, Math.min(w - x0, cw));
        ch = Math.max(1, Math.min(h - y0, ch));
        int[] px = new int[cw * ch];
        byte[] row = new byte[cw * pixelStride];
        for (int y = 0; y < ch; y++) {
            buf.position((oy + y0 + y) * rowStride + (ox + x0) * pixelStride);
            buf.get(row, 0, row.length);
            for (int x = 0, k = 0; x < cw; x++, k += pixelStride) {
                px[y * cw + x] = 0xFF000000 | ((row[k] & 0xFF) << 16) | ((row[k + 1] & 0xFF) << 8) | (row[k + 2] & 0xFF);
            }
        }
        buf.rewind();
        return Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888);
    }

    /** The whole game picture (for the text readers, which want a Bitmap); one native copy. */
    Bitmap full() {
        if (pixelStride != 4) return crop(0, 0, w, h);
        int padded = rowStride / 4;
        int rows = imageH; // the same copy the panel has always used
        Bitmap b = Bitmap.createBitmap(padded, rows, Bitmap.Config.ARGB_8888);
        buf.rewind();
        b.copyPixelsFromBuffer(buf); // RGBA bytes are exactly ARGB_8888's memory layout
        buf.rewind();
        if (ox == 0 && oy == 0 && padded == w && rows == h) return b;
        Bitmap c = Bitmap.createBitmap(b, ox, oy, w, h);
        b.recycle();
        return c;
    }

    /** The whole picture shrunk to width tw (nearest pixel), for the live target view. */
    Bitmap scaled(int tw) {
        tw = Math.max(16, Math.min(w, tw));
        int th = Math.max(9, Math.round(tw * h / (float) w));
        int[] px = new int[tw * th];
        for (int y = 0; y < th; y++) {
            int sy = oy + (int) ((y + 0.5f) * h / th);
            int base = sy * rowStride;
            for (int x = 0; x < tw; x++) {
                int i = base + (ox + (int) ((x + 0.5f) * w / tw)) * pixelStride;
                px[y * tw + x] = 0xFF000000 | ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF);
            }
        }
        return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888);
    }
}
