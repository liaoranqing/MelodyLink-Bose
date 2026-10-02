package com.melody.melodylink.hook;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.melody.melodylink.bose.BoseDeviceConfig;
import com.melody.melodylink.domain.AncMode;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Exposes the Bose BMAP earbuds through Melody's own
 * {@code EarphoneControlProvider}, which is what backs the native ColorOS
 * volume-panel noise-control tile. Ported from the field-verified v1.x module:
 *
 * <pre>
 *   query .../melody_method_active_device        -> name, address
 *   query .../melody_method_noise_reduction      -> name, address, type, supports
 *   call  melody_method_noise_reduction {address,type} -> mode change
 *   notify(base uri, 0x200 mode | 0x500 wear hint)
 * </pre>
 *
 * The provider runs in Melody's main process (no android:process attribute),
 * the same process that owns the BoseTransport session, so state is shared
 * in-memory through the callbacks below.
 */
final class BoseControlProviderBridge {
    private static final String TAG = "MelodyLinkObserver";

    private static final String PROVIDER = "com.oplus.melody.provider.EarphoneControlProvider";
    private static final String BASE_URI = "content://" + PROVIDER;
    private static final String PATH_ACTIVE = "/melody_method_active_device";
    private static final String PATH_NOISE = "/melody_method_noise_reduction";
    private static final String PATH_WEAR = "/melody_method_control_wear";
    private static final String PATH_SPATIAL = "/melody_method_spatial";
    private static final String METHOD_NOISE = "melody_method_noise_reduction";
    private static final String METHOD_WEAR = "melody_method_control_wear";
    private static final String METHOD_SPATIAL = "melody_method_spatial";

    /** ColorOS noise tile mode contract (verified on v1.x). */
    private static final int NOISE_OFF = 1;
    private static final int NOISE_ANC = 5;
    private static final int NOISE_TRANSPARENT = 2;
    private static final String SUPPORTS = "[1,5,2]";

    private static final int FLAG_NOISE = 0x200;
    private static final int FLAG_WEAR = 0x500;

    interface Callbacks {
        /** True when the Bose earbuds should be advertised as the active device. */
        boolean isBoseActive();

        /** Current confirmed UI mode, or null when unknown. */
        AncMode currentMode();

        /** A tile click requests this mode; returns false when it cannot proceed. */
        boolean requestMode(AncMode mode);

        /** Current [31.10] spatial byte (0=off, 1=room, 2=head). */
        int spatialType();

        /** Tile toggle for spatial audio; returns false when unavailable. */
        boolean requestSpatial(int type);

        String deviceName();

        String deviceAddress();
    }

    /** Diagnostic sink (routes into the module's LSPosed file log). */
    interface LogSink {
        void log(int level, String message, Throwable error);
    }

    private static volatile LogSink logSink;

    static void setLogSink(LogSink sink) {
        logSink = sink;
    }

    private static void diag(String message, Throwable error) {
        LogSink sink = logSink;
        if (sink != null) sink.log(error == null ? Log.INFO : Log.WARN, message, error);
    }

    private static volatile boolean installed;
    private static volatile boolean worn;
    private static volatile int lastNotifiedMode = -1;
    private static volatile Context appContext;
    private static volatile Callbacks callbacks;

    private BoseControlProviderBridge() {
    }

    static synchronized void install(XposedModule module, ClassLoader loader,
                                     Context context, Callbacks cb) {
        if (installed) return;
        try {
            appContext = context;
            callbacks = cb;
            Class<?> provider = Class.forName(PROVIDER, false, loader);
            // getDeclaredMethod on purpose: only patch this provider's own query/call.
            // Hooking an inherited ContentProvider method would affect every provider.
            Method query = provider.getDeclaredMethod("query", Uri.class, String[].class,
                    String.class, String[].class, String.class);
            module.hook(query)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Cursor patched = patchQuery((Uri) chain.getArg(0),
                                    (String) chain.getArg(2),
                                    (String[]) chain.getArg(3), (Cursor) result);
                            if (patched != null) return patched;
                        } catch (Throwable ignored) {
                            // Never break a stock provider query over our patching.
                        }
                        return result;
                    });
            Method call = provider.getDeclaredMethod("call", String.class, String.class, Bundle.class);
            module.hook(call)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            if (handleCall((String) chain.getArg(0), (Bundle) chain.getArg(2))) {
                                return new Bundle();
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            registerAclWatcher(context);
            installed = true;
            diag("bose control provider bridge installed", null);
        } catch (Throwable t) {
            diag("bose control provider bridge failed: " + t, t);
        }
    }

    /** Push the current mode to the tile after a confirmed state change. */
    static void refreshTile() {
        Callbacks cb = callbacks;
        Context ctx = appContext;
        if (!installed || ctx == null || cb == null) return;
        int tileMode = toTileMode(cb.currentMode());
        if (tileMode < 0 || tileMode == lastNotifiedMode) return;
        lastNotifiedMode = tileMode;
        notifyChange(ctx, FLAG_NOISE);
    }

    // ------------------------------------------------------------------ logic

    private static Cursor patchQuery(Uri uri, String selection, String[] args, Cursor result) {
        Callbacks cb = callbacks;
        if (cb == null) return null;
        String path = uri == null ? null : uri.getPath();
        if (path == null) return null;
        diag("query path=" + path + " selection=" + selection
                + " args=" + java.util.Arrays.toString(args)
                + " stockRows=" + (result == null ? "null" : result.getCount())
                + (result != null && result.getCount() > 0 ? " stock=" + dumpCursor(result) : ""), null);
        // v1.x rule: the wear hint broadcast must fire for every SystemUI poll
        // while the buds are present — including polls the stock provider answers
        // itself (0.2.7 passthrough regression: the broadcast sat behind the
        // takeover branch and never fired once the Enco X3 mask made stock rows
        // available, so the noise tile stayed hidden).
        if (PATH_ACTIVE.equals(path) || PATH_NOISE.equals(path) || PATH_SPATIAL.equals(path)) {
            if (cb.isBoseActive() || boseBonded(cb)) ensureWearAnnounced();
        }
        // Native rows win: once the Enco X3 mask registers the device in Melody's
        // repository the stock provider answers with real whitelist-backed columns,
        // which SystemUI trusts more than our synthesized row.
        if (result != null && result.getCount() > 0) return null;
        // Wear takeover stays off (SystemUI never queries it — confirmed from
        // the captured panel-open sequence). Spatial answers only when the stock
        // provider has no row, with the exact 4-column shape read from its smali.
        if (PATH_WEAR.equals(path)) return null;
        // SystemUI queries this provider on its own schedule, often while the
        // Melody UI never ran a session — so presence is a bond-state probe
        // (same rule the verified v1.x module used), not the in-app flag.
        if (!cb.isBoseActive() && !boseBonded(cb)) return null;
        String address = cb.deviceAddress();
        if (address == null) return null;
        if (PATH_ACTIVE.equals(path)) {
            ensureWearAnnounced();
            closeCursor(result);
            MatrixCursor cursor = new MatrixCursor(new String[]{"name", "address"});
            cursor.addRow(new Object[]{cb.deviceName(), address});
            return cursor;
        }
        if (PATH_NOISE.equals(path)) {
            if (args != null && args.length > 0 && args[0] != null
                    && !args[0].equalsIgnoreCase(address)) return null;
            ensureWearAnnounced();
            closeCursor(result);
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{"name", "address", "type", "supports"});
            int mode = toTileMode(cb.currentMode());
            cursor.addRow(new Object[]{cb.deviceName(), address,
                    Integer.valueOf(mode < 0 ? NOISE_TRANSPARENT : mode), SUPPORTS});
            return cursor;
        }
        if (PATH_WEAR.equals(path)) {
            // SystemUI hides the noise tile unless wear state != 0. BMAP has no
            // live sensor; the ACL/bond presence that got us here is the best
            // proxy (same rule the verified v1.x module shipped).
            if (args != null && args.length > 0 && args[0] != null
                    && !args[0].equalsIgnoreCase(address)) return null;
            closeCursor(result);
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{"name", "address", "ear_left", "ear_right"});
            cursor.addRow(new Object[]{cb.deviceName(), address,
                    Integer.valueOf(2), Integer.valueOf(2)});
            return cursor;
        }
        if (PATH_SPATIAL.equals(path)) {
            if (args != null && args.length > 0 && args[0] != null
                    && !args[0].equalsIgnoreCase(address)) return null;
            closeCursor(result);
            // Column shape copied from the stock query dispatcher:
            // name/address/type(=spatialType)/supports(=1 int flag).
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{"name", "address", "type", "supports"});
            cursor.addRow(new Object[]{cb.deviceName(), address,
                    Integer.valueOf(cb.spatialType()), Integer.valueOf(1)});
            return cursor;
        }
        return null;
    }

    /**
     * v1.x rule: SystemUI latches the "worn" hint from notifyChange(0x500|1);
     * without it the noise tile stays hidden even when queries answer. Fire it
     * once per install, right after we first answer an active/noise query.
     */
    private static void ensureWearAnnounced() {
        if (worn) return;
        worn = true;
        Context ctx = appContext;
        diag("wear hint broadcast 0x500|1 (noise tile prerequisite)", null);
        if (ctx != null) notifyChange(ctx, FLAG_WEAR | 0x01);
    }

    private static String dumpCursor(Cursor cursor) {
        StringBuilder builder = new StringBuilder();
        try {
            String[] columns = cursor.getColumnNames();
            if (cursor.moveToFirst()) {
                for (String column : columns) {
                    if (builder.length() > 0) builder.append(',');
                    int index = cursor.getColumnIndex(column);
                    builder.append(column).append('=')
                            .append(index >= 0 ? cursor.getString(index) : "?");
                }
            }
            builder.append(" rows=").append(cursor.getCount());
            cursor.moveToPosition(-1);
        } catch (Throwable t) {
            builder.append("dump-failed:").append(t);
        }
        return builder.toString();
    }

    private static boolean boseBonded(Callbacks cb) {
        try {
            Context ctx = appContext;
            if (ctx == null) return false;
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            for (String mac : BoseDeviceConfig.INSTANCE.getKNOWN_MACS()) {
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean handleCall(String method, Bundle extras) {
        Callbacks cb = callbacks;
        if (cb == null || extras == null || method == null) return false;
        String address = extras.getString("address");
        if (address == null || !address.equalsIgnoreCase(cb.deviceAddress())) return false;
        if (METHOD_NOISE.equals(method)) {
            AncMode mode = fromTileMode(extras.getInt("type", NOISE_OFF));
            boolean accepted = cb.requestMode(mode);
            diag("volume-panel ANC click mode=" + mode + " accepted=" + accepted, null);
            return accepted;
        }
        if (METHOD_SPATIAL.equals(method)) {
            int type = extras.getInt("type", -1);
            if (type < 0) return false;
            boolean accepted = cb.requestSpatial(type);
            diag("volume-panel spatial toggle type=" + type + " accepted=" + accepted, null);
            return accepted;
        }
        if (METHOD_WEAR.equals(method)) return true;
        return false;
    }

    // ------------------------------------------------------------------ state

    @SuppressLint("MissingPermission")
    private static void registerAclWatcher(Context context) {
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context receiver, Intent intent) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null || !BoseDeviceConfig.INSTANCE.matchesAddress(device.getAddress())) return;
                    if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()) && !worn) {
                        worn = true;
                        notifyChange(receiver, FLAG_WEAR | 0x01);
                    }
                    // Keep the tile alive on transient ACL drops (Bose Music profile
                    // switches) — SystemUI latches wear=false until reboot otherwise.
                }
            }, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "[MelodyLink] ACL watcher failed", t);
        }
    }

    private static void notifyChange(Context context, int flags) {
        try {
            context.getContentResolver().notifyChange(Uri.parse(BASE_URI), null, flags);
        } catch (Throwable ignored) {
        }
    }

    private static int toTileMode(AncMode mode) {
        if (mode == null) return -1;
        switch (mode) {
            case NOISE_CANCELING: return NOISE_ANC;
            case TRANSPARENCY:
            case AMBIENT_SOUND: return NOISE_TRANSPARENT;
            case OFF: return NOISE_OFF;
            default: return -1;
        }
    }

    private static AncMode fromTileMode(int tileMode) {
        switch (tileMode) {
            case NOISE_ANC: return AncMode.NOISE_CANCELING;
            case NOISE_TRANSPARENT: return AncMode.TRANSPARENCY;
            default: return AncMode.OFF;
        }
    }

    private static void closeCursor(Cursor cursor) {
        if (cursor == null) return;
        try {
            cursor.close();
        } catch (Throwable ignored) {
        }
    }
}
