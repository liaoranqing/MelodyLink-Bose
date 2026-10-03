package com.melody.melodylink.hook;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.melody.melodylink.observer.MethodCallObserver;
import com.melody.melodylink.domain.AncMode;
import com.melody.melodylink.domain.BatteryPart;
import com.melody.melodylink.domain.BatteryValue;
import com.melody.melodylink.domain.EarbudsState;
import com.melody.melodylink.earbuds.EarbudsFacade;
import com.melody.melodylink.vendor.sony.SonyDeviceCatalogAdapter;
import com.melody.melodylink.vendor.sony.SonyEarbudsFacade;
import com.melody.melodylink.vendor.samsung.SamsungEarbudsFacade;
import com.melody.melodylink.vendor.huawei.HuaweiEarbudsFacade;
import com.melody.melodylink.vendor.xiaomi.XiaomiEarbudsFacade;
import com.melody.melodylink.bose.BoseDeviceConfig;
import com.melody.melodylink.bose.BoseTransport;
import com.melody.melodylink.huawei.config.HuaweiDeviceCatalog;
import com.melody.melodylink.huawei.config.HuaweiConfigIssue;
import com.melody.melodylink.huawei.config.HuaweiConfigLoadResult;
import com.melody.melodylink.huawei.config.HuaweiConfigLoader;
import com.melody.melodylink.huawei.config.HuaweiDeviceConfig;
import com.melody.melodylink.xiaomi.config.XiaomiConfigIssue;
import com.melody.melodylink.xiaomi.config.XiaomiConfigLoadResult;
import com.melody.melodylink.xiaomi.config.XiaomiConfigLoader;
import com.melody.melodylink.xiaomi.config.XiaomiDeviceCatalog;
import com.melody.melodylink.xiaomi.config.XiaomiDeviceConfig;
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsCatalog;
import com.melody.melodylink.sony.config.SonyConfigIssue;
import com.melody.melodylink.sony.config.SonyConfigLoadResult;
import com.melody.melodylink.sony.config.SonyConfigLoader;
import com.melody.melodylink.sony.config.SonyDeviceConfig;
import com.melody.melodylink.sony.config.SonyAdvancedSettingId;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.lang.reflect.Modifier;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/** Sony transport bridge and target-scoped diagnostics for Melody 16.8.3. */
/** Installs behavior-changing hooks for the supported Sony device. */
public final class HookModule extends XposedModule {
    private static final String TAG = "MelodyLinkObserver";
    private static final String TARGET = "com.oplus.melody";
    private static final String ADVANCED_CATEGORY_KEY = "melodylink.advanced_settings";
    private static final String ADVANCED_SETTING_KEY_PREFIX = "melodylink.setting.";
    private static final String HUAWEI_LOW_LATENCY_SETTING_KEY = "melodylink.huawei.low_latency";
    private static final String SOUND_QUALITY_TITLE = "音质音效";
    private static final String BOSE_CNC_KEY = "melodylink.bose.cnc";
    /**
     * Key/tag of the 通用设置 copy of the CNC slider. The detail page uses
     * {@link #BOSE_CNC_KEY} inside a PreferenceScreen, but the 通用设置 three-state row is a
     * RecyclerView item, so the slider is attached to the view hierarchy and needs its own
     * identity to stay idempotent across RecyclerView rebinds.
     */
    /**
     * How often the detail page is re-checked while it has focus. The host re-hides the
     * rows it cannot populate some time after we reveal them (0.5.27), so a single pass is
     * not enough. 400ms keeps the content stable without measurable cost: the walk touches
     * roughly a dozen nodes and only runs while the page is actually focused.
     */
    /**
     * Text handed to detail rows that have no summary of their own. A single space passes
     * TextUtils.isEmpty() while adding no visible text — the row carries its own title.
     */
    private static final String PLACEHOLDER_SUMMARY = " ";
    private static final String BOSE_CNC_ONESPACE_KEY = "melodylink.bose.cnc.onespace";
    private static final String BOSE_CNC_CARD_KEY = "melodylink.bose.cnc.card";
    private static final String NOISE_EFFECT_TITLE = "降噪效果";
    /**
     * Both the earbud detail page and the "通用设置" page expose a noise row, but
     * under different classes. 0.4.3 injected into both via the "音质音效" anchor;
     * 0.4.5 narrowed it to NoiseReductionItem only and silently dropped 通用设置
     * (log evidence: that page adds OneSpaceNoisePreference, never NoiseReductionItem).
     */
    private static final String NOISE_ROW_CLASS_DETAIL =
            "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem";
    private static final String NOISE_ROW_CLASS_ONESPACE =
            "com.oplus.melody.onespace.items.OneSpaceNoisePreference";

    private static boolean isBoseNoiseRowClass(String className) {
        return NOISE_ROW_CLASS_DETAIL.equals(className) || NOISE_ROW_CLASS_ONESPACE.equals(className);
    }

    /**
     * True for the "降噪效果" row.
     *
     * <p>Accepts either the R8 class name or the preference key. The key is the stable
     * identifier: Melody stamps it via {@code PreferenceCategory.setKey(cls.getSimpleName())},
     * and the 17.6.3 smali confirms {@code NoiseReductionItem} uses exactly
     * {@code "NoiseReductionItem"}. R8 renames the class between releases; the key survives.
     * This is the same anchoring strategy Andrea-lyz/MelodyCodecTweaker uses.
     */
    private static boolean isBoseNoiseRow(Object preference) {
        if (preference == null) return false;
        if (isBoseNoiseRowClass(preference.getClass().getName())) return true;
        String key = PrefRef.getKey(preference);
        return "NoiseReductionItem".equals(key) || "OneSpaceNoisePreference".equals(key);
    }
    private static final int WF_1000XM3_PRODUCT_ID = 0x067410;
    private volatile int targetAddressHash;
    private volatile String targetAddress;
    private volatile BluetoothDevice targetSonyDevice;
    private volatile BluetoothDevice targetSamsungDevice;
    private volatile BluetoothDevice targetHuaweiDevice;
    private volatile BluetoothDevice targetXiaomiDevice;
    private volatile BluetoothDevice targetBoseDevice;
    /** Host A2DP/HFP state remains authoritative for UI connection, even if AF00 control setup fails. */
    private volatile boolean xiaomiHostConnected;
    private volatile Object earphoneRepository;
    private volatile MelodySharedStateStore sharedStateStore;
    private final MelodySessionState sonySessionState = new MelodySessionState();
    private final MelodySessionState huaweiSessionState = new MelodySessionState();
    private final MelodySessionState xiaomiSessionState = new MelodySessionState();
    private final MelodySessionState boseSessionState = new MelodySessionState();
    /** A2DP host link is authoritative for the Bose UI, like Xiaomi; the BMAP channel is transient. */
    private volatile boolean boseHostConnected;
    private volatile ClassLoader melodyClassLoader;
    private volatile CompletableFuture<Object> pendingNoiseWrite;
    private volatile AncMode pendingAncMode;
    private final Map<SonyAdvancedSettingId, Boolean> pendingSonySettings = new ConcurrentHashMap<>();
    private final Map<SonyAdvancedSettingId, Boolean> confirmedSonySettings = new ConcurrentHashMap<>();
    private volatile boolean pendingBatteryRefresh;
    private volatile ScheduledExecutorService foregroundStateWatcher;
    private volatile String lastForegroundStateFingerprint;
    private volatile String lastSonyCommandFingerprint;
    private volatile String lastSonyBatteryCommandNonce;
    private volatile String lastSonySettingCommandNonce;
    private volatile String lastBoseCncCommandNonce;
    private volatile boolean sonyConfigInitialized;
    private final MelodyDeviceBridge deviceBridge = new MelodyDeviceBridge();
    private volatile AssetManager sonyModuleAssets;
    private volatile SonyDeviceConfig activeSonyImageProfile;
    private volatile HuaweiDeviceConfig activeHuaweiImageProfile;
    private volatile XiaomiDeviceConfig activeXiaomiImageProfile;
    private volatile boolean retainSharedSonyStateAfterCommandDisconnect;
    private volatile boolean activityLifecycleRegistered;
    private volatile Activity detailActivity;
    private volatile Object lastAudioPreferenceAnchor;
    private volatile int startedActivityCount;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> detailAncWriteObserved = new ThreadLocal<>();
    private final Map<SonyAdvancedSettingId, Object> advancedPreferences = new ConcurrentHashMap<>();
    private volatile Object huaweiLowLatencyPreference;
    private volatile Object boseCncPreference;
    /** The 通用设置 copy, which lives in the view hierarchy rather than a screen. */
    private volatile Object boseCncOneSpacePreference;

    /**
     * The "降噪效果" row, captured in detailPreferenceAdd; its parent may be null then.
     *
     * <p>0.5.20 evidence: keeping one shared field for both pages made them corrupt each
     * other. The 通用设置 page and the detail page each add their own copy, and whichever
     * ran last overwrote the reference — so a 通用设置 retry tick could fire with the
     * detail page's anchor in hand and inject into the wrong tree, and vice versa. The
     * symptom was a page that rendered correctly for a second (t+800 had a populated
     * NestedScrollView with the device-info block and the 1356x1356 model view) and then
     * went empty at t+2000. One field per page removes the interference.
     */
    private volatile Object noiseEffectRow;
    private volatile Object oneSpaceNoiseEffectRow;

    /** Injected "抗风噪" switch, kept for state re-sync. */

    /** Injected "音效调节" panel: three EQ sliders, button remaps, mode slots. */
    private final List<Object> boseEqSliders = new ArrayList<>();
    private final List<Object> boseButtonDropdowns = new ArrayList<>();
    private final List<Object> boseModeSlotSliders = new ArrayList<>();
    private volatile Object boseExtraCategory;
    private volatile Boolean confirmedHuaweiLowLatency;
    private volatile XiaomiEarbudsFacade xiaomiTransport;
    private volatile boolean xiaomiBatteryReceiverRegistered;
    private final BroadcastReceiver xiaomiBatteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT.equals(intent.getAction()) || targetXiaomiDevice == null) return;
            BluetoothDevice source = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (source != null && !source.equals(targetXiaomiDevice)) return;
            Object value = intent.getSerializableExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS);
            XiaomiEarbudsFacade transport = xiaomiTransport;
            if (transport == null) return;
            java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
            if (value instanceof Object[]) {
                for (Object item : (Object[]) value) if (item instanceof String) arguments.add((String) item);
            } else if (value instanceof String) {
                arguments.add((String) value);
            }
            if (arguments.isEmpty()) {
                log(Log.INFO, TAG, event("Xiaomi vendor battery event ignored: no string arguments"));
                return;
            }
            transport.acceptVendorBatteryEvent(arguments);
        }
    };
    private final HuaweiEarbudsFacade huaweiTransport = new HuaweiEarbudsFacade(new HuaweiEarbudsFacade.Listener() {
        @Override public void onConnecting() { log(Log.INFO, TAG, event("Huawei RFCOMM connecting")); }
        @Override public void onConnected(EarbudsState state) {
            huaweiSessionState.acceptAnc(state);
            huaweiSessionState.acceptBattery(state);
            updateHuaweiLowLatencyPreference(null, true);
            writeSharedHuaweiState();
            publishBatteryState(state, "Huawei connected");
            refreshTargetRepository("Huawei connected");
            log(Log.INFO, TAG, event("Huawei RFCOMM connected; ANC state=" + state.getAncMode()));
        }
        @Override public void onBatteryState(EarbudsState state) {
            huaweiSessionState.acceptBattery(state);
            writeSharedHuaweiState();
            publishBatteryState(state, "Huawei battery read");
            refreshTargetRepository("Huawei battery read");
            log(Log.INFO, TAG, event("Huawei battery state received"));
        }
        @Override public void onAncWriteResult(boolean success, EarbudsState state, String reason) {
            CompletableFuture<Object> future;
            synchronized (HookModule.this) { future = pendingNoiseWrite; pendingNoiseWrite = null; }
            log(success ? Log.INFO : Log.WARN, TAG, event("Huawei ANC write " + (success ? "succeeded" : "failed: " + reason)));
            if (future == null) return;
            if (success) {
                if (state != null) {
                    huaweiSessionState.acceptAnc(state);
                    writeSharedHuaweiState();
                    refreshTargetRepository("Huawei ANC write");
                }
                Object result = createSetCommandState(0);
                if (result != null) future.complete(result); else future.completeExceptionally(new IllegalStateException("Huawei ANC result DTO unavailable"));
            } else future.completeExceptionally(new IllegalStateException(reason));
        }
        @Override public void onLowLatencyWriteResult(boolean success, Boolean enabled, String reason) {
            if (success && enabled != null) {
                confirmedHuaweiLowLatency = enabled;
                updateHuaweiLowLatencyPreference(enabled, true);
            } else {
                updateHuaweiLowLatencyPreference(null, true);
                log(Log.WARN, TAG, event("Huawei low-latency write failed: " + reason));
            }
        }
        @Override public void onDisconnected() {
            failPendingNoiseWrite("Huawei transport disconnected");
            clearHuaweiSessionState();
            refreshTargetRepository("Huawei disconnected");
            log(Log.INFO, TAG, event("Huawei RFCOMM disconnected"));
        }
        @Override public void onFailed(String reason) {
            failPendingNoiseWrite(reason);
            clearHuaweiSessionState();
            refreshTargetRepository("Huawei failed");
            log(Log.WARN, TAG, event("Huawei RFCOMM failed: " + reason));
        }
        @Override public void onLog(String message) { log(Log.INFO, TAG, event(message)); }
    });

    /**
     * Bose BMAP runs short-lived RFCOMM sessions (channel 2 is single-client), so the
     * host A2DP link — not the transport — is the authoritative "connected" marker,
     * mirroring the Xiaomi pattern.
     */
    private final BoseTransport boseTransport = new BoseTransport(new BoseTransport.Listener() {
        @Override public void onConnecting() { log(Log.INFO, TAG, event("Bose BMAP connecting")); }
        @Override public void onConnected(EarbudsState state) {
            boseSessionState.acceptAnc(state);
            // The native detail UI reads ANC/battery through sonySessionState (the
            // Enco X3 mask we map Bose onto), so mirror the state there too.
            sonySessionState.acceptAnc(state);
            writeSharedBoseState();
            BoseControlProviderBridge.refreshTile();
            refreshTargetRepository("Bose connected");
            updateBoseCncSlider();
            log(Log.INFO, TAG, event("Bose BMAP session done; ANC state=" + state.getAncMode()));
        }
        @Override public void onBatteryState(EarbudsState state) {
            boseSessionState.acceptBattery(state);
            sonySessionState.acceptBattery(state);
            publishBatteryState(state, "Bose battery read");
            refreshTargetRepository("Bose battery read");
            log(Log.INFO, TAG, event("Bose battery state received"));
        }
        @Override public void onAncWriteResult(boolean success, EarbudsState state, String reason) {
            CompletableFuture<Object> future;
            synchronized (HookModule.this) { future = pendingNoiseWrite; pendingNoiseWrite = null; }
            log(success ? Log.INFO : Log.WARN, TAG, event("Bose ANC write " + (success ? "succeeded" : "failed: " + reason)));
            if (future == null) return;
            if (success) {
                if (state != null) {
                    boseSessionState.acceptAnc(state);
                    sonySessionState.acceptAnc(state);
                    writeSharedBoseState();
                    refreshTargetRepository("Bose ANC write");
                    BoseControlProviderBridge.refreshTile();
                }
                Object result = createSetCommandState(0);
                if (result != null) future.complete(result); else future.completeExceptionally(new IllegalStateException("Bose ANC result DTO unavailable"));
            } else future.completeExceptionally(new IllegalStateException(reason));
        }
        @Override public void onDisconnected() {
            log(Log.INFO, TAG, event("Bose BMAP channel closed"));
        }
        @Override public void onFailed(String reason) {
            failPendingNoiseWrite(reason);
            refreshTargetRepository("Bose failed");
            log(Log.WARN, TAG, event("Bose BMAP failed: " + reason));
        }
        @Override public void onLog(String message) { log(Log.INFO, TAG, event(message)); }
    });

    private XiaomiEarbudsFacade ensureXiaomiTransport() {
        XiaomiEarbudsFacade existing = xiaomiTransport;
        if (existing != null) return existing;
        Application application = currentApplication();
        if (application == null) return null;
        synchronized (this) {
            if (xiaomiTransport != null) return xiaomiTransport;
            xiaomiTransport = new XiaomiEarbudsFacade(application, new XiaomiEarbudsFacade.Listener() {
                @Override public void onConnecting() { log(Log.INFO, TAG, event("Xiaomi SPP connecting")); }
                @Override public void onConnected(EarbudsState state) {
                    xiaomiSessionState.acceptAnc(state);
                    xiaomiSessionState.acceptBattery(state);
                    writeSharedXiaomiState();
                    publishBatteryState(state, "Xiaomi connected");
                    refreshTargetRepository("Xiaomi connected");
                    log(Log.INFO, TAG, event("Xiaomi SPP connected; ANC state=" + state.getAncMode()));
                }
                @Override public void onStateChanged(EarbudsState state) {
                    xiaomiSessionState.acceptAnc(state);
                    xiaomiSessionState.acceptBattery(state);
                    writeSharedXiaomiState();
                    refreshTargetRepository("Xiaomi SPP status notification");
                    log(Log.INFO, TAG, event("Xiaomi SPP state ANC=" + state.getAncMode()));
                }
                @Override public void onBatteryState(EarbudsState state) {
                    xiaomiSessionState.acceptBattery(state);
                    writeSharedXiaomiState();
                    publishBatteryState(state, "Xiaomi battery event");
                    refreshTargetRepository("Xiaomi battery event");
                }
                @Override public void onAncWriteResult(boolean success, EarbudsState state, String reason) {
                    CompletableFuture<Object> future;
                    synchronized (HookModule.this) { future = pendingNoiseWrite; pendingNoiseWrite = null; }
                    if (success && state != null) {
                        xiaomiSessionState.acceptAnc(state);
                        writeSharedXiaomiState();
                        refreshTargetRepository("Xiaomi ANC write");
                    }
                    if (future == null) return;
                    if (success) {
                        Object result = createSetCommandState(0);
                        if (result != null) future.complete(result);
                        else future.completeExceptionally(new IllegalStateException("Xiaomi ANC result DTO unavailable"));
                    } else future.completeExceptionally(new IllegalStateException(reason));
                }
                @Override public void onDisconnected() {
                    failPendingNoiseWrite("Xiaomi transport disconnected");
                    xiaomiSessionState.clear();
                    refreshTargetRepository("Xiaomi disconnected");
                }
                @Override public void onFailed(String reason) {
                    failPendingNoiseWrite(reason);
                    xiaomiSessionState.clear();
                    refreshTargetRepository("Xiaomi failed");
                    log(Log.WARN, TAG, event("Xiaomi BLE failed: " + reason));
                }
                @Override public void onLog(String message) { log(Log.INFO, TAG, event(message)); }
            });
            return xiaomiTransport;
        }
    }

    private final SamsungEarbudsFacade samsungTransport = new SamsungEarbudsFacade(new SamsungEarbudsFacade.Listener() {
        @Override
        public void onConnecting() {
            log(Log.INFO, TAG, event("Samsung RFCOMM connecting"));
        }

        @Override
        public void onConnected(EarbudsState state) {
            log(Log.INFO, TAG, event("Samsung RFCOMM connected; ANC state=" + state.getAncMode()));
        }

        @Override
        public void onBatteryState(EarbudsState state) {
            log(Log.INFO, TAG, event("Samsung battery state received"));
        }

        @Override
        public void onAncWriteResult(boolean success, EarbudsState state, String reason) {
            log(success ? Log.INFO : Log.WARN, TAG, event("Samsung ANC write "
                    + (success ? "succeeded" : "failed: " + reason)));
        }

        @Override
        public void onDisconnected() {
            log(Log.INFO, TAG, event("Samsung RFCOMM disconnected"));
        }

        @Override
        public void onFailed(String reason) {
            log(Log.WARN, TAG, event("Samsung RFCOMM failed: " + reason));
        }

        @Override
        public void onLog(String message) {
            log(Log.INFO, TAG, event(message));
        }
    });

    private final EarbudsFacade sonyTransport = new SonyEarbudsFacade(new EarbudsFacade.Listener() {
        @Override
        public void onConnecting() {
            log(Log.INFO, TAG, event("Sony RFCOMM connecting"));
        }

        @Override
        public void onConnected(EarbudsState state) {
            sonySessionState.acceptAnc(state);
            writeSharedSonyState();
            log(Log.INFO, TAG, event("Sony RFCOMM connected; ANC state=" + state.getAncMode()));
            publishSonyBatteryState("Sony connected");
            refreshTargetRepository("Sony connected");
            runPendingSonyOperation();
        }

        @Override
        public void onBatteryState(EarbudsState state) {
            sonySessionState.acceptBattery(state);
            publishSonyBatteryState("Sony battery read");
        }

        @Override
        public void onSettingState(SonyAdvancedSettingId id, boolean value) {
            updateAdvancedSetting(id, value);
            confirmedSonySettings.put(id, value);
            writeSharedSonyState();
        }

        @Override
        public void onSettingWriteResult(SonyAdvancedSettingId id, boolean success, Boolean value, String reason) {
            if (success && value != null) {
                updateAdvancedSetting(id, value);
                confirmedSonySettings.put(id, value);
                writeSharedSonyState();
            }
            if (!success) {
                setAdvancedSettingEnabled(id, true);
                log(Log.WARN, TAG, event("Sony setting " + id + " failed: " + reason));
            }
        }

        @Override
        public void onAncWriteResult(boolean success, EarbudsState state, String reason) {
            CompletableFuture<Object> future;
            synchronized (HookModule.this) {
                future = pendingNoiseWrite;
                pendingNoiseWrite = null;
            }
            if (success && state != null) {
                sonySessionState.acceptAnc(state);
                writeSharedSonyState();
                refreshTargetRepository("Sony ANC write");
                log(Log.INFO, TAG, event("Sony ANC write result status=0 mode="
                        + MelodyStateBridge.INSTANCE.ancModeIndex(state)));
            }
            if (future == null) return;
            if (success && state != null) {
                Object result = createSetCommandState(0);
                if (result == null) {
                    future.completeExceptionally(new IllegalStateException("Sony ANC result DTO unavailable"));
                } else {
                    future.complete(result);
                }
            } else {
                future.completeExceptionally(new IllegalStateException(reason));
            }
        }

        @Override
        public void onCommandSessionFinished(String reason) {
            retainSharedSonyStateAfterCommandDisconnect = true;
            log(Log.INFO, TAG, event("Sony RFCOMM command succeeded; retaining device state while closing session: "
                    + reason));
        }

        @Override
        public void onDisconnected() {
            failPendingNoiseWrite("Sony transport disconnected");
            if (retainSharedSonyStateAfterCommandDisconnect) {
                retainSharedSonyStateAfterCommandDisconnect = false;
                log(Log.INFO, TAG, event("Sony RFCOMM released after successful command; device state retained"));
                return;
            }
            sonySessionState.clear();
            clearSharedSonyState();
            log(Log.INFO, TAG, event("Sony RFCOMM disconnected"));
            refreshTargetRepository("Sony disconnected");
        }

        @Override
        public void onFailed(String reason) {
            failPendingNoiseWrite(reason);
            pendingAncMode = null;
            pendingSonySettings.clear();
            pendingBatteryRefresh = false;
            sonySessionState.clear();
            clearSharedSonyState();
            log(Log.WARN, TAG, event("Sony RFCOMM failed: " + reason));
            for (SonyAdvancedSettingId id : advancedPreferences.keySet()) {
                setAdvancedSettingEnabled(id, true);
            }
            refreshTargetRepository("Sony failed");
        }

        @Override
        public void onLog(String message) {
            log(Log.INFO, TAG, event(message));
        }
    });

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        try {
            initializeSonyConfig();
            if (isPrimaryProcess()) {
                clearStaleSharedSonyState();
                clearSharedSonyCommand();
                clearSharedSonyBatteryCommand();
                clearSharedSonySettingCommand();
                registerAppVisibilityLifecycleCallbacks();
                if (sonyTransport.isConnected()) {
                    writeSharedSonyState();
                    log(Log.INFO, TAG, event("republished live Sony session after package initialization"));
                }
            }
            ClassLoader loader = param.getClassLoader();
            melodyClassLoader = loader;
            if (isPrimaryProcess()) installProviderBridge(loader);
            hookAny(loader, "whitelist",
                    "com.oplus.melody.common.util.V#a#3",
                    "com.oplus.melody.common.util.T#a#3");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "f", 4, "deviceInfo");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "c", 1, "deviceRegistryAdd");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "d", 1, "deviceRegistryGet");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "h", 1, "deviceRegistryLookup");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "i", 1, "deviceRegistryEnsure");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "e", 1, "connectionRefresh");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceConnectState", 1, "sppState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceHeadsetConnectState", 1, "hfpState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceA2dpConnectState", 1, "a2dpState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceLeAudioConnectState", 2, "leAudioState");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainViewModel", "f", 1, "detailState");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainViewModel", "g", 1, "detailConnectionState");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainActivity", "onCreate", 1, "detailActivityCreate");
            // DetailMainActivity.A() is the whole content pipeline — 17.6.3 smali:
            //   CompletableFuture.supplyAsync(LA9/r;).whenCompleteAsync(LAa/h;, executor)
            // onCreate itself contains no fragment transaction at all, so this is the only
            // place the detail fragment can be created. 0.5.16 evidence: the page stayed
            // blank (melody_ui_fragment_container with zero children) while every hook we
            // had was firing normally, which means the failure is upstream of any
            // preference we could touch — inside A()'s future.
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainActivity", "A", 0, "detailPipelineStart");
            // 0.5.18 evidence: hooking A9/r.get and Aa/h.accept produced nothing. Both are
            // R8-merged lambda holders — a single class shared by dozens of call sites,
            // dispatched through a packed-switch on an int field. A() builds A9/r with
            // field a=0x12 and Aa/h with a=0x7, so those two hooks only covered unrelated
            // branches. Hooking the real work instead: the whitelist lookup.
            //
            // 17.6.3 DetailMainActivity.A() branch 0x12 resolves the MAC (from the "device"
            // extra, else "device_mac_info", else SharedPreferences launcher_address) and
            // then calls c9/a.a(mac) for a WhitelistConfigDTO. Bose has no catalog entry,
            // so that returns null and the page builds from a null config — which matches
            // the observed symptom exactly: the row attaches (container holds a
            // NestedScrollView) but stays empty.
            // 0.5.32: the class hooked here in 0.5.19 ("c9/a") was WRONG — R8 short names
            // are not unique, and c9/a is a guide fragment. The only method in the whole APK
            // that returns WhitelistConfigDTO is L6/a.a(String), and the list it matches
            // against comes from L6/a.b() -> List<WhitelistConfigDTO> (built from
            // WhitelistContentDO). Hooking b() is the better place anyway: injecting the
            // entry there means every consumer sees a normal catalog row, instead of
            // patching one call site.
            // 0.5.33: 0.5.32 hooked L6/a.b() and L6/a.a(String) and both reported
            // "cannot hook in L6/a" even though smali shows both methods clearly
            // (SupportConfigManager, both public final on a final class). libxposed will
            // not hook final methods, so this approach cannot work on this target.
            //
            // The constructor is a normal non-final method and always hooks. Its field 
            // is the com.oplus.melody.common.util.E provider that b() reads the catalog
            // from, so replacing that field with a proxy intercepts the catalog without
            // touching any final method.
            hookNamed(loader, "L6/a", "b", 0, "whitelistConfigList");
            hookNamed(loader, "L6/a", "a", 1, "detailWhitelistLookup");
            // The fragment that the callback is supposed to populate. Watching its lifecycle
            // tells us whether it is created at all once the config resolves.
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainFragment",
                    "onCreateView", 3, "detailFragmentCreated");
            // JADX labels this class v9.t; the runtime name in Melody 16.8.3 is v9.C1594t.
            hookNamed(loader, "v9.C1594t", "onViewCreated", 2, "detailPreferenceHostCreated");
            // MelodyCodecTweaker's stable entry: every DetailMain preference page inherits this.
            hookNamed(loader, "androidx.preference.g", "onViewCreated", 2,
                    "detailPreferenceFragmentViewCreated");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "i", 1, "sonyCardImage");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onBindViewHolder", 1, "sonyCardBind");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onShowAnimationEnd", 0, "sonyCardLoading");
            // 通用设置 ANC three-state row (降噪/关闭/通透). Its onBindViewHolder is the only
            // place that exposes the DeviceControlWidget in field d, i.e. the row the user
            // sees; the slider has to be attached to that widget's parent. Unlike the detail
            // page this row is a plain RecyclerView item, not a Preference, so it can never
            // be reached through the preference-screen injection path.
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceNoisePreference", "onBindViewHolder", 1, "onespaceNoiseBind");
            // Product image. Confirmed against Melody 17.6.3 smali: b(String) is the
            // 3D-model loader and c()Z is a low-memory check — neither touches the photo,
            // which is why 0.5.x reported a successful replacement that never showed up.
            // The Glide call lives in a() (void), and e() — the branch taken when the
            // product has no detail source, i.e. every non-catalog device like Bose —
            // invokes a(). So a() is the one place that always runs.
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "a", 0, "sonyDetailImage");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "d", 1, "sonyDetailPlaceholder");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "onFinishInflate", 0, "sonyDetailInflated");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "setViewModel", 1, "sonyDetailViewModel");
            hookNamed(loader, "androidx.preference.PreferenceGroup", "f", 1, "detailPreferenceAdd");
            hookAny(loader, "repositoryObserve",
                    "com.oplus.melody.model.repository.earphone.U#z#1",
                    "com.oplus.melody.model.repository.earphone.J#A#1");
            hookAny(loader, "repositoryGet",
                    "com.oplus.melody.model.repository.earphone.U#y#1",
                    "com.oplus.melody.model.repository.earphone.J#y#1");
            hookAny(loader, "repositoryDtoBuild",
                    "com.oplus.melody.model.repository.earphone.U#g1#1",
                    "com.oplus.melody.model.repository.earphone.J#k1#1");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getConnectionState", 0, "dtoConnectionState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getAclConnectionState", 0, "dtoAclState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isSupportSpp", 0, "dtoSupportSpp");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isInitCmdCompleted", 0, "dtoInitCompleted");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getNoiseReductionModeIndex", 0, "dtoNoiseReductionMode");
            hookAny(loader, "detailInfoConnectionState",
                    "v9.C1576a#getConnectionState#0", "G9.a#getConnectionState#0");
            hookAny(loader, "detailInfoHeadsetState",
                    "v9.C1576a#getHeadsetConnectionState#0", "G9.a#getHeadsetConnectionState#0");
            hookAny(loader, "detailInfoSupportSpp",
                    "v9.C1576a#getIsSpp#0", "G9.a#getIsSpp#0");
            hookNamed(loader, "v9.a", "getConnectionState", 0, "detailInfoConnectionStateActual");
            hookNamed(loader, "v9.a", "getHeadsetConnectionState", 0, "detailInfoHeadsetStateActual");
            hookNamed(loader, "v9.a", "getIsSpp", 0, "detailInfoSupportSppActual");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.a", "getCurrentNoiseReductionModeIndex", 0, "opsNoiseReductionMode");
            hookAny(loader, "noiseReductionModeVO",
                    "pa.C1405p#getCurrentNoiseReductionModeIndex#0",
                    "Ba.z#getCurrentNoiseReductionModeIndex#0");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onEarphoneDataChanged", 1, "noiseReductionItemDataChanged");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem$a", "c", 2, "nativeNoiseReductionClick");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u", 2, "connectToDevice");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "v", 1, "connectImmediate");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u0", 2, "sendPacket");
            hookAny(loader, "nativeConnectDevice",
                    "E7.c#b#1", "e7.c#c#1");
            hookAny(loader, "directConnectSpp",
                    "E7.c#a#2", "e7.c#a#2");
            hookAny(loader, "nativeConnectionState",
                    "E7.c#e#1", "e7.c#f#1");
            hookNamed(loader, "A7.h", "h", 2, "nativeConnectSuccess");
            hookNamed(loader, "A7.h", "e", 2, "nativeConnectFailure");
            hookNamed(loader, "C7.b", "k", 1, "socketFailureBranch");
            hookNamed(loader, "D7.a", "f", 2, "connectionFailureReport");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "m0", 1, "receiveEvent");
            hookAny(loader, "noiseWrite",
                    "com.oplus.melody.model.repository.earphone.U#L0#3",
                    "com.oplus.melody.model.repository.earphone.J#o0#3");
            hookAny(loader, "noiseModeWrite",
                    "com.oplus.melody.model.repository.earphone.U#s0#2",
                    "com.oplus.melody.model.repository.earphone.J#v0#2");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "s0", 2, "noiseModeWriteClient");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "z", 1, "repositoryClientObserve");
            hookNamed(loader, "V7.v", "g", 0, "melodyEarphoneLiveDataRequest");
            hookNamed(loader, "V7.u", "handleMessage", 1, "melodyEarphoneLiveDataResponse");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "k1", 1, "stateCallback");
            hookAny(loader, "repositoryNotify",
                    "com.oplus.melody.model.repository.earphone.U#x1#1",
                    "com.oplus.melody.model.repository.earphone.J#B1#1");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.OpsReductionItem", "onBindViewHolder", 1, "opsReductionItemBound");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "h", 0, "opsReductionSwitchToCurrentMode");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "i", 0, "opsReductionUpdateActionView");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "d", 0, "opsReductionApplyMode");
            // Melody 16.8.3's child-mode callback supplies the stable modeType before it is
            // converted to the opaque protocol index passed to EarphoneRepository.s0.
            startForegroundStateWatcher();
            startPersistentLogCapture();
            // Report every hard-coded anchor that this Melody build no longer ships, so a
            // host update is diagnosed from evidence instead of from guesswork.
            mainHandler.post(() -> ClassAudit.run(loader));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setup failed", t);
        }
    }

    /**
     * Starts the on-disk logcat capture.
     *
     * <p>The reason this exists: a plain {@code adb logcat -d} is worthless as evidence on
     * this device because the ring buffer turns over within seconds, which is how 0.4.x /
     * 0.5.x shipped several "fixes" based on guesses. The capture writes straight to a file
     * for the whole session, so a report can always be checked against real evidence.
     *
     * <p>Delayed: {@code su} spawns a shell, which must not happen on the hook-setup path.
     */
    private void startPersistentLogCapture() {
        // Hand MLog the logger that actually reaches the LSPosed log. Measured on 0.5.9:
        // MLog's own android.util.Log output never showed up in modules_*.log, so every
        // structured event was invisible and the whole point of adding them was lost.
        MLog.installSink((level, message) -> log(level, "MelodyLinkBose", message));
        Application application = currentApplication();
        if (application == null) return;
        mainHandler.postDelayed(() -> {
            try {
                LogcatCapture.start(application);
                MLog.event("logcat.capture.armed", "path", LogcatCapture.path());
            } catch (Throwable t) {
                MLog.event("logcat.capture.error", "error", MLog.compactThrowable(t));
            }
        }, 4000L);
    }

    /**
     * Binds the first candidate that resolves. Candidates are "className#methodName#arity".
     * Melody's obfuscated names drift between versions, so each hook lists every known alias.
     */
    private void hookAny(ClassLoader loader, String label, String... candidates) {
        for (String candidate : candidates) {
            String[] parts = candidate.split("#");
            if (hookNamed(loader, parts[0], parts[1], Integer.parseInt(parts[2]), label)) return;
        }
        log(Log.WARN, TAG, event("no candidate resolved for " + label));
    }

    /** Compact "name/arity -> returnType" listing used when a hook target is not found. */
    private static String describeMethods(Class<?> type) {
        try {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Method m : type.getDeclaredMethods()) {
                if (shown++ >= 12) {
                    sb.append("...");
                    break;
                }
                sb.append(m.getName()).append('/')
                  .append(m.getParameterTypes().length).append("->")
                  .append(m.getReturnType().getSimpleName()).append(' ');
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * Loads a class by either name form.
     *
     * <p>0.5.35. R8 short names come out of smali as {@code L6/a}, but
     * {@code Class.forName} on Android rejects a name with no dot: it throws
     * {@code ClassNotFoundException: Invalid name: L6/a}. Every hook at an obfuscated class
     * therefore failed silently for the whole project — the WARN was easy to miss among the
     * working hooks, and the failure looks identical to "method not found".
     *
     * <p>So: try the name as given, then with {@code /} turned into {@code .}, then with the
     * conventional {@code L;} prefix stripped. Fully-qualified names are unaffected.
     */
    private static Class<?> loadClass(ClassLoader loader, String className) throws ClassNotFoundException {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException first) {
            // smali form L6/a -> binary form L6.a
            if (className.indexOf('/') >= 0) {
                try {
                    return Class.forName(className.replace('/', '.'), false, loader);
                } catch (ClassNotFoundException ignored) {
                }
            }
            // smali descriptor form Lcom/oplus/Foo; -> com.oplus.Foo
            if (className.length() > 2 && className.charAt(0) == 'L'
                    && className.endsWith(";")) {
                return Class.forName(
                        className.substring(1, className.length() - 1).replace('/', '.'),
                        false, loader);
            }
            throw first;
        }
    }

    private boolean hookNamed(ClassLoader loader, String className, String methodName, int arity, String label) {
        try {
            Class<?> type = loadClass(loader, className);
            Method selected = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == arity) {
                    selected = method;
                    break;
                }
            }
            if (selected == null) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equalsIgnoreCase(methodName)
                            && method.getParameterTypes().length == arity) {
                        selected = method;
                        log(Log.WARN, TAG, label + " matched case-insensitive method name: "
                                + method.getName());
                        break;
                    }
                }
            }
            if (selected == null) {
                log(Log.WARN, TAG, label + " not found: " + className + "." + methodName + "/" + arity);
                MLog.event("bose.hook.miss",
                        "label", label,
                        "class", className,
                        "method", methodName,
                        "arity", arity,
                        // Dump what the class actually exposes, so a wrong name or a
                        // different arity is visible without another round trip.
                        "available", describeMethods(type));
                return false;
            }
            Method method = selected;
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                boolean melodyEarphoneLiveData = isMelodyEarphoneLiveData(chain.getThisObject());
                boolean traceCall = shouldTrace(label, chain, arity);
                if ("melodyEarphoneLiveDataRequest".equals(label)
                        || "melodyEarphoneLiveDataResponse".equals(label)) {
                    traceCall = melodyEarphoneLiveData;
                }
                if (traceCall) {
                    log(Log.INFO, TAG, event(label + " before " + signature(method) + " args=" + describeArgs(chain, arity)));
                }
                try {
                    captureRepository(label, chain);
                    if ("sonyCardImage".equals(label)) {
                        // Bose: OneSpaceHeaderPreference.i(Lf9/b;) is the 通用设置 product
                        // photo loader. 17.6.3 smali shows it reads getDetailImageRes(),
                        // which is empty for a device with no catalog entry, so the host
                        // never sets a drawable and the card stays blank. The Sony path
                        // below only fires when a SonyDeviceConfig exists, so it never
                        // covers Bose — this branch is what puts our photo there.
                        if (replaceBoseOneSpaceHeaderImage(chain.getThisObject())) {
                            return null;
                        }
                        if (replaceConfiguredProductImage(
                                chain.getThisObject(), "b", "c", "d", "e", "d", "card")) {
                            return null;
                        }
                    }
                    if ("sonyDetailImage".equals(label)) {
                        // a() issues the Glide load, so the replacement must run after
                        // proceed() or Glide overwrites it. This is the path a non-catalog
                        // device always takes, because e() calls a() directly.
                        Object result = chain.proceed();
                        replaceBoseDetailImageNow(chain.getThisObject());
                        return result;
                    }
                    if ("sonyDetailPlaceholder".equals(label)) {
                        Object result = chain.proceed();
                        replaceBoseDetailImageNow(chain.getThisObject());
                        return result;
                    }
                    if ("sonyDetailInflated".equals(label) || "sonyDetailViewModel".equals(label)) {
                        Object result = chain.proceed();
                        replaceSonyDetailImageLater(chain.getThisObject());
                        return result;
                    }
                    if ("sonyCardBind".equals(label)) {
                        Object result = chain.proceed();
                        replaceConfiguredProductImage(chain.getThisObject(), "b", "c", "d", "e", "d",
                                "card", findCardImageView(chain.getArg(0)));
                        return result;
                    }
                    if ("sonyCardLoading".equals(label) && activeSonyImageProfile != null) {
                        hideLoadingView(readField(chain.getThisObject(), "d"));
                        return null;
                    }
                    if ("onespaceNoiseBind".equals(label)) {
                        // Runs on every RecyclerView rebind, so the attach is idempotent
                        // (guarded by a marker tag) and the slider is never duplicated.
                        Object result = chain.proceed();
                        attachCncSliderUnderOneSpaceNoise(chain.getThisObject(), chain.getArg(0));
                        return result;
                    }
                    if ("detailPreferenceAdd".equals(label)) {
                        Object preference = chain.getArg(0);
                        Object result = chain.proceed();
                        removeUnsupportedDetailCategory(preference);
                        hideAncStrengthPreference(preference);
                        captureNoiseEffectRow(preference);
                        hideNoiseEffectRow(preference);
                        return result;
                    }
                    if ("detailPipelineStart".equals(label)) {
                        Object result = chain.proceed();
                        logDetailPipeline("start");
                        return result;
                    }
                    if ("detailPipelineSupply".equals(label)) {
                        // The supplier runs on a worker thread. If it throws, the future
                        // completes exceptionally and the fragment is never attached.
                        try {
                            Object result = chain.proceed();
                            logDetailPipeline("supply_ok", "value", describeValue(result));
                            return result;
                        } catch (Throwable t) {
                            logDetailPipeline("supply_threw", "error", MLog.compactThrowable(t));
                            throw t;
                        }
                    }
                    if ("whitelistConfigList".equals(label)) {
                        // 0.5.37: the constructor path had to be abandoned — libxposed
                        // wants a Method, and Constructor.toMethod() does not exist on the
                        // JDK this compiles against. b() is the catalog accessor and it does
                        // hook (0.5.35 proved it: 41 lookup events, each returning our DTO),
                        // so the list is augmented right here instead of at the source.
                        Object result = chain.proceed();
                        return boseBonded() ? injectBoseCatalogEntry(result, loader) : result;
                    }
                    if ("detailWhitelistLookup".equals(label)) {
                        // The decisive call: a null WhitelistConfigDTO means the page has no
                        // product to render, which is the whole blank-page story.
                        Object result = chain.proceed();
                        Object mac = arity > 0 ? chain.getArg(0) : null;
                        boolean isBoseTarget = mac instanceof String
                                && isTargetAddress((String) mac);
                        MLog.event("bose.detail.whitelist",
                                "mac", String.valueOf(mac),
                                "config", result == null ? "NULL" : result.getClass().getSimpleName(),
                                "bose", isBoseTarget);
                        if (isBoseTarget) pendingDetailMac = (String) mac;
                        if (result == null && isBoseTarget) {
                            // The catalog entry is added in whitelistConfigList. If the match
                            // still fails, the entry did not satisfy the matcher — report it
                            // rather than fabricating a DTO here, so the next round tells us
                            // what the matcher actually compared.
                            MLog.event("bose.detail.whitelist.still_null",
                                    "injected", boseCatalogInjected,
                                    "mac", String.valueOf(mac));
                        }
                        return result;
                    }
                    if ("detailFragmentCreated".equals(label)) {
                        Object result = chain.proceed();
                        Object frag = chain.getThisObject();
                        MLog.event("bose.detail.fragment",
                                "class", frag == null ? "null"
                                        : frag.getClass().getSimpleName(),
                                "args", String.valueOf(describeArgs(chain, arity)));
                        return result;
                    }
                    if ("detailActivityCreate".equals(label)) {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof Activity) {
                            detailActivity = (Activity) chain.getThisObject();
                            // Local final alias: the delayed lambdas below capture it, and a
                            // field reference would work too but this keeps the compiler
                            // honest about the capture (0.5.26 referenced an 'activity'
                            // variable that only existed as a field name pattern).
                            final Activity created = detailActivity;
                            requestSonyBatteryRefresh();
                            // Snapshot the container right after onCreate. If A() never
                            // runs at all, this is the only evidence we get.
                            reportDetailContainer("after_onCreate");
                            // 0.5.25 found the actual cause. The content IS built — the decor
                            // tree shows NestedScrollView > LinearLayout > two full-screen
                            // children — but BOTH of those children are View.GONE (vis=8),
                            // so nothing is drawn. Every other signal looked healthy: token
                            // valid, shown=true, not finishing, attached to the window.
                            //
                            // Bose has no catalog entry and the page responds to that by
                            // hiding the rows it cannot populate. Un-hiding them is safe: a
                            // row with no data renders empty rather than crashing.
                            fillDetailRowSummaries(created);
                            for (long delay : new long[]{300L, 800L, 2000L, 5000L}) {
                                mainHandler.postDelayed(() -> {
                                    fillDetailRowSummaries(created);
                                    reportDetailContainer("t+" + delay);
                                }, delay);
                            }
                        }
                        return result;
                    }
                    if ("detailPreferenceHostCreated".equals(label)) {
                        Object result = chain.proceed();
                        schedulePreferenceFragmentBinding(chain.getThisObject());
                        return result;
                    }
                    if ("detailPreferenceFragmentViewCreated".equals(label)) {
                        Object result = chain.proceed();
                        scheduleDirectPreferenceFragmentBinding(chain.getThisObject());
                        return result;
                    }
                    if ("noiseReductionItemDataChanged".equals(label)) {
                        Object result = chain.proceed();
                        lastAudioPreferenceAnchor = chain.getThisObject();
                        log(Log.INFO, TAG, event("Melody native ANC LiveData callback completed"));
                        return result;
                    }
                    if ("nativeNoiseReductionClick".equals(label)) {
                        int modeIndex = readNativeNoiseReductionMode(chain.getThisObject(), chain.getArg(0));
                        String address = readNativeNoiseReductionAddress(chain.getThisObject());
                        if (isTargetAddress(address) && modeIndex >= 0) {
                            log(Log.INFO, TAG, event("intercepted native ANC click mode=" + modeIndex));
                            dispatchCustomAncWrite(modeIndex, method.getDeclaringClass().getClassLoader());
                            return null;
                        }
                        return chain.proceed();
                    }
                    if ("repositoryDtoBuild".equals(label)) {
                        Object result = chain.proceed();
                        projectSonyAncModeIntoDto(chain.getArg(0), result);
                        projectBoseBatteryIntoDto(chain.getArg(0), result);
                        projectBoseSpatialIntoDto(chain.getArg(0), result);
                        projectBoseMasterTuningIntoDto(chain.getArg(0), result);
                        return result;
                    }
                    if ("melodyEarphoneLiveDataResponse".equals(label)
                            && melodyEarphoneLiveData) {
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response dispatching"));
                        Object result = chain.proceed();
                        Object value = readLiveDataValue(chain.getThisObject());
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response published value="
                                + describe(value)));
                        return result;
                    }
                    if ("melodyEarphoneLiveDataRequest".equals(label)
                            && melodyEarphoneLiveData) {
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData request dispatching"));
                        return chain.proceed();
                    }
                    if ("opsReductionItemBound".equals(label)
                            || "opsReductionSwitchToCurrentMode".equals(label)
                            || "opsReductionUpdateActionView".equals(label)) {
                        Object result = chain.proceed();
                        log(Log.INFO, TAG, event("Melody OPS ANC UI " + label + " completed"));
                        return result;
                    }
                    if ("opsReductionApplyMode".equals(label)) {
                        int modeIndex = readSelectedNoiseReductionMode(chain.getThisObject());
                        String address = readNoiseReductionAddress(chain.getThisObject());
                        log(Log.INFO, TAG, event("ANC apply UI entry mode=" + modeIndex
                                + " target=" + isTargetAddress(address)));
                        detailAncWriteObserved.set(false);
                        Object result;
                        try {
                            result = chain.proceed();
                        } finally {
                            Boolean observed = detailAncWriteObserved.get();
                            detailAncWriteObserved.remove();
                            if (!isPrimaryProcess() && !Boolean.TRUE.equals(observed)
                                    && isTargetAddress(address) && modeIndex >= 0) {
                            log(Log.INFO, TAG, event("forwarding ANC from confirmed detail UI entry mode="
                                    + modeIndex));
                            forwardSonyNoiseWrite(modeIndex, method.getDeclaringClass().getClassLoader());
                            }
                        }
                        return result;
                    }
                    if ("nativeConnectDevice".equals(label) && isTargetDeviceInfo(chain.getArg(0))) {
                        if (!startSonyConnection(chain.getArg(0))) {
                            return chain.proceed();
                        }
                        log(Log.WARN, TAG, event("bypassed OPPO E7.c.b/C7.b for registered Sony device"));
                        return null;
                    }
                    if ("directConnectSpp".equals(label) && isTargetDeviceInfo(chain.getArg(0))) {
                        boolean connect = chain.getArg(1) instanceof Boolean && (Boolean) chain.getArg(1);
                        if (connect) {
                            if (!startSonyConnection(chain.getArg(0))) {
                                return chain.proceed();
                            }
                        } else {
                            releaseSonySession("Melody requested Sony disconnect");
                        }
                        log(Log.WARN, TAG, event("bypassed OPPO m_spp_le for registered Sony device"));
                        return null;
                    }
                    if ("noiseModeWrite".equals(label) && isTargetAddress(chain.getArg(1))) {
                        detailAncWriteObserved.set(true);
                        if (isPrimaryProcess()) {
                            log(Log.INFO, TAG, event("intercepted Sony ANC mode write index=" + chain.getArg(0)));
                            return startSonyNoiseWriteFuture(chain.getArg(0), method.getDeclaringClass().getClassLoader());
                        }
                        log(Log.INFO, TAG, event("forwarding Sony ANC mode write to primary process index="
                                + chain.getArg(0)));
                        return forwardSonyNoiseWrite(chain.getArg(0), method.getDeclaringClass().getClassLoader());
                    }
                    if ("noiseWrite".equals(label) && isTargetAddress(chain.getArg(1))) {
                        if (hasPendingNoiseWrite()) {
                            log(Log.INFO, TAG, event("ignored duplicate Sony noise update while ANC write is pending"));
                        } else if ((sonyTransport.isConnected() || samsungTransport.isConnected() || huaweiTransport.isConnected()
                                || (xiaomiTransport != null && xiaomiTransport.isConnected()) || boseHostConnected)
                                && startSonyNoiseWrite(chain.getArg(2))) {
                            log(Log.INFO, TAG, event("routed target noise reduction write to vendor RFCOMM"));
                        } else {
                            log(Log.WARN, TAG, event("blocked target noise reduction write until Sony transport is ready"));
                        }
                        return null;
                    }
                    Object deviceName = arity > 2 ? chain.getArg(2) : null;
                    if ("whitelist".equals(label) && deviceName instanceof String
                            && (isRegisteredSonyName((String) deviceName)
                            || isRegisteredHuaweiName((String) deviceName)
                            || isRegisteredXiaomiName((String) deviceName)
                            || isRegisteredBoseName((String) deviceName)
                            || isBoseAddressArg(chain.getArg(0))
                            || isBoseAddressArg(chain.getArg(1)))) {
                        activeSonyImageProfile = findSonyProfileByName((String) deviceName);
                        activeHuaweiImageProfile = findHuaweiProfileByName((String) deviceName);
                        activeXiaomiImageProfile = findXiaomiProfileByName((String) deviceName);
                        Object profile = findProfile(chain.getArg(0), DeviceProfileMapper.SONY_TEST_PROFILE_ID, DeviceProfileMapper.SONY_TEST_PROFILE_NAME);
                        if (profile != null) {
                            Object mappedProfile = copyWithoutAncStrengthModes(profile);
                            if (mappedProfile != null) {
                                log(Log.WARN, TAG, event("mapping registered device " + deviceName
                                        + " to sanitized OPPO Enco X3 id=067410"));
                                return mappedProfile;
                            }
                            // The preference-level suppressor remains a fail-closed fallback for
                            // host versions whose whitelist DTO cannot be copied reflectively.
                            log(Log.WARN, TAG, event("mapping registered device " + deviceName
                                    + " to OPPO Enco X3 id=067410; DTO strength filtering unavailable"));
                            return profile;
                        }
                        log(Log.ERROR, TAG, "OPPO Enco X3 profile not found; preserving original result");
                    }
                    Object result = chain.proceed();
                    if ("deviceRegistryGet".equals(label) && chain.getArg(0) instanceof BluetoothDevice) {
                        BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                        if (isTargetDevice(device)) {
                            if (result == null) {
                                result = registerTargetDevice(chain.getThisObject(), device);
                            }
                        }
                    }
                    if (isTargetObject(chain.getThisObject())) {
                        if ("dtoConnectionState".equals(label) && isSonyConnected()) {
                            result = 2;
                        } else if ("dtoAclState".equals(label) && isSonyConnected()) {
                            result = 2;
                        } else if ("dtoInitCompleted".equals(label)) {
                            result = isSonyConnected();
                        } else if ("dtoNoiseReductionMode".equals(label)) {
                            EarbudsState state = sonySessionState.getAnc();
                            int mode = state == null ? readSharedSonyModeIndex()
                                    : MelodyStateBridge.INSTANCE.ancModeIndex(state);
                            if (mode >= 0) result = mode;
                        }
                    }
                    if (isDetailConnectionInfoObject(chain.getThisObject()) && isSonyConnected()) {
                        if (label.startsWith("detailInfoConnectionState")
                                || label.startsWith("detailInfoHeadsetState")) {
                            result = 2;
                        } else if (label.startsWith("detailInfoSupportSpp")) {
                            result = true;
                        }
                    }
                    if (("opsNoiseReductionMode".equals(label) || "noiseReductionModeVO".equals(label))
                            && isSonyConnected()) {
                        EarbudsState state = sonySessionState.getAnc();
                        int mode = state == null ? readSharedSonyModeIndex()
                                : MelodyStateBridge.INSTANCE.ancModeIndex(state);
                        if (mode >= 0) result = mode;
                    }
                    if (traceCall || result != null && "deviceRegistryGet".equals(label)) {
                        log(Log.INFO, TAG, event(label + " after result=" + describe(result)));
                    }
                    return result;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, label + " original threw", t);
                    throw t;
                }
            });
            log(Log.INFO, TAG, event("hooked " + label + " " + signature(method)));
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + label + " in " + className, t);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private Object registerTargetDevice(Object manager, BluetoothDevice device) {
        try {
            Class<?> managerClass = manager.getClass();
            Method create = managerClass.getDeclaredMethod("f", int.class, BluetoothDevice.class, String.class, String.class);
            create.setAccessible(true);
            Object info = create.invoke(null, WF_1000XM3_PRODUCT_ID, device, device.getAddress(), device.getName());
            Method add = managerClass.getDeclaredMethod("c", info.getClass());
            add.setAccessible(true);
            add.invoke(manager, info);
            log(Log.WARN, TAG, event("registered Sony DeviceInfo through Melody manager"));
            return info;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Sony DeviceInfo registration failed", t);
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean shouldTrace(String label, XposedInterface.Chain chain, int arity) {
        if ("whitelist".equals(label)) {
            return arity > 2 && chain.getArg(2) instanceof String
                    && isRegisteredSonyName((String) chain.getArg(2));
        }
        if ("deviceRegistryLookup".equals(label)) {
            Object address = chain.getArg(0);
            return address instanceof String && targetAddressHash != 0 && address.hashCode() == targetAddressHash;
        }
        if ("deviceRegistryGet".equals(label) && chain.getArg(0) instanceof BluetoothDevice) {
            return isTargetDevice((BluetoothDevice) chain.getArg(0));
        }
        if (label.startsWith("dto")) {
            return isTargetObject(chain.getThisObject());
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private boolean startSonyConnection(Object deviceInfo) {
        if (!initializeSonyConfig()) {
            log(Log.WARN, TAG, event("Sony connection skipped: configuration is not ready"));
            return false;
        }
        try {
            Method addressGetter = deviceInfo.getClass().getMethod("getDeviceAddress");
            Object address = addressGetter.invoke(deviceInfo);
            Method deviceGetter = deviceInfo.getClass().getMethod("getDevice");
            Object device = deviceGetter.invoke(deviceInfo);
            if (!(address instanceof String) || !(device instanceof BluetoothDevice)
                    || !isTargetDevice((BluetoothDevice) device)) {
                log(Log.WARN, TAG, event("Sony connection skipped: DeviceInfo has no registered BluetoothDevice"));
                return false;
            }
            if (isRegisteredXiaomiDevice((BluetoothDevice) device)) {
                targetXiaomiDevice = (BluetoothDevice) device;
                xiaomiHostConnected = true;
                rememberTargetAddress((String) address);
                // The foreground process cannot see this process's in-memory host marker.
                // Publish it before AF00 setup, since A2DP/HFP is already connected here.
                writeSharedXiaomiState();
                XiaomiEarbudsFacade transport = ensureXiaomiTransport();
                if (transport == null) return false;
                log(Log.INFO, TAG, event("starting Xiaomi SPP session name=" + ((BluetoothDevice) device).getName()
                        + " addressHash=" + Integer.toHexString(((String) address).hashCode())));
                transport.connect((BluetoothDevice) device);
                return true;
            }
            if (isBoseDevice((BluetoothDevice) device)) {
                targetBoseDevice = (BluetoothDevice) device;
                boseHostConnected = true;
                rememberTargetAddress((String) address);
                writeSharedBoseState();
                log(Log.INFO, TAG, event("starting Bose BMAP session name=" + ((BluetoothDevice) device).getName()
                        + " addressHash=" + Integer.toHexString(((String) address).hashCode())));
                boseTransport.connect((BluetoothDevice) device);
                return true;
            }
            if (isRegisteredHuaweiDevice((BluetoothDevice) device)) {
                targetHuaweiDevice = (BluetoothDevice) device;
                rememberTargetAddress((String) address);
                log(Log.INFO, TAG, event("starting Huawei session name=" + ((BluetoothDevice) device).getName()
                        + " addressHash=" + Integer.toHexString(((String) address).hashCode())));
                huaweiTransport.connect((BluetoothDevice) device);
                return true;
            }
            if (isRegisteredSamsungDevice((BluetoothDevice) device)) {
                targetSamsungDevice = (BluetoothDevice) device;
                rememberTargetAddress((String) address);
                log(Log.INFO, TAG, event("starting Samsung session name=" + ((BluetoothDevice) device).getName()
                        + " addressHash=" + Integer.toHexString(((String) address).hashCode())));
                samsungTransport.connect((BluetoothDevice) device);
                return true;
            }
            String bluetoothAddress = ((BluetoothDevice) device).getAddress();
            if (!((String) address).equalsIgnoreCase(bluetoothAddress)) {
                log(Log.WARN, TAG, event("Sony connection skipped: DeviceInfo address does not match BluetoothDevice"));
                return false;
            }
            rememberTargetAddress(bluetoothAddress);
            targetSonyDevice = (BluetoothDevice) device;
            // The adapter suppresses duplicate connects. Re-publish here so :fg can recover
            // a live session whose marker was lost before this repeated native connection call.
            if (sonyTransport.isConnected()) {
                writeSharedSonyState();
                log(Log.INFO, TAG, event("republished existing Sony RFCOMM session addressHash="
                        + Integer.toHexString(bluetoothAddress.hashCode())));
            }
            log(Log.INFO, TAG, event("starting Sony session name=" + ((BluetoothDevice) device).getName()
                    + " addressHash=" + Integer.toHexString(bluetoothAddress.hashCode())));
            sonyTransport.connect((BluetoothDevice) device);
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Sony connection setup failed", t);
            return false;
        }
    }

    private boolean isTargetDeviceInfo(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getDevice");
            Object device = getter.invoke(value);
            return device instanceof BluetoothDevice
                    && isTargetDevice((BluetoothDevice) device)
                    && isA2dpConnected(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isA2dpConnected(Object deviceInfo) {
        Object state = readField(deviceInfo, "mDeviceA2dpConnectState");
        return state instanceof Number && ((Number) state).intValue() == 2;
    }

    private boolean isTargetAddress(Object value) {
        if (!(value instanceof String)) return false;
        String address = (String) value;
        if (targetAddress != null && targetAddress.equalsIgnoreCase(address)) return true;

        String sharedAddress = readSharedSonyAddress();
        if (sharedAddress != null && sharedAddress.equalsIgnoreCase(address)) {
            rememberTargetAddress(address);
            return true;
        }
        return false;
    }

    private boolean startSonyNoiseWrite(Object dto) {
        if (dto == null) return false;
        try {
            Method modeInfo = dto.getClass().getMethod("isNoiseReductionModeInfo");
            if (!(modeInfo.invoke(dto) instanceof Boolean) || !((Boolean) modeInfo.invoke(dto))) {
                return false;
            }
            Method valueGetter = dto.getClass().getMethod("getValue");
            Object value = valueGetter.invoke(dto);
            if (!(value instanceof Integer)) return false;
            int modeIndex = (Integer) value;
            com.melody.melodylink.domain.AncMode domainMode = MelodyCommandBridge.INSTANCE.ancMode(modeIndex);
            if (domainMode == null) return false;
            if (targetXiaomiDevice != null && isRegisteredXiaomiDevice(targetXiaomiDevice)) {
                XiaomiEarbudsFacade transport = ensureXiaomiTransport();
                if (transport != null) transport.setAncMode(domainMode);
            } else if (targetBoseDevice != null && boseHostConnected) {
                boseTransport.setAncMode(domainMode);
            } else if (targetHuaweiDevice != null && isRegisteredHuaweiDevice(targetHuaweiDevice)) {
                huaweiTransport.setAncMode(domainMode);
            } else if (targetSamsungDevice != null && isRegisteredSamsungDevice(targetSamsungDevice)) {
                samsungTransport.setAncMode(domainMode);
            } else {
                sonyTransport.setAncMode(domainMode);
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony noise reduction mapping failed", t);
            return false;
        }
    }

    private Object startSonyNoiseWriteFuture(Object rawIndex, ClassLoader loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!(rawIndex instanceof Integer)) {
            future.completeExceptionally(new IllegalArgumentException("invalid Sony ANC mode index"));
            return future;
        }
        com.melody.melodylink.domain.AncMode domainMode = MelodyCommandBridge.INSTANCE.ancMode((Integer) rawIndex);
        if (domainMode == null) {
            future.completeExceptionally(new IllegalArgumentException("unsupported Sony ANC mode index"));
            return future;
        }
        synchronized (this) {
            CompletableFuture<Object> previous = pendingNoiseWrite;
            if (previous != null && !previous.isDone()) {
                previous.completeExceptionally(new IllegalStateException("Sony ANC write superseded"));
            }
            melodyClassLoader = loader;
            pendingNoiseWrite = future;
            pendingAncMode = domainMode;
            pendingBatteryRefresh = false;
        }
        if (targetXiaomiDevice != null && isRegisteredXiaomiDevice(targetXiaomiDevice)
                && ensureXiaomiTransport() != null && ensureXiaomiTransport().isConnected()) {
            pendingAncMode = null;
            ensureXiaomiTransport().setAncMode(domainMode);
        } else if (targetBoseDevice != null && boseHostConnected) {
            pendingAncMode = null;
            // Bose BMAP runs a serial short-session queue (battery refresh may be ahead
            // of this write); waiting for the device reply makes the UI time out and
            // toast a false "switch failed". Complete optimistically now — the icon
            // projection reads our mirrored state, and onAncWriteResult still syncs
            // the confirmed value (or logs a real failure) when the session lands.
            EarbudsState optimistic = new EarbudsState(
                    com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getCapabilities(),
                    domainMode, new java.util.HashMap<>());
            boseSessionState.acceptAnc(optimistic);
            sonySessionState.acceptAnc(optimistic);
            BoseControlProviderBridge.refreshTile();
            boseTransport.setAncMode(domainMode);
            Object result = createSetCommandState(0);
            if (result != null) future.complete(result);
            else future.completeExceptionally(new IllegalStateException("Bose ANC result DTO unavailable"));
        } else if (targetHuaweiDevice != null && isRegisteredHuaweiDevice(targetHuaweiDevice)
                && huaweiTransport.isConnected()) {
            pendingAncMode = null;
            huaweiTransport.setAncMode(domainMode);
        } else if (sonyTransport.isConnected()) {
            runPendingSonyOperation();
        } else if (!connectTargetSonyTransport("ANC command")) {
            pendingAncMode = null;
            failPendingNoiseWrite("ANC command cannot start: target Bluetooth device is unavailable");
        }
        return future;
    }

    private Object forwardSonyNoiseWrite(Object rawIndex, ClassLoader loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!(rawIndex instanceof Integer)) {
            future.completeExceptionally(new IllegalArgumentException("invalid Sony ANC mode index"));
            return future;
        }
        com.melody.melodylink.domain.AncMode domainMode = MelodyCommandBridge.INSTANCE.ancMode((Integer) rawIndex);
        String address = targetAddress;
        if (domainMode == null || address == null) {
            future.completeExceptionally(new IllegalStateException("Sony ANC bridge is not ready"));
            return future;
        }
        melodyClassLoader = loader;
        if (!writeSharedSonyCommand(address, (Integer) rawIndex)) {
            future.completeExceptionally(new IllegalStateException("Sony ANC bridge command failed"));
            return future;
        }
        Object result = createSetCommandState(0);
        if (result == null) {
            future.completeExceptionally(new IllegalStateException("Sony ANC result DTO unavailable"));
        } else {
            future.complete(result);
        }
        return future;
    }

    private void removeUnsupportedDetailCategory(Object preference) {
        if (preference == null) return;
        try {
            Method getTitle = preference.getClass().getMethod("getTitle");
            Object title = getTitle.invoke(preference);
            if (!DetailSectionFilter.shouldSuppressCategory(
                    preference.getClass().getName(),
                    title instanceof CharSequence ? (CharSequence) title : null)) {
                return;
            }
            Method getParent = preference.getClass().getMethod("getParent");
            Object parent = getParent.invoke(preference);
            if (parent == null) return;
            ClassLoader loader = preference.getClass().getClassLoader();
            Class<?> preferenceType = Class.forName("androidx.preference.Preference", false, loader);
            Method remove = parent.getClass().getMethod("j", preferenceType);
            remove.invoke(parent, preference);
            log(Log.INFO, TAG, event("removed unsupported Oppo-only detail category from PreferenceGroup"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "native detail category removal failed", t);
        }
    }

    private void dispatchCustomAncWrite(int modeIndex, ClassLoader loader) {
        if (isPrimaryProcess()) {
            startSonyNoiseWriteFuture(modeIndex, loader);
        } else {
            forwardSonyNoiseWrite(modeIndex, loader);
        }
    }

    private synchronized boolean initializeSonyConfig() {
        if (sonyConfigInitialized) return true;
        try {
            Application application = currentApplication();
            if (application == null) {
                log(Log.WARN, TAG, event("Sony configuration unavailable: target application not ready"));
                return false;
            }
            sharedStateStore = MelodySharedStateStore.from(application);
            ApplicationInfo moduleInfo = getModuleApplicationInfo();
            String moduleApkPath = moduleInfo.sourceDir;
            if (moduleApkPath == null || moduleApkPath.isEmpty()) {
                log(Log.ERROR, TAG, event("Sony configuration unavailable: module APK path is empty"));
                return false;
            }
            AssetManager moduleAssets = application.getAssets();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            Object cookie = addAssetPath.invoke(moduleAssets, moduleApkPath);
            if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
                log(Log.ERROR, TAG, event("Sony configuration unavailable: cannot open module APK assets"));
                return false;
            }
            SonyConfigLoadResult result = SonyConfigLoader.INSTANCE.fromAssets(moduleAssets);
            HuaweiConfigLoadResult huaweiResult = HuaweiConfigLoader.INSTANCE.fromAssets(moduleAssets);
            XiaomiConfigLoadResult xiaomiResult = XiaomiConfigLoader.INSTANCE.fromAssets(moduleAssets);
            sonyTransport.setCatalog(new SonyDeviceCatalogAdapter(result.getRegistry()));
            deviceBridge.setRegistry(result.getRegistry());
            HuaweiDeviceCatalog.INSTANCE.setRegistry(huaweiResult.getRegistry());
            XiaomiDeviceCatalog.INSTANCE.setRegistry(xiaomiResult.getRegistry());
            registerXiaomiBatteryReceiver(application);
            sonyModuleAssets = moduleAssets;
            for (SonyConfigIssue issue : result.getIssues()) {
                log(Log.WARN, TAG, event("Sony configuration skipped " + issue.getPath()
                        + ": " + issue.getMessage()));
            }
            for (HuaweiConfigIssue issue : huaweiResult.getIssues()) {
                log(Log.WARN, TAG, event("Huawei configuration skipped " + issue.getPath()
                        + ": " + issue.getMessage()));
            }
            for (XiaomiConfigIssue issue : xiaomiResult.getIssues()) {
                log(Log.WARN, TAG, event("Xiaomi configuration skipped " + issue.getPath()
                        + ": " + issue.getMessage()));
            }
            sonyConfigInitialized = true;
            log(Log.INFO, TAG, event("loaded " + result.getRegistry().getProfiles().size()
                    + " Sony, " + huaweiResult.getRegistry().getProfiles().size()
                    + " Huawei and " + xiaomiResult.getRegistry().getProfiles().size()
                    + " Xiaomi device profiles from " + moduleApkPath));
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Sony configuration initialization failed", t);
            return false;
        }
    }

    /**
     * Injection entry point for the earbud detail page.
     *
     * <p>Melody's "降噪效果" row is the anchor. It is identified structurally (by its
     * Preference key) rather than by its R8-hashed class name, because the host renames those
     * between releases while the key survives — the same approach
     * Andrea-lyz/MelodyCodecTweaker uses for its {@code HiQualityAudioItem} / {@code
     * EqualizerItem} anchors.
     *
     * <p>Injection is <em>not</em> done inline in {@code addPreference}. Racing the host's own
     * tree construction is what left the 0.5.x detail page blank: the rows were added while
     * COUI was still measuring, and the resulting order collisions made the RecyclerView
     * produce nothing. Instead we record the anchor and run a bounded retry loop that waits
     * for a live screen, then inserts with a proper order shift.
     */
    private void captureNoiseEffectRow(Object preference) {
        if (preference == null || !boseBonded()) return;
        // 0.5.10 evidence: evt=bose.anchor.captured fired for COUIMenuPreference,
        // COUIJumpPreference, OneSpaceDisconnectPreference and finally
        // footer_preference, and the last one won. detailPreferenceAdd runs for EVERY
        // preference the host adds, so without this guard the anchor ends up being
        // whichever preference was added last — the page footer, which has no children
        // (evt=bose.detail.state total=0). The filter was lost in the 0.5.7 rewrite.
        if (!isBoseNoiseRow(preference)) return;
        // Do NOT skip when the instance is unchanged: 0.5.12 showed the retry loop
        // stopping after the very first tick, so a page whose row was captured before
        // the loop was armed never got another chance. Re-arming is cheap — every
        // install path is guarded by findPreferenceByKeyRecursive.
        if (NOISE_ROW_CLASS_ONESPACE.equals(preference.getClass().getName())) {
            oneSpaceNoiseEffectRow = preference;
        } else {
            noiseEffectRow = preference;
        }
        MLog.event("bose.anchor.captured",
                "class", preference.getClass().getSimpleName(),
                "key", PrefRef.getKey(preference),
                "thread", Thread.currentThread().getName());
        // Run one attempt inline as well: if the posted retry never fires we still
        // learn where it stops, and the install path is idempotent.
        try {
            boolean done = installBoseIntoLiveScreen();
            MLog.event("bose.inject.inline",
                    "ok", done,
                    "bonded", boseBonded(),
                    "parent", describeParent());
        } catch (Throwable t) {
            MLog.event("bose.inject.inline_error", "error", MLog.compactThrowable(t));
        }
        scheduleBoseInjection(0);
    }

    /**
     * Bounded back-off retry. Melody's PreferenceScreen is assembled asynchronously (the
     * first-launch WhitelistConfig path involves a network round trip), so a single shot at
     * onCreatePreferences time is a race; but retrying forever would fight the host. Fifteen
     * attempts over ~15 s covers the observed spread, then we give up and say so.
     */
    private void scheduleBoseInjection(int attempt) {
        mainHandler.postDelayed(() -> {
            try {
                if (!boseBonded()) return;
                MLog.event("bose.retry.tick", "attempt", attempt,
                "bonded", boseBonded(),
                "injected", isBoseInjected(),
                "anchor_class", noiseEffectRow == null ? "null"
                        : noiseEffectRow.getClass().getSimpleName());
        if (isBoseInjected()) return;
                if (installBoseIntoLiveScreen()) {
                    scheduleBoseInjectionRecheck(attempt);
                    return;
                }
            } catch (Throwable t) {
                MLog.event("bose.inject.error", "error", MLog.compactThrowable(t));
                return;
            }
            if (attempt == 2 || attempt == 7) reportDetailPageState(attempt);
            if (attempt < 14) {
                scheduleBoseInjection(attempt + 1);
            } else {
                MLog.event("bose.inject.exhausted", "attempts", attempt + 1);
                reportDetailPageState(attempt);
            }
        }, attempt == 0 ? 200L : 1000L);
    }

    private void scheduleBoseInjectionRecheck(int attempt) {
        if (attempt >= 14) return;
        mainHandler.postDelayed(() -> {
            try {
                if (boseBonded() && isBoseInjected()) scheduleBoseInjection(attempt + 1);
            } catch (Throwable ignored) {
            }
        }, 1000L);
    }

    /** True once any of our keys is present on the live screen. */
    private boolean isBoseInjected() {
        Object screen = boseLiveScreen();
        if (screen == null) return false;
        return PrefRef.findPreferenceRecursive(screen, BOSE_CNC_KEY) != null
                || PrefRef.findPreferenceRecursive(screen, BOSE_CNC_CARD_KEY) != null;
    }

    /** The PreferenceScreen of the fragment currently hosting our anchor. */
    private Object boseLiveScreen() {
        try {
            // Scan the whole set: it holds screens from both pages, and picking
            // iterator().next() would report "already injected" for the detail page just
            // because 通用设置 was injected first.
            for (Object screen : boseInjectedScreens) {
                if (screen == null) continue;
                if (PrefRef.getPreferenceCount(screen) <= 0) continue;
                return screen;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Performs the actual insertion against a live tree. Returns false when the tree is not
     * ready yet so the caller can retry.
     */
    /**
     * Chooses the anchor to inject against: the first one still attached to a window.
     *
     * <p>0.5.20 traced the blank page to this. A single shared anchor field let the two
     * pages overwrite each other's reference, so a retry tick armed by 通用设置 could fire
     * while holding the detail page's row and write into the wrong tree. The observed
     * signature was a page that rendered for about a second (populated NestedScrollView
     * with the device-info block and the 1356x1356 model view) and then went back to an
     * empty container.
     */
    private Object pickLiveAnchor() {
        for (Object candidate : new Object[]{noiseEffectRow, oneSpaceNoiseEffectRow}) {
            if (candidate == null) continue;
            try {
                Object context = PrefRef.invokeNoArg(candidate, "getContext");
                if (!(context instanceof View)) continue;
                View view = (View) context;
                if (view.isAttachedToWindow()) return candidate;
            } catch (Throwable ignored) {
            }
        }
        return noiseEffectRow != null ? noiseEffectRow : oneSpaceNoiseEffectRow;
    }

    private boolean installBoseIntoLiveScreen() {
        // Pick the anchor that is actually attached to a live window. Both pages keep
        // their own copy now, and the detail page is the one with a fragment that can
        // disappear mid-flight, so prefer whichever is still showing; fall back to the
        // detail anchor because that is the page that renders nothing without us.
        Object noiseRow = pickLiveAnchor();
        if (noiseRow == null) return false;

        ClassLoader loader = noiseRow.getClass().getClassLoader();
        // 0.5.11: this used to bail out when no PreferenceScreen had been located yet,
        // but the screen set was only populated on a successful install — so every
        // attempt failed at this line and evt=bose.injected never fired. The anchor's
        // own parent group is all we actually need; the screen is only bookkeeping.
        // Resolve the screen from THIS anchor rather than from the shared set, which
        // holds entries from both pages and whose iterator().next() could hand back the
        // other page's screen. The screen is bookkeeping only — the anchor's own parent
        // group is what we insert into.
        Object screen = screenForAnchor(noiseRow);

        Object parent = PrefRef.getParent(noiseRow);
        if (parent == null) parent = screen;
        if (parent == null) {
            MLog.event("bose.inject.no_parent", "attempt_class",
                    noiseRow.getClass().getSimpleName());
            return false;
        }
        if (screen != null) boseInjectedScreens.add(screen);
        if (PrefRef.findPreferenceRecursive(parent, BOSE_CNC_KEY) != null) return true;

        Object context = PrefRef.invokeNoArg(noiseRow, "getContext");
        if (!(context instanceof Context)) return false;
        Activity activity = findActivity((Context) context);
        if (activity == null) activity = detailActivity;
        if (activity == null) return false;

        boolean detailPage = NOISE_ROW_CLASS_DETAIL.equals(noiseRow.getClass().getName());
        // Hide the Enco-only "降噪效果" row where it is safe to do so. The method itself
        // filters by class: it hides only the 通用设置 copy and leaves the detail page's
        // copy visible, because that one anchors the section it lives in.
        hideNoiseEffectRow(noiseRow);

        int anchorOrder = PrefRef.getOrder(noiseRow);
        int target = anchorOrder < 0 ? 0 : anchorOrder + 1;
        // Make room before inserting, otherwise the new rows collide with the host's own
        // order values and COUI merges or drops entries.
        PrefRef.shiftPreferenceOrders(parent, target, +40);

        boolean ok = addBoseCncPreference(parent, loader, activity, target);
        if (!ok) {
            MLog.event("bose.cnc.add_failed",
                    "parent", parent.getClass().getSimpleName(),
                    "children", PrefRef.getPreferenceCount(parent),
                    "order", target);
            return false;
        }
        if (detailPage) addBoseExtraCategory(parent, loader, activity, target + 10);

        MLog.event("bose.injected",
                "page", detailPage ? "detail" : "general",
                "anchor", PrefRef.getKey(noiseRow),
                "order", target,
                // 0.5.16: screen is legitimately null whenever the anchor's parent chain
                // is the only thing that resolved — which is the normal case since 0.5.11
                // stopped requiring a PreferenceScreen. Dereferencing it here threw an NPE
                // that aborted installBoseIntoLiveScreen *after* the rows were already added,
                // so every run logged nothing past this point even though the insert had
                // succeeded.
                "fragment", screen == null ? "none" : screen.getClass().getSimpleName(),
                "children", PrefRef.getPreferenceCount(parent));
        return true;
    }

    /** Finds the PreferenceScreen by asking the anchor's context for a fragment manager. */
    private Object screenForAnchor(Object anchor) {
        try {
            Object context = PrefRef.invokeNoArg(anchor, "getContext");
            if (!(context instanceof Context)) return null;
            if (!(context instanceof android.app.Activity)) return null;
            Object manager = null;
            for (String name : new String[]{"getSupportFragmentManager", "getFragmentManager"}) {
                manager = PrefRef.invokeNoArg(context, name);
                if (manager != null) break;
            }
            if (manager == null) return null;
            java.util.List<?> fragments = readFragmentList(manager);
            if (fragments == null) return null;
            for (Object fragment : fragments) {
                if (fragment == null) continue;
                Object screen = PrefRef.getPreferenceScreen(fragment);
                if (screen == null) continue;
                if (PrefRef.findPreferenceRecursive(screen, BOSE_CNC_KEY) != null) return screen;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Pulls the fragment list out of a FragmentManager without compile-time androidx. */
    private static java.util.List<?> readFragmentList(Object manager) {
        if (manager == null) return null;
        for (java.lang.reflect.Field f : allFieldsOf(manager.getClass())) {
            if (!java.util.List.class.equals(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(manager);
                if (v instanceof java.util.List) return (java.util.List<?>) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static java.lang.reflect.Field[] allFieldsOf(Class<?> type) {
        java.util.LinkedHashSet<java.lang.reflect.Field> out = new java.util.LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) out.add(f);
        }
        return out.toArray(new java.lang.reflect.Field[0]);
    }

    /** Screens we already injected into; weak so a destroyed Activity is not retained. */
    private final java.util.Set<Object> boseInjectedScreens =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    /**
     * Hides the "降噪效果" row on the earbud detail page.
     *
     * <p>This row is the OPPO/Enco ANC intensity picker — 深度 / 中度 / 轻度 / 智能降噪 —
     * which drives an Enco-only protocol. Bose QC Ultra 2 has no four-level ANC path, so the
     * control cannot do anything for this device. {@code setVisible(false)} is used rather than
     * removing the preference: the row's own LiveData observer still calls
     * {@code onBindViewHolder}, and a removed-but-observed preference crashes the page.
     */
    private void hideNoiseEffectRow(Object noiseRow) {
        try {
            if (noiseRow == null || !boseBonded()) return;
            String className = noiseRow.getClass().getName();
            // Only the 通用设置 (OneSpace) copy is hidden. The detail page's copy
            // (NoiseReductionItem) stays visible: on that page this preference is the
            // anchor the whole surrounding section hangs off, and 0.5.11 measured that
            // hiding it made the entire section stop binding (see below).
            if (!NOISE_ROW_CLASS_ONESPACE.equals(className)) return;
            PrefRef.setVisible(noiseRow, false);
            MLog.event("bose.anco.row.hidden", "key", PrefRef.getKey(noiseRow),
                    "class", className);
        } catch (Throwable t) {
            MLog.event("bose.anco.row.hide_failed", "error", MLog.compactThrowable(t));
        }
    }

    /**
     * Dumps what the earbud detail page actually ended up rendering.
     *
     * <p>Added because 0.5.9 was still reported as a fully blank page while the log showed
     * the host adding ~50 native preferences (firmware update, find-device, privacy, …).
     * A tree that is populated but invisible means a rendering / layout-visibility problem,
     * not an injection problem — the two need completely different fixes, so we log the
     * actual state instead of assuming.
     */
    /** Compact, log-safe rendering of an arbitrary pipeline value. */
    private static String describeValue(Object value) {
        if (value == null) return "null";
        try {
            String text = String.valueOf(value);
            return value.getClass().getSimpleName() + "(" + text + ")";
        } catch (Throwable t) {
            return value.getClass().getName() + "(unprintable)";
        }
    }

    /**
     * Traces the DetailMainActivity content pipeline.
     *
     * <p>17.6.3 {@code DetailMainActivity.A()} is the entire content path:
     * {@code CompletableFuture.supplyAsync(LA9/r;).whenCompleteAsync(LAa/h;, executor)}.
     * {@code onCreate} contains no fragment transaction whatsoever, so if this future fails
     * the {@code melody_ui_fragment_container} is guaranteed to stay empty — which is
     * exactly the 0.5.16 symptom, with every other hook firing normally.
     */
    private void logDetailPipeline(String stage, String... extra) {
        StringBuilder sb = new StringBuilder("bose.pipeline.").append(stage);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            sb.append(' ').append(extra[i]).append('=').append(extra[i + 1]);
        }
        MLog.event(sb.toString());
    }

    /**
     * Reports whether the detail content container actually has children, plus the fragment
     * manager's view of the activity. This separates "fragment never attached" from
     * "fragment attached but empty" — the two look identical in a uiautomator dump but need
     * completely different fixes.
     */
    /**
     * Renders a view subtree as {@code ClassName#resourceName[children,size,vis]}.
     *
     * <p>IDs are the only stable way to tell one row from another, and the child count is
     * what separates "the row exists" from "the row has content" — a preference row with
     * zero children is a real symptom, not an empty container. Depth is capped so a
     * pathological tree cannot spin.
     */
    private static String describeViewTree(View view, int depth) {
        if (view == null) return "null";
        if (depth > 8) return "...";
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(view.getClass().getSimpleName());
            if (view.getId() != View.NO_ID) {
                String name;
                try {
                    name = view.getResources().getResourceEntryName(view.getId());
                } catch (Throwable t) {
                    name = "id" + view.getId();
                }
                sb.append('#').append(name);
            }
            sb.append('[');
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                sb.append(group.getChildCount()).append(" kids");
                int shown = Math.min(group.getChildCount(), 12);
                if (shown > 0) sb.append(", ");
                for (int i = 0; i < shown; i++) {
                    sb.append(describeViewTree(group.getChildAt(i), depth + 1));
                    if (i < shown - 1) sb.append(' ');
                }
                if (group.getChildCount() > shown) sb.append(" +more");
            }
            sb.append(' ').append(view.getWidth()).append('x').append(view.getHeight());
            if (view.getVisibility() != View.VISIBLE) {
                sb.append(" HIDDEN(vis=").append(view.getVisibility()).append(')');
            }
            // 0.5.21: the tree reported a fully populated NestedScrollView while the
            // screen stayed blank and uiautomator saw zero children. That combination
            // means the views belong to a hierarchy that is no longer in the window, so
            // the attach state and the owning context are the discriminating facts.
            if (!view.isAttachedToWindow()) sb.append(" DETACHED");
            float alpha = view.getAlpha();
            if (alpha <= 0.01f) sb.append(" ALPHA=").append(alpha);
            sb.append(']');
        } catch (Throwable t) {
            sb.append("[unreadable: ").append(t.getClass().getSimpleName()).append(']');
        }
        return sb.toString();
    }

    private static volatile String pendingDetailMac;
    /** One-shot guard so the Bose catalog entry is added exactly once per process. */
    private static volatile boolean boseCatalogInjected;

    /**
     * Builds a minimal {@code WhitelistConfigDTO} so Melody's own detail page has something
     * to render for a device that is not in its catalog.
     *
     * <p>17.6.3 {@code DetailMainActivity.A()} branch {@code 0x12} does:
     * <pre>
     *   mac  = device extra -> device_mac_info -> SharedPreferences launcher_address
     *   cfg  = c9/a.f().a(mac)          // WhitelistConfigDTO
     *   pair = Pair.create(mac, cfg)
     * </pre>
     * For Bose the lookup returns null, and every downstream consumer then has no product
     * to build from. The DTO has one 16-argument constructor and a no-arg one; we fill the
     * fields the page actually reads ({@code id}, {@code name}, {@code brand}, {@code uuid},
     * {@code type}) and leave the tunables at sane defaults.
     *
     * <p>If construction fails we return null and the page stays exactly as it is now —
     * this is a best-effort fallback, never a new failure mode.
     */
    /**
     * Appends a Bose entry to Melody's product catalog.
     *
     * <p>0.5.32. The detail page builds its sections by matching the connected device against
     * the catalog: {@code L6/a.a(mac)} reads {@code L6/a.b()} — a
     * {@code List<WhitelistConfigDTO>} built from {@code WhitelistContentDO} — and returns the
     * matching entry. Bose has no entry, so nothing matches and no section is ever created.
     * 0.5.31's runtime dump confirmed it: the container held only seven classes, all of them
     * part of the device-info header, and not a single preference row.
     *
     * <p>Rather than fabricate a DTO from its 16-argument constructor (0.5.29, which produced
     * something the host could not use), this clones a real entry from the list — so all the
     * fields the host actually reads (function flags, protocol type, Rssi thresholds, brand
     * colour, version gates) carry the values of a device Melody genuinely supports. Only the
     * identifying fields are rewritten to Bose. That is what makes the host build its own
     * sections instead of us drawing them.
     *
     * <p>Per the user's steer: target Enco X4, not an older model, so there is no need to
     * dodge the X3-era obfuscation.
     */
    /**
     * Replaces {@code SupportConfigManager}'s catalog provider with a proxy that appends a
     * Bose entry to whatever the real provider returns.
     *
     * <p>0.5.33. {@code L6/a} is {@code SupportConfigManager}: a final class whose
     * {@code b()} and {@code a(String)} are both public final, and libxposed refuses to hook
     * final methods — 0.5.32 reported "cannot hook" for both even though smali lists them
     * plainly. The constructor is not final, so hooking it works, and field {@code a} is the
     * {@code com.oplus.melody.common.util.E} provider that {@code b()} reads the catalog
     * from. Interposing there sidesteps the final methods entirely.
     *
     * <p>The proxy forwards everything untouched except the catalog call, whose result it
     * augments. If anything about the provider shape is unexpected it silently passes
     * through, so the worst case is the behaviour before this change.
     */
    private void wrapCatalogProvider(Object manager) {
        if (manager == null || boseCatalogInjected) return;
        try {
            java.lang.reflect.Field field = null;
            for (Class<?> c = manager.getClass(); c != null && field == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals("com.oplus.melody.common.util.E")) {
                        field = f;
                        break;
                    }
                }
            }
            if (field == null) {
                MLog.event("bose.catalog.no_provider", "class", manager.getClass().getName());
                return;
            }
            field.setAccessible(true);
            Object provider = field.get(manager);
            if (provider == null) {
                MLog.event("bose.catalog.provider_null");
                return;
            }
            if (java.lang.reflect.Proxy.isProxyClass(provider.getClass())) return;

            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    provider.getClass().getClassLoader(),
                    new Class<?>[]{provider.getClass()},
                    (p, method, args) -> {
                        Object result = method.invoke(provider, args);
                        // b() calls E.b() and gets a WhitelistContentDO; the DTO list is
                        // derived from it. Appending here means the DTO we added is present
                        // before any conversion or matching happens.
                        if (result != null && isCatalogContent(result)) {
                            Object augmented = appendToCatalogContent(result);
                            if (augmented != null) return augmented;
                        }
                        return result;
                    });
            field.set(manager, proxy);
            MLog.event("bose.catalog.provider_wrapped",
                    "interface", provider.getClass().getName());
        } catch (Throwable t) {
            MLog.event("bose.catalog.wrap_error", "error", MLog.compactThrowable(t));
        }
    }

    /** True for the {@code WhitelistContentDO} the provider hands back. */
    private static boolean isCatalogContent(Object value) {
        if (value == null) return false;
        String name = value.getClass().getName();
        return name.endsWith("WhitelistContentDO");
    }

    /**
     * Appends a Bose entry to a {@code WhitelistContentDO}.
     *
     * <p>Returns null when the shape is not what we expect, in which case the caller keeps
     * the original object. The clone is made from an existing entry so every field the host
     * reads — function flags, protocol type, Rssi thresholds, version gates — carries values
     * of a device Melody genuinely supports.
     */
    private Object appendToCatalogContent(Object content) {
        try {
            if (boseCatalogInjected) return null;
            // The DTO list lives inside the content object under some field; find the first
            // List<WhitelistConfigDTO>-shaped one and work on that instead of guessing a name.
            for (java.lang.reflect.Field f : allFieldsOf(content.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(content);
                if (!(value instanceof java.util.List)) continue;
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) value;
                if (list.isEmpty()) continue;
                if (!(list.get(0).getClass().getName().endsWith("WhitelistConfigDTO"))) continue;

                Object clone = cloneCatalogEntry(list.get(list.size() - 1), null);
                if (clone == null) continue;
                list.add(clone);
                boseCatalogInjected = true;
                MLog.event("bose.catalog.injected",
                        "field", f.getName(),
                        "new_size", list.size(),
                        "class", clone.getClass().getSimpleName());
                return content;
            }
            MLog.event("bose.catalog.no_list_field",
                    "class", content.getClass().getName());
        } catch (Throwable t) {
            MLog.event("bose.catalog.append_error", "error", MLog.compactThrowable(t));
        }
        return null;
    }

    /**
     * Counts the control entries on a catalog row.
     *
     * <p>0.5.39. Which sections the detail page shows is driven by the row's
     * Control / ControlList sub-objects. A row whose controls are empty contributes nothing
     * visible, which is why cloning an arbitrary entry produced a catalog entry that the host
     * happily returned from its lookup and then ignored.
     */
    private static int countControls(Object dto) {
        if (dto == null) return 0;
        int total = 0;
        try {
            for (java.lang.reflect.Field f : allFieldsOf(dto.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value = f.get(dto);
                if (value instanceof java.util.Collection) {
                    total += ((java.util.Collection<?>) value).size();
                } else if (value instanceof java.util.Map) {
                    total += ((java.util.Map<?, ?>) value).size();
                } else if (value != null
                        && value.getClass().getName().contains("WhitelistConfigDTO$")) {
                    // A populated sub-object counts once: it means the row declares support.
                    total++;
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private Object injectBoseCatalogEntry(Object listResult, ClassLoader loader) {
        if (!(listResult instanceof java.util.List)) return listResult;
        java.util.List<?> list = (java.util.List<?>) listResult;
        try {
            if (boseCatalogInjected) return listResult;
            // Profile the catalog before touching it: how many entries carry controls, and
            // what the richest one looks like. That tells us what the host uses to decide
            // which sections exist, without another guess-and-check round.
            int withControls = 0;
            int richest = 0;
            int richestIndex = -1;
            for (int i = 0; i < list.size(); i++) {
                int c = countControls(list.get(i));
                if (c > 0) withControls++;
                if (c > richest) {
                    richest = c;
                    richestIndex = i;
                }
            }
            MLog.event("bose.catalog.probe",
                    "size", list.size(),
                    "with_controls", withControls,
                    "richest", richest,
                    "richest_name", richestIndex >= 0
                            ? String.valueOf(readField(list.get(richestIndex), "name")) : "n/a",
                    "sample", String.valueOf(list.isEmpty()
                            ? "empty" : list.get(0).getClass().getName()));
            if (list.isEmpty()) return listResult;
            @SuppressWarnings("unchecked")
            java.util.List<Object> mutable = (java.util.List<Object>) list;

            // 0.5.39: cloning the LAST entry (0.5.37/38) put a row in the catalog but the
            // host still built no sections. A WhitelistConfigDTO carries Control / ControlList
            // sub-objects, and those are what decide which rows the detail page shows. The
            // last entry evidently has an empty one, so nothing appeared.
            //
            // Clone from entries that actually have controls, and add several so the page
            // ends up with the union of whatever the catalog offers. Candidate selection is by
            // field inspection, not by product name, so it survives catalog reordering.
            int added = 0;
            for (Object template : list) {
                if (template == null) continue;
                if (countControls(template) == 0) continue;
                Object clone = cloneCatalogEntry(template, loader);
                if (clone == null) continue;
                mutable.add(clone);
                added++;
                if (added >= 3) break;
            }
            if (added == 0) {
                // Nothing in the catalog carries controls we can see; fall back to the
                // previous behaviour so the entry is at least present.
                Object clone = cloneCatalogEntry(list.get(list.size() - 1), loader);
                if (clone != null) {
                    mutable.add(clone);
                    added = 1;
                }
            }
            boseCatalogInjected = true;
            MLog.event("bose.catalog.injected",
                    "new_size", mutable.size(),
                    "added", added);
        } catch (Throwable t) {
            MLog.event("bose.catalog.error", "error", MLog.compactThrowable(t));
        }
        return listResult;
    }

    /**
     * Copies every field of a catalog entry, then rewrites the identifying ones.
     * Copies by value so the clone is independent of the original.
     */
    private Object cloneCatalogEntry(Object template, ClassLoader loader) {
        try {
            Class<?> type = template.getClass();
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> candidate : type.getDeclaredConstructors()) {
                if (candidate.getParameterCount() == 0) {
                    ctor = candidate;
                    break;
                }
            }
            if (ctor == null) return null;
            ctor.setAccessible(true);
            Object copy = ctor.newInstance();

            for (java.lang.reflect.Field field : allFieldsOf(type)) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try {
                    Object value = field.get(template);
                    if (value instanceof android.os.Parcelable) continue; // deep copy is not needed
                    field.set(copy, value);
                } catch (Throwable ignored) {
                }
            }
            // Identify as Bose. uid=197609(liaoran) gid=197609 groups=197609 is what the catalog match keys on for some lookups and
            //  is what the UI shows.
            setIfPresent(type, copy, "name", "Bose QC Ultra 2");
            setIfPresent(type, copy, "brand", "Bose");
            if (pendingDetailMac != null) {
                setIfPresent(type, copy, "uuid", pendingDetailMac);
                setIfPresent(type, copy, "id", pendingDetailMac);
            }
            return copy;
        } catch (Throwable t) {
            MLog.event("bose.catalog.clone_error", "error", MLog.compactThrowable(t));
            return null;
        }
    }

    /**
     * Sets a field by name, walking up the class chain.
     *
     * <p>WhitelistConfigDTO keeps its identity fields on the base class, so
     * {@code getDeclaredField} on a subclass would not find them. 0.5.32 needs this to
     * rewrite name/brand/id on a cloned entry whose concrete class is not the DTO itself.
     */
    private static void setIfPresent(Class<?> type, Object instance, String field, Object value) {
        try {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                java.lang.reflect.Field f;
                try {
                    f = c.getDeclaredField(field);
                } catch (NoSuchFieldException e) {
                    continue;
                }
                f.setAccessible(true);
                f.set(instance, value);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private void reportDetailContainer(String stage) {
        try {
            Activity activity = detailActivity;
            if (activity == null) {
                MLog.event("bose.container.state", "stage", stage, "reason", "no_activity");
                return;
            }
            ViewGroup container = findDetailContainer(activity);
            if (container == null) {
                MLog.event("bose.container.state", "stage", stage, "reason", "no_container");
                return;
            }
            // 0.5.23: the container looked healthy (attached, visible, populated) while the
            // screen showed nothing but the back button. Dumping the decor hierarchy of the
            // CURRENTLY FOCUSED window, found via the window manager rather than through our
            // own Activity reference, tells us whether we are inspecting a different window
            // than the one on screen.
            MLog.event("bose.container.decorOfFocused",
                    "stage", stage,
                    "focused", describeFocusedWindow(activity));
            MLog.event("bose.container.state",
                    "stage", stage,
                    // The container subtree can be perfectly healthy while the screen is
                    // still blank, because the occluder is a SIBLING higher up. Dumping the
                    // whole decor hierarchy settles it: 0.5.23 reported a populated,
                    // attached, visible container while the screenshot showed nothing but the
                    // back button.
                    "decor", describeViewTree(
                            activity.getWindow().getDecorView(), 0),
                    // 0.5.21: children=1 with a populated tree but a blank screen means the
                    // container we are measuring is not the one the user is looking at. The
                    // activity class and window token settle that immediately.
                    "activity", activity.getClass().getSimpleName(),
                    // getWindowToken() lives on View, not on Activity — the token has to be
                    // read off the decor view. A null token means this activity is no longer
                    // attached to a window, which is exactly the case we are hunting for.
                    "token", String.valueOf(
                            activity.getWindow().getDecorView().getWindowToken()),
                    "shown", activity.getWindow().getDecorView().isShown(),
                    "finishing", activity.isFinishing(),
                    "children", container.getChildCount(),
                    "visible", container.getVisibility(),
                    "size", container.getWidth() + "x" + container.getHeight(),
                    // 0.5.18 evidence: at +800ms the container held one child
                    // (NestedScrollView / melody_ui_detail_scrollview) yet the final
                    // uiautomator dump showed zero children. The row is therefore
                    // attached and then emptied again, so a single snapshot is not
                    // enough — the whole subtree has to be printed to see what is
                    // inside it at each sample.
                    "tree", describeViewTree(container, 0));
        } catch (Throwable t) {
            MLog.event("bose.container.state", "stage", stage,
                    "error", MLog.compactThrowable(t));
        }
    }

    /**
     * Gives the detail rows the summary text the host demands before it shows them.
     *
     * <p>Correction to the earlier note: the class first blamed here,
     * {@code MelodyJumpPreference}, is NOT the detail page's row base class — 0.5.29 matched
     * zero rows because of it. The real chain, from 17.6.3 smali, is
     * <pre>
     *   DeviceInfoItem / AccountInfoItem / ... (all the detail sections)
     *     extends MelodyUiCOUIJumpPreference
     *       extends COUIJumpPreference
     * </pre>
     * {@code AbsItem} is the item-level base and has no visibility logic of its own.
     *
     * <p>Confirmed about {@code MelodyUiCOUIJumpPreference}: its {@code onBindViewHolder}
     * contains
     * <pre>
     *   if (mIsHideJumpView) v = GONE; else v = VISIBLE;
     *   jumpView.setVisibility(v);
     * </pre>
     * so it hides the jump arrow, not the row. Whether an empty summary also collapses the
     * row is NOT yet established — hence the row-class diagnostic emitted alongside this
     * call, so the next run reports what is actually in the container instead of what we
     * expect it to be.
     */
    private void fillDetailRowSummaries(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) return;
            ViewGroup container = findDetailContainer(activity);
            if (container == null) return;
            boseRowClasses.clear();
            int filled = fillSummaries(container, 0);
            MLog.event("bose.detail.row_classes",
                    "found", boseRowClasses.size(),
                    "classes", String.valueOf(boseRowClasses));
            if (filled > 0) {
                MLog.event("bose.detail.summary_filled", "count", filled);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose detail summary fill failed", t);
        }
    }

    /** True when this view is a MelodyUiCOUIJumpPreference row or a subclass of it. */
    private static boolean isJumpPreferenceRow(Object view) {
        if (view == null) return false;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if ("com.oplus.melody.ui.widget.MelodyUiCOUIJumpPreference".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 0.5.29 diagnostic: which view classes actually live under the detail container. */
    private static final java.util.Set<String> boseRowClasses =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    private static int fillSummaries(View view, int depth) {
        if (view == null || depth > 8) return 0;
        int filled = 0;
        if (depth <= 4) {
            boseRowClasses.add(view.getClass().getName());
        }
        if (isJumpPreferenceRow(view)) {
            // androidx Preference stores the summary CharSequence here; the host clears it
            // for a device it has no catalog entry for, and the row then lays out empty.
            Object summary = readField(view, "mSummary");
            Object container = readField(view, "c");
            boolean empty = summary == null
                    || (summary instanceof CharSequence && ((CharSequence) summary).length() == 0);
            if (empty) {
                // setSummary(String) makes the host's own isEmpty() check pass, so the row
                // is laid out with its container visible from then on.
                if (setPreferenceValue(view, "setSummary", PLACEHOLDER_SUMMARY)) {
                    filled++;
                    if (container instanceof View) {
                        ((View) container).setVisibility(View.VISIBLE);
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                filled += fillSummaries(group.getChildAt(i), depth + 1);
            }
        }
        return filled;
    }

    private static String describeFocusedWindow(Activity activity) {
        try {
            if (activity == null) return "no_activity";
            View decor = activity.getWindow().getDecorView();
            StringBuilder sb = new StringBuilder();
            sb.append("token=").append(decor.getWindowToken())
                    .append(" shown=").append(decor.isShown())
                    .append(" size=").append(decor.getWidth())
                    .append('x').append(decor.getHeight())
                    .append(" | tree=").append(describeViewTree(decor, 0));
            ViewGroup container = findDetailContainer(activity);
            if (container == null) {
                sb.append(" | container=not_found");
                return sb.toString();
            }
            // The container itself is healthy; the occluder, if any, is a sibling drawn
            // after it. A full-screen opaque or translucent sibling is the classic cause of
            // "the tree is fully populated but the screen is blank".
            ViewGroup parent = container.getParent() instanceof ViewGroup
                    ? (ViewGroup) container.getParent() : null;
            sb.append(" | siblings_above=");
            if (parent == null) {
                sb.append("no_parent");
            } else {
                int index = parent.indexOfChild(container);
                int drawn = 0;
                for (int i = index + 1; i < parent.getChildCount() && drawn < 6; i++) {
                    View sib = parent.getChildAt(i);
                    drawn++;
                    sb.append(sib.getClass().getSimpleName())
                            .append('[').append(sib.getWidth())
                            .append('x').append(sib.getHeight())
                            .append(" vis=").append(sib.getVisibility())
                            .append(" alpha=").append(sib.getAlpha())
                            .append(" z=").append(sib.getZ())
                            .append("] ");
                }
                if (drawn == 0) sb.append("none");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName();
        }
    }

    private static ViewGroup findDetailContainer(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "melody_ui_fragment_container", "id", activity.getPackageName());
            if (id != 0) {
                View found = activity.findViewById(id);
                if (found instanceof ViewGroup) return (ViewGroup) found;
            }
            // The id lives in Melody's package, not the host app's, under some builds.
            for (String pkg : new String[]{activity.getPackageName(), TARGET}) {
                int alt = activity.getResources().getIdentifier(
                        "melody_ui_fragment_container", "id", pkg);
                if (alt != 0) {
                    View found = activity.findViewById(alt);
                    if (found instanceof ViewGroup) return (ViewGroup) found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void reportDetailPageState(int attempt) {
        try {
            Object row = noiseEffectRow;
            if (row == null) {
                MLog.event("bose.detail.no_anchor", "attempt", attempt);
                return;
            }
            // The anchor is a single Preference, so its own child count is always 0.
            // Walk up to the enclosing group — that is the list the user actually sees.
            Object parent = PrefRef.getParent(row);
            if (parent == null) {
                MLog.event("bose.detail.anchor_detached", "attempt", attempt);
                return;
            }
            if (PrefRef.getPreferenceCount(parent) == 0) parent = PrefRef.getParent(parent);
            if (parent == null) {
                MLog.event("bose.detail.no_group", "attempt", attempt);
                return;
            }
            StringBuilder keys = new StringBuilder();
            int visible = 0;
            int total = 0;
            for (int i = 0; i < PrefRef.getPreferenceCount(parent); i++) {
                Object pref = PrefRef.getPreference(parent, i);
                if (pref == null) continue;
                total++;
                if (PrefRef.isVisible(pref)) visible++;
                if (keys.length() > 0) keys.append(',');
                String key = PrefRef.getKey(pref);
                keys.append(key != null ? key : pref.getClass().getSimpleName());
            }
            MLog.event("bose.detail.state",
                    "attempt", attempt,
                    "total", total,
                    "visible", visible,
                    "anchor", PrefRef.getKey(row),
                    "anchor_visible", PrefRef.isVisible(row),
                    "injected", PrefRef.findPreferenceRecursive(parent, BOSE_CNC_KEY) != null,
                    "keys", keys);
        } catch (Throwable t) {
            MLog.event("bose.detail.state_error", "error", MLog.compactThrowable(t));
        }
    }

    /** Short description of where the injection would land, for log triage. */
    private String describeParent() {
        try {
            Object row = noiseEffectRow;
            if (row == null) return "no_anchor";
            Object parent = PrefRef.getParent(row);
            if (parent == null) return "no_parent";
            return parent.getClass().getSimpleName() + "/"
                    + PrefRef.getPreferenceCount(parent);
        } catch (Throwable t) {
            return "error:" + MLog.compactThrowable(t);
        }
    }

    private void hideAncStrengthPreference(Object preference) {
        if (preference == null || !hasMappedDeviceActive()) return;
        try {
            Object key = preference.getClass().getMethod("getKey").invoke(preference);
            if (!"pref_noise_menu".equals(key) && !"pref_noise_menu_category".equals(key)) return;
            Method setVisible = preference.getClass().getMethod("setVisible", boolean.class);
            setVisible.invoke(preference, false);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ANC strength preference suppression failed", t);
        }
    }

    private boolean hasMappedDeviceActive() {
        return (targetSonyDevice != null && isRegisteredSonyName(targetSonyDevice.getName()))
                || (targetSamsungDevice != null && isRegisteredSamsungDevice(targetSamsungDevice))
                || (targetHuaweiDevice != null && isRegisteredHuaweiDevice(targetHuaweiDevice))
                || (targetXiaomiDevice != null && isRegisteredXiaomiDevice(targetXiaomiDevice))
                || (targetBoseDevice != null && boseHostConnected);
    }

    private synchronized void registerXiaomiBatteryReceiver(Application application) {
        if (xiaomiBatteryReceiverRegistered) return;
        try {
            application.registerReceiver(xiaomiBatteryReceiver,
                    new IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT),
                    // This broadcast originates in the Bluetooth system process.  Android 13+
                    // drops it for a NOT_EXPORTED dynamic receiver before onReceive is called.
                    Context.RECEIVER_EXPORTED);
            xiaomiBatteryReceiverRegistered = true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Xiaomi battery event receiver registration failed", t);
        }
    }

    private boolean replaceConfiguredProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface
    ) {
        return replaceConfiguredProductImage(owner, viewModelField, addressField, nameField,
                imageField, loadingField, surface, null);
    }

    private boolean replaceConfiguredProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface,
            ImageView fallbackImageView
    ) {
        try {
            return replaceSonyProductImage(owner, viewModelField, addressField, nameField,
                    imageField, loadingField, surface, fallbackImageView)
                    || replaceHuaweiProductImage(owner, viewModelField, addressField, nameField,
                    imageField, loadingField, surface, fallbackImageView)
                    || replaceXiaomiProductImage(owner, viewModelField, addressField, nameField,
                    imageField, loadingField, surface, fallbackImageView)
                    || replaceBoseProductImage(owner, viewModelField, addressField, nameField,
                    imageField, loadingField, surface, fallbackImageView);
        } catch (Throwable t) {
            // Hooks run inside Melody's own call stack: an exception escaping here
            // takes the whole page process down. Never let that happen.
            log(Log.WARN, TAG, "product image replacement aborted on " + surface, t);
            return false;
        }
    }

    /** Show the bundled Bose QC Earbuds Ultra 2 photo on the masked detail/card. */
    private boolean replaceBoseProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface,
            ImageView fallbackImageView
    ) {
        Object viewModel = readField(owner, viewModelField);
        String address = asString(readField(viewModel, addressField));
        if (!com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.matchesAddress(address)) {
            return false;
        }
        Object imageValue = readField(owner, imageField);
        ImageView imageView = imageValue instanceof ImageView
                ? (ImageView) imageValue : fallbackImageView;
        if (imageView == null) return false;
        File imageFile = materializeBoseImage();
        if (imageFile == null) {
            log(Log.WARN, TAG, event("Bose " + surface + " image skipped: asset unavailable"));
            return false;
        }
        applyBoseImage(imageView, imageFile, owner, loadingField);
        // Remember this exact view: onBindViewHolder recycles one ImageView across
        // pages, and every later bind re-runs the stock image load, overwriting
        // whatever we set. Force it back on each pass (see keepBoseImagePinned).
        pinBoseImageView(imageView);
        log(Log.INFO, TAG, event("replaced Bose " + surface + " product image"));
        return true;
    }

    /**
     * Re-asserts our photo on a view Melody keeps recycling. The stock artwork is
     * fetched asynchronously, so a one-shot setImage loses the race — 0.4.4 saw the
     * generic earbud photo win seconds later, and the spinner flash before it.
     * Each re-bind gets several passes, and the last one is also posted on the view
     * itself so it lands after any layout-triggered rebind.
     */
    private void pinBoseImageView(ImageView imageView) {
        if (imageView == null) return;
        File file = materializeBoseImage();
        if (file == null) return;
        // Mark the view. getTag() is null on a fresh view, so the constant must be
        // on the left of the comparison — 0.4.6 crashed the detail page on
        // "view.getTag().equals(...)" with an NPE. setTag(Object) is deliberate:
        // setTag(int,Object) requires a real resource id and throws otherwise.
        try {
            Object tag = imageView.getTag();
            if (!BOSE_IMAGE_TAG.equals(tag)) imageView.setTag(BOSE_IMAGE_TAG);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose image tagging failed", t);
            return;
        }
        for (int i = 0; i < 4; i++) {
            final long delay = i == 0 ? 200L : (i == 1 ? 800L : (i == 2 ? 1800L : 3500L));
            mainHandler.postDelayed(() -> {
                try {
                    if (!isBoseImagePinned(imageView)) return;
                    if (imageView.getDrawable() == null) return;
                    imageView.setImageURI(Uri.fromFile(file));
                    imageView.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {
                }
            }, delay);
        }
        // Re-apply once per attach cycle: the stock header rebinds on every scroll
        // settle, which is exactly when the generic photo returns. Tracked in a weak
        // set so the listener lands at most once per view — adding it on every
        // rebind stacked duplicates for the view's whole lifetime.
        if (boseAttachGuards.add(imageView)) {
            try {
                // Last line of defence: the stock load lands after every timer we
                // schedule, so restore our drawable right before the frame goes out
                // when the current drawable is not the one we installed.
                final Uri ourUri = Uri.fromFile(file);
                boseInstalledUris.put(imageView, ourUri);
                imageView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                    try {
                        ImageView iv = (ImageView) v;
                        if (!isBoseImagePinned(iv)) return;
                        // Re-apply whenever the stock loader swapped the drawable out
                        // under us; layout changes are exactly when that happens.
                        if (!ourUri.equals(boseInstalledUris.get(iv))) return;
                        iv.setImageURI(ourUri);
                    } catch (Throwable ignored) {
                    }
                });
                View.OnAttachStateChangeListener guard = new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {
                        mainHandler.postDelayed(() -> {
                            try {
                                if (!isBoseImagePinned((ImageView) v)) return;
                                File latest = materializeBoseImage();
                                if (latest == null) return;
                                ((ImageView) v).setImageURI(Uri.fromFile(latest));
                            } catch (Throwable ignored) {
                            }
                        }, 350L);
                    }
                    @Override public void onViewDetachedFromWindow(View v) { }
                };
                imageView.addOnAttachStateChangeListener(guard);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Bose image attach guard failed", t);
            }
        }
    }

    /**
     * Views that already carry the attach listener. A weak set keyed by view, so
     * a recycled ImageView gets exactly one guard: setTag(int, Object) cannot be
     * used here because it demands a real resource id and throws otherwise.
     */
    /**
     * Tag slot holding the Uri we last installed, so the layout listener can tell
     * "my drawable" from "the stock one". Uses a generated key via View.setTag
     * on a per-view basis through a weak map instead of a resource id, which would
     * require declaring one in the module's R class at runtime.
     */
    private static final java.util.Map<ImageView, android.net.Uri> boseInstalledUris =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ImageView, android.net.Uri>());

    private static final java.util.Set<ImageView> boseAttachGuards =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Marker tag identifying a product-image view pinned to the Bose photo. */
    private static final String BOSE_IMAGE_TAG = "melodylink.bose.image";

    private static boolean isBoseImagePinned(ImageView view) {
        try {
            return view != null && BOSE_IMAGE_TAG.equals(view.getTag());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sets the Bose photo and silences the stock loading spinner for that view. */
    private void applyBoseImage(ImageView imageView, File imageFile, Object owner, String loadingField) {
        try {
            imageView.setImageURI(Uri.fromFile(imageFile));
            imageView.setVisibility(View.VISIBLE);
            // The stock header starts its own spinner on a post(), so a single sweep
            // is not enough (0.5.0: "通用设置图片没了" — the spinner covered our art).
            // Re-sweep a few times after the layout settles.
            final ImageView pinned = imageView;
            for (int i = 0; i < 3; i++) {
                mainHandler.postDelayed(() -> {
                    try {
                        if (!isBoseImagePinned(pinned)) return;
                        stopLoadingSpinners(pinned);
                    } catch (Throwable ignored) {
                    }
                }, 120L * (i + 1));
            }
            // The stock header keeps its own spinner running above the artwork; it
            // lives in the same layout, so sweep the neighbourhood for it instead of
            // relying on the model field alone (it was often null on 通用设置).
            stopLoadingSpinners(imageView);
            Object loading = loadingField == null ? null : readField(owner, loadingField);
            hideLoadingView(loading);
            if (loading instanceof View) ((View) loading).setVisibility(View.GONE);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose image apply failed", t);
        }
    }

    /**
     * Cancels any loading animation found around the product-image view. The
     * "转圈" the user reported is not the model field: it is a sibling view inside
     * the header layout, so we walk the ancestors and cancel anything that looks
     * like a progress spinner.
     */
    private static void stopLoadingSpinners(View start) {
        try {
            View parent = start.getParent() instanceof View ? (View) start.getParent() : null;
            for (int depth = 0; parent != null && depth < 4; depth++, parent =
                    parent.getParent() instanceof View ? (View) parent.getParent() : null) {
                if (parent instanceof android.view.ViewGroup) {
                    android.view.ViewGroup group = (android.view.ViewGroup) parent;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        cancelSpinnerIfAny(group.getChildAt(i));
                    }
                }
                hideLoadingView(parent);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelSpinnerIfAny(View view) {
        if (view == null) return;
        String name = view.getClass().getName();
        if (name.contains("Loading") || name.contains("Progress") || name.contains("Spin")
                || name.contains("LoadingAnimation")) {
            hideLoadingView(view);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelSpinnerIfAny(group.getChildAt(i));
        }
    }

    private File materializeBoseImage() {
        Application application = currentApplication();
        AssetManager assets = sonyModuleAssets;
        if (application == null || assets == null) return null;
        File directory = new File(application.getFilesDir(), "melodylink/bose-images");
        File output = new File(directory, "qc_ultra2.png");
        try {
            if (output.isFile() && output.length() > 0L) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (java.io.InputStream input = assets.open("bose/images/qc_ultra2.png");
                 FileOutputStream stream = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
            return output.isFile() && output.length() > 0L ? output : null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose image materialization failed", t);
            return null;
        }
    }

    /** Replaces only the two product-image views identified from Melody 16.8.3's resource flow. */
    private boolean replaceSonyProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface
    ) {
        return replaceSonyProductImage(owner, viewModelField, addressField, nameField,
                imageField, loadingField, surface, null);
    }

    private boolean replaceSonyProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface,
            ImageView fallbackImageView
    ) {
        Object viewModel = readField(owner, viewModelField);
        String address = asString(readField(viewModel, addressField));
        String name = asString(readField(viewModel, nameField));
        SonyDeviceConfig profile = findSonyImageProfile(address, name);
        if (profile == null) {
            log(Log.INFO, TAG, event("Sony " + surface + " image skipped: profile unavailable"
                    + " viewModel=" + (viewModel != null)));
            return false;
        }

        Object imageValue = readField(owner, imageField);
        ImageView imageView = imageValue instanceof ImageView
                ? (ImageView) imageValue : fallbackImageView;
        if (imageView == null) {
            log(Log.WARN, TAG, event("Sony " + surface + " image target unavailable"));
            return false;
        }
        File imageFile = materializeSonyImage(profile);
        if (imageFile == null) {
            log(Log.WARN, TAG, event("Sony " + surface + " image skipped: asset unavailable profile="
                    + profile.getId()));
            return false;
        }

        imageView.setImageURI(Uri.fromFile(imageFile));
        imageView.setVisibility(View.VISIBLE);
        Object loadingView = readField(owner, loadingField);
        hideLoadingView(loadingView);
        log(Log.INFO, TAG, event("replaced Sony " + surface + " product image profile="
                + profile.getId()));
        return true;
    }

    private ImageView findCardImageView(Object holder) {
        Object itemView = readField(holder, "itemView");
        if (!(itemView instanceof View)) return null;
        View root = (View) itemView;
        int imageId = root.getResources().getIdentifier("device_image", "id", TARGET);
        View image = imageId == 0 ? null : root.findViewById(imageId);
        if (image instanceof ImageView) return (ImageView) image;
        return largestAttachedImageView(root);
    }

    private static void hideLoadingView(Object loadingView) {
        if (loadingView == null) return;
        try {
            Method cancelAnimation = loadingView.getClass().getMethod("cancelAnimation");
            cancelAnimation.invoke(loadingView);
        } catch (Throwable ignored) {
        }
        if (loadingView instanceof View) ((View) loadingView).setVisibility(View.GONE);
    }

    /**
     * Replaces the product photo on the earbud detail header.
     *
     * <p>Field {@code d} on MelodyDetailModelView is the ImageView that actually shows the
     * product photo — confirmed in the 17.6.3 smali. The earlier implementation resolved the
     * view through resource ids and hooked {@code b}/{@code c}, which turned out to be the 3D
     * model loader and a low-memory check respectively; that is why the log kept reporting a
     * successful replacement while nothing appeared.
     *
     * <p>Glide loads into that view asynchronously, so the assignment is re-asserted several
     * times after the host settles.
     */
    private void replaceBoseDetailImageNow(Object owner) {
        if (!(owner instanceof View)) return;
        Object viewModel = readField(owner, "g");
        String address = asString(readField(viewModel, "b"));
        if (!com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.matchesAddress(address)) {
            return;
        }
        File file = materializeBoseImage();
        if (file == null) {
            MLog.event("bose.image.skip", "reason", "asset_unavailable");
            return;
        }
        Object field = readField(owner, "d");
        ImageView imageView = field instanceof ImageView ? (ImageView) field
                : findDetailImageView(owner);
        if (imageView == null) {
            MLog.event("bose.image.skip", "reason", "no_image_view");
            return;
        }
        applyBoseImage(imageView, file, owner, "e");
        pinBoseImageView(imageView);
        MLog.event("bose.image.applied", "surface", "detail");
    }

    /**
     * Puts the Bose product photo into the 通用设置 header card.
     *
     * <p>{@code OneSpaceHeaderPreference} (Melody 17.6.3, classes2.dex) holds the photo in
     * field {@code e} (ImageView) and loads it in {@code i(Lf9/b;)} via
     * {@code getDetailImageRes()} — a catalog lookup. A device with no catalog entry (which
     * is every Bose) gets {@code null}, so the host leaves the ImageView empty and the card
     * renders as a blank circle. This installs our asset instead.
     *
     * @return true when the photo was installed, so the caller skips the host's own load.
     */
    private boolean replaceBoseOneSpaceHeaderImage(Object owner) {
        try {
            if (!boseBonded()) return false;
            if (!(owner instanceof View)) return false;
            Object field = readField(owner, "e");
            if (!(field instanceof ImageView)) {
                MLog.event("bose.image.skip", "surface", "onespace", "reason", "no_image_view");
                return false;
            }
            File file = materializeBoseImage();
            if (file == null) {
                MLog.event("bose.image.skip", "surface", "onespace", "reason", "asset_unavailable");
                return false;
            }
            applyBoseImage((ImageView) field, file, owner, "e");
            pinBoseImageView((ImageView) field);
            MLog.event("bose.image.applied", "surface", "onespace");
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose 通用设置 photo install failed", t);
            return false;
        }
    }

    /**
     * Attaches the Bose CNC (降噪等级) slider directly under the ANC three-state widget in
     * the 通用设置 page.
     *
     * <p>The three-state control (降噪 / 关闭 / 通透) is a {@code DeviceControlWidget} in
     * field {@code d} of {@code OneSpaceNoisePreference}; the dump of that page shows it as
     * {@code noise_action_view} sitting above the preference list. It is a plain
     * RecyclerView row, so the preference-screen injection used on the detail page cannot
     * reach it — the slider has to be added to the view hierarchy instead.
     *
     * <p>Called on every {@code onBindViewHolder}, hence the tag guard: the host re-binds on
     * scroll and on state changes, and a second slider would be a visible duplicate.
     */
    private void attachCncSliderUnderOneSpaceNoise(Object preference, Object holder) {
        try {
            if (!boseBonded()) return;
            Object widgetField = readField(preference, "d");
            if (!(widgetField instanceof View)) {
                MLog.event("bose.cnc.onespace.skip", "reason", "no_widget");
                return;
            }
            View widget = (View) widgetField;
            Context context = widget.getContext();
            Activity activity = findActivity(context);
            if (activity == null) activity = detailActivity;
            if (activity == null) {
                MLog.event("bose.cnc.onespace.skip", "reason", "no_activity");
                return;
            }

            // 0.5.16 evidence: reason=not_a_view. The first attempt built a
            // MelodyPromptVolumeSeekBarPreference and called addView on the widget's
            // parent, but that class extends COUIPreference — it is a Preference, never a
            // View, so it cannot be added to a ViewGroup at all. The 通用设置 list is a
            // COUIPanel driven by a real preference tree (OneSpaceListFragment, field r =
            // COUIPreferenceCategory), so the row has to be added there instead. The
            // onBindViewHolder hook is kept only as the trigger point: it is the earliest
            // moment the tree is guaranteed to be built.
            Object tree = findOneSpacePreferenceTree(preference);
            if (tree == null) {
                MLog.event("bose.cnc.onespace.skip", "reason", "no_tree");
                return;
            }
            // Idempotence: onBindViewHolder re-runs on every scroll and state change, and
            // findPreferenceByKeyRecursive answers with a boolean, not a node.
            if (findPreferenceByKeyRecursive(tree, BOSE_CNC_ONESPACE_KEY)) return;

            ClassLoader loader = preference.getClass().getClassLoader();
            Object seek = newPreference(loader,
                    "com.oplus.melody.ui.widget.MelodyPromptVolumeSeekBarPreference", activity);
            if (seek == null) {
                log(Log.WARN, TAG, event("Bose 通用设置 slider unavailable: COUI seekbar ctor failed"));
                return;
            }
            setPreferenceValue(seek, "setKey", BOSE_CNC_ONESPACE_KEY);
            setPreferenceValue(seek, "setTitle", "降噪等级");
            setPreferenceValue(seek, "setPersistent", false);
            setPreferenceValue(seek, "setPromptVolumePercent", Boolean.FALSE);
            invokeInt(seek, "setBarMaxValue", 10);

            int level = boseTransport.getCncLevel();
            if (level < 0) {
                int[] shared = MelodySharedStateStore.readBoseCncState(boseCncStateFile());
                if (shared != null) level = shared[1];
            }
            if (level < 0) level = 3;
            invokeInt(seek, "setProgress", level);
            setPreferenceValue(seek, "setSummary", "效果强度 " + level + "/10");
            installBoseCncListener(seek, loader);

            int anchorOrder = PrefRef.getOrder(preference);
            int target = anchorOrder < 0 ? 0 : anchorOrder + 1;
            PrefRef.shiftPreferenceOrders(tree, target, +40);
            if (!addPreference(tree, seek, loader)) {
                MLog.event("bose.cnc.onespace.skip", "reason", "add_rejected",
                        "parent", tree.getClass().getSimpleName());
                return;
            }
            boseCncOneSpacePreference = seek;
            MLog.event("bose.cnc.onespace.attached", "level", level, "order", target,
                    "parent", tree.getClass().getSimpleName());
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose 通用设置 slider attach failed", t);
        }
    }

    /**
     * Walks up from a 通用设置 row to the preference container that drives its RecyclerView.
     *
     * <p>{@code OneSpaceNoisePreference} has no parent link of its own — it is added by
     * {@code OneSpaceListFragment} (field r, a {@code COUIPreferenceCategory}) to a
     * {@code androidx.preference.g} fragment. So the tree is found by asking the row's own
     * context for the fragment instead, and falling back to the screen the anchor lives on.
     */
    private Object findOneSpacePreferenceTree(Object preference) {
        try {
            Object context = PrefRef.invokeNoArg(preference, "getContext");
            if (!(context instanceof Context)) return null;
            // screenForAnchor() bails out unless the preference's context is itself an
            // Activity. On 通用设置 it is not: the rows live in a COUIPanelFragment hosted
            // by OneSpaceDetailActivity, so getContext() returns a ContextWrapper. Unwrap
            // to the Activity first, then reuse the fragment-manager walk.
            Activity activity = findActivity((Context) context);
            if (activity == null) return null;
            Object manager = null;
            for (String name : new String[]{"getSupportFragmentManager", "getFragmentManager"}) {
                manager = PrefRef.invokeNoArg(activity, name);
                if (manager != null) break;
            }
            if (manager == null) return null;
            java.util.List<?> fragments = readFragmentList(manager);
            if (fragments == null) return null;
            for (Object fragment : fragments) {
                if (fragment == null) continue;
                Object screen = PrefRef.getPreferenceScreen(fragment);
                if (screen == null) continue;
                if (PrefRef.findPreferenceRecursive(screen, "pref_noise_switch") != null) {
                    MLog.event("bose.cnc.onespace.tree",
                            "fragment", fragment.getClass().getSimpleName(),
                            "screen", screen.getClass().getSimpleName());
                    return screen;
                }
            }
        } catch (Throwable t) {
            MLog.event("bose.cnc.onespace.tree_error", "error", MLog.compactThrowable(t));
        }
        return null;
    }

    private ImageView findDetailImageView(Object owner) {
        if (!(owner instanceof View)) return null;
        View root = (View) owner;
        int imageId = root.getResources().getIdentifier("normal_image", "id", TARGET);
        View image = imageId == 0 ? null : root.findViewById(imageId);
        if (image instanceof ImageView) return (ImageView) image;
        // 0.5.5 wrote the PNG, called setImageURI and reported success, yet nothing
        // appeared: the id lookup resolved to an ImageView that is not on screen
        // (a freshly inflated template, discarded on the first real bind). Fall
        // back to the largest attached ImageView in the real tree.
        return largestAttachedImageView(root);
    }

    /** The biggest ImageView actually laid out under this root, or null. */
    private static ImageView largestAttachedImageView(View root) {
        if (root == null) return null;
        ImageView best = null;
        long bestArea = 0L;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 400) {
            View current = queue.poll();
            if (current == null) continue;
            if (current instanceof ImageView && current.isAttachedToWindow()) {
                long area = (long) current.getWidth() * current.getHeight();
                if (area > bestArea) {
                    bestArea = area;
                    best = (ImageView) current;
                }
            }
            if (current instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return bestArea > 0L ? best : null;
    }

    private void replaceSonyDetailImageLater(Object owner) {
        if (!(owner instanceof View)) return;
        View view = (View) owner;
        // The page can be gone by the time this runs (0.4.6 crashed the detail page
        // from here), so every step is guarded and nothing propagates.
        view.post(() -> {
            try {
                if (!view.isAttachedToWindow()) return;
                replaceConfiguredProductImage(owner, "g", "b", "c", "d", "e", "detail",
                        findDetailImageView(owner));
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Bose detail image replacement skipped", t);
            }
        });
    }

    private SonyDeviceConfig findSonyImageProfile(String address, String name) {
        if (!deviceBridge.hasProfiles()) return null;
        SonyDeviceConfig profile = findSonyProfileByName(name);
        if (profile == null) profile = activeSonyImageProfile;
        if (profile == null || profile.getImage() == null || profile.getImage().trim().isEmpty()) return null;
        // The whitelist hook has already verified this profile. The ViewModel is populated later.
        if (address != null && isTargetAddress(address)) rememberTargetAddress(address);
        return profile;
    }

    private SonyDeviceConfig findSonyProfileByName(String name) {
        return deviceBridge.profileForName(name);
    }

    private File materializeSonyImage(SonyDeviceConfig profile) {
        Application application = currentApplication();
        AssetManager assets = sonyModuleAssets;
        String assetPath = profile.getImage();
        if (application == null || assets == null || assetPath == null || !assetPath.startsWith("sony/images/")) {
            return null;
        }
        String fileName = new File(assetPath).getName();
        File directory = new File(application.getFilesDir(), "melodylink/sony-images");
        File output = new File(directory, profile.getId().replace('.', '_') + "-" + fileName);
        try {
            if (output.isFile() && output.length() > 0L) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) {
                log(Log.WARN, TAG, event("Sony image directory creation failed"));
                return null;
            }
            try (java.io.InputStream input = assets.open(assetPath);
                 FileOutputStream stream = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
            return output.isFile() && output.length() > 0L ? output : null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony image materialization failed", t);
            return null;
        }
    }

    private boolean replaceHuaweiProductImage(
            Object owner,
            String viewModelField,
            String addressField,
            String nameField,
            String imageField,
            String loadingField,
            String surface,
            ImageView fallbackImageView
    ) {
        Object viewModel = readField(owner, viewModelField);
        String address = asString(readField(viewModel, addressField));
        String name = asString(readField(viewModel, nameField));
        HuaweiDeviceConfig profile = findHuaweiImageProfile(address, name);
        if (profile == null) return false;

        Object imageValue = readField(owner, imageField);
        ImageView imageView = imageValue instanceof ImageView
                ? (ImageView) imageValue : fallbackImageView;
        if (imageView == null) {
            log(Log.WARN, TAG, event("Huawei " + surface + " image target unavailable"));
            return false;
        }
        File imageFile = materializeHuaweiImage(profile);
        if (imageFile == null) {
            log(Log.WARN, TAG, event("Huawei " + surface + " image skipped: asset unavailable profile="
                    + profile.getId()));
            return false;
        }

        imageView.setImageURI(Uri.fromFile(imageFile));
        imageView.setVisibility(View.VISIBLE);
        hideLoadingView(readField(owner, loadingField));
        log(Log.INFO, TAG, event("replaced Huawei " + surface + " product image profile="
                + profile.getId()));
        return true;
    }

    private HuaweiDeviceConfig findHuaweiImageProfile(String address, String name) {
        HuaweiDeviceConfig profile = findHuaweiProfileByName(name);
        if (profile == null) profile = activeHuaweiImageProfile;
        if (profile == null || profile.getImage().trim().isEmpty()) return null;
        if (address != null && isTargetAddress(address)) rememberTargetAddress(address);
        return profile;
    }

    private HuaweiDeviceConfig findHuaweiProfileByName(String name) {
        com.melody.melodylink.huawei.config.HuaweiDeviceMatch match = HuaweiDeviceCatalog.INSTANCE.find(
                new com.melody.melodylink.domain.DeviceIdentity(name, null,
                        java.util.Collections.emptySet(), null)
        );
        return match != null ? match.getRoute() : null;
    }

    private XiaomiDeviceConfig findXiaomiProfileByName(String name) {
        com.melody.melodylink.xiaomi.config.XiaomiDeviceMatch match = XiaomiDeviceCatalog.INSTANCE.find(
                new com.melody.melodylink.domain.DeviceIdentity(name, null,
                        java.util.Collections.emptySet(), null)
        );
        return match != null ? match.getRoute() : null;
    }

    private File materializeHuaweiImage(HuaweiDeviceConfig profile) {
        Application application = currentApplication();
        AssetManager assets = sonyModuleAssets;
        String assetPath = profile.getImage();
        if (application == null || assets == null || !assetPath.startsWith("huawei/images/")) return null;
        String fileName = new File(assetPath).getName();
        File directory = new File(application.getFilesDir(), "melodylink/huawei-images");
        File output = new File(directory, profile.getId().replace('.', '_') + "-" + fileName);
        try {
            if (output.isFile() && output.length() > 0L) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (java.io.InputStream input = assets.open(assetPath);
                 FileOutputStream stream = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
            return output.isFile() && output.length() > 0L ? output : null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Huawei image materialization failed", t);
            return null;
        }
    }

    private boolean replaceXiaomiProductImage(
            Object owner, String viewModelField, String addressField, String nameField,
            String imageField, String loadingField, String surface, ImageView fallbackImageView
    ) {
        Object viewModel = readField(owner, viewModelField);
        String address = asString(readField(viewModel, addressField));
        XiaomiDeviceConfig profile = findXiaomiProfileByName(asString(readField(viewModel, nameField)));
        if (profile == null) profile = activeXiaomiImageProfile;
        if (profile == null || profile.getImage().trim().isEmpty()) return false;
        Object imageValue = readField(owner, imageField);
        ImageView imageView = imageValue instanceof ImageView ? (ImageView) imageValue : fallbackImageView;
        if (imageView == null) return false;
        File imageFile = materializeXiaomiImage(profile);
        if (imageFile == null) return false;
        imageView.setImageURI(Uri.fromFile(imageFile));
        imageView.setVisibility(View.VISIBLE);
        hideLoadingView(readField(owner, loadingField));
        if (address != null && isTargetAddress(address)) rememberTargetAddress(address);
        log(Log.INFO, TAG, event("replaced Xiaomi " + surface + " product image profile=" + profile.getId()));
        return true;
    }

    private File materializeXiaomiImage(XiaomiDeviceConfig profile) {
        Application application = currentApplication();
        AssetManager assets = sonyModuleAssets;
        String assetPath = profile.getImage();
        if (application == null || assets == null || !assetPath.startsWith("xiaomi/images/")) return null;
        File directory = new File(application.getFilesDir(), "melodylink/xiaomi-images");
        File output = new File(directory, profile.getId().replace('.', '_') + "-" + new File(assetPath).getName());
        try {
            if (output.isFile() && output.length() > 0L) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (java.io.InputStream input = assets.open(assetPath);
                 FileOutputStream stream = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
            return output.isFile() && output.length() > 0L ? output : null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Xiaomi image materialization failed", t);
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static View findViewByClassName(View view, String className) {
        if (view == null) return null;
        if (className.equals(view.getClass().getName())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findViewByClassName(group.getChildAt(i), className);
            if (found != null) return found;
        }
        return null;
    }

    private static int readSelectedNoiseReductionMode(Object view) {
        Object selectedValue = readField(view, "s");
        if (!(selectedValue instanceof Integer)) return -1;
        switch ((Integer) selectedValue) {
            case 1: return 0;
            case 2: return 1;
            case 4: return 2;
            case 8: return 3;
            case 16: return 4;
            default: return -1;
        }
    }

    private static int readNativeNoiseReductionMode(Object listener, Object modeItem) {
        if (listener == null || modeItem == null) return -1;
        try {
            Method getPosition = modeItem.getClass().getMethod("f");
            Object positionValue = getPosition.invoke(modeItem);
            if (!(positionValue instanceof String)) return -1;
            int position = Integer.parseInt((String) positionValue);
            Object item = readField(listener, "a");
            Object modes = readField(item, "mNoiseReductionModeList");
            if (!(modes instanceof java.util.List<?>)) return -1;
            java.util.List<?> modeList = (java.util.List<?>) modes;
            if (position < 0 || position >= modeList.size()) return -1;
            Object mode = modeList.get(position);
            Method getProtocolIndex = mode.getClass().getMethod("getProtocolIndex");
            Object protocolIndex = getProtocolIndex.invoke(mode);
            return protocolIndex instanceof Integer ? (Integer) protocolIndex : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readNativeNoiseReductionAddress(Object listener) {
        Object item = readField(listener, "a");
        Object viewModel = readField(item, "mViewModel");
        Object address = readField(viewModel, "b");
        return address instanceof String ? (String) address : null;
    }

    private static String readNoiseReductionAddress(Object view) {
        Object bus = readField(view, "f");
        if (bus == null) return null;
        try {
            Method getAddress = bus.getClass().getMethod("getAddress");
            Object address = getAddress.invoke(bus);
            return address instanceof String ? (String) address : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private synchronized boolean hasPendingNoiseWrite() {
        return pendingNoiseWrite != null && !pendingNoiseWrite.isDone();
    }

    private void failPendingNoiseWrite(String reason) {
        CompletableFuture<Object> future;
        synchronized (this) {
            future = pendingNoiseWrite;
            pendingNoiseWrite = null;
        }
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private Object createSetCommandState(int status) {
        ClassLoader loader = melodyClassLoader;
        if (loader == null) return null;
        String packageName = "com.oplus.melody.model.repository.earphone.";
        String[] classNames = {
                packageName + "SetCommandStateDTO",
                packageName + "Z",
                packageName + "O"
        };
        Throwable lastFailure = null;
        for (String className : classNames) {
            try {
                Class<?> type = loadClass(loader, className);
                Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
                constructor.setAccessible(true);
                Object result = constructor.newInstance(targetAddress, status);
                log(Log.INFO, TAG, event("created Sony ANC result DTO " + className
                        + " status=" + status));
                return result;
            } catch (Throwable t) {
                lastFailure = t;
            }
        }
        log(Log.WARN, TAG, "Sony ANC result DTO creation failed", lastFailure);
        return null;
    }

    @SuppressLint("MissingPermission")
    private boolean isTargetDevice(BluetoothDevice device) {
        try {
            return isRegisteredSonyName(device.getName()) || isRegisteredSamsungDevice(device)
                    || isRegisteredHuaweiDevice(device) || isRegisteredXiaomiDevice(device)
                    || isBoseDevice(device);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isRegisteredHuaweiDevice(BluetoothDevice device) {
        try {
            java.util.Set<String> uuids = new java.util.HashSet<>();
            if (device.getUuids() != null) {
                for (android.os.ParcelUuid uuid : device.getUuids()) uuids.add(uuid.getUuid().toString());
            }
            return HuaweiDeviceCatalog.INSTANCE.find(
                    new com.melody.melodylink.domain.DeviceIdentity(device.getName(), device.getAddress(), uuids, null)
            ) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isRegisteredSamsungDevice(BluetoothDevice device) {
        try {
            java.util.Set<String> uuids = new java.util.HashSet<>();
            if (device.getUuids() != null) {
                for (android.os.ParcelUuid uuid : device.getUuids()) uuids.add(uuid.getUuid().toString());
            }
            return SamsungGalaxyBudsCatalog.INSTANCE.find(
                    new com.melody.melodylink.domain.DeviceIdentity(device.getName(), device.getAddress(), uuids, null)
            ) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTargetObject(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getMacAddress");
            Object address = getter.invoke(value);
            return isTargetAddress(address);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDetailConnectionInfoObject(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getAddress");
            Object address = getter.invoke(value);
            return address instanceof String && isTargetAddress(address);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isRegisteredBoseName(String bluetoothName) {
        return BoseDeviceConfig.INSTANCE.matchesName(bluetoothName);
    }

    @SuppressLint("MissingPermission")
    private boolean isBoseDevice(BluetoothDevice device) {
        try {
            return BoseDeviceConfig.INSTANCE.matches(device.getName(), device.getAddress());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Matches a known Bose MAC in any whitelist argument position. */
    private boolean isBoseAddressArg(Object value) {
        return value instanceof String && BoseDeviceConfig.INSTANCE.matchesAddress((String) value);
    }

    private void writeSharedBoseState() {
        if (!isPrimaryProcess() || targetAddress == null || targetBoseDevice == null) return;
        File file = sharedStateFile();
        if (file == null) return;
        int mode = MelodyStateBridge.INSTANCE.ancModeIndex(boseSessionState.getAnc());
        if (MelodySharedStateStore.writeState(file, targetAddress, android.os.Process.myPid(), mode, null, null)) {
            log(Log.INFO, TAG, event("shared Bose state published addressHash="
                    + Integer.toHexString(targetAddress.hashCode()) + " mode=" + mode));
        } else {
            log(Log.WARN, TAG, "shared Bose state write failed");
        }
        // Publish the confirmed CNC level too, so the :fg detail slider can read it.
        int cnc = boseTransport.getCncLevel();
        if (cnc >= 0) {
            MelodySharedStateStore.writeBoseCncState(boseCncStateFile(), targetAddress, cnc);
        }
    }

    private void clearBoseSessionState() {
        boseSessionState.clear();
        boseHostConnected = false;
        targetBoseDevice = null;
    }

    private boolean isSonyConnected() {
        return targetAddress != null && (sonyTransport.isConnected()
                || samsungTransport.isConnected()
                || huaweiTransport.isConnected()
                || (xiaomiTransport != null && xiaomiTransport.isConnected())
                || (targetXiaomiDevice != null && xiaomiHostConnected)
                || (targetBoseDevice != null && boseHostConnected)
                || targetAddress.equalsIgnoreCase(readSharedSonyAddress()));
    }

    private void rememberTargetAddress(String address) {
        targetAddress = address;
        targetAddressHash = address.hashCode();
    }

    private static boolean isPrimaryProcess() {
        return TARGET.equals(Application.getProcessName());
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Application ? (Application) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The volume-panel tile reads EarphoneControlProvider, which lives in this
     * process but may be instantiated before our package hook runs; install the
     * bridge directly and retry once Application.attach lands.
     */
    private void installProviderBridge(ClassLoader loader) {
        Application application = currentApplication();
        if (application != null && tryInstallBridge(loader, application)) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Context base = chain.getArg(0) instanceof Context
                                    ? (Context) chain.getArg(0) : null;
                            Context app = base == null ? null : base.getApplicationContext();
                            tryInstallBridge(loader, app != null ? app : base);
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "provider bridge attach hook unavailable", t);
        }
    }

    /**
     * Resolve the Bose device for a volume-panel tile action, cold-starting the
     * session from the known MAC when no in-app session has run yet.
     */
    @SuppressLint("MissingPermission")
    private BluetoothDevice resolveBoseForTile() {
        BluetoothDevice device = targetBoseDevice;
        if (device != null && boseHostConnected) {
            boseTransport.setDevice(device); // idempotent re-bind
            return device;
        }
        String mac = BoseDeviceConfig.INSTANCE.getKNOWN_MACS().isEmpty()
                ? null : BoseDeviceConfig.INSTANCE.getKNOWN_MACS().iterator().next();
        if (mac == null) return null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            device = adapter == null ? null : adapter.getRemoteDevice(mac);
        } catch (Throwable ignored) {
            device = null;
        }
        if (device == null) return null;
        targetBoseDevice = device;
        boseHostConnected = true;
        // Bind the transport too: on the main process no Melody connect flow runs
        // for a tile/slider-initiated write, so BoseTransport.device stayed null and
        // every [31.10] write died with "no Bose device selected".
        boseTransport.setDevice(device);
        rememberTargetAddress(mac);
        writeSharedBoseState();
        return device;
    }

    private boolean tryInstallBridge(ClassLoader loader, Context context) {
        if (context == null) return false;
        BoseControlProviderBridge.setLogSink((level, message, error) ->
                log(level, TAG, event(message)));
        BoseControlProviderBridge.install(this, loader, context,
                new BoseControlProviderBridge.Callbacks() {
                    @Override public boolean isBoseActive() {
                        return targetBoseDevice != null && boseHostConnected;
                    }
                    @Override public AncMode currentMode() {
                        EarbudsState state = boseSessionState.getAnc();
                        return state == null ? null : state.getAncMode();
                    }
                    @Override public boolean requestMode(AncMode requested) {
                        BluetoothDevice device = resolveBoseForTile();
                        if (device == null) return false;
                        // The 0.4.0 log disproved the "SystemUI sends a stale target"
                        // theory: it actually sends a correct OFF→ANC→TRANSP cycle. The
                        // real bug was BoseTransport dropping every queued-but-unsent
                        // ANC session on a rapid tap (per-call generation bump), so only
                        // the last landed. That is now fixed with a coalescing worker, so
                        // we honour SystemUI's requested target directly. Mirroring it
                        // into both session states keeps the detail UI and tile in sync.
                        AncMode mode = requested;
                        EarbudsState optimistic = new EarbudsState(
                                com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getCapabilities(),
                                mode, new java.util.HashMap<>());
                        boseSessionState.acceptAnc(optimistic);
                        sonySessionState.acceptAnc(optimistic);
                        writeSharedBoseState();
                        boseTransport.setAncMode(mode);
                        refreshTargetRepository("Bose tile ANC");
                        BoseControlProviderBridge.refreshTile();
                        return true;
                    }
                    @Override public int spatialType() {
                        int type = boseTransport.getSpatialType();
                        return type < 0 ? 0 : type;
                    }
                    @Override public boolean requestSpatial(int type) {
                        BluetoothDevice device = resolveBoseForTile();
                        if (device == null) return false;
                        int clamped = Math.max(0, Math.min(2, type));
                        // Optimistic cache update so the immediately following spatial
                        // query (triggered by our notifyChange) already reports the new
                        // value; the BMAP SETGET confirmation re-syncs the real state.
                        boseTransport.cacheSpatialType(clamped);
                        boseTransport.writeSetting(BoseDeviceConfig.SETTING_SPATIAL, clamped);
                        // The tile's type column comes from the stock DTO, so the DTO
                        // must be rebuilt with the new spatial value before SystemUI
                        // re-queries — same chain the ANC click uses.
                        refreshTargetRepository("Bose tile spatial");
                        BoseControlProviderBridge.refreshSpatialTile();
                        return true;
                    }
                    @Override public String deviceName() {
                        BluetoothDevice device = targetBoseDevice;
                        try {
                            if (device != null && device.getName() != null) return device.getName();
                        } catch (SecurityException ignored) {
                        }
                        return "Bose";
                    }
                    @Override public String deviceAddress() {
                        BluetoothDevice device = targetBoseDevice;
                        if (device != null) return device.getAddress();
                        // Fall back to the field-verified MAC so the tile can answer
                        // SystemUI queries before any in-app session exists.
                        return BoseDeviceConfig.INSTANCE.getKNOWN_MACS().isEmpty()
                                ? null
                                : BoseDeviceConfig.INSTANCE.getKNOWN_MACS().iterator().next();
                    }
                });
        return true;
    }

    private static File sharedStateFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).stateFile();
    }

    private static File sharedCommandFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).commandFile();
    }

    private static File sharedBatteryCommandFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).batteryCommandFile();
    }

    private static File sharedSettingCommandFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).settingCommandFile();
    }

    private static File boseCncStateFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).boseCncStateFile();
    }

    private static File boseExtraStateFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).boseExtraStateFile();
    }

    private static File boseExtraCommandFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).boseExtraCommandFile();
    }

    private static File boseCncCommandFile() {
        Application application = currentApplication();
        return application == null ? null : MelodySharedStateStore.from(application).boseCncCommandFile();
    }

    /**
     * Cross-process Bose presence, used from the :fg detail page where no
     * in-memory device reference exists.
     *
     * Layer 1 asks BluetoothAdapter. Layer 2 is the fallback that actually kept
     * the slider alive in 0.4.3-0.4.9: our own state file, which the primary
     * process writes only after a real BMAP session exists. The UI must never
     * depend on layer 1 alone — that dependency is why the detail page could
     * silently inject nothing.
     */
    @SuppressLint("MissingPermission")
    private boolean boseBonded() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                for (String mac : com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getKNOWN_MACS()) {
                    try {
                        BluetoothDevice device = adapter.getRemoteDevice(mac);
                        if (device == null) continue;
                        if (device.getBondState() == BluetoothDevice.BOND_BONDED) return true;
                        if (isDeviceConnected(device)) return true;
                    } catch (Throwable ignored) {
                        // getRemoteDevice throws for an unknown MAC; try the next one
                    }
                }
                // 0.5.37: the KNOWN_MACS list is a fixed set, but the unit that actually
                // reports bose=true in the whitelist lookup is whichever MAC isTargetAddress
                // accepts — on this device 68:F2:1F:3D:41:D7, which is not in that list.
                // Gating the catalog injection on boseBonded() therefore suppressed it
                // entirely. Fall back to asking isTargetAddress about the live bonded set,
                // so both checks agree on what "our device" means.
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (device == null) continue;
                    String address = device.getAddress();
                    if (address != null && isTargetAddress(address)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return MelodySharedStateStore.readBoseCncAddress(boseCncStateFile()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * BluetoothDevice.getConnectionState() / STATE_CONNECTED are not resolvable
     * against every android.jar the build may use, so they are called
     * reflectively: a compile-time reference broke CI with "cannot find symbol"
     * while the local android-36 stub still had them. Reflection also degrades
     * gracefully on older platform levels.
     */
    private static boolean isDeviceConnected(BluetoothDevice device) {
        try {
            Method getter = device.getClass().getMethod("getConnectionState");
            Object state = getter.invoke(device);
            // STATE_CONNECTED == 2 on every platform level that has the method.
            return state instanceof Integer && (Integer) state == 2;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void writeSharedSonyState() {
        if (!isPrimaryProcess() || targetAddress == null) return;
        File file = sharedStateFile();
        if (file == null) return;
        EarbudsState state = sonySessionState.getAnc();
        int mode = MelodyStateBridge.INSTANCE.ancModeIndex(state);
        if (MelodySharedStateStore.writeState(file, targetAddress, android.os.Process.myPid(), mode,
                confirmedSonySettings.get(SonyAdvancedSettingId.DSEE),
                confirmedSonySettings.get(SonyAdvancedSettingId.PAUSE_WHEN_REMOVED))) {
            log(Log.INFO, TAG, event("shared Sony state published addressHash="
                    + Integer.toHexString(targetAddress.hashCode()) + " mode=" + mode));
        } else {
            log(Log.WARN, TAG, "shared Sony state write failed");
        }
    }

    private void writeSharedHuaweiState() {
        if (!isPrimaryProcess() || targetAddress == null || targetHuaweiDevice == null) return;
        File file = sharedStateFile();
        if (file == null) return;
        int mode = MelodyStateBridge.INSTANCE.ancModeIndex(huaweiSessionState.getAnc());
        if (MelodySharedStateStore.writeState(file, targetAddress, android.os.Process.myPid(), mode, null, null)) {
            log(Log.INFO, TAG, event("shared Huawei state published addressHash="
                    + Integer.toHexString(targetAddress.hashCode()) + " mode=" + mode));
        } else {
            log(Log.WARN, TAG, "shared Huawei state write failed");
        }
    }

    /**
     * Mirrors the host Bluetooth connection rather than AF00 readiness.  The target app has
     * a separate foreground process, so the A2DP/HFP connection marker must cross processes
     * even when Xiaomi's optional control channel cannot be established.
     */
    private void writeSharedXiaomiState() {
        if (!isPrimaryProcess() || targetAddress == null || targetXiaomiDevice == null || !xiaomiHostConnected) return;
        File file = sharedStateFile();
        if (file == null) return;
        int mode = MelodyStateBridge.INSTANCE.ancModeIndex(xiaomiSessionState.getAnc());
        if (MelodySharedStateStore.writeState(file, targetAddress, android.os.Process.myPid(), mode, null, null)) {
            log(Log.INFO, TAG, event("shared Xiaomi host connection published addressHash="
                    + Integer.toHexString(targetAddress.hashCode()) + " mode=" + mode));
        } else {
            log(Log.WARN, TAG, "shared Xiaomi state write failed");
        }
    }

    private void clearHuaweiSessionState() {
        huaweiSessionState.clear();
        if (!isPrimaryProcess() || targetHuaweiDevice == null) return;
        File file = sharedStateFile();
        MelodySharedStateStore.SharedState state = MelodySharedStateStore.readState(file);
        if (state != null && targetAddress != null && targetAddress.equalsIgnoreCase(state.address)) {
            clearSharedSonyState();
        }
        targetHuaweiDevice = null;
    }

    /** Removes malformed or previous-process markers while retaining a live hook re-entry marker. */
    private void clearStaleSharedSonyState() {
        if (!isPrimaryProcess()) return;
        File file = sharedStateFile();
        if (file == null || !file.isFile()) return;
        MelodySharedStateStore.SharedState state = readSharedSonyState();
        if (state != null && state.ownerPid == android.os.Process.myPid()) {
            log(Log.INFO, TAG, event("preserved live shared Sony state during package initialization"));
            return;
        }
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "stale shared Sony state delete failed");
        } else {
            log(Log.INFO, TAG, event("cleared stale shared Sony state during package initialization"));
        }
    }

    private void clearSharedSonyState() {
        if (!isPrimaryProcess()) return;
        File file = sharedStateFile();
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "shared Sony state delete failed");
        }
    }

    private boolean writeSharedSonyCommand(String address, int modeIndex) {
        File file = sharedCommandFile();
        if (file == null) return false;
        boolean written = MelodySharedStateStore.writeCommand(file, address, modeIndex, Long.toString(System.nanoTime()));
        if (!written) log(Log.WARN, TAG, "shared Sony ANC command write failed");
        return written;
    }

    private void releaseSonySession(String reason) {
        retainSharedSonyStateAfterCommandDisconnect = false;
        pendingAncMode = null;
        pendingBatteryRefresh = false;
        failPendingNoiseWrite(reason);
        sonySessionState.clear();
        clearSharedSonyState();
        clearSharedSonyCommand();
        clearSharedSonyBatteryCommand();
        clearSharedSonySettingCommand();
        log(Log.INFO, TAG, event(reason + "; releasing Sony RFCOMM session"));
        sonyTransport.disconnect();
        samsungTransport.disconnect();
        huaweiTransport.disconnect();
        if (xiaomiTransport != null) xiaomiTransport.disconnect();
        boseTransport.disconnect();
        targetSamsungDevice = null;
        targetHuaweiDevice = null;
        targetXiaomiDevice = null;
        xiaomiHostConnected = false;
        clearBoseSessionState();
        refreshTargetRepository(reason);
    }

    private void registerAppVisibilityLifecycleCallbacks() {
        if (activityLifecycleRegistered) return;
        Application application = currentApplication();
        if (application == null) {
            log(Log.WARN, TAG, event("Melody activity lifecycle observer unavailable: application is null"));
            return;
        }
        synchronized (this) {
            if (activityLifecycleRegistered) return;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, android.os.Bundle state) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    startedActivityCount++;
                }

                @Override
                public void onActivityResumed(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    startedActivityCount = Math.max(0, startedActivityCount - 1);
                    if (startedActivityCount != 0 || activity.isChangingConfigurations()) return;
                    mainHandler.postDelayed(() -> {
                        if (startedActivityCount != 0 || targetAddress == null) return;
                        releaseSonySession("Melody left foreground");
                    }, 400L);
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
            activityLifecycleRegistered = true;
            log(Log.INFO, TAG, event("registered Melody app visibility lifecycle observer"));
        }
    }

    private boolean isRegisteredSonyName(String bluetoothName) {
        return initializeSonyConfig() && deviceBridge.isRegisteredDevice(bluetoothName);
    }

    private boolean isRegisteredHuaweiName(String bluetoothName) {
        if (!initializeSonyConfig()) return false;
        return HuaweiDeviceCatalog.INSTANCE.find(
                new com.melody.melodylink.domain.DeviceIdentity(bluetoothName, null,
                        java.util.Collections.emptySet(), null)
        ) != null;
    }

    private void clearSharedSonyCommand() {
        File file = sharedCommandFile();
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "shared Sony ANC command delete failed");
        }
    }

    private void requestSonyBatteryRefresh() {
        String address = targetAddress;
        if (address == null) address = readSharedSonyAddress();
        if (!isTargetAddress(address)) return;
        if (isPrimaryProcess()) {
            if (targetBoseDevice != null && boseHostConnected) {
                boseTransport.refreshBattery();
                return;
            }
            if (sonyTransport.isConnected()) {
                sonyTransport.refreshBattery();
            } else {
                pendingBatteryRefresh = true;
                if (!connectTargetSonyTransport("battery refresh")) {
                    pendingBatteryRefresh = false;
                    log(Log.WARN, TAG, event("Sony battery refresh skipped: target Bluetooth device is unavailable"));
                }
            }
            return;
        }
        writeSharedSonyBatteryCommand(address);
    }

    @SuppressLint("MissingPermission")
    private boolean connectTargetSonyTransport(String reason) {
        BluetoothDevice device = resolveTargetSonyDevice();
        if (device == null) return false;
        log(Log.INFO, TAG, event("opening temporary Sony RFCOMM session for " + reason
                + " addressHash=" + Integer.toHexString(device.getAddress().hashCode())));
        sonyTransport.connect(device);
        return true;
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice resolveTargetSonyDevice() {
        BluetoothDevice remembered = targetSonyDevice;
        if (remembered != null && isTargetDevice(remembered)) return remembered;
        String address = targetAddress;
        if (address == null) return null;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return null;
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (!isTargetDevice(device)) return null;
            targetSonyDevice = device;
            return device;
        } catch (IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private void runPendingSonyOperation() {
        AncMode mode = pendingAncMode;
        if (mode != null) {
            pendingAncMode = null;
            log(Log.INFO, TAG, event("sending queued Sony ANC command after temporary connection"));
            sonyTransport.setAncMode(mode);
            return;
        }
        if (!pendingSonySettings.isEmpty()) {
            Map<SonyAdvancedSettingId, Boolean> pending = new java.util.HashMap<>(pendingSonySettings);
            pendingSonySettings.keySet().removeAll(pending.keySet());
            for (Map.Entry<SonyAdvancedSettingId, Boolean> entry : pending.entrySet()) {
                log(Log.INFO, TAG, event("sending queued Sony setting " + entry.getKey()));
                sonyTransport.writeSetting(entry.getKey(), entry.getValue());
            }
        }
        if (pendingBatteryRefresh) {
            pendingBatteryRefresh = false;
            log(Log.INFO, TAG, event("sending queued Sony battery refresh after temporary connection"));
            sonyTransport.refreshBattery();
        }
    }

    private boolean writeSharedSonyBatteryCommand(String address) {
        File file = sharedBatteryCommandFile();
        if (file == null) return false;
        boolean written = MelodySharedStateStore.writeBatteryCommand(file, address, Long.toString(System.nanoTime()));
        if (!written) log(Log.WARN, TAG, "shared Sony battery command write failed");
        return written;
    }

    private boolean writeSharedSonySettingCommand(String address, SonyAdvancedSettingId id, boolean value) {
        File file = sharedSettingCommandFile();
        if (file == null) return false;
        boolean written = MelodySharedStateStore.writeSettingCommand(
                file, address, id.name(), value, Long.toString(System.nanoTime()));
        if (!written) log(Log.WARN, TAG, "shared Sony setting command write failed");
        return written;
    }

    private void clearSharedSonyBatteryCommand() {
        File file = sharedBatteryCommandFile();
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "shared Sony battery command delete failed");
        }
    }

    private void clearSharedSonySettingCommand() {
        File file = sharedSettingCommandFile();
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "shared Sony setting command delete failed");
        }
    }

    private static MelodySharedStateStore.SharedBatteryCommand readSharedSonyBatteryCommand() {
        return MelodySharedStateStore.readBatteryCommand(sharedBatteryCommandFile());
    }

    private static MelodySharedStateStore.SharedSettingCommand readSharedSonySettingCommand() {
        return MelodySharedStateStore.readSettingCommand(sharedSettingCommandFile());
    }

    private void installAdvancedSettings(Object anchor) {
        boolean hasSonySettings = activeSonyImageProfile != null
                && !activeSonyImageProfile.getAdvancedSettings().isEmpty();
        boolean hasHuaweiLowLatency = activeHuaweiImageProfile != null
                && activeHuaweiImageProfile.getSupportsLowLatency();
        if (!isAdvancedSettingsAnchor(anchor) || (!hasSonySettings && !hasHuaweiLowLatency)) return;
        try {
            Object soundGroup = invokeNoArg(anchor, "getParent");
            if (soundGroup == null) return;
            Object parent = invokeNoArg(soundGroup, "getParent");
            if (parent == null) parent = soundGroup;
            if (findPreference(parent, ADVANCED_CATEGORY_KEY) != null
                    || findPreferenceByKeyRecursive(parent, ADVANCED_CATEGORY_KEY)) return;
            ClassLoader loader = anchor.getClass().getClassLoader();
            Object context = invokeNoArg(anchor, "getContext");
            Activity activity = context instanceof Context ? findActivity((Context) context) : null;
            if (activity == null) activity = detailActivity;
            if (activity == null) return;
            Object category = newPreference(loader,
                    "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
            if (category == null) category = newPreference(loader,
                    "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
            if (category == null) return;
            setPreferenceValue(category, "setTitle", "高级设置");
            setPreferenceValue(category, "setKey", ADVANCED_CATEGORY_KEY);
            Integer order = (Integer) invokeNoArg(soundGroup, "getOrder");
            if (order != null) setPreferenceValue(category, "setOrder", order + 1);
            if (!addPreference(parent, category, loader)) {
                log(Log.WARN, TAG, event("advanced settings category could not be added"));
                return;
            }
            advancedPreferences.clear();
            huaweiLowLatencyPreference = null;
            if (hasHuaweiLowLatency) {
                addHuaweiLowLatencyPreference(category, loader, activity, 10);
                log(Log.INFO, TAG, event("installed Huawei low-latency setting"));
                return;
            }
            if (isPrimaryProcess() && !sonyTransport.isConnected()) {
                connectTargetSonyTransport("advanced settings read");
            }
            for (com.melody.melodylink.sony.config.SonyAdvancedSettingConfig setting
                    : activeSonyImageProfile.getAdvancedSettings()) {
                Object item = newSwitchPreference(loader, activity);
                if (item == null) {
                    log(Log.WARN, TAG, event("advanced setting switch constructor unavailable id="
                            + setting.getId()));
                    continue;
                }
                String key = ADVANCED_SETTING_KEY_PREFIX + setting.getId().name().toLowerCase();
                setPreferenceValue(item, "setKey", key);
                setPreferenceValue(item, "setOrder", setting.getOrder());
                setPreferenceValue(item, "setTitle", setting.getId() == SonyAdvancedSettingId.DSEE
                        ? "DSEE" : "摘下暂停");
                setPreferenceValue(item, "setSummary", setting.getId() == SonyAdvancedSettingId.DSEE
                        ? "提升压缩音源的音质" : "摘下耳机时自动暂停播放");
                setPreferenceValue(item, "setEnabled", false);
                installSettingListener(item, setting.getId(), loader);
                if (addPreference(category, item, loader)) {
                    advancedPreferences.put(setting.getId(), item);
                    if (sonyTransport.isConnected()) sonyTransport.readSetting(setting.getId());
                } else {
                    log(Log.WARN, TAG, event("advanced setting add rejected id=" + setting.getId()));
                }
            }
            log(Log.INFO, TAG, event("installed Sony advanced settings category"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "advanced settings installation failed", t);
        }
    }

    private void schedulePreferenceFragmentBinding(Object hostFragment) {
        if (hostFragment == null) return;
        Object activityValue = invokeNoArg(hostFragment, "getActivity");
        if (!(activityValue instanceof Activity)) {
            log(Log.WARN, TAG, event("advanced settings host activity unavailable"));
            return;
        }
        Activity activity = (Activity) activityValue;
        Object manager = invokeNoArg(hostFragment, "getChildFragmentManager");
        if (manager == null) {
            log(Log.WARN, TAG, event("advanced settings child fragment manager unavailable"));
            return;
        }
        long[] delays = new long[]{0L, 50L, 200L, 500L, 1000L};
        for (int i = 0; i < delays.length; i++) {
            final boolean reportFailure = i == delays.length - 1;
            mainHandler.postDelayed(() -> {
                try {
                    Object fragment = findTaggedFragment(manager, "DetailMainPreferenceFragment");
                    if (fragment == null) {
                        if (reportFailure) log(Log.WARN, TAG,
                                event("advanced settings preference fragment not found"));
                        return;
                    }
                    if (!"v9.z".equals(fragment.getClass().getName())) {
                        if (reportFailure) log(Log.WARN, TAG, event(
                                "advanced settings unexpected preference fragment="
                                        + fragment.getClass().getName()));
                        return;
                    }
                    installAdvancedSettingsFromPreferenceFragment(activity, fragment);
                } catch (Throwable t) {
                    if (reportFailure) log(Log.WARN, TAG,
                            "advanced settings fragment binding failed", t);
                }
            }, delays[i]);
        }
    }

    private void scheduleDirectPreferenceFragmentBinding(Object fragment) {
        if (fragment == null) return;
        // Sony-only path. For Bose the tree walk below always bails out at
        // "if (!hasSonySettings && !hasHuaweiLowLatency) return;", so scheduling
        // five retries just re-entered it for nothing while the page was laying
        // out. All Bose injection goes through captureNoiseEffectRow instead.
        Object activityValue = invokeNoArg(fragment, "getActivity");
        if (!(activityValue instanceof Activity)) return;
        if (boseBonded()) return;
        Activity activity = (Activity) activityValue;
        long[] delays = new long[]{0L, 50L, 200L, 500L, 1000L};
        for (int i = 0; i < delays.length; i++) {
            final boolean reportFailure = i == delays.length - 1;
            mainHandler.postDelayed(() -> {
                try {
                    installAdvancedSettingsFromPreferenceFragment(activity, fragment);
                } catch (Throwable t) {
                    if (reportFailure) log(Log.WARN, TAG,
                            "direct advanced settings preference binding failed", t);
                }
            }, delays[i]);
        }
    }

    private static Object findTaggedFragment(Object manager, String tag) {
        for (Method method : allMethods(manager.getClass())) {
            if (!method.getName().equals("D") || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != String.class) continue;
            try {
                method.setAccessible(true);
                return method.invoke(manager, tag);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void installAdvancedSettingsFromPreferenceFragment(Activity activity, Object fragment) {
        boolean hasSonySettings = activeSonyImageProfile != null
                && !activeSonyImageProfile.getAdvancedSettings().isEmpty();
        boolean hasHuaweiLowLatency = activeHuaweiImageProfile != null
                && activeHuaweiImageProfile.getSupportsLowLatency();
        // Bose: expose the 10-level CNC slider in the detail page. The detail
        // fragment binds in the :fg process where targetBoseDevice is never set,
        // so presence must be the cross-process bond probe, not in-memory state.
        boolean hasBoseCnc = boseBonded();
        if (!hasSonySettings && !hasHuaweiLowLatency && !hasBoseCnc) return;
        try {
            ClassLoader loader = fragment.getClass().getClassLoader();
            Class<?> managerType = Class.forName("androidx.preference.g", false, loader);
            Object preferenceManager = null;
            for (Field field : allFields(managerType)) {
                if (field.getType().getName().equals("androidx.preference.k")) {
                    field.setAccessible(true);
                    preferenceManager = field.get(fragment);
                    if (preferenceManager != null) break;
                }
            }
            if (preferenceManager == null) throw new IllegalStateException("preference manager unavailable");
            Object screen = null;
            for (Field field : allFields(preferenceManager.getClass())) {
                if (field.getType().getName().equals("androidx.preference.PreferenceScreen")) {
                    field.setAccessible(true);
                    screen = field.get(preferenceManager);
                    if (screen != null) break;
                }
            }
            if (screen == null) throw new IllegalStateException("preference screen unavailable");
            // Bose CNC slider goes directly under the "降噪效果" row on the detail
            // page. The privacy page reuses the same fragment class but has no such
            // row, so this lookup doubles as the page guard (0.4.1 regression: the
            // root-screen fallback dumped "高级设置" at the bottom of every page).
            // The Bose CNC slider is injected from the detailPreferenceAdd hook
            // (captureNoiseEffectRow), which anchors on the real NoiseReductionItem
            // instance as Melody adds it. 0.4.3 lost the slider whenever this
            // tree walk ran before the row existed. Here we only make sure the
            // dead-end "降噪效果" row stays hidden even if the add hook missed it.
            // The "降噪效果" row is deliberately left visible on both pages: it is
            // the live three-state ANC switch we hijack. 0.4.5-0.4.9 hid it, which
            // removed the ANC mode control the user relies on.
            if (!hasSonySettings && !hasHuaweiLowLatency) return;
            Object anchor = findPreferenceByTitle(screen, SOUND_QUALITY_TITLE);
            if (anchor == null) return;
            Object parent = invokeNoArg(anchor, "getParent");
            if (parent == null) return;
            if (findPreference(parent, ADVANCED_CATEGORY_KEY) != null
                    || findPreferenceByKeyRecursive(parent, ADVANCED_CATEGORY_KEY)) return;
            Object category = newPreference(loader,
                    "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
            if (category == null) category = newPreference(loader,
                    "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
            if (category == null) throw new IllegalStateException("category constructor unavailable");
            setPreferenceValue(category, "setTitle", "\u9ad8\u7ea7\u8bbe\u7f6e");
            setPreferenceValue(category, "setKey", ADVANCED_CATEGORY_KEY);
            Integer order = anchor == null ? null : (Integer) invokeNoArg(anchor, "getOrder");
            if (order != null) setPreferenceValue(category, "setOrder", order + 1);
            if (!addPreference(parent, category, loader)) throw new IllegalStateException("category add rejected");
            advancedPreferences.clear();
            huaweiLowLatencyPreference = null;
            if (hasHuaweiLowLatency) {
                addHuaweiLowLatencyPreference(category, loader, activity, 10);
                log(Log.INFO, TAG, event("installed Huawei low-latency setting via preference fragment"));
                return;
            }
            for (com.melody.melodylink.sony.config.SonyAdvancedSettingConfig setting
                    : activeSonyImageProfile.getAdvancedSettings()) {
                Object item = newSwitchPreference(loader, activity);
                if (item == null) continue;
                String key = ADVANCED_SETTING_KEY_PREFIX + setting.getId().name().toLowerCase();
                setPreferenceValue(item, "setKey", key);
                setPreferenceValue(item, "setOrder", setting.getOrder());
                setPreferenceValue(item, "setTitle", setting.getId() == SonyAdvancedSettingId.DSEE
                        ? "DSEE" : "\u6458\u4e0b\u6682\u505c");
                setPreferenceValue(item, "setSummary", setting.getId() == SonyAdvancedSettingId.DSEE
                        ? "\u63d0\u5347\u538b\u7f29\u97f3\u6e90\u7684\u97f3\u8d28"
                        : "\u6458\u4e0b\u8033\u673a\u65f6\u81ea\u52a8\u6682\u505c\u64ad\u653e");
                setPreferenceValue(item, "setPersistent", false);
                setPreferenceValue(item, "setVisible", true);
                setPreferenceValue(item, "setEnabled", false);
                installSettingListener(item, setting.getId(), loader);
                if (addPreference(category, item, loader)) {
                    advancedPreferences.put(setting.getId(), item);
                    if (isPrimaryProcess()) {
                        if (!sonyTransport.isConnected()) connectTargetSonyTransport("advanced settings read");
                        else sonyTransport.readSetting(setting.getId());
                    } else {
                        setPreferenceValue(item, "setEnabled", true);
                        applySharedAdvancedSettings(readSharedSonyState());
                    }
                }
            }
            log(Log.INFO, TAG, event("advanced settings installed via preference fragment anchor="
                    + anchor.getClass().getName() + " fragment=" + fragment.getClass().getName()));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "advanced settings preference fragment installation failed", t);
        }
    }

    private static Object findPreferenceByClass(Object group, String className) {
        if (group == null) return null;
        if (group.getClass().getName().equals(className)) return group;
        Field childrenField = null;
        for (Field field : allFields(group.getClass())) {
            if (Collection.class.isAssignableFrom(field.getType())
                    || java.util.List.class.isAssignableFrom(field.getType())) {
                childrenField = field;
                break;
            }
        }
        if (childrenField == null) return null;
        try {
            childrenField.setAccessible(true);
            Object value = childrenField.get(group);
            if (!(value instanceof Collection)) return null;
            for (Object child : (Collection<?>) value) {
                Object found = findPreferenceByClass(child, className);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findPreferenceByTitle(Object group, String title) {
        if (group == null) return null;
        Object currentTitle = invokeNoArg(group, "getTitle");
        if (currentTitle != null && title.equals(currentTitle.toString().trim())) return group;
        Field childrenField = null;
        for (Field field : allFields(group.getClass())) {
            if (Collection.class.isAssignableFrom(field.getType())) {
                childrenField = field;
                break;
            }
        }
        if (childrenField == null) return null;
        try {
            childrenField.setAccessible(true);
            Object children = childrenField.get(group);
            if (!(children instanceof Collection)) return null;
            for (Object child : (Collection<?>) children) {
                Object found = findPreferenceByTitle(child, title);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean findPreferenceByKeyRecursive(Object group, String key) {
        if (group == null) return false;
        Object value = invokeNoArg(group, "getKey");
        if (key.equals(value)) return true;
        Field childrenField = null;
        for (Field field : allFields(group.getClass())) {
            if (Collection.class.isAssignableFrom(field.getType())
                    || java.util.List.class.isAssignableFrom(field.getType())) {
                childrenField = field;
                break;
            }
        }
        if (childrenField == null) return false;
        try {
            childrenField.setAccessible(true);
            Object children = childrenField.get(group);
            if (children instanceof Collection) {
                for (Object child : (Collection<?>) children) {
                    if (findPreferenceByKeyRecursive(child, key)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private static Field[] allFields(Class<?> type) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) fields.add(field);
        }
        return fields.toArray(new Field[0]);
    }

    private static Method[] allMethods(Class<?> type) {
        java.util.ArrayList<Method> methods = new java.util.ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) methods.add(method);
        }
        return methods.toArray(new Method[0]);
    }

    private void updateAdvancedSetting(SonyAdvancedSettingId id, Boolean value) {
        Object preference = advancedPreferences.get(id);
        if (preference == null || value == null) return;
        mainHandler.post(() -> {
            setPreferenceValue(preference, "setChecked", value);
            setPreferenceValue(preference, "setEnabled", true);
        });
    }

    private void applySharedAdvancedSettings(MelodySharedStateStore.SharedState state) {
        if (state == null) return;
        if (state.dsee != null) updateAdvancedSetting(SonyAdvancedSettingId.DSEE, state.dsee);
        if (state.pauseWhenRemoved != null) {
            updateAdvancedSetting(SonyAdvancedSettingId.PAUSE_WHEN_REMOVED, state.pauseWhenRemoved);
        }
    }

    private void setAdvancedSettingEnabled(SonyAdvancedSettingId id, boolean enabled) {
        Object preference = advancedPreferences.get(id);
        if (preference == null) return;
        mainHandler.post(() -> setPreferenceValue(preference, "setEnabled", enabled));
    }

    private boolean isRegisteredXiaomiName(String bluetoothName) {
        return initializeSonyConfig() && findXiaomiProfileByName(bluetoothName) != null;
    }

    @SuppressLint("MissingPermission")
    private boolean isRegisteredXiaomiDevice(BluetoothDevice device) {
        try {
            java.util.Set<String> uuids = new java.util.HashSet<>();
            if (device.getUuids() != null) {
                for (android.os.ParcelUuid uuid : device.getUuids()) uuids.add(uuid.getUuid().toString());
            }
            return XiaomiDeviceCatalog.INSTANCE.find(
                    new com.melody.melodylink.domain.DeviceIdentity(device.getName(), device.getAddress(), uuids, null)
            ) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void addHuaweiLowLatencyPreference(Object category, ClassLoader loader, Activity activity, int order) {
        Object item = newSwitchPreference(loader, activity);
        if (item == null) {
            log(Log.WARN, TAG, event("Huawei low-latency switch constructor unavailable"));
            return;
        }
        setPreferenceValue(item, "setKey", HUAWEI_LOW_LATENCY_SETTING_KEY);
        setPreferenceValue(item, "setOrder", order);
        setPreferenceValue(item, "setTitle", "低时延模式");
        setPreferenceValue(item, "setSummary", "降低游戏和视频的音频延迟");
        setPreferenceValue(item, "setPersistent", false);
        setPreferenceValue(item, "setChecked", confirmedHuaweiLowLatency != null && confirmedHuaweiLowLatency);
        setPreferenceValue(item, "setEnabled", huaweiTransport.isConnected());
        installHuaweiLowLatencyListener(item, loader);
        if (addPreference(category, item, loader)) huaweiLowLatencyPreference = item;
    }

    private void updateHuaweiLowLatencyPreference(Boolean value, boolean enabled) {
        Object preference = huaweiLowLatencyPreference;
        if (preference == null) return;
        mainHandler.post(() -> {
            if (value != null) setPreferenceValue(preference, "setChecked", value);
            setPreferenceValue(preference, "setEnabled", enabled);
        });
    }

    private void installHuaweiLowLatencyListener(Object preference, ClassLoader loader) {
        Method listenerSetter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnPreferenceChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                listenerSetter = candidate;
                break;
            }
        }
        if (listenerSetter == null || !listenerSetter.getParameterTypes()[0].isInterface()) return;
        Class<?> listenerType = listenerSetter.getParameterTypes()[0];
        Method callback = null;
        for (Method candidate : listenerType.getMethods()) {
            if (candidate.getReturnType() == Boolean.TYPE && candidate.getParameterTypes().length == 2) {
                callback = candidate;
                break;
            }
        }
        final Method changeCallback = callback;
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if ("toString".equals(method.getName())) return "MelodyLinkHuaweiLowLatencyListener";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
            if (changeCallback == null || !method.getName().equals(changeCallback.getName())
                    || args == null || args.length < 2 || !(args[1] instanceof Boolean)) return null;
            if (!isPrimaryProcess() || !huaweiTransport.isConnected()) {
                log(Log.WARN, TAG, event("Huawei low-latency write skipped: RFCOMM session unavailable"));
                updateHuaweiLowLatencyPreference(null, huaweiTransport.isConnected());
                return false;
            }
            setPreferenceValue(preference, "setEnabled", false);
            huaweiTransport.setLowLatency((Boolean) args[1]);
            return true;
        });
        try {
            listenerSetter.setAccessible(true);
            listenerSetter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Huawei low-latency listener attach failed", t);
        }
    }

    /**
     * Inject a 0..10 SeekBar preference that drives Bose's [31.10] byte 0 (CNC
     * noise-cancelling intensity). Uses androidx SeekBarPreference reflectively
     * (Melody bundles androidx.preference); a dynamic Proxy listens for changes.
     */
    /**
     * Inject a 0..10 CNC slider using Melody's OWN COUI seekbar preference so it
     * matches ColorOS 17 styling (the plain androidx SeekBarPreference looked
     * foreign and its max field is R8-renamed, which broke the range). Melody's
     * widget exposes clean public setBarMaxValue/setProgress/setOnTrackChangeListener.
     * Placed directly under the "降噪效果" row, not in a buried category.
     */
    private boolean addBoseCncPreference(
            Object parent, ClassLoader loader, Activity activity, int order) {
        if (parent == null) return false;
        Object card = newPreference(loader,
                "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
        if (card == null) card = newPreference(loader,
                "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
        Object host = card != null ? card : parent;
        if (card != null) {
            setPreferenceValue(card, "setKey", BOSE_CNC_CARD_KEY);
            if (order >= 0) setPreferenceValue(card, "setOrder", order);
            if (!addPreference(parent, card, loader)) host = parent;
        }
        Object seek = newPreference(loader,
                "com.oplus.melody.ui.widget.MelodyPromptVolumeSeekBarPreference", activity);
        if (seek == null) {
            log(Log.WARN, TAG, event("Bose CNC slider unavailable: COUI seekbar ctor failed"));
            return false;
        }
        setPreferenceValue(seek, "setKey", BOSE_CNC_KEY);
        setPreferenceValue(seek, "setTitle", "\u964d\u566a\u7b49\u7ea7");
        setPreferenceValue(seek, "setPersistent", false);
        // Raw 0..10 readout, not a percentage.
        setPreferenceValue(seek, "setPromptVolumePercent", Boolean.FALSE);
        invokeInt(seek, "setBarMaxValue", 10);
        int level = boseTransport.getCncLevel();
        if (level < 0) {
            int[] shared = MelodySharedStateStore.readBoseCncState(boseCncStateFile());
            if (shared != null) level = shared[1];
        }
        if (level < 0) level = 3;
        invokeInt(seek, "setProgress", level);
        setPreferenceValue(seek, "setSummary", "\u6548\u679c\u5f3a\u5ea6 " + level + "/10");
        setPreferenceValue(seek, "setOrder", 0);
        installBoseCncListener(seek, loader);
        if (!addPreference(host, seek, loader)) {
            log(Log.WARN, TAG, event("Bose CNC slider add rejected by parent"));
            return false;
        }
        boseCncPreference = seek;
        return true;
    }

    // ------------------------------------------------- EQ / buttons / mode slots / power

    /** Command kinds mirrored in MelodySharedStateStore. */
    private static final int CMD_EQ_BAND = 0;
    private static final int CMD_BUTTON = 1;
    private static final int CMD_MODE_SLOT = 2;
    private static final int CMD_STANDBY = 3;
    private static final int CMD_POWER_OFF = 4;

    private static final String BOSE_EXTRA_CATEGORY_KEY = "melodylink.bose.extra";

    /**
     * Builds the "Bose 音效" category: 3-band EQ, Action-button remaps, custom
     * mode slots, auto-off timer and power off. Everything is forwarded to the
     * primary process (which owns the BMAP session) through one command file.
     */
    private void addBoseExtraCategory(
            Object group, ClassLoader loader, Activity activity, int order) {
        if (findPreferenceByKeyRecursive(group, BOSE_EXTRA_CATEGORY_KEY)) return;
        int[] shared = MelodySharedStateStore.readBoseExtraState(boseExtraStateFile());

        // One category per feature. 0.5.5 crammed EQ + buttons + 6 mode slots +
        // standby + power into a single COUIPreferenceCategory (15 rows) and the
        // detail page rendered blank. Splitting them keeps every card short
        // enough for the stock layout to measure, and each one is skipped
        // independently so a failure in one cannot take the others down.
        addBoseEqCard(group, loader, activity, order, shared);
        addBoseButtonCard(group, loader, activity, order + 10, shared);
        addBoseModeSlotCard(group, loader, activity, order + 20, shared);
        addBosePowerCard(group, loader, activity, order + 30);

        log(Log.INFO, TAG, event("installed Bose extras as 4 separate cards"));
    }

    /** Creates the COUI card wrapper every Bose feature below lives in. */
    private Object newBoseCard(Object parent, ClassLoader loader, Activity activity,
            String key, String title, int order) {
        Object category = newPreference(loader,
                "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
        if (category == null) category = newPreference(loader,
                "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
        if (category == null) return null;
        setPreferenceValue(category, "setTitle", title);
        setPreferenceValue(category, "setKey", key);
        if (order >= 0) setPreferenceValue(category, "setOrder", order);
        if (!addPreference(parent, category, loader)) return null;
        return category;
    }

    private void addBoseEqCard(Object parent, ClassLoader loader, Activity activity,
            int order, int[] shared) {
        if (findPreferenceByKeyRecursive(parent, BOSE_EXTRA_CATEGORY_KEY)) return;
        Object card = newBoseCard(parent, loader, activity, BOSE_EXTRA_CATEGORY_KEY,
                "Bose \u97f3\u6548", order);
        if (card == null) {
            log(Log.WARN, TAG, event("Bose EQ card unavailable"));
            return;
        }
        boseExtraCategory = card;
        boseEqSliders.clear();
        int bass = shared != null && shared.length > 0 && shared[0] >= -10 ? shared[0] : 0;
        int mid = shared != null && shared.length > 1 && shared[1] >= -10 ? shared[1] : 0;
        int treble = shared != null && shared.length > 2 && shared[2] >= -10 ? shared[2] : 0;
        addBoseBandSlider(card, loader, activity, 0, "\u4f4e\u9891", 0, bass);
        addBoseBandSlider(card, loader, activity, 1, "\u4e2d\u9891", 1, mid);
        addBoseBandSlider(card, loader, activity, 2, "\u9ad8\u9891", 2, treble);
    }

    private void addBoseButtonCard(Object parent, ClassLoader loader, Activity activity,
            int order, int[] shared) {
        String key = BOSE_EXTRA_CATEGORY_KEY + ".button";
        if (findPreferenceByKeyRecursive(parent, key)) return;
        Object card = newBoseCard(parent, loader, activity, key,
                "Bose \u6309\u952e", order);
        if (card == null) return;
        boseButtonDropdowns.clear();
        int[] events = {com.melody.melodylink.bose.BoseBmap.EVENT_SINGLE_PRESS,
                com.melody.melodylink.bose.BoseBmap.EVENT_LONG_PRESS,
                com.melody.melodylink.bose.BoseBmap.EVENT_DOUBLE_PRESS};
        String[] eventNames = {"\u5355\u51fb", "\u957f\u6309", "\u53cc\u51fb"};
        for (int i = 0; i < events.length; i++) {
            int current = shared != null && shared.length > 3 + i ? shared[3 + i] : 0;
            addBoseButtonRow(card, loader, activity, i, eventNames[i], events[i], current);
        }
    }

    private void addBoseModeSlotCard(Object parent, ClassLoader loader, Activity activity,
            int order, int[] shared) {
        String key = BOSE_EXTRA_CATEGORY_KEY + ".slot";
        if (findPreferenceByKeyRecursive(parent, key)) return;
        Object card = newBoseCard(parent, loader, activity, key,
                "Bose \u6a21\u5f0f\u69fd\u4f4d", order);
        if (card == null) return;
        boseModeSlotSliders.clear();
        addBoseModeSlotHint(card, loader, activity, 0);
        for (int slot = com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST;
                slot <= com.melody.melodylink.bose.BoseBmap.MODE_SLOT_LAST; slot++) {
            int offset = 6 + (slot - com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST);
            int level = shared != null && shared.length > offset ? shared[offset] : -1;
            addBoseModeSlotRow(card, loader, activity, slot, slot, level);
        }
    }

    private void addBosePowerCard(Object parent, ClassLoader loader, Activity activity, int order) {
        String key = BOSE_EXTRA_CATEGORY_KEY + ".power";
        if (findPreferenceByKeyRecursive(parent, key)) return;
        Object card = newBoseCard(parent, loader, activity, key,
                "Bose \u7535\u6e90", order);
        if (card == null) return;
        addBoseStandbyRow(card, loader, activity, 0);
        addBosePowerRow(card, loader, activity, 1);
    }

    /** One EQ band slider. The COUI bar counts 0..20; the wire value is -10..10. */
    private void addBoseBandSlider(Object parent, ClassLoader loader, Activity activity,
            int order, String title, final int bandIndex, int value) {
        Object host = addBoseCardRow(parent, loader, activity,
                "melodylink.bose.eq.card." + bandIndex, order);
        if (host == null) return;
        Object seek = newPreference(loader,
                "com.oplus.melody.ui.widget.MelodyPromptVolumeSeekBarPreference", activity);
        if (seek == null) return;
        setPreferenceValue(seek, "setKey", "melodylink.bose.eq." + bandIndex);
        setPreferenceValue(seek, "setTitle", title);
        setPreferenceValue(seek, "setPersistent", false);
        setPreferenceValue(seek, "setPromptVolumePercent", Boolean.FALSE);
        invokeInt(seek, "setBarMaxValue", 20);
        final int clamped = Math.max(-10, Math.min(10, value));
        invokeInt(seek, "setProgress", clamped + 10);
        setPreferenceValue(seek, "setSummary", eqLabel(clamped));
        setPreferenceValue(seek, "setOrder", 0);
        installBoseBandListener(seek, bandIndex);
        if (addPreference(host, seek, loader)) boseEqSliders.add(seek);
    }

    private static String eqLabel(int value) {
        if (value == 0) return "0";
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private void installBoseBandListener(Object preference, final int bandIndex) {
        Method setter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnTrackChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null || !setter.getParameterTypes()[0].isInterface()) return;
        Class<?> type = setter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(classLoaderOf(type),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseEqListener";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (args == null || args.length < 1 || !(args[0] instanceof Number)) return null;
                    int raw = ((Number) args[0]).intValue() - 10;   // bar 0..20 -> wire -10..10
                    int value = Math.max(-10, Math.min(10, raw));
                    setPreferenceValue(preference, "setSummary", eqLabel(value));
                    forwardBoseCommand(CMD_EQ_BAND, bandIndex, value, 0, 0);
                    return null;
                });
        try {
            setter.setAccessible(true);
            setter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose EQ listener install failed", t);
        }
    }

    /**
     * A plain, non-switch preference row for "tap to choose" entries.
     *
     * COUISwitchPreference must not be used here: its click helper
     * (COUISwitchPreferenceClickHelper) invokes the listener as a
     * boolean-returning callback, so a void onClick proxy made the process die
     * with "Expected to unbox a 'boolean' primitive type but was returned null"
     * on every tap (10 crashes in the 0.4.9 report).
     */
    private static Object newActionPreference(ClassLoader loader, Context context) {
        for (String typeName : new String[]{
                "com.oplus.melody.common.widget.MelodyPreference",
                "com.coui.appcompat.preference.COUIPreference",
                "androidx.preference.Preference"}) {
            Object preference = newPreference(loader, typeName, context);
            if (preference != null) return preference;
        }
        return null;
    }

    /**
     * Adds one row inside its own COUI category so it renders as an independent
     * rounded card. Without this, consecutive orders collapse into a single card
     * and the rows look glued together with no gap.
     */
    private static Object addBoseCardRow(
            Object parent, ClassLoader loader, Activity activity, String cardKey, int order) {
        if (parent == null) return null;
        Object card = newPreference(loader,
                "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
        if (card == null) card = newPreference(loader,
                "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
        if (card == null) return parent;
        setPreferenceValue(card, "setKey", cardKey);
        if (order >= 0) setPreferenceValue(card, "setOrder", order);
        if (!addPreference(parent, card, loader)) return parent;
        return card;
    }

    private static ClassLoader classLoaderOf(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? HookModule.class.getClassLoader() : loader;
    }

    /** One Action-button event row: tapping opens a list of supported actions. */
    private void addBoseButtonRow(Object parent, ClassLoader loader, Activity activity,
            int order, String label, final int event, int currentAction) {
        Object host = addBoseCardRow(parent, loader, activity,
                "melodylink.bose.btn.card." + event, order);
        if (host == null) return;
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.btn." + event);
        setPreferenceValue(row, "setTitle", label);
        setPreferenceValue(row, "setSummary",
                com.melody.melodylink.bose.BoseBmap.actionLabel(currentAction));
        setPreferenceValue(row, "setOrder", 0);
        installBoseButtonListener(row, event);
        if (addPreference(host, row, loader)) boseButtonDropdowns.add(row);
    }

    private void installBoseButtonListener(Object row, final int event) {
        Method setter = null;
        for (Method candidate : allMethods(row.getClass())) {
            if (candidate.getName().equals("setOnPreferenceClickListener")
                    && candidate.getParameterTypes().length == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null || !setter.getParameterTypes()[0].isInterface()) return;
        Class<?> type = setter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(classLoaderOf(type),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseButtonListener";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (!"onClick".equals(method.getName())) {
                        // Void and primitive-returning callbacks share one proxy:
                        // returning null crashes the app when the caller unboxes a
                        // boolean, so hand back a type-appropriate default.
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : Boolean.TRUE;
                    }
                    showBoseActionPicker(row, event);
                    return null;
                });
        try {
            setter.setAccessible(true);
            setter.invoke(row, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose button listener install failed", t);
        }
    }

    /**
     * Offers the action list in a native single-choice dialog. [1.9] reports the
     * supported set per button; until we have that read, the full action table is
     * offered and the firmware rejects anything unsupported.
     */
    private void showBoseActionPicker(final Object row, final int event) {
        try {
            Context context = detailActivity;
            if (context == null) return;
            final int[] actions = {0, 2, 9, 4, 10, 11, 3, 7, 8, 5, 6, 13, 17, 19, 1, 12, 14, 15};
            String[] labels = new String[actions.length];
            for (int i = 0; i < actions.length; i++) {
                labels[i] = com.melody.melodylink.bose.BoseBmap.actionLabel(actions[i]);
            }
            final int[] chosen = new int[1];
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context)
                    .setTitle("\u6309\u952e\u529f\u80fd")
                    .setItems(labels, (d, which) -> {
                        chosen[0] = actions[which];
                        setPreferenceValue(row, "setSummary", labels[which]);
                        forwardBoseCommand(CMD_BUTTON, event, chosen[0], 0, 0);
                    })
                    .setNegativeButton("\u53d6\u6d88", null)
                    .create();
            dialog.show();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose action picker failed", t);
        }
    }

    /** Explains what the mode slots are, so the six sliders are not a mystery. */
    private void addBoseModeSlotHint(Object parent, ClassLoader loader, Activity activity,
            int order) {
        Object hint = newPreference(loader,
                "com.oplus.melody.common.widget.MelodyPreference", activity);
        if (hint == null) hint = newActionPreference(loader, activity);
        if (hint == null) return;
        setPreferenceValue(hint, "setKey", "melodylink.bose.slot.hint");
        setPreferenceValue(hint, "setTitle", "\u6a21\u5f0f\u69fd 5-10");
        setPreferenceValue(hint, "setSummary",
                "\u4f60\u53ef\u4ee5\u628a\u964d\u566a\u7b49\u7ea7\u5b58\u6210\u591a\u7ec4"
                        + "\u914d\u7f6e\uff0c\u5728\u97f3\u91cf\u9762\u677f\u78c1\u8d34"
                        + "\u5207\u6362\u6a21\u5f0f\u65f6\u751f\u6548");
        setPreferenceValue(hint, "setSelectable", Boolean.FALSE);
        setPreferenceValue(hint, "setPersistent", false);
        if (order >= 0) setPreferenceValue(hint, "setOrder", order);
        addPreference(parent, hint, loader);
    }

    /** One custom mode slot: name is fixed, CNC level is a slider. */
    private void addBoseModeSlotRow(Object parent, ClassLoader loader, Activity activity,
            int order, final int slot, int level) {
        Object host = addBoseCardRow(parent, loader, activity,
                "melodylink.bose.mode.card." + slot, order);
        if (host == null) return;
        Object seek = newPreference(loader,
                "com.oplus.melody.ui.widget.MelodyPromptVolumeSeekBarPreference", activity);
        if (seek == null) return;
        setPreferenceValue(seek, "setKey", "melodylink.bose.mode." + slot);
        setPreferenceValue(seek, "setTitle", "\u6a21\u5f0f\u69fd " + slot);
        setPreferenceValue(seek, "setSummary", "\u964d\u566a\u7b49\u7ea7 "
                + (level >= 0 ? String.valueOf(level) : "?") + "/10");
        setPreferenceValue(seek, "setPersistent", false);
        setPreferenceValue(seek, "setPromptVolumePercent", Boolean.FALSE);
        invokeInt(seek, "setBarMaxValue", 10);
        invokeInt(seek, "setProgress", Math.max(0, level));
        setPreferenceValue(seek, "setOrder", 0);
        installBoseModeSlotListener(seek, slot);
        if (addPreference(host, seek, loader)) boseModeSlotSliders.add(seek);
    }

    private void installBoseModeSlotListener(Object preference, final int slot) {
        Method setter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnTrackChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null || !setter.getParameterTypes()[0].isInterface()) return;
        Class<?> type = setter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(classLoaderOf(type),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseModeSlot";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (args == null || args.length < 1 || !(args[0] instanceof Number)) return null;
                    int value = Math.max(0, Math.min(10, ((Number) args[0]).intValue()));
                    setPreferenceValue(preference, "setSummary",
                            "\u964d\u566a\u7b49\u7ea7 " + value + "/10");
                    forwardBoseCommand(CMD_MODE_SLOT, slot, value, 0, 0);
                    return null;
                });
        try {
            setter.setAccessible(true);
            setter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose mode slot listener install failed", t);
        }
    }

    /** Auto-off timer row: cycles through the firmware's preset minute values. */
    private void addBoseStandbyRow(Object parent, ClassLoader loader, Activity activity, int order) {
        Object host = addBoseCardRow(parent, loader, activity, "melodylink.bose.standby.card", order);
        if (host == null) return;
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.standby");
        setPreferenceValue(row, "setTitle", "\u81ea\u52a8\u5173\u673a");
        setPreferenceValue(row, "setSummary",
                com.melody.melodylink.bose.BoseBmap.standbyLabel(
                        boseTransport.getStandbyMinutes() < 0 ? 0 : boseTransport.getStandbyMinutes()));
        setPreferenceValue(row, "setOrder", 0);
        installBoseStandbyListener(row);
        addPreference(host, row, loader);
    }

    private void installBoseStandbyListener(Object row) {
        Method setter = null;
        for (Method candidate : allMethods(row.getClass())) {
            if (candidate.getName().equals("setOnPreferenceClickListener")
                    && candidate.getParameterTypes().length == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null || !setter.getParameterTypes()[0].isInterface()) return;
        Class<?> type = setter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(classLoaderOf(type),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseStandby";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (!"onClick".equals(method.getName())) {
                        // Void and primitive-returning callbacks share one proxy:
                        // returning null crashes the app when the caller unboxes a
                        // boolean, so hand back a type-appropriate default.
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : Boolean.TRUE;
                    }
                    int current = boseTransport.getStandbyMinutes();
                    int[] presets = com.melody.melodylink.bose.BoseBmap.STANDBY_MINUTES;
                    int next = presets[0];
                    for (int value : presets) {
                        if (value > current) {
                            next = value;
                            break;
                        }
                    }
                    setPreferenceValue(row, "setSummary",
                            com.melody.melodylink.bose.BoseBmap.standbyLabel(next));
                    forwardBoseCommand(CMD_STANDBY, next, 0, 0, 0);
                    return null;
                });
        try {
            setter.setAccessible(true);
            setter.invoke(row, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose standby listener install failed", t);
        }
    }

    /** Power-off row with a confirmation step: the earbuds drop the link. */
    private void addBosePowerRow(Object parent, ClassLoader loader, Activity activity, int order) {
        Object host = addBoseCardRow(parent, loader, activity, "melodylink.bose.poweroff.card", order);
        if (host == null) return;
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.poweroff");
        setPreferenceValue(row, "setTitle", "\u5173\u673a");
        setPreferenceValue(row, "setSummary", "\u5173\u95ed\u8033\u673a\u5e76\u65ad\u5f00\u8fde\u63a5");
        setPreferenceValue(row, "setOrder", 0);
        installBosePowerListener(row);
        addPreference(host, row, loader);
    }

    private void installBosePowerListener(Object row) {
        Method setter = null;
        for (Method candidate : allMethods(row.getClass())) {
            if (candidate.getName().equals("setOnPreferenceClickListener")
                    && candidate.getParameterTypes().length == 1) {
                setter = candidate;
                break;
            }
        }
        if (setter == null || !setter.getParameterTypes()[0].isInterface()) return;
        Class<?> type = setter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(classLoaderOf(type),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBosePower";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (!"onClick".equals(method.getName())) {
                        // Void and primitive-returning callbacks share one proxy:
                        // returning null crashes the app when the caller unboxes a
                        // boolean, so hand back a type-appropriate default.
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : Boolean.TRUE;
                    }
                    confirmBosePowerOff();
                    return null;
                });
        try {
            setter.setAccessible(true);
            setter.invoke(row, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose power listener install failed", t);
        }
    }

    private void confirmBosePowerOff() {
        try {
            Context context = detailActivity;
            if (context == null) return;
            new android.app.AlertDialog.Builder(context)
                    .setTitle("\u5173\u673a")
                    .setMessage("\u786e\u5b9a\u5173\u95ed\u8033\u673a\uff1f\u5c06\u65ad\u5f00\u84dd\u7259\u8fde\u63a5\u3002")
                    .setPositiveButton("\u5173\u673a", (d, which) -> {
                        setPreferenceValue(boseExtraCategory, "setKey", BOSE_EXTRA_CATEGORY_KEY);
                        forwardBoseCommand(CMD_POWER_OFF, 0, 0, 0, 0);
                    })
                    .setNegativeButton("\u53d6\u6d88", null)
                    .show();
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose power confirm failed", t);
        }
    }

    /**
     * Writes one transaction to the shared command file. The primary process polls
     * it and performs the BMAP write, then republishes the state file.
     */
    private void forwardBoseCommand(int kind, int index, int value, int extra1, int extra2) {
        try {
            String address = targetAddress == null
                    ? MelodySharedStateStore.readBoseExtraAddress(boseExtraStateFile())
                    : targetAddress;
            if (address == null) {
                address = com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE
                        .getKNOWN_MACS().iterator().next();
            }
            MelodySharedStateStore.writeBoseExtraCommand(boseExtraCommandFile(), address,
                    kind, index, value, extra1, extra2, java.util.UUID.randomUUID().toString());
            log(Log.INFO, TAG, event("queued Bose command kind=" + kind + " index=" + index
                    + " value=" + value));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose command forward failed", t);
        }
    }

    /** Re-sync the injected slider when a BMAP session reports the real CNC level. */
    private volatile int lastCncSliderLevel = -2;
    private void updateBoseCncSlider() {
        Object slider = boseCncPreference;
        Object oneSpaceSlider = boseCncOneSpacePreference;
        if (slider == null && oneSpaceSlider == null) return;
        int level = boseTransport.getCncLevel();
        if (level < 0) {
            int[] shared = MelodySharedStateStore.readBoseCncState(boseCncStateFile());
            if (shared != null) level = shared[1];
        }
        if (level < 0 || level == lastCncSliderLevel) return;
        lastCncSliderLevel = level;
        final int value = level;
        mainHandler.post(() -> {
            // Both pages can be alive at once (detail is a separate Activity on top of the
            // 通用设置 bottom sheet), so each copy is updated in its own right.
            for (Object target : new Object[]{slider, oneSpaceSlider}) {
                if (target == null) continue;
                invokeInt(target, "setProgress", value);
                setPreferenceValue(target, "setSummary", "\u6548\u679c\u5f3a\u5ea6 " + value + "/10");
            }
        });
    }

    private void installBoseCncListener(Object preference, ClassLoader loader) {
        Method listenerSetter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnTrackChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                listenerSetter = candidate;
                break;
            }
        }
        if (listenerSetter == null || !listenerSetter.getParameterTypes()[0].isInterface()) {
            log(Log.WARN, TAG, event("Bose CNC listener setter unavailable"));
            return;
        }
        Class<?> listenerType = listenerSetter.getParameterTypes()[0];
        Object listener = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{listenerType}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseCncListener";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (args == null || args.length < 1 || !(args[0] instanceof Number)) return null;
                    int value = Math.max(0, Math.min(10, ((Number) args[0]).intValue()));
                    setPreferenceValue(preference, "setSummary", "\u6548\u679c\u5f3a\u5ea6 " + value + "/10");
                    if (isPrimaryProcess()) {
                        BluetoothDevice device = resolveBoseForTile();
                        if (device != null) {
                            boseTransport.cacheCncLevel(value);
                            boseTransport.writeSetting(
                                    com.melody.melodylink.bose.BoseDeviceConfig.SETTING_CNC, value);
                        }
                    } else {
                        String address = targetAddress == null
                                ? MelodySharedStateStore.readBoseCncAddress(boseCncStateFile())
                                : targetAddress;
                        if (address == null) {
                            address = com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE
                                    .getKNOWN_MACS().iterator().next();
                        }
                        MelodySharedStateStore.writeBoseCncCommand(boseCncCommandFile(),
                                address, value, java.util.UUID.randomUUID().toString());
                    }
                    return null;
                });
        try {
            listenerSetter.setAccessible(true);
            listenerSetter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose CNC listener install failed", t);
        }
    }

    private void installSettingListener(Object preference, SonyAdvancedSettingId id, ClassLoader loader) {        Method listenerSetter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnPreferenceChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                listenerSetter = candidate;
                break;
            }
        }
        if (listenerSetter == null || !listenerSetter.getParameterTypes()[0].isInterface()) {
            log(Log.WARN, TAG, event("advanced setting listener unavailable id=" + id));
            return;
        }
        Class<?> listenerType = listenerSetter.getParameterTypes()[0];
        Method callback = null;
        for (Method candidate : listenerType.getMethods()) {
            if (candidate.getReturnType() == Boolean.TYPE && candidate.getParameterTypes().length == 2) {
                callback = candidate;
                break;
            }
        }
        final Method changeCallback = callback;
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if ("toString".equals(method.getName())) return "MelodyLinkSettingListener";
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
            if (changeCallback == null || !method.getName().equals(changeCallback.getName())
                    || args == null || args.length < 2 || !(args[1] instanceof Boolean)) return null;
            Boolean value = (Boolean) args[1];
            setPreferenceValue(preference, "setEnabled", false);
            if (isPrimaryProcess() && sonyTransport.isConnected()) {
                sonyTransport.writeSetting(id, value);
            } else if (!isPrimaryProcess()) {
                String address = targetAddress == null ? readSharedSonyAddress() : targetAddress;
                if (address == null || !isTargetAddress(address)
                        || !writeSharedSonySettingCommand(address, id, value)) {
                    setAdvancedSettingEnabled(id, true);
                    log(Log.WARN, TAG, event("Sony setting " + id
                            + " skipped: primary process command forwarding unavailable"));
                } else {
                    log(Log.INFO, TAG, event("forwarded Sony setting " + id + " to primary process"));
                    // The command acknowledgement is delivered in the primary process.  Keep
                    // this foreground preference responsive while that process performs I/O.
                    updateAdvancedSetting(id, value);
                }
            } else {
                pendingSonySettings.put(id, value);
                if (!connectTargetSonyTransport("advanced setting write")) {
                    pendingSonySettings.remove(id);
                    setAdvancedSettingEnabled(id, true);
                    log(Log.WARN, TAG, event("Sony setting " + id
                            + " skipped: target Bluetooth device is unavailable"));
                }
            }
            return true;
        });
        try {
            listenerSetter.setAccessible(true);
            listenerSetter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "advanced setting listener attach failed id=" + id, t);
        }
    }

    private static boolean isAdvancedSettingsAnchor(Object preference) {
        if (preference == null) return false;
        String className = preference.getClass().getName();
        if (className.equals("com.oplus.melody.onespace.items.OneSpaceNoisePreference")
                || className.equals("com.oplus.melody.ui.component.detail.spatialaudio.SpatialAudioItem")
                || className.equals("com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem")) {
            return true;
        }
        Object title = invokeNoArg(preference, "getTitle");
        return title != null && SOUND_QUALITY_TITLE.equals(title.toString().trim());
    }

    /** Builds a host preference with the theming (Context, AttributeSet) constructor. */
    private static Object newPreference(ClassLoader loader, String typeName, Context context) {
        return PrefRef.create(loader, typeName, context);
    }

    private static Object newSwitchPreference(ClassLoader loader, Context context) {
        for (String typeName : new String[]{
                "com.oplus.melody.ui.widget.MelodyUiTipsSwitchPreference",
                "com.coui.appcompat.preference.COUISwitchPreference",
                "androidx.preference.SwitchPreferenceCompat"}) {
            Object preference = newPreference(loader, typeName, context);
            if (preference != null) return preference;
        }
        return null;
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private static boolean addPreference(Object parent, Object child, ClassLoader loader) {
        return PrefRef.addPreference(parent, child);
    }

    private static Object findPreference(Object group, String key) {
        for (String name : new String[]{"findPreference", "e"}) {
            try {
                Method method = group.getClass().getMethod(name, CharSequence.class);
                return method.invoke(group, key);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            for (Method method : allMethods(target.getClass())) {
                if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return method.invoke(target);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reflective int field writer (walks superclasses) for androidx SeekBarPreference internals. */
    private static void setIntField(Object target, String fieldName, int value) {
        if (target == null) return;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        if (target == null) return;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static void invokeInt(Object target, String methodName, int value) {
        if (target == null) return;
        for (Method method : allMethods(target.getClass())) {
            if (method.getName().equals(methodName) && method.getParameterTypes().length == 1
                    && (method.getParameterTypes()[0] == Integer.TYPE
                            || method.getParameterTypes()[0] == Integer.class)) {
                try {
                    method.setAccessible(true);
                    method.invoke(target, value);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Invokes a 1-arg setter by name, falling back to a signature-based lookup.
     *
     * <p>The fallback matters because a pure name lookup fails <em>silently</em> once R8 has
     * renamed the member: no exception is thrown, the value simply never lands, and the panel
     * stays blank. We re-discover the setter by parameter type, and only when exactly one
     * candidate exists — with two or more we deliberately do nothing, because writing into the
     * wrong setter is worse than the no-op. This mirrors PrefRef.invokeSetter.
     */
    private static boolean setPreferenceValue(Object target, String name, Object value) {
        if (target == null) return false;
        for (Method method : allMethods(target.getClass())) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1) {
                try {
                    method.setAccessible(true);
                    method.invoke(target, value);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        if (value == null) return false;
        Class<?> paramType = value instanceof String ? String.class
                : value instanceof Boolean ? boolean.class
                : value instanceof Integer ? int.class
                : value.getClass();
        Method fallback = uniqueSetterByType(target.getClass(), paramType);
        if (fallback == null) return false;
        try {
            fallback.setAccessible(true);
            fallback.invoke(target, value);
            MLog.event("pref.setter.sigfallback", "logical", name,
                    "resolved", fallback.getName(), "type", paramType.getSimpleName());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The single 1-arg method on the hierarchy accepting {@code paramType}, or null. */
    private static Method uniqueSetterByType(Class<?> startCls, Class<?> paramType) {
        boolean textType = paramType == String.class || paramType == CharSequence.class;
        Method match = null;
        for (Class<?> cls = startCls; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                Class<?> p = m.getParameterTypes()[0];
                boolean accepts = textType
                        ? (p == String.class || p == CharSequence.class)
                        : p == paramType;
                if (!accepts) continue;
                if (match != null && !match.getName().equals(m.getName())) return null;
                match = m;
            }
        }
        return match;
    }

    private static MelodySharedStateStore.SharedCommand readSharedSonyCommand() {
        return MelodySharedStateStore.readCommand(sharedCommandFile());
    }

    private static String readSharedSonyAddress() {
        MelodySharedStateStore.SharedState state = readSharedSonyState();
        return state == null ? null : state.address;
    }

    private static int readSharedSonyModeIndex() {
        MelodySharedStateStore.SharedState state = readSharedSonyState();
        return state == null ? -1 : state.modeIndex;
    }

    private static MelodySharedStateStore.SharedState readSharedSonyState() {
        return MelodySharedStateStore.readState(sharedStateFile());
    }

    private void captureRepository(String label, XposedInterface.Chain chain) {
        if (!label.startsWith("repository") && !"noiseWrite".equals(label)) return;
        Object address = null;
        if (("repositoryGet".equals(label) || "repositoryObserve".equals(label)
                || "repositoryClientObserve".equals(label)
                || "repositoryNotify".equals(label)) && chain.getArg(0) instanceof String) {
            address = chain.getArg(0);
        } else if ("noiseWrite".equals(label) && chain.getArg(1) instanceof String) {
            address = chain.getArg(1);
        }
        if (address instanceof String && isTargetAddress(address)) {
            rememberTargetAddress((String) address);
            earphoneRepository = chain.getThisObject();
            if (!isPrimaryProcess() && !"repositoryNotify".equals(label)
                    && !"repositoryClientObserve".equals(label)
                    && readSharedSonyState() != null) {
                refreshTargetRepository("foreground repository observed");
            }
        }
    }

    private synchronized void startForegroundStateWatcher() {
        if (foregroundStateWatcher != null) return;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "MelodyLinkSonyState");
            thread.setDaemon(true);
            return thread;
        };
        foregroundStateWatcher = Executors.newSingleThreadScheduledExecutor(threadFactory);
        foregroundStateWatcher.scheduleWithFixedDelay(() -> {
            try {
                observeSharedSonyState();
            } catch (Throwable t) {
                log(Log.WARN, TAG, "foreground Sony state watcher failed", t);
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS);
        log(Log.INFO, TAG, event("started foreground Sony state watcher"));
    }

    private void observeSharedSonyState() {
        if (isPrimaryProcess()) {
            observeSharedSonyCommand();
            observeSharedSonyBatteryCommand();
            observeSharedSonySettingCommand();
            observeSharedBoseCncCommand();
            observeSharedBoseExtraCommand();
        }
        MelodySharedStateStore.SharedState state = readSharedSonyState();
        String fingerprint = state == null
                ? null
                : state.address + "\n" + state.modeIndex + "\n" + state.dsee + "\n" + state.pauseWhenRemoved;
        if (fingerprint == null ? lastForegroundStateFingerprint == null
                : fingerprint.equals(lastForegroundStateFingerprint)) {
            return;
        }
        lastForegroundStateFingerprint = fingerprint;
        if (state != null) {
            rememberTargetAddress(state.address);
            if (!isPrimaryProcess()) {
                applySharedAdvancedSettings(state);
                updateBoseCncSlider();
            }
        }
        log(Log.INFO, TAG, event("foreground Sony state changed; requesting native Melody LiveData refresh"
                + " mode=" + (state == null ? -1 : state.modeIndex)));
        refreshTargetRepository("foreground shared Sony state changed");
    }

    private void observeSharedBoseCncCommand() {
        MelodySharedStateStore.SharedBoseCncCommand command =
                MelodySharedStateStore.readBoseCncCommand(boseCncCommandFile());
        if (command == null || command.nonce.equals(lastBoseCncCommandNonce)) return;
        lastBoseCncCommandNonce = command.nonce;
        if (!isTargetAddress(command.address)) {
            String mac = com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getKNOWN_MACS()
                    .isEmpty() ? null
                    : com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getKNOWN_MACS().iterator().next();
            if (mac == null || !mac.equalsIgnoreCase(command.address)) {
                log(Log.WARN, TAG, event("ignored Bose CNC command for a different device"));
                return;
            }
        }
        BluetoothDevice device = resolveBoseForTile();
        if (device == null) {
            log(Log.WARN, TAG, event("Bose CNC command skipped: device unavailable"));
            return;
        }
        int level = Math.max(0, Math.min(10, command.level));
        if (boseTransport.getCncLevel() != level) {
            log(Log.INFO, TAG, event("executing forwarded Bose CNC level write level=" + level));
            boseTransport.cacheCncLevel(level);
            boseTransport.writeSetting(
                    com.melody.melodylink.bose.BoseDeviceConfig.SETTING_CNC, level);
        }
        String publishedAddress = targetAddress == null ? command.address : targetAddress;
        MelodySharedStateStore.writeBoseCncState(boseCncStateFile(), publishedAddress, level);
    }

    private volatile String lastBoseExtraCommandNonce;

    /**
     * Primary-process side of the EQ / button / mode-slot / standby / power
     * channel. Each command opens one BMAP session, performs the write and
     * republishes the state file so the :fg page re-reads the real values.
     */
    private void observeSharedBoseExtraCommand() {
        MelodySharedStateStore.SharedBoseExtraCommand command =
                MelodySharedStateStore.readBoseExtraCommand(boseExtraCommandFile());
        if (command == null) return;
        if (command.nonce.equals(lastBoseExtraCommandNonce)) return;
        lastBoseExtraCommandNonce = command.nonce;
        if (!isBoseCommandTarget(command.address)) return;
        BluetoothDevice device = resolveBoseForTile();
        if (device == null) {
            log(Log.WARN, TAG, event("Bose extra command skipped: device unavailable"));
            return;
        }
        switch (command.kind) {
            case CMD_EQ_BAND:
                boseTransport.readEq(null);
                boseTransport.writeEqBand(command.index, command.value,
                        (ok, bass, mid, treble) -> {
                            log(Log.INFO, TAG, event("Bose EQ band " + command.index + " -> "
                                    + command.value + (ok ? " ok" : " failed")
                                    + " [bass=" + bass + " mid=" + mid + " treble=" + treble + "]"));
                            publishBoseExtraState();
                        });
                break;
            case CMD_BUTTON:
                boseTransport.writeButton(com.melody.melodylink.bose.BoseBmap.BUTTON_ACTION,
                        command.index, command.value, (ok, bass, mid, treble) -> {
                            log(Log.INFO, TAG, event("Bose button ev=" + command.index
                                    + " -> " + com.melody.melodylink.bose.BoseBmap
                                    .actionLabel(command.value) + (ok ? " ok" : " failed")));
                            publishBoseExtraState();
                        });
                break;
            case CMD_MODE_SLOT:
                boseTransport.writeModeSlot(command.index,
                        "ML" + command.index, command.value, 0, 0,
                        (ok, slot) -> {
                            log(Log.INFO, TAG, event("Bose mode slot " + slot
                                    + " cnc=" + command.value + (ok ? " ok" : " failed")));
                            publishBoseExtraState();
                        });
                break;
            case CMD_STANDBY:
                boseTransport.writeStandbyTimer(command.index, (ok, minutes) -> {
                    log(Log.INFO, TAG, event("Bose standby timer -> "
                            + com.melody.melodylink.bose.BoseBmap.standbyLabel(minutes)
                            + (ok ? " ok" : " failed")));
                    publishBoseExtraState();
                });
                break;
            case CMD_POWER_OFF:
                log(Log.INFO, TAG, event("executing forwarded Bose power off"));
                boseTransport.powerOff(delivered -> {
                    log(delivered ? Log.INFO : Log.WARN, TAG,
                            event("Bose power off " + (delivered ? "sent" : "failed")));
                });
                break;
            default:
                log(Log.WARN, TAG, event("unknown Bose extra command kind=" + command.kind));
        }
    }

    /** Publishes EQ / button / mode-slot state for the :fg detail page. */
    private void publishBoseExtraState() {
        String address = targetAddress;
        if (address == null) {
            address = com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE
                    .getKNOWN_MACS().iterator().next();
        }
        int[] values = new int[12];
        values[0] = boseTransport.getEqBand(0);
        values[1] = boseTransport.getEqBand(1);
        values[2] = boseTransport.getEqBand(2);
        values[3] = boseTransport.getButtonAction(
                com.melody.melodylink.bose.BoseBmap.BUTTON_ACTION,
                com.melody.melodylink.bose.BoseBmap.EVENT_SINGLE_PRESS);
        values[4] = boseTransport.getButtonAction(
                com.melody.melodylink.bose.BoseBmap.BUTTON_ACTION,
                com.melody.melodylink.bose.BoseBmap.EVENT_LONG_PRESS);
        values[5] = boseTransport.getButtonAction(
                com.melody.melodylink.bose.BoseBmap.BUTTON_ACTION,
                com.melody.melodylink.bose.BoseBmap.EVENT_DOUBLE_PRESS);
        for (int slot = com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST;
                slot <= com.melody.melodylink.bose.BoseBmap.MODE_SLOT_LAST; slot++) {
            int level = boseTransport.getModeSlotCnc(slot);
            values[6 + (slot - com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST)] = level;
        }
        MelodySharedStateStore.writeBoseExtraState(boseExtraStateFile(), address, values);
    }

    /** Same address policy the CNC command path uses. */
    private boolean isBoseCommandTarget(String address) {
        if (isTargetAddress(address)) return true;
        return com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.matchesAddress(address);
    }

    private void observeSharedSonyCommand() {
        MelodySharedStateStore.SharedCommand command = readSharedSonyCommand();
        if (command == null) return;
        String fingerprint = command.address + "\n" + command.modeIndex + "\n" + command.nonce;
        if (fingerprint.equals(lastSonyCommandFingerprint)) return;
        lastSonyCommandFingerprint = fingerprint;
        if (!isTargetAddress(command.address)) {
            log(Log.WARN, TAG, event("ignored Sony ANC command for a different device"));
            return;
        }
        log(Log.INFO, TAG, event("executing forwarded Sony ANC mode write index=" + command.modeIndex));
        startSonyNoiseWriteFuture(command.modeIndex, melodyClassLoader);
    }

    private void observeSharedSonyBatteryCommand() {
        MelodySharedStateStore.SharedBatteryCommand command = readSharedSonyBatteryCommand();
        if (command == null || command.nonce.equals(lastSonyBatteryCommandNonce)) return;
        lastSonyBatteryCommandNonce = command.nonce;
        if (!isTargetAddress(command.address)) {
            log(Log.WARN, TAG, event("ignored Sony battery refresh for a different device"));
            return;
        }
        if (targetBoseDevice != null && boseHostConnected) {
            boseTransport.refreshBattery();
            return;
        }
        sonyTransport.refreshBattery();
    }

    private void observeSharedSonySettingCommand() {
        MelodySharedStateStore.SharedSettingCommand command = readSharedSonySettingCommand();
        if (command == null || command.nonce.equals(lastSonySettingCommandNonce)) return;
        lastSonySettingCommandNonce = command.nonce;
        if (!isTargetAddress(command.address)) {
            log(Log.WARN, TAG, event("ignored Sony setting command for a different device"));
            return;
        }
        SonyAdvancedSettingId id;
        try {
            id = SonyAdvancedSettingId.valueOf(command.settingId);
        } catch (IllegalArgumentException ignored) {
            log(Log.WARN, TAG, event("ignored unknown Sony setting command"));
            return;
        }
        pendingSonySettings.put(id, command.value);
        if (sonyTransport.isConnected()) {
            runPendingSonyOperation();
        } else if (!connectTargetSonyTransport("forwarded advanced setting write")) {
            pendingSonySettings.remove(id);
            log(Log.WARN, TAG, event("Sony setting " + id + " skipped: target Bluetooth device is unavailable"));
        }
    }

    private void refreshTargetRepository(String reason) {
        Object repository = earphoneRepository;
        String address = targetAddress;
        if (repository == null || address == null) {
            log(Log.WARN, TAG, event("Melody repository refresh skipped: repository not observed (" + reason + ")"));
            return;
        }
        try {
            if (repository.getClass().getName().equals(
                    "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl")) {
                Method observe = repository.getClass().getDeclaredMethod("z", String.class);
                observe.setAccessible(true);
                Object liveData = observe.invoke(repository, address);
                if (liveData != null) {
                    Method activate = liveData.getClass().getMethod("g");
                    activate.setAccessible(true);
                    activate.invoke(liveData);
                    log(Log.INFO, TAG, event("requested native Melody foreground LiveData reload"
                            + " for registered Sony device (" + reason + ")"));
                    return;
                }
            }
            Method notifyChanged = null;
            for (String candidate : new String[]{"x1", "B1"}) {
                try {
                    notifyChanged = repository.getClass().getDeclaredMethod(candidate, String.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (notifyChanged == null) throw new NoSuchMethodException("repository notify (x1/B1)");
            notifyChanged.setAccessible(true);
            notifyChanged.invoke(repository, address);
            log(Log.INFO, TAG, event("published native Melody repository update for registered Sony device"
                    + " (" + reason + ")"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Melody repository refresh failed", t);
        }
    }

    private void publishSonyBatteryState(String reason) {
        publishBatteryState(sonySessionState.getBattery(), reason);
    }

    private void publishBatteryState(EarbudsState state, String reason) {
        if (!isPrimaryProcess()) return;
        Object repository = earphoneRepository;
        String address = targetAddress;
        if (state == null || repository == null || address == null) {
            log(Log.WARN, TAG, event("battery publish skipped: repository or state unavailable"));
            return;
        }
        try {
            Field statuses = repository.getClass().getDeclaredField("r");
            statuses.setAccessible(true);
            Object value = statuses.get(repository);
            if (!(value instanceof java.util.Map<?, ?>)) {
                log(Log.WARN, TAG, event("Sony battery publish skipped: Melody V map unavailable"));
                return;
            }
            Object status = ((java.util.Map<?, ?>) value).get(address);
            if (status == null) {
                log(Log.WARN, TAG, event("Sony battery publish skipped: target Melody V unavailable"));
                return;
            }
            ClassLoader loader = status.getClass().getClassLoader();
            Class<?> batteryStatusClass;
            try {
                batteryStatusClass = Class.forName(
                        "com.oplus.melody.model.repository.earphone.V$a", false, loader);
            } catch (ClassNotFoundException renamed) {
                batteryStatusClass = Class.forName(
                        "com.oplus.melody.model.repository.earphone.K$a", false, loader);
            }
            // Melody 17.6.3: the per-part battery model is K$a with a single
            // `int battery` field and a `setBattery(I)` setter. The older
            // setLeftBatteryStatus / setRightBatteryStatus / setBoxBatteryStatus trio this
            // code used no longer exists — every publish threw
            // NoSuchMethodException, and because the throw escaped publishBatteryState the
            // repository was never notified. Verified in the 17.6.3 smali:
            //   .method public constructor <init>(IZ)V
            //   .method public final setBattery(I)V
            // Constructors are tried in order; the first that exists wins.
            Constructor<?> constructor = null;
            for (Class<?>[] signature : new Class<?>[][]{
                    {int.class, boolean.class}, {int.class}, {}}) {
                try {
                    constructor = batteryStatusClass.getConstructor(signature);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (constructor == null) {
                log(Log.WARN, TAG, event("battery status constructor unavailable on "
                        + batteryStatusClass.getName()));
                return;
            }
            Class<?>[] signature = constructor.getParameterTypes();
            boolean updated = false;
            // One part per publish is enough: the earbud detail page shows a single
            // battery figure for this device class, and a failure on one part must not
            // abort the whole publish.
            BatteryValue[] parts = {state.getBattery().get(BatteryPart.LEFT),
                    state.getBattery().get(BatteryPart.RIGHT),
                    state.getBattery().get(BatteryPart.CASE)};
            for (BatteryValue part : parts) {
                if (part == null) continue;
                try {
                    Object batteryStatus = signature.length == 0
                            ? constructor.newInstance()
                            : (signature.length == 1
                            ? constructor.newInstance(part.getPercent())
                            : constructor.newInstance(part.getPercent(), part.getCharging()));
                    Method setter = findBatterySetter(batteryStatusClass);
                    if (setter == null) {
                        log(Log.WARN, TAG, event("no setBattery on " + batteryStatusClass.getName()));
                        return;
                    }
                    setter.invoke(batteryStatus, part.getPercent());
                    Method outer = null;
                    for (Method candidate : allMethods(status.getClass())) {
                        if (candidate.getName().equals("setBatteryStatus")
                                && candidate.getParameterTypes().length == 1
                                && candidate.getParameterTypes()[0].isInstance(batteryStatus)) {
                            outer = candidate;
                            break;
                        }
                    }
                    if (outer == null) {
                        log(Log.WARN, TAG, event("no setBatteryStatus on "
                                + status.getClass().getName()));
                        return;
                    }
                    outer.invoke(status, batteryStatus);
                    updated = true;
                    break;   // one successful part is enough to notify
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "battery part publish failed", t);
                }
            }
            if (!updated) {
                log(Log.INFO, TAG, event("Sony battery publish retained previous Melody values (" + reason + ")"));
                return;
            }

            Method notifyChanged = null;
            for (String notifyName : new String[]{"x1", "B1"}) {
                for (Method candidate : allMethods(repository.getClass())) {
                    if (candidate.getName().equals(notifyName)
                            && candidate.getParameterTypes().length == 1
                            && candidate.getParameterTypes()[0] == String.class) {
                        notifyChanged = candidate;
                        break;
                    }
                }
                if (notifyChanged != null) break;
            }
            if (notifyChanged == null) {
                log(Log.WARN, TAG, event("battery notify method missing on "
                        + repository.getClass().getName()));
                return;
            }
            notifyChanged.setAccessible(true);
            notifyChanged.invoke(repository, address);
            log(Log.INFO, TAG, event("published Sony battery through Melody V/U.x1 (" + reason + ")"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony battery publish failed", t);
        }
    }

    /** The {@code setBattery(I)} setter on a K$a style battery holder. */
    private static Method findBatterySetter(Class<?> batteryStatusClass) {
        for (Method m : allMethods(batteryStatusClass)) {
            if (m.getName().equals("setBattery") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == int.class) {
                return m;
            }
        }
        return null;
    }

    private static Object findProfile(Object value, String id, String name) {
        if (!(value instanceof Collection<?>)) return null;
        for (Object item : (Collection<?>) value) {
            if (item == null) continue;
            Object itemId = readField(item, "id");
            Object itemName = readField(item, "name");
            if (id.equals(String.valueOf(itemId)) && name.equals(String.valueOf(itemName))) return item;
        }
        return null;
    }

    /**
     * Returns an independent Enco X3 whitelist DTO with Melody's three ANC-intensity
     * child modes removed. The original entry remains untouched for native OPPO devices.
     */
    private Object copyWithoutAncStrengthModes(Object source) {
        try {
            Object copy = copyWhitelistValue(source, new IdentityHashMap<>());
            if (copy == null || copy == source) return null;
            int removed = removeAncStrengthModes(copy, new IdentityHashMap<>());
            if (removed <= 0) {
                log(Log.WARN, TAG, event("Enco X3 whitelist clone contains no removable ANC strength modes"));
                return null;
            }
            return copy;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Enco X3 whitelist DTO clone failed", t);
            return null;
        }
    }

    private static Object copyWhitelistValue(Object source, IdentityHashMap<Object, Object> copied)
            throws ReflectiveOperationException {
        if (source == null || isWhitelistLeaf(source.getClass())) return source;
        Object existing = copied.get(source);
        if (existing != null) return existing;
        Class<?> type = source.getClass();
        if (type.isArray()) {
            int length = Array.getLength(source);
            Object copy = Array.newInstance(type.getComponentType(), length);
            copied.put(source, copy);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, copyWhitelistValue(Array.get(source, index), copied));
            }
            return copy;
        }
        if (source instanceof Collection<?>) {
            Collection<Object> copy = source instanceof java.util.Set<?>
                    ? new LinkedHashSet<>() : new ArrayList<>();
            copied.put(source, copy);
            for (Object value : (Collection<?>) source) copy.add(copyWhitelistValue(value, copied));
            return copy;
        }
        if (!isMelodyWhitelistValue(type)) return source;
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object copy = constructor.newInstance();
        copied.put(source, copy);
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || field.isSynthetic()) continue;
                field.setAccessible(true);
                field.set(copy, copyWhitelistValue(field.get(source), copied));
            }
        }
        return copy;
    }

    private static int removeAncStrengthModes(Object value, IdentityHashMap<Object, Boolean> visited)
            throws IllegalAccessException {
        if (value == null || isWhitelistLeaf(value.getClass()) || visited.put(value, Boolean.TRUE) != null) return 0;
        int removed = 0;
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            java.util.Iterator<?> iterator = collection.iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                if (isAncStrengthMode(item)) {
                    iterator.remove();
                    removed++;
                } else {
                    removed += removeAncStrengthModes(item, visited);
                }
            }
            return removed;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                removed += removeAncStrengthModes(Array.get(value, index), visited);
            }
            return removed;
        }
        if (!isMelodyWhitelistValue(type)) return 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                field.setAccessible(true);
                removed += removeAncStrengthModes(field.get(value), visited);
            }
        }
        return removed;
    }

    private static boolean isAncStrengthMode(Object value) {
        Object modeType = readField(value, "modeType");
        if (!(modeType instanceof Number)) return false;
        int valueType = ((Number) modeType).intValue();
        return valueType == 3 || valueType == 4 || valueType == 8;
    }

    private static boolean isMelodyWhitelistValue(Class<?> type) {
        Package valuePackage = type.getPackage();
        return valuePackage != null && valuePackage.getName().startsWith("com.oplus.melody.common.data");
    }

    private static boolean isWhitelistLeaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type == Class.class;
    }

    private static Object readField(Object object, String fieldName) {
        if (object == null) return null;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private void projectSonyAncModeIntoDto(Object address, Object dto) {
        if (!isTargetAddress(address) || dto == null) return;

        EarbudsState state = sonySessionState.getAnc();
        int mode = state == null
                ? readSharedSonyModeIndex()
                : MelodyStateBridge.INSTANCE.ancModeIndex(state);
        if (mode < 0) {
            log(Log.WARN, TAG, event("Melody DTO ANC projection skipped: Sony mode unavailable"));
            return;
        }

        Object previous = readField(dto, "noiseReductionModeIndex");
        if (previous instanceof Number && ((Number) previous).intValue() == mode) {
            log(Log.INFO, TAG, event("Melody DTO ANC projection unchanged mode=" + mode));
            return;
        }
        if (writeIntField(dto, "noiseReductionModeIndex", mode)) {
            log(Log.INFO, TAG, event("projected Sony ANC mode into Melody EarphoneDTO old="
                    + compact(previous) + " new=" + mode));
        } else {
            log(Log.WARN, TAG, event("Melody DTO ANC projection failed old="
                    + compact(previous) + " requested=" + mode));
        }
    }

    /**
     * Melody 17.6.3 keeps battery levels on EarphoneDTO (leftBattery/rightBattery/
     * boxBattery + isBatteryInfoReceived) instead of the old per-address V map, so
     * publish through the DTO build hook.
     */
    private void projectBoseBatteryIntoDto(Object address, Object dto) {
        if (targetBoseDevice == null || !boseHostConnected) return;
        if (!isTargetAddress(address) || dto == null) return;
        EarbudsState battery = boseSessionState.getBattery();
        if (battery == null || battery.getBattery().isEmpty()) return;
        boolean updated = false;
        com.melody.melodylink.domain.BatteryValue left = battery.getBattery().get(BatteryPart.LEFT);
        com.melody.melodylink.domain.BatteryValue right = battery.getBattery().get(BatteryPart.RIGHT);
        com.melody.melodylink.domain.BatteryValue box = battery.getBattery().get(BatteryPart.CASE);
        if (left != null) updated |= writeIntField(dto, "leftBattery", left.getPercent());
        if (right != null) updated |= writeIntField(dto, "rightBattery", right.getPercent());
        if (box != null) updated |= writeIntField(dto, "boxBattery", box.getPercent());
        if (!updated) return;
        writeBooleanField(dto, "isBatteryInfoReceived", true);
        log(Log.INFO, TAG, event("projected Bose battery into Melody EarphoneDTO"));
    }

    /**
     * The volume-panel spatial tile reads its type from the stock EarphoneDTO
     * (spatialSoundStatus), not from our synthesized provider row — so mirror the
     * live Bose [31.10] byte 2 into the DTO. Values coincide: 0=off,
     * 1=fixed-to-room, 2=fixed-to-head, exactly what the tile cycles through.
     */
    private void projectBoseSpatialIntoDto(Object address, Object dto) {
        if (targetBoseDevice == null) return;
        if (!isTargetAddress(address) || dto == null) return;
        int type = boseTransport.getSpatialType();
        if (type < 0) return;
        boolean updated = writeIntField(dto, "spatialSoundStatus", type);
        updated |= writeIntField(dto, "headsetSpatialType", type);
        if (updated) {
            log(Log.INFO, TAG, event("projected Bose spatial type=" + type + " into Melody EarphoneDTO"));
        }
    }

    /**
     * Melody's "大师调音" (adaptive ear / game equalizer) is driven by three
     * EarphoneDTO ints. OPPO implements them over its own SPP channel, which does
     * not exist for a Bose device, so the switch stayed dead. Claiming the feature
     * in the DTO makes the rows live; the actual DSP is ours — the 3-band EQ we
     * already write to [1.7] — so the toggle is mirrored onto the EQ bands instead
     * of being left as a no-op.
     */
    private void projectBoseMasterTuningIntoDto(Object address, Object dto) {
        if (targetBoseDevice == null) return;
        if (!isTargetAddress(address) || dto == null) return;
        boolean updated = writeIntField(dto, "adaptiveEar", 1);
        updated |= writeIntField(dto, "adaptiveVolume", 1);
        updated |= writeIntField(dto, "gameEqualizerStatus", 1);
        if (updated) {
            log(Log.INFO, TAG,
                    event("claimed Melody master-tuning flags for the Bose device"));
        }
    }

    private static boolean writeBooleanField(Object object, String fieldName, boolean value) {
        if (object == null) return false;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setBoolean(object, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean writeIntField(Object object, String fieldName, int value) {
        if (object == null) return false;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(object, value);
                return field.getInt(object) == value;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isMelodyEarphoneLiveData(Object value) {
        Object liveData = value;
        Object requestCode = readField(liveData, "l");
        if (!(requestCode instanceof Number)) {
            liveData = readField(value, "b");
            requestCode = readField(liveData, "l");
        }
        return requestCode instanceof Number && ((Number) requestCode).intValue() == 0xBDD;
    }

    private static Object readLiveDataValue(Object callback) {
        Object liveData = readField(callback, "b");
        return readField(liveData, "p");
    }

    private static String compact(Object value) {
        return MethodCallObserver.compact(value);
    }

    private static String signature(Method method) {
        return MethodCallObserver.signature(method);
    }

    private static String describeArgs(XposedInterface.Chain chain, int arity) {
        return MethodCallObserver.describeArgs(chain, arity);
    }

    private static String describe(Object value) {
        return MethodCallObserver.describe(value);
    }

    private static String event(String message) {
        return MethodCallObserver.event(message);
    }
}
