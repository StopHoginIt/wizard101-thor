package io.github.stophoginit.w101panel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to W101Quick (the Wizard101 launcher running inside GameNative/Wine)
 * over UDP on the device's loopback address. W101Quick turns each message into
 * key presses for the game.
 */
final class Bridge {
    static final int PORT = 47101;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    interface PingResult { void onResult(String launcherVersion); }

    private Bridge() {}

    static void key(String action) { send("w101 key " + action); }

    static void send(String msg) {
        IO.execute(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(b, b.length, InetAddress.getLoopbackAddress(), PORT));
            } catch (Exception ignored) {
            }
        });
    }

    /** Calls back with the launcher version, or null if it didn't answer. Runs off the main thread. */
    static void ping(PingResult cb) {
        IO.execute(() -> {
            String version = null;
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(800);
                byte[] b = "w101 ping".getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(b, b.length, InetAddress.getLoopbackAddress(), PORT));
                byte[] buf = new byte[64];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                if (reply.startsWith("w101 pong ")) version = reply.substring(10).trim();
            } catch (Exception ignored) {
            }
            cb.onResult(version);
        });
    }
}
