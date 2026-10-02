package com.melody.melodylink.bose;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.melody.melodylink.domain.AncMode;
import com.melody.melodylink.domain.BatteryPart;
import com.melody.melodylink.domain.BatteryValue;
import com.melody.melodylink.domain.EarbudsState;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Bose BMAP session over an insecure RFCOMM socket on channel 2.
 *
 * Protocol knowledge is ported from the field-verified Bose Melody Control
 * implementation (v1.4-1.7): short-lived connections, [31.3] mode START with
 * read-back confirmation, [31.10] AudioModesSettingsConfig GET/SETGET for
 * CNC/ANC/spatial, and the 4-byte repeating battery records on [2.2].
 *
 * The public API is non-blocking (mirrors HuaweiTransportAdapter): each call
 * runs on a worker thread and results arrive through {@link Listener} on the
 * main thread, so the Xposed hook context can call it directly.
 */
public final class BoseTransport {

    public interface Listener {
        void onConnecting();
        void onConnected(EarbudsState state);
        void onBatteryState(EarbudsState state);
        void onAncWriteResult(boolean success, EarbudsState state, String reason);
        void onDisconnected();
        void onFailed(String reason);
        void onLog(String message);
    }

    private static final long CONNECT_TIMEOUT_MS = 4_000L;
    private static final long RESPONSE_TIMEOUT_MS = 3_000L;
    private static final long WRITE_TIMEOUT_MS = 2_500L;
    private static final long POST_WRITE_DELAY_MS = 200L;
    private static final long BATTERY_THROTTLE_MS = 30_000L;

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bose-bmap-session");
                thread.setDaemon(true);
                return thread;
            });
    private final Object ioLock = new Object();
    private final Object replyLock = new Object();

    private volatile BluetoothDevice device;
    private volatile boolean linkDead;
    private volatile boolean active;
    /** Bumped without ioLock so UI-thread callers never block behind a long session. */
    private final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile BluetoothSocket socket;
    private long batteryLastQueryAt;

    private BoseBmap.Frame reply;
    private int replyBlock = -1;
    private int replyFunction = -1;

    public BoseTransport(Listener listener) {
        this.listener = listener;
    }

    /** True only while a BMAP session is actually open; the host link uses boseHostConnected. */
    public boolean isConnected() {
        return socket != null && !linkDead;
    }

    // ---------------------------------------------------------------- session

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice target) {
        device = target;
        int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        post(new Runnable() {
            @Override public void run() { openSession(myGen); }
        });
    }

    public void disconnect() {
        post(new Runnable() {
            @Override public void run() {
                closeSocket();
                active = false;
                post(new Runnable() {
                    @Override public void run() { listener.onDisconnected(); }
                });
            }
        });
    }

    /**
     * Open the RFCOMM link, read mode + settings + battery, publish state, and
     * close the link again (Bose keeps the channel single-client; holding it
     * open would starve the Bose Music app and later writes).
     */
    private void openSession(int myGen) {
        listenerOnUi(new Runnable() {
            @Override public void run() { listener.onConnecting(); }
        });
        BluetoothSocket opened = null;
        try {
            opened = openSocket();
        } catch (Throwable error) {
            final String message = "rfcomm open failed: " + error;
            active = false;
            closeQuietly(opened);
            listenerOnUi(new Runnable() {
                @Override public void run() { listener.onFailed(message); }
            });
            return;
        }
        final BluetoothSocket current = opened;
        synchronized (ioLock) {
            if (myGen != generation.get()) {
                closeQuietly(current);
                return;
            }
            socket = current;
        }
        linkDead = false;
        try {
            sleepQuietly(POST_WRITE_DELAY_MS + 100L);
            drainStartup(current);
            startReader(current);
            BoseBmap.Frame mode = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_GET, null);
            BoseBmap.Frame settings = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_AUDIO_SETTINGS, BoseBmap.OP_GET, null);
            BoseBmap.Frame battery = command(BoseBmap.BLOCK_BATTERY,
                    BoseBmap.FUNC_BATTERY, BoseBmap.OP_GET, null);
            if (mode != null || settings != null) {
                final EarbudsState state = buildState(mode, settings);
                batteryLastQueryAt = SystemClock.elapsedRealtime();
                listenerOnUi(new Runnable() {
                    @Override public void run() { listener.onConnected(state); }
                });
                if (battery != null && battery.operator != BoseBmap.OP_ERROR) {
                    final EarbudsState batteryState = buildBatteryState(battery.payload);
                    if (batteryState != null) {
                        listenerOnUi(new Runnable() {
                            @Override public void run() { listener.onBatteryState(batteryState); }
                        });
                    }
                }
            } else {
                final String message = "session handshake produced no replies";
                listenerOnUi(new Runnable() {
                    @Override public void run() { listener.onFailed(message); }
                });
            }
        } finally {
            closeSocket();
        }
    }

    // ------------------------------------------------------------------- ANC

    public void setAncMode(AncMode mode) {
        int boseMode;
        Integer anc;
        switch (mode) {
            case NOISE_CANCELING: boseMode = BoseDeviceConfig.MODE_QUIET; anc = 1; break;
            case TRANSPARENCY:
            case AMBIENT_SOUND:   boseMode = BoseDeviceConfig.MODE_AWARE; anc = null; break;
            case OFF:             boseMode = BoseDeviceConfig.MODE_QUIET; anc = 0; break;
            default:
                listenerOnUi(new Runnable() {
                    @Override public void run() {
                        listener.onAncWriteResult(false, null, "mode not supported by Bose");
                    }
                });
                return;
        }
        int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        final int modeValue = boseMode;
        final Integer ancValue = anc;
        final AncMode domainMode = mode;
        post(new Runnable() {
            @Override public void run() { runAncWrite(myGen, modeValue, ancValue, domainMode); }
        });
    }

    private void runAncWrite(int myGen, int modeValue, Integer ancValue, AncMode domainMode) {
        BluetoothSocket opened = null;
        try {
            opened = openSocket();
        } catch (Throwable error) {
            reportAnc(false, null, "rfcomm open failed: " + error);
            return;
        }
        final BluetoothSocket current = opened;
        synchronized (ioLock) {
            if (myGen != generation.get()) {
                closeQuietly(current);
                return;
            }
            socket = current;
        }
        linkDead = false;
        startReader(current);
        try {
            sleepQuietly(POST_WRITE_DELAY_MS + 100L);
            drainStartup(current);
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_START,
                    new byte[]{(byte) modeValue, 0});
            if (answer == null || answer.operator == BoseBmap.OP_ERROR
                    || (answer.operator != BoseBmap.OP_RESULT && answer.operator != BoseBmap.OP_PROCESSING)) {
                reportAnc(false, null, "mode START rejected");
                return;
            }
            if (ancValue != null) {
                if (!writeSettingsLocked(myGen, BoseDeviceConfig.SETTING_ANC, ancValue)) {
                    reportAnc(false, null, "ANC byte rejected");
                    return;
                }
            }
            final EarbudsState state = new EarbudsState(
                    BoseDeviceConfig.INSTANCE.getCapabilities(), domainMode, new HashMap<>());
            reportAnc(true, state, "ok");
        } finally {
            closeSocket();
        }
    }

    /** [31.10] GET → merge → SETGET → GET confirm, on the open session. */
    private boolean writeSettingsLocked(int myGen, int index, int value) {
        BoseBmap.Frame current = command(BoseBmap.BLOCK_AUDIO_MODES,
                BoseBmap.FUNC_AUDIO_SETTINGS, BoseBmap.OP_GET, null);
        if (current == null || current.payload.length < 5) return false;
        byte[] payload = new byte[5];
        System.arraycopy(current.payload, 0, payload, 0, 5);
        if (index >= 0 && index < payload.length) payload[index] = (byte) value;
        BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES,
                BoseBmap.FUNC_AUDIO_SETTINGS, BoseBmap.OP_SETGET, payload);
        if (answer == null || answer.operator == BoseBmap.OP_ERROR) return false;
        BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_AUDIO_MODES,
                BoseBmap.FUNC_AUDIO_SETTINGS, BoseBmap.OP_GET, null);
        return confirmed != null && confirmed.payload.length >= 5
                && (confirmed.payload[index] & 0xff) == value;
    }

    // --------------------------------------------------------------- battery

    public void refreshBattery() {
        if (!isConnected() && device == null) return;
        long now = SystemClock.elapsedRealtime();
        if (batteryLastQueryAt != 0L && now - batteryLastQueryAt < BATTERY_THROTTLE_MS) return;
        int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        post(new Runnable() {
            @Override public void run() { runBatteryRead(myGen); }
        });
    }

    private void runBatteryRead(int myGen) {
        BluetoothSocket opened = null;
        try {
            opened = openSocket();
        } catch (Throwable error) {
            return;
        }
        final BluetoothSocket current = opened;
        synchronized (ioLock) {
            if (myGen != generation.get()) {
                closeQuietly(current);
                return;
            }
            socket = current;
        }
        linkDead = false;
        startReader(current);
        try {
            sleepQuietly(POST_WRITE_DELAY_MS + 100L);
            drainStartup(current);
            BoseBmap.Frame battery = command(BoseBmap.BLOCK_BATTERY,
                    BoseBmap.FUNC_BATTERY, BoseBmap.OP_GET, null);
            if (battery == null || battery.operator == BoseBmap.OP_ERROR) return;
            batteryLastQueryAt = SystemClock.elapsedRealtime();
            final EarbudsState state = buildBatteryState(battery.payload);
            if (state != null) {
                listenerOnUi(new Runnable() {
                    @Override public void run() { listener.onBatteryState(state); }
                });
            }
        } finally {
            closeSocket();
        }
    }

    // -------------------------------------------------------------- plumbing

    @SuppressLint("MissingPermission")
    private BluetoothSocket openSocket() throws Exception {
        BluetoothDevice target = device;
        if (target == null) throw new IllegalStateException("no Bose device selected");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("bluetooth disabled");
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // Discovery cancellation is an optimization; the connect still works.
        }
        Method factory = BluetoothDevice.class.getMethod("createInsecureRfcommSocket", int.class);
        BluetoothSocket opened = (BluetoothSocket) factory.invoke(target, BoseDeviceConfig.RFCOMM_CHANNEL);
        ConnectTimeout timeout = new ConnectTimeout(opened);
        timeout.start();
        try {
            opened.connect();
        } finally {
            timeout.abort();
        }
        return opened;
    }

    private final class ConnectTimeout extends Thread {
        private final BluetoothSocket socketToClose;
        private volatile boolean finished;

        ConnectTimeout(BluetoothSocket socketToClose) {
            this.socketToClose = socketToClose;
            setDaemon(true);
            setName("bose-connect-timeout");
        }

        @Override public void run() {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!finished) {
                closeQuietly(socketToClose);
            }
        }

        void abort() {
            finished = true;
            interrupt();
        }
    }

    private void startReader(BluetoothSocket current) {
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                BoseBmap.Parser parser = new BoseBmap.Parser();
                byte[] chunk = new byte[1024];
                try {
                    InputStream input = current.getInputStream();
                    while (current == socket) {
                        int count = input.read(chunk);
                        if (count <= 0) break;
                        parser.feed(chunk, 0, count, new BoseBmap.Sink() {
                            @Override public void onFrame(BoseBmap.Frame frame) {
                                deliverFrame(frame);
                            }
                        });
                    }
                } catch (Throwable error) {
                    // Link dropped or socket closed; expected during teardown.
                } finally {
                    linkDead = true;
                    if (current == socket) socket = null;
                    // BluetoothSocket does not free the OS-side RFCOMM channel
                    // until close(); an unclosed reference keeps channel 2
                    // reserved and every later connect() is refused.
                    closeQuietly(current);
                    synchronized (replyLock) {
                        replyLock.notifyAll();
                    }
                }
            }
        }, "bose-bmap-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void deliverFrame(BoseBmap.Frame frame) {
        synchronized (replyLock) {
            if (reply == null && (replyBlock < 0 || frame.matches(replyBlock, replyFunction))) {
                reply = frame;
                replyLock.notifyAll();
            }
        }
    }

    private void drainStartup(BluetoothSocket current) {
        try {
            InputStream input = current.getInputStream();
            int total = 0;
            while (total < 4096) {
                int available = input.available();
                if (available <= 0) break;
                byte[] stale = new byte[Math.min(available, 4096 - total)];
                int count = input.read(stale);
                if (count <= 0) break;
                total += count;
            }
        } catch (Throwable ignored) {
            // Nothing stale to drain.
        }
    }

    /** One BMAP request; returns the first matching reply frame or null. */
    private BoseBmap.Frame command(int block, int function, int operator, byte[] payload) {
        synchronized (ioLock) {
            BluetoothSocket current = socket;
            if (current == null) return null;
            final byte[] out = BoseBmap.packet(block, function, operator, payload);
            synchronized (replyLock) {
                reply = null;
                replyBlock = block;
                replyFunction = function;
            }
            final boolean[] writeOk = {false};
            final BluetoothSocket writeSocket = current;
            Thread writer = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        OutputStream output = writeSocket.getOutputStream();
                        output.write(out);
                        output.flush();
                        writeOk[0] = true;
                    } catch (Throwable error) {
                        linkDead = true;
                    }
                }
            }, "bose-bmap-write");
            writer.setDaemon(true);
            writer.start();
            try {
                writer.join(WRITE_TIMEOUT_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!writeOk[0]) {
                closeSocket();
                return null;
            }
            try {
                Thread.sleep(POST_WRITE_DELAY_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return null;
            }
            long deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS;
            synchronized (replyLock) {
                while (reply == null && !linkDead && SystemClock.elapsedRealtime() < deadline) {
                    try {
                        replyLock.wait(150L);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                return reply;
            }
        }
    }

    private void closeSocket() {
        BluetoothSocket closing;
        synchronized (ioLock) {
            closing = socket;
            socket = null;
        }
        closeQuietly(closing);
    }

    private static void closeQuietly(BluetoothSocket closing) {
        if (closing == null) return;
        try { closing.close(); } catch (Throwable ignored) { }
    }

    private EarbudsState buildState(BoseBmap.Frame mode, BoseBmap.Frame settings) {
        AncMode ancMode = null;
        if (mode != null && mode.payload.length > 0) {
            int value = mode.u8(0);
            boolean ancOn = settings != null && settings.payload.length >= 5
                    && settings.u8(BoseDeviceConfig.SETTING_ANC) != 0;
            if (value == BoseDeviceConfig.MODE_QUIET) {
                ancMode = ancOn ? AncMode.NOISE_CANCELING : AncMode.OFF;
            } else if (value == BoseDeviceConfig.MODE_AWARE) {
                ancMode = AncMode.TRANSPARENCY;
            }
        }
        return new EarbudsState(BoseDeviceConfig.INSTANCE.getCapabilities(), ancMode, new HashMap<>());
    }

    private EarbudsState buildBatteryState(byte[] payload) {
        BatteryValue left = null;
        BatteryValue right = null;
        BatteryValue box = null;
        if (payload != null) {
            for (int offset = 0; offset + 3 < payload.length; offset += 4) {
                int level = payload[offset] & 0xff;
                int component = payload[offset + 3] & 0xff;
                if (level > 100) continue;
                if (component == 1) right = new BatteryValue(level, false);
                else if (component == 2) left = new BatteryValue(level, false);
                else if (component == 3) box = new BatteryValue(level, false);
            }
        }
        if (left == null && right == null && box == null) return null;
        Map<BatteryPart, BatteryValue> values = new HashMap<>();
        if (left != null) values.put(BatteryPart.LEFT, left);
        if (right != null) values.put(BatteryPart.RIGHT, right);
        if (box != null) values.put(BatteryPart.CASE, box);
        return new EarbudsState(BoseDeviceConfig.INSTANCE.getCapabilities(), null, values);
    }

    private void reportAnc(boolean success, EarbudsState state, String reason) {
        final boolean ok = success;
        final EarbudsState finalState = state;
        final String finalReason = reason;
        listenerOnUi(new Runnable() {
            @Override public void run() { listener.onAncWriteResult(ok, finalState, finalReason); }
        });
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void listenerOnUi(Runnable action) {
        mainHandler.post(action);
    }

    /** Session work (socket connect/read/write) runs on a dedicated worker thread. */
    private void post(Runnable action) {
        worker.execute(action);
    }
}
