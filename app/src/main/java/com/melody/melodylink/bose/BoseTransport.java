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
    /** [31.10] byte 2 cache: 0=off, 1=room, 2=head (-1 = never read). */
    private volatile int spatialType = -1;
    /** [31.10] byte 0 cache: Bose CNC level 0..10 (-1 = never read). */
    private volatile int cncLevel = -1;

    /** [31.10] byte 3 cache: wind block 0=off, 1=on (-1 = never read). */
    private volatile int windBlock = -1;

    /** Cached [1.7] EQ values for bass/mid/treble, in dB-like steps (-10..10). */
    private final byte[] eqBands = new byte[]{0, 0, 0};

    /** Cached [1.9] button actions indexed by buttonIndex(buttonId, event). */
    private final int[] buttonActions = new int[32 * 8];

    /** [31.2] capability bitmap; -1 until read. */
    private volatile int capabilityFlags = -1;

    /** [1.4] auto-off timer in minutes; -1 until read. */
    private volatile int standbyMinutes = -1;

    /** Cached [31.6] custom mode slots 5-10. */
    private final int[] modeSlotCnc = new int[6];
    private final int[] modeSlotSpatial = new int[6];
    private final int[] modeSlotWind = new int[6];
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

    /**
     * Bind the transport to a device without opening a session. Tile/UI paths
     * (volume panel, CNC slider) resolve the device on the main process but no
     * connect flow has run there, so without this every openSocket() failed with
     * "no Bose device selected" and the write was silently dropped.
     */
    @SuppressLint("MissingPermission")
    public void setDevice(BluetoothDevice target) {
        if (target != null) device = target;
    }

    /** Cached [31.10] spatial byte; -1 until the first session read it. */
    public int getSpatialType() {
        return spatialType;
    }

    /** Cached [31.10] CNC level 0..10; -1 until the first session read it. */
    /** Cached wind-block state; -1 until the first session read it. */
    public int getWindBlock() {
        return windBlock;
    }

    /** Optimistic seed so the next query already reports the new value. */
    public void cacheWindBlock(int value) {
        windBlock = value;
    }

    public int getCncLevel() {
        return cncLevel;
    }

    /** Optimistically seed the CNC cache before a slider-triggered write. */
    public void cacheCncLevel(int value) {
        cncLevel = value;
    }

    /**
     * Optimistically seed the spatial cache right before a tile-triggered write,
     * so the notifyChange-driven re-query already reports the new value instead
     * of the stale one (the BMAP SETGET confirmation re-syncs the real state).
     */
    public void cacheSpatialType(int value) {
        spatialType = value;
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
        if (myGen != generation.get()) return; // superseded before the socket handshake
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
            if (settings != null && settings.payload.length > BoseDeviceConfig.SETTING_SPATIAL) {
                spatialType = settings.payload[BoseDeviceConfig.SETTING_SPATIAL] & 0xff;
            if (settings.payload.length > BoseDeviceConfig.SETTING_WIND) {
                windBlock = settings.payload[BoseDeviceConfig.SETTING_WIND] & 0xff;
            }
                cncLevel = settings.payload[BoseDeviceConfig.SETTING_CNC] & 0xff;
            }
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
        // Coalesce rather than cancel: rapid tile taps each carry a distinct
        // target SystemUI computed from its own cycle, so dropping every but the
        // last (the old per-call generation bump) made intermediate modes vanish.
        // Store the latest target and let the single worker drain it; a queued
        // session for an older target is superseded by the newest at its start,
        // but a target never yet started is always executed.
        active = true;
        linkDead = false;
        synchronized (ancLock) {
            ancModeValue = boseMode;
            ancByteValue = anc;
            ancDomainMode = mode;
            ancDirty = true;
            if (ancWorkerRunning) return;
            ancWorkerRunning = true;
        }
        post(new Runnable() {
            @Override public void run() { ancWorker(); }
        });
    }

    private final Object ancLock = new Object();
    private int ancModeValue;
    private Integer ancByteValue;
    private AncMode ancDomainMode;
    private boolean ancDirty;
    private boolean ancWorkerRunning;
    private int lastAncModeValue = -1;
    private Integer lastAncByteValue;
    /** Gap after a session close so Bose can release RFCOMM channel 2. */
    private static final long SESSION_SETTLE_MS = 600L;

    private void ancWorker() {
        while (true) {
            int modeValue;
            Integer ancValue;
            AncMode domainMode;
            int myGen;
            synchronized (ancLock) {
                if (!ancDirty) { ancWorkerRunning = false; return; }
                ancDirty = false;
                modeValue = ancModeValue;
                ancValue = ancByteValue;
                domainMode = ancDomainMode;
                // Same target as the last completed write: nothing to do.
                if (modeValue == lastAncModeValue && java.util.Objects.equals(ancValue, lastAncByteValue)) {
                    ancWorkerRunning = false;
                    return;
                }
                myGen = generation.incrementAndGet();
            }
            runAncWrite(myGen, modeValue, ancValue, domainMode);
            // Bose needs a beat to release RFCOMM channel 2 after a session
            // closes; opening the next one immediately fails with
            // "read failed, socket might closed" (0.4.1 alternating-failure log).
            sleepQuietly(SESSION_SETTLE_MS);
        }
    }

    private void runAncWrite(int myGen, int modeValue, Integer ancValue, AncMode domainMode) {
        // Superseded by a newer click while queued: skip the whole 2-3s RFCOMM
        // handshake instead of only noticing after it (rapid-tap responsiveness).
        if (myGen != generation.get()) return;
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
            synchronized (ancLock) {
                lastAncModeValue = modeValue;
                lastAncByteValue = ancValue;
            }
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
        boolean ok = confirmed != null && confirmed.payload.length >= 5
                && (confirmed.payload[index] & 0xff) == value;
        if (ok && index == BoseDeviceConfig.SETTING_SPATIAL) spatialType = value;
        if (ok && index == BoseDeviceConfig.SETTING_CNC) cncLevel = value;
        if (ok && index == BoseDeviceConfig.SETTING_WIND) windBlock = value;
        return ok;
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

    /** Write one [31.10] settings byte (e.g. SETTING_SPATIAL) via a short session. */
    public void writeSetting(int index, int value) {
        writeSetting(index, value, null);
    }

    /**
     * Settings writes run on the worker thread, so the outcome is only known
     * later. Callers that must report success (e.g. the injected wind-block
     * switch) pass a callback; it is invoked on the main thread with the
     * SETGET-confirmed result.
     */
    public void writeSetting(int index, int value, final SettingResult callback) {
        int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        post(new Runnable() {
            @Override public void run() { runSettingWrite(myGen, index, value, callback); }
        });
    }

    /** Result callback for asynchronous settings writes. */
    public interface SettingResult {
        void onResult(boolean success, int index, int value);
    }

    private void runSettingWrite(int myGen, int index, int value) {
        runSettingWrite(myGen, index, value, null);
    }

    private void runSettingWrite(int myGen, int index, int value, final SettingResult callback) {
        if (myGen != generation.get()) return; // superseded before the socket handshake
        BluetoothSocket opened = null;
        try {
            opened = openSocket();
        } catch (Throwable error) {
            final String reason = "settings rfcomm open failed: " + error;
            listenerOnUi(new Runnable() {
                @Override public void run() { listener.onLog(reason); }
            });
            notifySettingResult(callback, false, index, value);
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
            final boolean ok = writeSettingsLocked(myGen, index, value);
            final String result = "Bose setting[" + index + "]=" + value
                    + (ok ? " written" : " write failed");
            listenerOnUi(new Runnable() {
                @Override public void run() { listener.onLog(result); }
            });
            notifySettingResult(callback, ok, index, value);
        } finally {
            closeSocket();
        }
    }

    // ------------------------------------------------------- EQ / buttons / mode slots

    /** Callback for a generic settings transaction. */
    public interface BlockResult {
        void onDone(boolean success, byte[] payload);
    }

    /**
     * Runs one read-modify-write transaction on a worker session. Everything that
     * is not a [31.10] byte tweak goes through here: 3-band EQ ([1.7]), button
     * remap ([1.9]) and custom mode slots ([31.6]). Session teardown and the
     * pre-handshake supersede check are shared with the CNC path.
     */
    private void runBlockTransaction(final String label, final BlockWork work) {
        final int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        post(new Runnable() {
            @Override public void run() {
                if (myGen != generation.get()) return;
                BluetoothSocket opened = null;
                try {
                    opened = openSocket();
                } catch (Throwable error) {
                    final String reason = label + " rfcomm open failed: " + error;
                    listenerOnUi(new Runnable() {
                        @Override public void run() { listener.onLog(reason); }
                    });
                    listenerOnUi(new Runnable() {
                        @Override public void run() { work.onDone(false, null); }
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
                startReader(current);
                try {
                    sleepQuietly(POST_WRITE_DELAY_MS + 100L);
                    drainStartup(current);
                    final boolean ok = work.run(current, myGen);
                    listenerOnUi(new Runnable() {
                        @Override public void run() {
                            listener.onLog(label + (ok ? " ok" : " failed"));
                        }
                    });
                    listenerOnUi(new Runnable() {
                        @Override public void run() {
                            work.onDone(ok, work.lastPayload());
                        }
                    });
                } catch (Throwable t) {
                    listenerOnUi(new Runnable() {
                        @Override public void run() { listener.onLog(label + " error: " + t); }
                    });
                    listenerOnUi(new Runnable() {
                        @Override public void run() { work.onDone(false, null); }
                    });
                } finally {
                    closeSocket();
                }
            }
        });
    }

    /** Work unit executed inside an open session. */
    private interface BlockWork {
        boolean run(BluetoothSocket socket, int generation) throws Exception;

        void onDone(boolean success, byte[] payload);

        byte[] lastPayload();
    }

    /** Sends one frame and waits for the matching STATUS/ERROR reply. */
    private byte[] exchange(int myGen, int block, int function, int operator, byte[] payload)
            throws Exception {
        BoseBmap.Frame answer = command(block, function, operator, payload);
        if (answer == null) return null;
        if (answer.operator == BoseBmap.OP_ERROR) return null;
        return answer.payload;
    }

    /** Reads the current 3-band EQ via [1.7]; result stored in eqBands. */
    public void readEq(EqCallback callback) {
        runBlockTransaction("Bose EQ read", new BlockWork() {
            private byte[] payload;

            @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                BoseBmap.Frame frame = command(BoseBmap.BLOCK_DEVICE_SETTINGS, BoseBmap.FUNC_EQ,
                        BoseBmap.OP_GET, null);
                if (frame == null || frame.operator == BoseBmap.OP_ERROR) return false;
                payload = frame.payload;
                parseEqPayload(payload);
                return true;
            }

            @Override public void onDone(boolean success, byte[] ignored) {
                if (callback != null) callback.onEq(success, getEqBand(0), getEqBand(1), getEqBand(2));
            }

            @Override public byte[] lastPayload() {
                return payload;
            }
        });
    }

    /** Writes one EQ band via [1.7] SETGET, then re-reads to confirm. */
    public void writeEqBand(final int bandId, final int value, final EqCallback callback) {
        final int clamped = Math.max(-10, Math.min(10, value));
        runBlockTransaction("Bose EQ band " + bandId + "=" + clamped, new BlockWork() {
            private byte[] payload;

            @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                byte[] answer = exchange(gen, BoseBmap.BLOCK_DEVICE_SETTINGS, BoseBmap.FUNC_EQ,
                        BoseBmap.OP_SETGET, BoseBmap.eqBandPayload(clamped, bandId));
                if (answer == null) return false;
                BoseBmap.Frame confirm = command(BoseBmap.BLOCK_DEVICE_SETTINGS, BoseBmap.FUNC_EQ,
                        BoseBmap.OP_GET, null);
                if (confirm == null || confirm.operator == BoseBmap.OP_ERROR) return false;
                payload = confirm.payload;
                parseEqPayload(payload);
                return eqBands[clampBand(bandId)] == clamped;
            }

            @Override public void onDone(boolean success, byte[] ignored) {
                if (callback != null) callback.onEq(success, getEqBand(0), getEqBand(1), getEqBand(2));
            }

            @Override public byte[] lastPayload() {
                return payload;
            }
        });
    }

    private static int clampBand(int bandId) {
        if (bandId == BoseBmap.EQ_BAND_BASS) return 0;
        if (bandId == BoseBmap.EQ_BAND_TREBLE) return 2;
        return 1;
    }

    /** [1.7] GET returns 4-byte groups: [min, max, current, bandId]. */
    private void parseEqPayload(byte[] payload) {
        if (payload == null) return;
        for (int i = 0; i + 3 < payload.length; i += 4) {
            int bandId = payload[i + 3] & 0xff;
            int index = bandId == BoseBmap.EQ_BAND_BASS ? 0 : (bandId == BoseBmap.EQ_BAND_TREBLE ? 2 : 1);
            if (bandId > BoseBmap.EQ_BAND_TREBLE) continue;
            eqBands[index] = (byte) payload[i + 2];
        }
    }

    public int getEqBand(int index) {
        return eqBands[index < 0 || index > 2 ? 0 : index];
    }

    /** EQ read callback. */
    public interface EqCallback {
        void onEq(boolean success, int bass, int mid, int treble);
    }

    /** Remaps one button event via [1.9] SETGET. */
    public void writeButton(final int buttonId, final int event, final int action,
            final EqCallback callback) {
        runBlockTransaction("Bose button " + buttonId + " ev=" + event + "->" + action,
                new BlockWork() {
                    private byte[] payload;

                    @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                        byte[] answer = exchange(gen, BoseBmap.BLOCK_DEVICE_SETTINGS,
                                BoseBmap.FUNC_BUTTONS, BoseBmap.OP_SETGET,
                                BoseBmap.buttonPayload(buttonId, event, action));
                        if (answer == null) return false;
                        BoseBmap.Frame confirm = command(BoseBmap.BLOCK_DEVICE_SETTINGS,
                                BoseBmap.FUNC_BUTTONS, BoseBmap.OP_GET, null);
                        if (confirm != null && confirm.operator != BoseBmap.OP_ERROR) {
                            payload = confirm.payload;
                            if (payload != null && payload.length >= 3) {
                                buttonActions[buttonIndex(buttonId, event)] = payload[2] & 0xff;
                            }
                        }
                        return true;
                    }

                    @Override public void onDone(boolean success, byte[] ignored) {
                        if (callback != null) {
                            callback.onEq(success, getEqBand(0), getEqBand(1), getEqBand(2));
                        }
                    }

                    @Override public byte[] lastPayload() {
                        return payload;
                    }
                });
    }

    private static int buttonIndex(int buttonId, int event) {
        return (buttonId & 0x1f) * 8 + (event & 0x0f);
    }

    public int getButtonAction(int buttonId, int event) {
        int index = buttonIndex(buttonId, event);
        int value = index < buttonActions.length ? buttonActions[index] : -1;
        return value;
    }

    /**
     * Writes a custom mode slot via [31.6] ModeConfig SETGET. Slots 5-10 are the
     * firmware's empty user slots; 0-4 are locked presets.
     */
    public void writeModeSlot(final int slot, final String name, final int cncLevel,
            final int spatial, final int windBlock, final ModeCallback callback) {
        final int index = Math.max(BoseBmap.MODE_SLOT_FIRST, Math.min(BoseBmap.MODE_SLOT_LAST, slot));
        runBlockTransaction("Bose mode slot " + index, new BlockWork() {
            private byte[] payload;

            @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                byte[] body = BoseBmap.modeConfigPayload(index, name, cncLevel, spatial,
                        windBlock, 1);
                byte[] answer = exchange(gen, BoseBmap.BLOCK_AUDIO_MODES, BoseBmap.FUNC_MODE_CONFIG,
                        BoseBmap.OP_SETGET, body);
                if (answer == null) return false;
                BoseBmap.Frame status = command(BoseBmap.BLOCK_AUDIO_MODES,
                        BoseBmap.FUNC_MODE_CONFIG, BoseBmap.OP_STATUS, null);
                if (status != null && status.operator != BoseBmap.OP_ERROR) {
                    payload = status.payload;
                    parseModeConfigPayload(index, payload);
                }
                return true;
            }

            @Override public void onDone(boolean success, byte[] ignored) {
                if (callback != null) callback.onMode(success, index);
            }

            @Override public byte[] lastPayload() {
                return payload;
            }
        });
    }

    /** [31.6] STATUS is 48 bytes on this firmware; config fields start at 42. */
    private void parseModeConfigPayload(int slot, byte[] payload) {
        if (payload == null || payload.length < 48) return;
        int index = slot - BoseBmap.MODE_SLOT_FIRST;
        if (index < 0 || index >= modeSlotCnc.length) return;
        modeSlotCnc[index] = payload[42] & 0xff;
        modeSlotSpatial[index] = payload[44] & 0xff;
        modeSlotWind[index] = payload[45] & 0xff;
    }

    public int getModeSlotCnc(int slot) {
        int index = slot - BoseBmap.MODE_SLOT_FIRST;
        return index < 0 || index >= modeSlotCnc.length ? -1 : modeSlotCnc[index];
    }

    /** Mode-slot write callback. */
    public interface ModeCallback {
        void onMode(boolean success, int slot);
    }

    /**
     * Reads [31.2] AudioModes capabilities. The feature bitmap is the honest way
     * to learn whether wind block exists on this model before offering the switch:
     * bit 3 (CAP_WIND) is the firmware's own answer. -1 when unknown.
     */
    public void readCapabilities(final CapCallback callback) {
        runBlockTransaction("Bose capabilities", new BlockWork() {
            private byte[] payload;

            @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                BoseBmap.Frame frame = command(BoseBmap.BLOCK_AUDIO_MODES, 2, BoseBmap.OP_GET, null);
                if (frame == null || frame.operator == BoseBmap.OP_ERROR) return false;
                payload = frame.payload;
                if (payload != null && payload.length >= 6) capabilityFlags = payload[5] & 0xff;
                return true;
            }

            @Override public void onDone(boolean success, byte[] ignored) {
                if (callback != null) callback.onCapabilities(success, capabilityFlags);
            }

            @Override public byte[] lastPayload() {
                return payload;
            }
        });
    }

    /** -1 until [31.2] has been read. */
    public int getCapabilityFlags() {
        return capabilityFlags;
    }

    public boolean supportsFeature(int capFlag) {
        return capabilityFlags >= 0 && (capabilityFlags & capFlag) != 0;
    }

    /** Capabilities callback. */
    public interface CapCallback {
        void onCapabilities(boolean success, int flags);
    }

    /** Writes the auto-off timer via [1.4] SETGET (0 = never). */
    public void writeStandbyTimer(final int minutes, final StandbyCallback callback) {
        runBlockTransaction("Bose standby timer " + minutes, new BlockWork() {
            private byte[] payload;

            @Override public boolean run(BluetoothSocket socket, int gen) throws Exception {
                byte[] answer = exchange(gen, BoseBmap.BLOCK_DEVICE_SETTINGS,
                        BoseBmap.FUNC_STANDBY_TIMER, BoseBmap.OP_SETGET,
                        BoseBmap.standbyTimerPayload(minutes));
                if (answer == null) return false;
                BoseBmap.Frame confirm = command(BoseBmap.BLOCK_DEVICE_SETTINGS,
                        BoseBmap.FUNC_STANDBY_TIMER, BoseBmap.OP_GET, null);
                if (confirm != null && confirm.operator != BoseBmap.OP_ERROR) {
                    payload = confirm.payload;
                    if (payload != null && payload.length >= 1) standbyMinutes = payload[0] & 0xff;
                }
                return true;
            }

            @Override public void onDone(boolean success, byte[] ignored) {
                if (callback != null) callback.onStandby(success, standbyMinutes);
            }

            @Override public byte[] lastPayload() {
                return payload;
            }
        });
    }

    public int getStandbyMinutes() {
        return standbyMinutes;
    }

    /** Standby-timer callback. */
    public interface StandbyCallback {
        void onStandby(boolean success, int minutes);
    }

    /**
     * Powers the earbuds off via [7.4] START (0 = off). The link drops as soon as
     * the firmware accepts it, so success means "delivered", not "confirmed".
     */
    public void powerOff(final PowerCallback callback) {
        final int myGen = generation.incrementAndGet();
        active = true;
        linkDead = false;
        post(new Runnable() {
            @Override public void run() {
                if (myGen != generation.get()) return;
                BluetoothSocket opened = null;
                try {
                    opened = openSocket();
                    final BluetoothSocket current = opened;
                    synchronized (ioLock) {
                        socket = current;
                    }
                    startReader(current);
                    sleepQuietly(POST_WRITE_DELAY_MS + 100L);
                    drainStartup(current);
                    command(BoseBmap.BLOCK_CONTROL, BoseBmap.FUNC_POWER, BoseBmap.OP_START,
                            BoseBmap.powerPayload(false));
                    listenerOnUi(new Runnable() {
                        @Override public void run() { listener.onLog("Bose power off sent"); }
                    });
                    listenerOnUi(new Runnable() {
                        @Override public void run() { callback.onPower(true); }
                    });
                } catch (Throwable t) {
                    listenerOnUi(new Runnable() {
                        @Override public void run() {
                            listener.onLog("Bose power off failed: " + t);
                        }
                    });
                    listenerOnUi(new Runnable() {
                        @Override public void run() { callback.onPower(false); }
                    });
                } finally {
                    closeSocket();
                }
            }
        });
    }

    /** Power callback. */
    public interface PowerCallback {
        void onPower(boolean delivered);
    }

    private void notifySettingResult(
            final SettingResult callback, final boolean ok, final int index, final int value) {
        if (callback == null) return;
        listenerOnUi(new Runnable() {
            @Override public void run() { callback.onResult(ok, index, value); }
        });
    }

    private void runBatteryRead(int myGen) {
        if (myGen != generation.get()) return; // superseded before the socket handshake
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
