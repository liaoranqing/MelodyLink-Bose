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
    private static final String BOSE_WIND_KEY = "melodylink.bose.wind";
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

    /** The "降噪效果" row, captured in detailPreferenceAdd; its parent may be null then. */
    private volatile Object noiseEffectRow;

    /** Injected "抗风噪" switch, kept for state re-sync. */
    private volatile Object boseWindPreference;
    private volatile int lastWindSwitchState = -1;

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
            // JADX labels this class v9.t; the runtime name in Melody 16.8.3 is v9.C1594t.
            hookNamed(loader, "v9.C1594t", "onViewCreated", 2, "detailPreferenceHostCreated");
            // MelodyCodecTweaker's stable entry: every DetailMain preference page inherits this.
            hookNamed(loader, "androidx.preference.g", "onViewCreated", 2,
                    "detailPreferenceFragmentViewCreated");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "i", 1, "sonyCardImage");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onBindViewHolder", 1, "sonyCardBind");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onShowAnimationEnd", 0, "sonyCardLoading");
            hookAny(loader, "sonyDetailImage",
                    "com.oplus.melody.ui.widget.MelodyDetailModelView#c#1",
                    "com.oplus.melody.ui.widget.MelodyDetailModelView#b#1");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "d", 0, "sonyDetailPlaceholder");
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
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setup failed", t);
        }
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

    private boolean hookNamed(ClassLoader loader, String className, String methodName, int arity, String label) {
        try {
            Class<?> type = Class.forName(className, false, loader);
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
                    if ("sonyCardImage".equals(label) && replaceConfiguredProductImage(
                            chain.getThisObject(), "b", "c", "d", "e", "d", "card")) {
                        return null;
                    }
                    if ("sonyDetailImage".equals(label) && replaceConfiguredProductImage(
                            chain.getThisObject(), "g", "b", "c", "d", "e", "detail",
                            findDetailImageView(chain.getThisObject()))) {
                        return null;
                    }
                    if ("sonyDetailPlaceholder".equals(label) && replaceConfiguredProductImage(
                            chain.getThisObject(), "g", "b", "c", "d", "e", "detail",
                            findDetailImageView(chain.getThisObject()))) {
                        return null;
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
                    if ("detailPreferenceAdd".equals(label)) {
                        Object preference = chain.getArg(0);
                        Object result = chain.proceed();
                        removeUnsupportedDetailCategory(preference);
                        hideAncStrengthPreference(preference);
                        captureNoiseEffectRow(preference);
                        keepNoiseEffectRowHidden();
                        return result;
                    }
                    if ("detailActivityCreate".equals(label)) {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof Activity) {
                            detailActivity = (Activity) chain.getThisObject();
                            requestSonyBatteryRefresh();
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

    /** Melody's child-menu is for ANC intensity, which this module intentionally does not support. */
    /**
     * The "降噪效果" row is the reliable anchor for the injected CNC slider.
     * Capturing it the moment Melody adds it (inside detailPreferenceAdd) beats
     * searching the finished tree later: 0.4.3 lost the slider whenever
     * findPreferenceByTitle ran before the row existed, and the recursive
     * Collection-field walk is not reliable across PreferenceGroup subclasses.
     * The row itself is a dead end for Bose (it opens OPPO's own noise page,
     * which drives a non-existent SPP channel) so we hide it too.
     */
    private void captureNoiseEffectRow(Object preference) {
        if (preference == null || !boseBonded()) return;
        if (!isBoseNoiseRowClass(preference.getClass().getName())) return;
        // Remember the row instance, not its parent: at this point in
        // onCreatePreferences the parent is often still null (0.4.4 lost the slider
        // on exactly the screen where the row got added first). Retrying against
        // the live row once the tree is assembled is what actually works.
        noiseEffectRow = preference;
        for (int i = 0; i < 5; i++) {
            final long delay = i == 0 ? 100L : (i == 1 ? 300L : (i == 2 ? 700L : (i == 3 ? 1500L : 3000L)));
            mainHandler.postDelayed(this::installBoseCncUnderNoiseRow, delay);
        }
    }

    /**
     * The "降噪效果" card is re-shown by its own onBindViewHolder, so a one-shot
     * setVisible(false) is not enough. Re-assert it on every addPreference pass
     * and on a short timer after each bind.
     */
    private void keepNoiseEffectRowHidden() {
        Object noiseRow = noiseEffectRow;
        if (noiseRow == null || !boseBonded()) return;
        if (!NOISE_ROW_CLASS_DETAIL.equals(noiseRow.getClass().getName())) return;
        setPreferenceValue(noiseRow, "setVisible", Boolean.FALSE);
        mainHandler.postDelayed(() -> {
            Object row = noiseEffectRow;
            if (row == null || !boseBonded()) return;
            if (!NOISE_ROW_CLASS_DETAIL.equals(row.getClass().getName())) return;
            setPreferenceValue(row, "setVisible", Boolean.FALSE);
        }, 220L);
    }

    private void installBoseCncUnderNoiseRow() {
        try {
            Object noiseRow = noiseEffectRow;
            if (noiseRow == null || !boseBonded()) return;
            ClassLoader loader = noiseRow.getClass().getClassLoader();
            Object context = invokeNoArg(noiseRow, "getContext");
            if (!(context instanceof Context)) return;
            Activity activity = findActivity((Context) context);
            if (activity == null) activity = detailActivity;
            if (activity == null) return;
            // The row is the "降噪效果" card itself — a dead end for Bose (it opens
            // OPPO's own page, which drives a non-existent SPP channel). Hiding it
            // also stops the card background from wrapping our slider, so add the
            // slider to the row's OWN parent (the section list) instead.
            Object group = invokeNoArg(noiseRow, "getParent");
            if (group == null) return; // tree not ready yet; a later retry handles it
            // On the detail page the row is a dead end (it opens OPPO's own noise
            // page, which drives a non-existent SPP channel), so it is hidden and
            // the slider replaces it. "通用设置" renders the same concept as
            // OneSpaceNoisePreference, and there it *is* the ANC mode switch we
            // hijack — hiding it would remove a feature the user relies on, so on
            // that page we only append below it.
            // IMPORTANT: on the detail page NoiseReductionItem is BOTH the three-state
            // ANC switch the user wants and an "open OPPO's page" row. Hiding it
            // removed the ANC modes (reported as "三个耳机状态调节不见了"), so it
            // stays visible on both pages; the slider is simply added next to it.
            boolean detailPage = NOISE_ROW_CLASS_DETAIL.equals(noiseRow.getClass().getName());
            boolean hideRow = false;
            if (findPreferenceByKeyRecursive(group, BOSE_CNC_KEY)) {
                if (hideRow) setPreferenceValue(noiseRow, "setVisible", Boolean.FALSE);
                return;
            }
            Integer order = (Integer) invokeNoArg(noiseRow, "getOrder");
            // COUI renders a rounded card per contiguous run of rows; injecting at
            // order+1 glued our rows into the neighbouring card (the "items stuck
            // together" report). A gap of 10 starts a new card, which is what the
            // stock list uses between sections.
            int sliderOrder = order == null ? -1 : order + 10;
            if (addBoseCncPreference(group, loader, activity, sliderOrder)) {
                addBoseWindSwitch(group, loader, activity, sliderOrder + 1);
                // The "Bose 音效" block (EQ + remaps + 6 mode slots + power) is long;
                // on 通用设置 it duplicated the detail page and made the list
                // unreadable, so it only goes into the earbud detail page.
                if (detailPage) addBoseExtraCategory(group, loader, activity, sliderOrder + 2);
                if (hideRow) setPreferenceValue(noiseRow, "setVisible", Boolean.FALSE);
                log(Log.INFO, TAG, event("installed Bose CNC slider on "
                        + (detailPage ? "detail" : "general settings") + " page"));
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose CNC slider install failed", t);
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
        return image instanceof ImageView ? (ImageView) image : null;
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

    private ImageView findDetailImageView(Object owner) {
        if (!(owner instanceof View)) return null;
        View root = (View) owner;
        int imageId = root.getResources().getIdentifier("normal_image", "id", TARGET);
        View image = imageId == 0 ? null : root.findViewById(imageId);
        return image instanceof ImageView ? (ImageView) image : null;
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
                Class<?> type = Class.forName(className, false, loader);
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
            MelodySharedStateStore.writeBoseCncState(boseCncStateFile(), targetAddress, cnc,
                    boseTransport.getWindBlock());
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

    /** Cross-process Bose presence: any known MAC bonded (works in :fg too). */
    @SuppressLint("MissingPermission")
    private static boolean boseBonded() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            for (String mac : com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getKNOWN_MACS()) {
                BluetoothDevice device = adapter.getRemoteDevice(mac);
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
        Object activityValue = invokeNoArg(fragment, "getActivity");
        if (!(activityValue instanceof Activity)) return;
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
        if (order >= 0) setPreferenceValue(seek, "setOrder", order);
        installBoseCncListener(seek, loader);
        if (!addPreference(parent, seek, loader)) {
            log(Log.WARN, TAG, event("Bose CNC slider add rejected by parent"));
            return false;
        }
        boseCncPreference = seek;
        return true;
    }

    /**
     * Injects the "抗风噪" switch under the CNC slider. Bose exposes Wind Block on
     * the same unauthenticated [31.10] register (byte 3) but hides it in the
     * official app — see bosectl's notes on QC Ultra 2 / edith hardware.
     *
     * Audibility caveat: turning wind on masks the CNC DSP path, so the 0-10 level
     * stops sounding different until wind is switched off again.
     */
    private void addBoseWindSwitch(
            Object group, ClassLoader loader, Activity activity, int order) {
        Object toggle = newSwitchPreference(loader, activity);
        if (toggle == null) {
            log(Log.WARN, TAG, event("Bose wind switch unavailable"));
            return;
        }
        setPreferenceValue(toggle, "setKey", BOSE_WIND_KEY);
        setPreferenceValue(toggle, "setTitle", "\u6297\u98ce\u566a");
        setPreferenceValue(toggle, "setSummary",
                "\u542f\u7528\u540e\u964d\u566a\u7b49\u7ea7\u6682\u65f6\u5931\u6548");
        setPreferenceValue(toggle, "setPersistent", false);
        int wind = boseTransport.getWindBlock();
        if (wind < 0) wind = MelodySharedStateStore.readBoseCncWind(boseCncStateFile());
        if (wind < 0) wind = 0;
        setPreferenceValue(toggle, "setChecked", wind != 0);
        if (order >= 0) setPreferenceValue(toggle, "setOrder", order);
        installBoseWindListener(toggle, loader);
        if (!addPreference(group, toggle, loader)) {
            log(Log.WARN, TAG, event("Bose wind switch add rejected"));
            return;
        }
        boseWindPreference = toggle;
        lastWindSwitchState = wind;
    }

    private void installBoseWindListener(Object preference, ClassLoader loader) {
        Method listenerSetter = null;
        for (Method candidate : allMethods(preference.getClass())) {
            if (candidate.getName().equals("setOnPreferenceChangeListener")
                    && candidate.getParameterTypes().length == 1) {
                listenerSetter = candidate;
                break;
            }
        }
        if (listenerSetter == null || !listenerSetter.getParameterTypes()[0].isInterface()) {
            log(Log.WARN, TAG, event("Bose wind listener setter unavailable"));
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
        Object listener = java.lang.reflect.Proxy.newProxyInstance(loader,
                new Class<?>[]{listenerType}, (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "MelodyLinkBoseWindListener";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                    if (changeCallback == null || method.getName() != changeCallback.getName()
                            || args == null || args.length < 2 || !(args[1] instanceof Boolean)) {
                        return Boolean.TRUE;
                    }
                    boolean on = (Boolean) args[1];
                    int value = on ? 1 : 0;
                    setPreferenceValue(preference, "setSummary", on
                            ? "\u5df2\u5f00\u542f\uff0c\u964d\u566a\u7b49\u7ea7\u6682\u65f6\u5931\u6548"
                            : "\u542f\u7528\u540e\u964d\u566a\u7b49\u7ea7\u6682\u65f6\u5931\u6548");
                    if (isPrimaryProcess()) {
                        BluetoothDevice device = resolveBoseForTile();
                        if (device != null) {
                            boseTransport.cacheWindBlock(value);
                            boseTransport.writeSetting(
                                    com.melody.melodylink.bose.BoseDeviceConfig.SETTING_WIND,
                                    value, (success, index, written) -> {
                                        if (!success) {
                                            boseTransport.cacheWindBlock(-1);
                                            lastWindSwitchState = -1;
                                            mainHandler.post(() -> {
                                                setPreferenceValue(preference, "setChecked", !on);
                                                setPreferenceValue(preference, "setSummary",
                                                        "\u6297\u98ce\u566a\u672a\u751f\u6548"
                                                                + "\uff08\u56fa\u4ef6\u53ef\u80fd\u4e0d\u652f\u6301\uff09");
                                                log(Log.WARN, TAG,
                                                        "Bose wind block rejected by firmware; switch reverted");
                                            });
                                        }
                                    });
                        }
                    } else {
                        String address = targetAddress == null
                                ? MelodySharedStateStore.readBoseCncAddress(boseCncStateFile())
                                : targetAddress;
                        if (address == null) {
                            address = com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE
                                    .getKNOWN_MACS().iterator().next();
                        }
                        MelodySharedStateStore.writeBoseCncCommand(boseCncCommandFile(), address,
                                boseTransport.getCncLevel(), value,
                                java.util.UUID.randomUUID().toString());
                    }
                    return Boolean.TRUE;
                });
        try {
            listenerSetter.setAccessible(true);
            listenerSetter.invoke(preference, listener);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose wind listener install failed", t);
        }
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
        // findPreferenceByKeyRecursive answers a boolean, not an object (0.4.7
        // compared it to null and failed to compile).
        if (findPreferenceByKeyRecursive(group, BOSE_EXTRA_CATEGORY_KEY)) return;
        Object category = newPreference(loader,
                "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
        if (category == null) category = newPreference(loader,
                "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
        if (category == null) return;
        setPreferenceValue(category, "setTitle", "Bose \u97f3\u6548");
        setPreferenceValue(category, "setKey", BOSE_EXTRA_CATEGORY_KEY);
        if (order >= 0) setPreferenceValue(category, "setOrder", order);
        if (!addPreference(group, category, loader)) return;
        boseExtraCategory = category;

        boseEqSliders.clear();
        boseButtonDropdowns.clear();
        boseModeSlotSliders.clear();

        int[] shared = MelodySharedStateStore.readBoseExtraState(boseExtraStateFile());
        int eqBass = shared != null && shared.length > 0 && shared[0] >= -10 ? shared[0] : 0;
        int eqMid = shared != null && shared.length > 1 && shared[1] >= -10 ? shared[1] : 0;
        int eqTreble = shared != null && shared.length > 2 && shared[2] >= -10 ? shared[2] : 0;

        // --- 3-band EQ. Values are stored signed; the COUI bar is 0..20 offset by 10.
        addBoseBandSlider(category, loader, activity, order + 1,
                "\u4f4e\u9891", 0, eqBass);
        addBoseBandSlider(category, loader, activity, order + 2,
                "\u4e2d\u9891", 1, eqMid);
        addBoseBandSlider(category, loader, activity, order + 3,
                "\u9ad8\u9891", 2, eqTreble);

        // --- Action button remaps: single / long / double press.
        int[] events = {com.melody.melodylink.bose.BoseBmap.EVENT_SINGLE_PRESS,
                com.melody.melodylink.bose.BoseBmap.EVENT_LONG_PRESS,
                com.melody.melodylink.bose.BoseBmap.EVENT_DOUBLE_PRESS};
        String[] eventNames = {"\u5355\u51fb", "\u957f\u6309", "\u53cc\u51fb"};
        for (int i = 0; i < events.length; i++) {
            int current = shared != null && shared.length > 3 + i ? shared[3 + i] : 0;
            addBoseButtonRow(category, loader, activity, order + 4 + i,
                    eventNames[i], events[i], current);
        }

        // --- Custom mode slots 5-10. These are stored profiles: each slot keeps a
        // name plus CNC/spatial/wind/ANC so a single tap can restore that combo.
        // Writing a slot alone does not switch to it — that needs [31.3] START,
        // which the volume-panel tile already does for modes 0-4.
        addBoseModeSlotHint(category, loader, activity, order + 7);
        for (int slot = com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST;
                slot <= com.melody.melodylink.bose.BoseBmap.MODE_SLOT_LAST; slot++) {
            int offset = 6 + (slot - com.melody.melodylink.bose.BoseBmap.MODE_SLOT_FIRST);
            int level = shared != null && shared.length > offset ? shared[offset] : -1;
            addBoseModeSlotRow(category, loader, activity, order + 8 + slot, slot, level);
        }

        // --- Auto-off timer + power off.
        addBoseStandbyRow(category, loader, activity, order + 18);
        addBosePowerRow(category, loader, activity, order + 19);

        log(Log.INFO, TAG, event("installed Bose extras: EQ / buttons / mode slots / standby"));
    }

    /** One EQ band slider. The COUI bar counts 0..20; the wire value is -10..10. */
    private void addBoseBandSlider(Object parent, ClassLoader loader, Activity activity,
            int order, String title, final int bandIndex, int value) {
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
        if (order >= 0) setPreferenceValue(seek, "setOrder", order);
        installBoseBandListener(seek, bandIndex);
        if (addPreference(parent, seek, loader)) boseEqSliders.add(seek);
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

    private static ClassLoader classLoaderOf(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null ? HookModule.class.getClassLoader() : loader;
    }

    /** One Action-button event row: tapping opens a list of supported actions. */
    private void addBoseButtonRow(Object parent, ClassLoader loader, Activity activity,
            int order, String label, final int event, int currentAction) {
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.btn." + event);
        setPreferenceValue(row, "setTitle", label);
        setPreferenceValue(row, "setSummary",
                com.melody.melodylink.bose.BoseBmap.actionLabel(currentAction));
        if (order >= 0) setPreferenceValue(row, "setOrder", order);
        installBoseButtonListener(row, event);
        if (addPreference(parent, row, loader)) boseButtonDropdowns.add(row);
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
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
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
        if (order >= 0) setPreferenceValue(seek, "setOrder", order);
        installBoseModeSlotListener(seek, slot);
        if (addPreference(parent, seek, loader)) boseModeSlotSliders.add(seek);
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
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.standby");
        setPreferenceValue(row, "setTitle", "\u81ea\u52a8\u5173\u673a");
        setPreferenceValue(row, "setSummary",
                com.melody.melodylink.bose.BoseBmap.standbyLabel(
                        boseTransport.getStandbyMinutes() < 0 ? 0 : boseTransport.getStandbyMinutes()));
        if (order >= 0) setPreferenceValue(row, "setOrder", order);
        installBoseStandbyListener(row);
        addPreference(parent, row, loader);
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
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
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
        Object row = newActionPreference(loader, activity);
        if (row == null) return;
        setPreferenceValue(row, "setKey", "melodylink.bose.poweroff");
        setPreferenceValue(row, "setTitle", "\u5173\u673a");
        setPreferenceValue(row, "setSummary", "\u5173\u95ed\u8033\u673a\u5e76\u65ad\u5f00\u8fde\u63a5");
        if (order >= 0) setPreferenceValue(row, "setOrder", order);
        installBosePowerListener(row);
        addPreference(parent, row, loader);
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
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
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

    /** Pushes the earbud's real wind-block state back onto the injected switch. */
    private void updateBoseWindSwitch() {
        Object toggle = boseWindPreference;
        if (toggle == null) return;
        int wind = boseTransport.getWindBlock();
        if (wind < 0) wind = MelodySharedStateStore.readBoseCncWind(boseCncStateFile());
        if (wind < 0 || wind == lastWindSwitchState) return;
        lastWindSwitchState = wind;
        final boolean on = wind != 0;
        mainHandler.post(() -> {
            setPreferenceValue(toggle, "setChecked", on);
            setPreferenceValue(toggle, "setSummary", on
                    ? "\u5df2\u5f00\u542f\uff0c\u964d\u566a\u7b49\u7ea7\u6682\u65f6\u5931\u6548"
                    : "\u542f\u7528\u540e\u964d\u566a\u7b49\u7ea7\u6682\u65f6\u5931\u6548");
        });
    }

    /** Re-sync the injected slider when a BMAP session reports the real CNC level. */
    private volatile int lastCncSliderLevel = -2;
    private void updateBoseCncSlider() {
        updateBoseWindSwitch();
        Object slider = boseCncPreference;
        if (slider == null) return;
        int level = boseTransport.getCncLevel();
        if (level < 0) {
            int[] shared = MelodySharedStateStore.readBoseCncState(boseCncStateFile());
            if (shared != null) level = shared[1];
        }
        if (level < 0 || level == lastCncSliderLevel) return;
        lastCncSliderLevel = level;
        final int value = level;
        mainHandler.post(() -> {
            invokeInt(slider, "setProgress", value);
            setPreferenceValue(slider, "setSummary", "\u6548\u679c\u5f3a\u5ea6 " + value + "/10");
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

    private static Object newPreference(ClassLoader loader, String typeName, Context context) {
        try {
            Class<?> type = Class.forName(typeName, false, loader);
            try {
                Constructor<?> constructor = type.getConstructor(android.content.Context.class,
                        android.util.AttributeSet.class);
                return constructor.newInstance(context, null);
            } catch (NoSuchMethodException ignored) {
                return type.getConstructor(android.content.Context.class).newInstance(context);
            }
        } catch (Throwable ignored) {
            return null;
        }
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
        try {
            Class<?> preference = Class.forName("androidx.preference.Preference", false, loader);
            for (String name : new String[]{"addPreference", "f"}) {
                for (Method method : allMethods(parent.getClass())) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != 1
                            || !method.getParameterTypes()[0].isAssignableFrom(child.getClass())) continue;
                    try {
                        method.setAccessible(true);
                        Object result = method.invoke(parent, child);
                        return !(result instanceof Boolean) || (Boolean) result;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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

    private static void setPreferenceValue(Object target, String name, Object value) {        if (target == null) return;
        for (Method method : allMethods(target.getClass())) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 1) {
                try {
                    method.setAccessible(true);
                    method.invoke(target, value);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
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
        if (command.wind >= 0) {
            // The switch changed: write only the wind byte so the level we echo
            // back does not clobber it (and vice versa).
            final int wind = command.wind > 0 ? 1 : 0;
            log(Log.INFO, TAG, event("executing forwarded Bose wind write value=" + wind));
            boseTransport.cacheWindBlock(wind);
            boseTransport.writeSetting(
                    com.melody.melodylink.bose.BoseDeviceConfig.SETTING_WIND, wind,
                    (success, index, written) -> {
                        if (success) {
                            log(Log.INFO, TAG, event("Bose wind block "
                                    + (written != 0 ? "enabled" : "disabled")));
                        } else {
                            // The firmware accepted the frame but may not have a
                            // wind path on in-true-wireless models; report honestly.
                            log(Log.WARN, TAG, event("Bose wind block write failed"
                                    + " (firmware may ignore it on this model)"));
                            boseTransport.cacheWindBlock(-1);
                        }
                    });
        }
        if (boseTransport.getCncLevel() != level) {
            log(Log.INFO, TAG, event("executing forwarded Bose CNC level write level=" + level));
            boseTransport.cacheCncLevel(level);
            boseTransport.writeSetting(
                    com.melody.melodylink.bose.BoseDeviceConfig.SETTING_CNC, level);
        }
        String publishedAddress = targetAddress == null ? command.address : targetAddress;
        MelodySharedStateStore.writeBoseCncState(boseCncStateFile(), publishedAddress, level,
                boseTransport.getWindBlock());
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
                        "ML" + command.index, command.value, 0, boseTransport.getWindBlock() > 0 ? 1 : 0,
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
            Constructor<?> constructor = batteryStatusClass.getConstructor(int.class, boolean.class);
            boolean updated = false;
            updated |= setBatteryStatus(status, "setLeftBatteryStatus", constructor, state.getBattery().get(BatteryPart.LEFT));
            updated |= setBatteryStatus(status, "setRightBatteryStatus", constructor, state.getBattery().get(BatteryPart.RIGHT));
            updated |= setBatteryStatus(status, "setBoxBatteryStatus", constructor, state.getBattery().get(BatteryPart.CASE));
            if (!updated) {
                log(Log.INFO, TAG, event("Sony battery publish retained previous Melody values (" + reason + ")"));
                return;
            }
            Method notifyChanged = null;
            for (String candidate : new String[]{"x1", "B1"}) {
                try {
                    notifyChanged = repository.getClass().getDeclaredMethod(candidate, String.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (notifyChanged == null) throw new NoSuchMethodException("battery notify (x1/B1)");
            notifyChanged.setAccessible(true);
            notifyChanged.invoke(repository, address);
            log(Log.INFO, TAG, event("published Sony battery through Melody V/U.x1 (" + reason + ")"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony battery publish failed", t);
        }
    }

    private static boolean setBatteryStatus(
            Object status,
            String setterName,
            Constructor<?> constructor,
            BatteryValue battery
    ) throws Exception {
        if (battery == null) return false;
        Object batteryStatus = constructor.newInstance(battery.getPercent(), battery.getCharging());
        Method setter = status.getClass().getMethod(setterName, batteryStatus.getClass());
        setter.invoke(status, batteryStatus);
        return true;
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
