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
    private static final int WF_1000XM3_PRODUCT_ID = 0x067410;
    private volatile int targetAddressHash;
    private volatile String targetAddress;
    private volatile BluetoothDevice targetSonyDevice;
    private volatile BluetoothDevice targetSamsungDevice;
    private volatile BluetoothDevice targetHuaweiDevice;
    private volatile BluetoothDevice targetXiaomiDevice;
    /** Host A2DP/HFP state remains authoritative for UI connection, even if AF00 control setup fails. */
    private volatile boolean xiaomiHostConnected;
    private volatile Object earphoneRepository;
    private volatile MelodySharedStateStore sharedStateStore;
    private final MelodySessionState sonySessionState = new MelodySessionState();
    private final MelodySessionState huaweiSessionState = new MelodySessionState();
    private final MelodySessionState xiaomiSessionState = new MelodySessionState();
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
            hookNamed(loader, "com.oplus.melody.common.util.V", "a", 3, "whitelist");
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
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "c", 1, "sonyDetailImage");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "d", 0, "sonyDetailPlaceholder");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "onFinishInflate", 0, "sonyDetailInflated");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "setViewModel", 1, "sonyDetailViewModel");
            hookNamed(loader, "androidx.preference.PreferenceGroup", "f", 1, "detailPreferenceAdd");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "z", 1, "repositoryObserve");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "y", 1, "repositoryGet");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "g1", 1, "repositoryDtoBuild");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getConnectionState", 0, "dtoConnectionState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getAclConnectionState", 0, "dtoAclState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isSupportSpp", 0, "dtoSupportSpp");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isInitCmdCompleted", 0, "dtoInitCompleted");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getNoiseReductionModeIndex", 0, "dtoNoiseReductionMode");
            hookNamed(loader, "v9.C1576a", "getConnectionState", 0, "detailInfoConnectionState");
            hookNamed(loader, "v9.C1576a", "getHeadsetConnectionState", 0, "detailInfoHeadsetState");
            hookNamed(loader, "v9.C1576a", "getIsSpp", 0, "detailInfoSupportSpp");
            hookNamed(loader, "v9.a", "getConnectionState", 0, "detailInfoConnectionStateActual");
            hookNamed(loader, "v9.a", "getHeadsetConnectionState", 0, "detailInfoHeadsetStateActual");
            hookNamed(loader, "v9.a", "getIsSpp", 0, "detailInfoSupportSppActual");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.a", "getCurrentNoiseReductionModeIndex", 0, "opsNoiseReductionMode");
            hookNamed(loader, "pa.C1405p", "getCurrentNoiseReductionModeIndex", 0, "noiseReductionModeVO");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onEarphoneDataChanged", 1, "noiseReductionItemDataChanged");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem$a", "c", 2, "nativeNoiseReductionClick");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u", 2, "connectToDevice");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "v", 1, "connectImmediate");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u0", 2, "sendPacket");
            hookNamed(loader, "E7.c", "b", 1, "nativeConnectDevice");
            hookNamed(loader, "E7.c", "a", 2, "directConnectSpp");
            hookNamed(loader, "E7.c", "e", 1, "nativeConnectionState");
            hookNamed(loader, "A7.h", "h", 2, "nativeConnectSuccess");
            hookNamed(loader, "A7.h", "e", 2, "nativeConnectFailure");
            hookNamed(loader, "C7.b", "k", 1, "socketFailureBranch");
            hookNamed(loader, "D7.a", "f", 2, "connectionFailureReport");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "m0", 1, "receiveEvent");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "L0", 3, "noiseWrite");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "s0", 2, "noiseModeWrite");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "s0", 2, "noiseModeWriteClient");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "z", 1, "repositoryClientObserve");
            hookNamed(loader, "V7.v", "g", 0, "melodyEarphoneLiveDataRequest");
            hookNamed(loader, "V7.u", "handleMessage", 1, "melodyEarphoneLiveDataResponse");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "k1", 1, "stateCallback");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "x1", 1, "repositoryNotify");
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

    private void hookNamed(ClassLoader loader, String className, String methodName, int arity, String label) {
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
                return;
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
                                || (xiaomiTransport != null && xiaomiTransport.isConnected()))
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
                            || isRegisteredXiaomiName((String) deviceName))) {
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
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + label + " in " + className, t);
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
                || (targetXiaomiDevice != null && isRegisteredXiaomiDevice(targetXiaomiDevice));
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
        return replaceSonyProductImage(owner, viewModelField, addressField, nameField,
                imageField, loadingField, surface, fallbackImageView)
                || replaceHuaweiProductImage(owner, viewModelField, addressField, nameField,
                imageField, loadingField, surface, fallbackImageView)
                || replaceXiaomiProductImage(owner, viewModelField, addressField, nameField,
                imageField, loadingField, surface, fallbackImageView);
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
        view.post(() -> replaceConfiguredProductImage(owner, "g", "b", "c", "d", "e", "detail",
                findDetailImageView(owner)));
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
                packageName + "Z"
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
                    || isRegisteredHuaweiDevice(device) || isRegisteredXiaomiDevice(device);
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

    private boolean isSonyConnected() {
        return targetAddress != null && (sonyTransport.isConnected()
                || samsungTransport.isConnected()
                || huaweiTransport.isConnected()
                || (xiaomiTransport != null && xiaomiTransport.isConnected())
                || (targetXiaomiDevice != null && xiaomiHostConnected)
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
        targetSamsungDevice = null;
        targetHuaweiDevice = null;
        targetXiaomiDevice = null;
        xiaomiHostConnected = false;
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
        if (!hasSonySettings && !hasHuaweiLowLatency) return;
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
            Object anchor = findPreferenceByTitle(screen, SOUND_QUALITY_TITLE);
            if (anchor == null) throw new IllegalStateException("audio quality anchor unavailable");
            Object parent = invokeNoArg(anchor, "getParent");
            if (parent == null) throw new IllegalStateException("audio quality parent unavailable");
            if (findPreference(parent, ADVANCED_CATEGORY_KEY) != null
                    || findPreferenceByKeyRecursive(parent, ADVANCED_CATEGORY_KEY)) return;
            Object category = newPreference(loader,
                    "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory", activity);
            if (category == null) category = newPreference(loader,
                    "com.coui.appcompat.preference.COUIPreferenceCategory", activity);
            if (category == null) throw new IllegalStateException("category constructor unavailable");
            setPreferenceValue(category, "setTitle", "\u9ad8\u7ea7\u8bbe\u7f6e");
            setPreferenceValue(category, "setKey", ADVANCED_CATEGORY_KEY);
            Integer order = (Integer) invokeNoArg(anchor, "getOrder");
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

    private void installSettingListener(Object preference, SonyAdvancedSettingId id, ClassLoader loader) {
        Method listenerSetter = null;
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

    private static void setPreferenceValue(Object target, String name, Object value) {
        if (target == null) return;
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
            if (!isPrimaryProcess()) applySharedAdvancedSettings(state);
        }
        log(Log.INFO, TAG, event("foreground Sony state changed; requesting native Melody LiveData refresh"
                + " mode=" + (state == null ? -1 : state.modeIndex)));
        refreshTargetRepository("foreground shared Sony state changed");
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
            Method notifyChanged = repository.getClass().getDeclaredMethod("x1", String.class);
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
            Class<?> batteryStatusClass = Class.forName(
                    "com.oplus.melody.model.repository.earphone.V$a", false, loader);
            Constructor<?> constructor = batteryStatusClass.getConstructor(int.class, boolean.class);
            boolean updated = false;
            updated |= setBatteryStatus(status, "setLeftBatteryStatus", constructor, state.getBattery().get(BatteryPart.LEFT));
            updated |= setBatteryStatus(status, "setRightBatteryStatus", constructor, state.getBattery().get(BatteryPart.RIGHT));
            updated |= setBatteryStatus(status, "setBoxBatteryStatus", constructor, state.getBattery().get(BatteryPart.CASE));
            if (!updated) {
                log(Log.INFO, TAG, event("Sony battery publish retained previous Melody values (" + reason + ")"));
                return;
            }
            Method notifyChanged = repository.getClass().getDeclaredMethod("x1", String.class);
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
