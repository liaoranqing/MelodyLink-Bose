package com.melody.melodylink.hook;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a tag-filtered logcat capture on disk for the whole session.
 *
 * <p>This exists because ordinary logcat is unusable as evidence on this device. The ring
 * buffer turns over in seconds under ColorOS load, so a bug the user noticed minutes ago is
 * already gone — which is exactly how 0.4.x / 0.5.x shipped several "fixes" built on guesses
 * instead of evidence. Verified on this handset: a plain {@code adb logcat -d} returns nothing
 * useful, while this capture keeps every line.
 *
 * <p>Two device-specific findings, both established by testing on the handset rather than by
 * copying the reference project:
 *
 * <ul>
 *   <li><b>{@code logcat -f} does not work here.</b> It creates the file but never writes to
 *       it (0 bytes). The module therefore redirects stdout to the file instead.</li>
 *   <li><b>The redirect must run under {@code su 0 sh -c}.</b> With a plain {@code su -c} the
 *       shell performing the redirection is the unprivileged caller, so it fails with
 *       {@code Permission denied} even though the logcat process itself would be root.</li>
 * </ul>
 *
 * <p>Rotation is done in the module rather than by logcat (since {@code -r}/{@code -n} only
 * apply to {@code -f}). The capture is bounded and self-terminating; a failure to start is
 * logged and otherwise ignored, because diagnostics must never break the module.
 *
 * <p>Retrieve with:
 * <pre>
 *   adb shell su -c "cat /data/local/tmp/melodylink-bose/capture.log"
 * </pre>
 */
final class LogcatCapture {

    private static final String DIR = "/data/local/tmp/melodylink-bose";
    private static final String LOG = DIR + "/capture.log";
    private static final String PID = DIR + "/capture.pid";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_SECONDS = 60 * 60;

    private static final String FILTERS =
            "MelodyLinkBose:V LSPosedFramework:V AndroidRuntime:E '*:S'";

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private LogcatCapture() {
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static String path() {
        return LOG;
    }

    /**
     * Starts the background capture. Safe to call repeatedly. Never throws.
     *
     * <p>Verified on this handset: the whole thing must be one {@code su 0 sh -c} string
     * including the trailing {@code &}. Splitting it — {@code su -c "..."} with a nested
     * {@code sh -c '...' &} — makes the redirect run as the unprivileged caller and it fails
     * with {@code Permission denied}.
     */
    static void start(Object context) {
        if (context == null || !RUNNING.compareAndSet(false, true)) return;
        String command =
                "mkdir -p " + DIR + "; chmod 777 " + DIR + "; rm -f " + LOG + " " + PID + "; "
                        + "nohup timeout " + TIMEOUT_SECONDS + " logcat -b all -v threadtime "
                        + FILTERS + " > " + LOG + " 2>&1 & echo $! > " + PID + "; echo launched";
        String out = runShell(command);
        boolean started = out != null && out.contains("launched") && new File(LOG).exists();
        if (started) armSizeCap();
        RUNNING.set(started);
        MLog.event("logcat.capture.start",
                "status", started ? "started" : "failed",
                "path", LOG);
    }

    /**
     * Trims the capture once it grows past {@link #MAX_BYTES}. Without this a long session
     * would fill /data. Runs once, shortly after start, because the growth rate is predictable.
     */
    private static void armSizeCap() {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(30_000L);
                File file = new File(LOG);
                if (file.length() <= MAX_BYTES) return;
                List<String> lines = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = r.readLine()) != null) lines.add(line);
                }
                int from = Math.max(0, lines.size() - MAX_BYTES / 200);
                StringBuilder trimmed = new StringBuilder();
                for (int i = from; i < lines.size(); i++) trimmed.append(lines.get(i)).append('\n');
                java.io.FileOutputStream out = new java.io.FileOutputStream(file, false);
                try {
                    out.write(trimmed.toString().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                MLog.event("logcat.capture.trimmed", "kept_lines", lines.size() - from);
            } catch (Throwable t) {
                MLog.event("logcat.capture.trim_error", "error", MLog.compactThrowable(t));
            }
        }, "melodylink-logcat-trim");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the capture. The log file is left on disk for retrieval. */
    static void stop(Object context) {
        if (!RUNNING.compareAndSet(true, false)) return;
        runShell("if [ -r " + PID + " ]; then kill -TERM $(cat " + PID + ") 2>/dev/null; "
                + "rm -f " + PID + "; fi; echo capture_stopped");
        MLog.event("logcat.capture.stop");
    }

    /** Reads the capture back, newest last. For in-module diagnostics and tests. */
    static String read(int maxChars) {
        File file = new File(LOG);
        if (!file.isFile()) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null && out.length() < maxChars) {
                out.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    /** {@code su -c <command>}, returning stdout or null. Never throws. */
    private static String runShell(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "0", "sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
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
