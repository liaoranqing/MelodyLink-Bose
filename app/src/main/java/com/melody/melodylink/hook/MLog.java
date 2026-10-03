package com.melody.melodylink.hook;

import android.util.Log;

/**
 * Sink installed by {@link HookModule} so events reach the LSPosed log.
 *
 * <p>Measured on 0.5.9: {@code android.util.Log} output from this module never appears in
 * {@code /data/adb/lspd/log/modules_*.log}, while the inherited {@code XposedModule#log}
 * does. libxposed is a compileOnly dependency supplied by the framework at runtime, so
 * {@code MLog} cannot reach the framework object itself; instead HookModule — which extends
 * {@code XposedModule} — hands over its working logger once at install time.
 */
interface LogSink {
    void write(int level, String message);
}

/**
 * Structured module logging.
 *
 * <p>Two things this adds over a plain {@code Log.i} call, both learned the hard way while
 * debugging 0.5.x on a real device:
 *
 * <ol>
 *   <li><b>Greppable events.</b> {@code event("name", "k", v, ...)} emits
 *       {@code evt=name k=v}. Free-text sentences force full-text searching; a fixed
 *       {@code evt=} prefix means {@code grep 'evt=bose.inject'} reliably returns exactly the
 *       lines that matter. This mirrors the convention used by
 *       Andrea-lyz/MelodyCodecTweaker, whose README documents a keyword table for exactly this
 *       reason.</li>
 *   <li><b>Persistent capture.</b> A plain logcat dump is useless on this device: the
 *       {@code logcat} ring buffer turns over in seconds under ColorOS load, so by the time the
 *       user reports a problem the evidence is gone. {@link LogcatCapture} keeps a bounded,
 *       tag-filtered capture on disk for the whole session instead.</li>
 * </ol>
 */
final class MLog {

    static final String TAG = "MelodyLinkBose";

    private static volatile LogSink SINK;

    private MLog() {
    }

    static void installSink(LogSink sink) {
        SINK = sink;
    }

    /** {@code evt=<name> k=v k=v} — grep with {@code grep 'evt='} on the LSPosed module log. */
    static void event(String name, Object... kvPairs) {
        write(Log.INFO, format(name, kvPairs));
    }

    /** Single-token throwable summary suitable as an event value. */
    static String compactThrowable(Throwable t) {
        if (t == null) return "unknown";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String value = root.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ':' + message);
        value = value.replaceAll("\\s+", "_");
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    static void w(String message) {
        write(Log.WARN, message);
    }

    static void w(String message, Throwable t) {
        write(Log.WARN, message + " | " + t);
    }

    static void e(String message, Throwable t) {
        write(Log.ERROR, message + " | " + t);
    }

    /** Writes to android.util.Log always, and to the LSPosed log when a sink is installed. */
    private static void write(int level, String message) {
        try {
            Log.println(level, TAG, message);
        } catch (Throwable ignored) {
        }
        LogSink sink = SINK;
        if (sink == null) return;
        try {
            sink.write(level, message);
        } catch (Throwable ignored) {
        }
    }

    private static String format(String name, Object... kvPairs) {
        StringBuilder sb = new StringBuilder("evt=").append(name);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            sb.append(' ').append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        return sb.toString();
    }
}
