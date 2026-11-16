package io.github.stophoginit.w101panel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.Display;

import java.nio.ByteBuffer;

/**
 * Takes an occasional screenshot of the top screen (where the game is) with
 * Android's MediaProjection, the same mechanism screen recorders use. The
 * virtual display only gets a surface for the moment a frame is needed, so it
 * costs almost nothing between snapshots.
 */
final class Capture {
    interface Listener { void onFrame(Bitmap screen); }

    private final HandlerThread thread = new HandlerThread("w101-capture");
    private final Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Listener pending;
    private int framesSinceAttach;

    Capture(Context ctx, int resultCode, Intent data) {
        thread.start();
        handler = new Handler(thread.getLooper());
        MediaProjectionManager mpm = ctx.getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { release(); }
        }, handler);

        // Size the capture like the game's screen: the largest display (the Thor's top screen).
        // DEFAULT_DISPLAY alone isn't reliable here: depending on how the panel was started it can
        // report the bottom screen's 1240x1080, which letterboxes the game into the frame.
        DisplayMetrics m = new DisplayMetrics();
        ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);
        for (Display d : ctx.getSystemService(DisplayManager.class).getDisplays()) {
            DisplayMetrics dm = new DisplayMetrics();
            d.getRealMetrics(dm);
            if ((long) dm.widthPixels * dm.heightPixels > (long) m.widthPixels * m.heightPixels) m = dm;
        }
        int w = Math.max(m.widthPixels, m.heightPixels);   // the Thor's top screen is used in landscape
        int h = Math.min(m.widthPixels, m.heightPixels);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            // The first frame after the surface is attached can be half drawn; use the next one.
            if (++framesSinceAttach < 2) { img.close(); return; }
            Listener l = pending;
            pending = null;
            if (display != null) display.setSurface(null); // pause until the next snapshot
            Bitmap bmp = null;
            if (l != null) bmp = toBitmap(img);
            img.close();
            if (l != null && bmp != null) l.onFrame(bmp);
        }, handler);
        display = projection.createVirtualDisplay("w101-panel", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler);
    }

    boolean alive() { return projection != null; }

    /** Asks for one frame; the listener runs on the capture thread. */
    void snapshot(Listener l) {
        handler.post(() -> {
            if (display == null || reader == null) return;
            pending = l;
            framesSinceAttach = 0;
            display.setSurface(reader.getSurface());
        });
    }

    void release() {
        handler.post(() -> {
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
        });
        thread.quitSafely();
    }

    private static Bitmap toBitmap(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int pixelStride = p.getPixelStride(), rowStride = p.getRowStride();
        int w = img.getWidth(), h = img.getHeight();
        int padded = rowStride / pixelStride;
        Bitmap b = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(buf);
        // The game is 16:9. If the capture surface has another shape, the mirror is letterboxed
        // (black bars above and below, or at the sides); keep only the picture so every reader's
        // 1920x1080 positions line up.
        int cx = 0, cy = 0, cw = w, ch = h;
        if (w * 9 < h * 16 - 16) { ch = Math.round(w * 9f / 16f); cy = (h - ch) / 2; }
        else if (w * 9 > h * 16 + 16) { cw = Math.round(h * 16f / 9f); cx = (w - cw) / 2; }
        if (padded == w && cx == 0 && cy == 0 && cw == w && ch == h) return b;
        Bitmap c = Bitmap.createBitmap(b, cx, cy, cw, ch);
        b.recycle();
        return c;
    }
}
