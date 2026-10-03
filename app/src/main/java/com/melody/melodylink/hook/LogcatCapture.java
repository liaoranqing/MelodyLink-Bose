package com.melody.melodylink.hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a bounded, tag-filtered logcat capture on disk for the whole session.
 *
 * <p>This exists because ordinary logcat is unusable as evidence on this device. The
 * {@code logcat} ring buffer turns over in seconds under ColorOS load, so a bug the user
 * noticed minutes ago is already gone — which is exactly how 0.5.x shipped three
 * "fixes" built on guesses instead of evidence.
 *
 * <p>The approach follows Andrea-lyz/MelodyCodecTweaker's {@code RootBluetoothLogCapture}:
 * a detached background {@code logcat} writing straight to a file with {@code -f}, bounded by
 * {@code -r}/{@code -n} rotation, a hard {@code timeout} so it cannot outlive the session, and
 * a pid file for clean shutdown. Requires root (this device has it); degrades to a no-op with
 * a logged reason otherwise, because a failed capture must never break the module.
 *
 * <p>It deliberately does not clear or resize the global logcat buffers — other diagnostics
 * depend on them.
 *
 * <p>Usage:
 * <pre>
 *   LogcatCapture.start(context);     // once, after the host app is ready
 *   adb shell su -c "cat /data/local/tmp/melodylink-bose/logcat.log*"  &gt; capture.txt
 *   LogcatCapture.stop(context);
 * </pre>
 */
final class LogcatCapture {

    private static final String ROOT_DIR = "/data/local/tmp/melodylink-bose";
    private static final String LOG_PATH = ROOT_DIR + "/logcat.log";
    private static final String PID_PATH = ROOT_DIR + "/logcat.pid";
    private static final int ROTATE_SIZE_KB = 2048;
    private static final int ROTATE_COUNT = 3;
    private static final int TIMEOUT_SECONDS = 60 * 60;

    /**
     * Tags worth keeping. The module's own events plus the host's LSPosed traffic; everything
     * else is dropped on the floor so the rotation window lasts.
     */
    private static final String FILTERS =
            "MelodyLinkBose:V LSPosedFramework:V Melody:V AndroidRuntime:E "
                    + "ActivityManager:I BluetoothManager:V *:S";

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private LogcatCapture() {
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Starts the background capture. Safe to call repeatedly: a second call while running is a
     * no-op. Never throws.
     */
    static void start(Object context) {
        if (context == null || !RUNNING.compareAndSet(false, true)) return;
        String pid = runShell("mkdir -p " + ROOT_DIR + " && chmod 700 " + ROOT_DIR
                + " && echo $$");
        if (pid == null || pid.trim().isEmpty()) {
            RUNNING.set(false);
            MLog.event("logcat.capture.unavailable", "reason", "no_root");
            return;
        }
        String command = "LOG=" + LOG_PATH + "; PID=" + PID_PATH + "; "
                + "rm -f \"$LOG\" \"$LOG.1\" \"$LOG.2\" \"$LOG.3\" \"$PID\"; "
                + "( exec toybox nohup toybox timeout " + TIMEOUT_SECONDS
                + " logcat -b all -v threadtime -f \"$LOG\" "
                + "-r " + ROTATE_SIZE_KB + " -n " + ROTATE_COUNT + " " + FILTERS
                + " ) </dev/null >/dev/null 2>&1 & echo $! > \"$PID\"; "
                + "sleep 1; if [ -r \"$PID\" ]; then echo capture_started; "
                + "else echo capture_failed; fi";
        String out = runShell(command);
        boolean started = out != null && out.contains("capture_started");
        RUNNING.set(started);
        MLog.event("logcat.capture.start", "status", started ? "started" : "failed",
                "detail", MLog.compactThrowable(new Throwable(String.valueOf(out))));
    }

    /** Stops the capture. The log files are left on disk for later retrieval. */
    static void stop(Object context) {
        if (!RUNNING.compareAndSet(true, false)) return;
        runShell("PID=" + PID_PATH + "; if [ -r \"$PID\" ]; then "
                + "P=$(cat \"$PID\"); kill -TERM \"$P\" 2>/dev/null; sleep 1; "
                + "kill -KILL \"$P\" 2>/dev/null; rm -f \"$PID\"; fi; echo capture_stopped");
        MLog.event("logcat.capture.stop");
    }

    /**
     * Reads the captured log back. Used by the in-app diagnostics path and by tests; the user
     * normally pulls the file over adb instead.
     */
    static String read(int maxChars) {
        File base = new File(LOG_PATH);
        File[] files = {new File(LOG_PATH + ".3"), new File(LOG_PATH + ".2"),
                new File(LOG_PATH + ".1"), base};
        StringBuilder out = new StringBuilder();
        for (File f : files) {
            if (!f.isFile()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null && out.length() < maxChars) {
                    out.append(line).append('\n');
                }
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    /** Where the capture lives, so the UI can tell the user how to pull it. */
    static String path() {
        return LOG_PATH;
    }

    /** {@code su -c <command>}, returning stdout or null. Never throws. */
    private static String runShell(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
            // The backgrounded logcat keeps the pipe open; do not block on it.
            process.waitFor();
            return String.join("\n", lines);
        } catch (Throwable t) {
            MLog.event("logcat.capture.error", "error", MLog.compactThrowable(t));
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
