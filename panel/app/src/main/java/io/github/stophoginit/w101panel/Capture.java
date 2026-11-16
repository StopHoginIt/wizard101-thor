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

    Capture(Context ctx, int resultCode, Intent data) {
        thread.start();
        handler = new Handler(thread.getLooper());
        MediaProjectionManager mpm = ctx.getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { release(); }
        }, handler);

        Display main = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        main.getRealMetrics(m);
        int w = Math.max(m.widthPixels, m.heightPixels);   // the Thor's top screen is used in landscape
        int h = Math.min(m.widthPixels, m.heightPixels);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
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
        if (padded == w) return b;
        Bitmap c = Bitmap.createBitmap(b, 0, 0, w, h);
        b.recycle();
        return c;
    }
}
