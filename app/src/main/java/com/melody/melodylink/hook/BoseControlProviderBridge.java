package com.melody.melodylink.hook;

import android.annotation.SuppressLint;
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
    private static final String METHOD_NOISE = "melody_method_noise_reduction";
    private static final String METHOD_WEAR = "melody_method_control_wear";

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

        String deviceName();

        String deviceAddress();
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
            Log.i(TAG, "[MelodyLink] bose control provider bridge installed");
        } catch (Throwable t) {
            Log.w(TAG, "[MelodyLink] bose control provider bridge failed", t);
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

    private static Cursor patchQuery(Uri uri, String[] args, Cursor result) {
        Callbacks cb = callbacks;
        if (cb == null) return null;
        String path = uri == null ? null : uri.getPath();
        if (path == null) return null;
        if (!cb.isBoseActive()) return null;
        String address = cb.deviceAddress();
        if (address == null) return null;
        if (PATH_ACTIVE.equals(path)) {
            closeCursor(result);
            MatrixCursor cursor = new MatrixCursor(new String[]{"name", "address"});
            cursor.addRow(new Object[]{cb.deviceName(), address});
            return cursor;
        }
        if (PATH_NOISE.equals(path)) {
            if (args != null && args.length > 0 && args[0] != null
                    && !args[0].equalsIgnoreCase(address)) return null;
            closeCursor(result);
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{"name", "address", "type", "supports"});
            int mode = toTileMode(cb.currentMode());
            cursor.addRow(new Object[]{cb.deviceName(), address,
                    Integer.valueOf(mode < 0 ? NOISE_TRANSPARENT : mode), SUPPORTS});
            return cursor;
        }
        return null;
    }

    private static boolean handleCall(String method, Bundle extras) {
        Callbacks cb = callbacks;
        if (cb == null || extras == null || method == null) return false;
        String address = extras.getString("address");
        if (address == null || !address.equalsIgnoreCase(cb.deviceAddress())) return false;
        if (METHOD_NOISE.equals(method)) {
            AncMode mode = fromTileMode(extras.getInt("type", NOISE_OFF));
            boolean accepted = cb.requestMode(mode);
            Log.i(TAG, "[MelodyLink] volume-panel ANC click mode=" + mode + " accepted=" + accepted);
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
            }, Context.RECEIVER_EXPORTED);
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
