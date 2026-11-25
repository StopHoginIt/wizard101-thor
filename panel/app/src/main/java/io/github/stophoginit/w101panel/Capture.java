package io.github.stophoginit.w101panel;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

/**
 * Watches the top screen (where the game is) with Android's MediaProjection, the same mechanism
 * screen recorders use.
 *
 * Two speeds, set with {@link #setInterval}:
 *  - slow (400 ms or more, outside battle): the virtual display only gets a surface for the
 *    moment a frame is needed, so it costs almost nothing in between;
 *  - fast (in battle): the surface stays attached and frames are handed over as they arrive,
 *    at most one per interval, read straight from the capture buffer (see {@link Frame}).
 */
final class Capture {
    interface Listener { void onFrame(Frame f); }

    private static final long STREAM_BELOW_MS = 400;

    private final HandlerThread thread = new HandlerThread("w101-capture");
    private final Handler handler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private volatile Listener listener;
    private long interval = 700, lastFrame;
    private boolean attached;
    private int framesSinceAttach;
    private final Runnable attach = this::attach;

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
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> onImage(), handler);
        display = projection.createVirtualDisplay("w101-panel", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler);
    }

    boolean alive() { return projection != null; }

    /** Where frames go; the listener runs on the capture thread. */
    void setListener(Listener l) {
        listener = l;
        handler.post(attach);
    }

    /** Hand over at most one frame per ms; below 400 ms the capture streams. */
    void setInterval(long ms) {
        handler.post(() -> {
            if (ms == interval) return;
            boolean faster = ms < interval;
            interval = ms;
            if (faster) {
                // Don't wait out a long pause: look again now.
                handler.removeCallbacks(attach);
                attach();
            }
        });
    }

    /** Ask for a frame now (after a tap), whatever the interval. */
    void poke() {
        handler.post(() -> {
            lastFrame = 0;
            handler.removeCallbacks(attach);
            attach();
        });
    }

    private void attach() {
        if (display == null || reader == null || attached) return;
        attached = true;
        framesSinceAttach = 0;
        display.setSurface(reader.getSurface());
    }

    private void onImage() {
        if (reader == null) return;
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            // The first frame after the surface is attached can be half drawn; use the next one.
            if (++framesSinceAttach < 2) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < interval - 8) return;
            lastFrame = now;
            Listener l = listener;
            if (l != null) {
                Frame f = new Frame(img, now);
                try { l.onFrame(f); } catch (RuntimeException ignored) {} finally { f.invalidate(); }
            }
            if (interval >= STREAM_BELOW_MS && display != null) {
                // Slow mode: pause until the next frame is due.
                display.setSurface(null);
                attached = false;
                handler.postDelayed(attach, interval);
            }
        } finally {
            img.close();
        }
    }

    void release() {
        handler.post(() -> {
            handler.removeCallbacks(attach);
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
        });
        thread.quitSafely();
    }
}
