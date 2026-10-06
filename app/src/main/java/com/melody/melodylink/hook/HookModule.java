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
import android.view.ViewParent;
import android.widget.ImageView;

import com.melody.melodylink.observer.MethodCallObserver;
import com.melody.melodylink.domain.AncMode;
import com.melody.melodylink.domain.BatteryPart;
import com.melody.melodylink.domain.BatteryValue;
import com.melody.melodylink.domain.EarbudsState;
import com.melody.melodylink.bose.BoseDeviceConfig;
import com.melody.melodylink.bose.BoseTransport;

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
    /** The catalog entry with the most controls, used to fill a null children list. */
    private static volatile Object richestCatalogEntry;
    /** Marks catalog entries we added, so containsBoseEntry() can recognise them. */
    private static final String BOSE_ENTRY_PREFIX = "Bose QC Ultra";
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
    private volatile BluetoothDevice targetBoseDevice;
    private volatile Object earphoneRepository;
    private volatile MelodySharedStateStore sharedStateStore;
    private final MelodySessionState sonySessionState = new MelodySessionState();
    private final MelodySessionState boseSessionState = new MelodySessionState();
    /** A2DP host link is authoritative for the Bose UI, like Xiaomi; the BMAP channel is transient. */
    private volatile boolean boseHostConnected;
    private volatile ClassLoader melodyClassLoader;
    private volatile CompletableFuture<Object> pendingNoiseWrite;
    private volatile AncMode pendingAncMode;
    private volatile boolean pendingBatteryRefresh;
    private volatile ScheduledExecutorService foregroundStateWatcher;
    private volatile String lastForegroundStateFingerprint;
    private volatile String lastSonyCommandFingerprint;
    private volatile String lastSonyBatteryCommandNonce;
    private volatile String lastSonySettingCommandNonce;
    private volatile String lastBoseCncCommandNonce;
    private volatile boolean sonyConfigInitialized;
    private volatile AssetManager sonyModuleAssets;
    private volatile boolean retainSharedSonyStateAfterCommandDisconnect;
    private volatile boolean activityLifecycleRegistered;
    private volatile Activity detailActivity;
    private volatile Object lastAudioPreferenceAnchor;
    /** The Ba/z noiseReductionModeVO most recently passed to onEarphoneDataChanged (:fg). */
    private volatile Object lastNoiseReductionVo;
    private volatile int startedActivityCount;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> detailAncWriteObserved = new ThreadLocal<>();
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
    /** The 通用设置 row repaints only from its LiveData observer; keep it so we can replay it. */
    private volatile Object oneSpaceNoiseObserver;
    private volatile Object oneSpaceNoiseVo;

    /** Injected "抗风噪" switch, kept for state re-sync. */

    /** Injected "音效调节" panel: three EQ sliders, button remaps, mode slots. */
    private final List<Object> boseEqSliders = new ArrayList<>();
    private final List<Object> boseButtonDropdowns = new ArrayList<>();
    private final List<Object> boseModeSlotSliders = new ArrayList<>();
    private volatile Object boseExtraCategory;
    private volatile Boolean confirmedHuaweiLowLatency;

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




    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        try {
            initializeSonyConfig();
            // onPackageReady fires before Application.attach, so the first init
            // attempt usually sees currentApplication()==null. Before the vendor
            // strip, the whitelist-branch isRegistered*Name() calls kept retrying
            // the init as a side effect; 2.0.0 removed them, so retry explicitly
            // until the module asset path resolves.
            for (long delay : new long[]{300L, 1000L, 3000L, 8000L, 20000L}) {
                mainHandler.postDelayed(this::initializeSonyConfig, delay);
            }
            if (isPrimaryProcess()) {
                clearSharedSonyCommand();
                clearSharedSonyBatteryCommand();
                registerAppVisibilityLifecycleCallbacks();
            }
            ClassLoader loader = param.getClassLoader();
            melodyClassLoader = loader;
            hostLoaderRef = loader;
            if (isPrimaryProcess()) installProviderBridge(loader);
            hookAny(loader, "whitelist",
                    "com.oplus.melody.common.util.V#a#3",
                    "com.oplus.melody.common.util.T#a#3");
            // ---- 17.6.3 real whitelist source (diagnosis, no behaviour change) ----
            // The 16.x entry points above are gone in 17.6.3. Verified from smali:
            //   com/oplus/melody/model/repository/whitelist/a.smali
            //     .source "WhitelistRepositoryServerImpl.kt"
            //   a(String) -> WhitelistConfigDTO   has TWO paths:
            //     1) earphone/b;->I().y(mac) -> EarphoneDTO, then c(productId, name)
            //     2) fallback: Lr7/m;->h(mac) -> BluetoothDevice, then T.b(device, g())
            //   g() -> i() -> WhitelistContentDO.getWhiteList(); EMPTY_LIST when null.
            //   i() waits CompletableFuture.get(500ms) then reads a LiveData snapshot,
            // so the content is ASYNC and can legitimately be absent on first read.
            // These hooks only report; nothing is rewritten yet.
            //
            // 0.5.52 REGRESSION FIX: g() and i() were BOTH hooked in 0.5.51. g() calls i()
            // internally, and the host hits this path dozens of times per second, so the two
            // probes plus their MLog writes produced 46 events in a single second and stalled
            // the main thread badly enough that even 通用设置 would not open. Only the
            // low-frequency a(String) entry point is hooked now; the catalog size and content
            // readiness are read from inside that one event instead of via extra hooks.
            hookNamed(loader, "com.oplus.melody.model.repository.whitelist.a",
                    "a", 1, "wl17Lookup");
            // ---- The detail page's PreferenceFragment (0.5.54: class was wrong) ----
            // 16.x looked for "v9.z"; that class does not exist in 17.6.3.
            // 0.5.51 hooked G9/H, but 0.5.53 device logs proved that is the WRONG class:
            //   evt=bose.frag17 class=G9.H arg0=null result=null
            // fired only for the STATIC u(String)Z probe — no instance method ever ran, and
            // "new-instance LG9/H;" appears nowhere in either dex. G9/H is never instantiated.
            //
            // The real host is G9/Q, proven from A9/f.1 (the "settingListChanged" path):
            //   iget-object v0, v0, LA9/f;->b:Ljava/lang/Object;
            //   check-cast       v0, LG9/Q;
            //   const-string     v4, "DetailMainPreferenceFragment"
            //   invoke-virtual  {v0}, Fragment;->getActivity()
            //
            // Full chain verified across both dex files:
            //   G9/Q -> com/oplus/melody/ui/base/b
            //        -> com/coui/appcompat/preference/j
            //        -> com/coui/appcompat/preference/g
            // G9/Q declares: onCreate(Bundle) / onDestroy() / onHiddenChanged(Z) / t() /
            // u(LayoutInflater,ViewGroup) returning a RecyclerView, plus the static
            // y:Ljava/util/List; field.
            // G9/Q.t() is the whole detail page: its entire body is
            //     invoke-virtual {p0, v0}, Landroidx/preference/g;->s(I)V
            // i.e. setPreferencesFromResource(0x7f140012). That single call inflates the
            // XML into the preference tree, so it is the exact moment the tree becomes
            // real. 0.5.54 evidence: onCreate still reported screen=null, because
            // onCreate only sets a flag (ui/base/b.u = false).
            // 0.5.58: hookAny binds only the FIRST candidate that resolves, so listing four
            // methods under one label meant only onCreate was ever hooked — t(), the method
            // that actually inflates the XML into the tree, was silently skipped. That is why
            // 0.5.54-0.5.57 all reported screen=null: the hook fired before the tree existed.
            // Each method now gets its own label so all four are really hooked.
            hookNamed(loader, "G9.Q", "onCreate", 1, "detailFragCreate");
            hookNamed(loader, "G9.Q", "onViewCreated", 2, "detailFragViewCreated");
            hookNamed(loader, "G9.Q", "onHiddenChanged", 1, "detailFragHidden");
            // t() is the whole page: setPreferencesFromResource(0x7f140012).
            hookNamed(loader, "G9.Q", "t", 0, "detailFragBuild");
            // 0.5.65: 0.5.64 device logs are decisive —
            //   evt=bose.anc.tree_row_hidden key=NoiseReductionItem
            //       visible_after=false view_gone=false
            // setVisible(false) DID work, but getView() returned null, i.e. the row had not
            // been bound yet at sweep time, so there was no view to collapse. The bind happens
            // later, per RecyclerView pass, which is why the picker came back. Hiding the row
            // again right after every bind closes the loop.
            // 0.5.66: 0.5.65 hooked NoiseReductionItem.onBindViewHolder and it never fired
            // (no bose.anc.bind_hidden event at all, and no hook.miss either, so the hook was
            // registered). Reason found in smali: nothing in the APK ever *calls*
            // Preference.onBindViewHolder with a preference receiver — only
            // invoke-super chains exist. The real entry point is the RecyclerView adapter:
            //     androidx/preference/h.smali:1704
            //       onBindViewHolder(RecyclerView$E, I)V
            //       :1809 invoke-virtual {p1}, Landroidx/preference/Preference;->onBindViewHolder(...)
            // So the adapter method is hooked instead, and the row view is taken straight from
            // the ViewHolder's itemView, which always exists at that point.
            hookNamed(loader, "androidx.preference.h", "onBindViewHolder", 2, "ancRowBind");
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
            // 0.5.75 — THE REAL #4 PATH, proven from smali (no more guessing):
            //
            //   NoiseReductionItem.<init>(Context, DetailMainViewModel, LifecycleOwner)
            //     -> new Ba/m(ctx, viewModel)                       [NRI:313-321]
            //   Ba/m.a(List, int, Ba/z)  builds Ba/x (四级降噪选择器) and
            //     Ba/A (增强人声) ONLY when a mode's getChildrenMode() is non-empty
            //     [Ba/m:519-550, 355/511 getChildrenMode, 475 new Ba/x, 646 new Ba/A]
            //   The list comes from Ba/z.getNoiseReductionModeList() [NRI:549, 4052]
            //   Ba/z.<init>(EarphoneDTO) fills it from
            //     c9/a.f().c(productId, name).getFunction().getNoiseReductionMode()
            //     [Ba/z:207-292] and stores the SAME List reference — no copy.
            //
            // Two facts kill every earlier fix:
            //  (1) the lookup that feeds this page is c9/a.c(PRODUCT_ID, NAME), keyed by
            //      product id + bluetooth name — NOT by MAC. That is why the Bose MAC
            //      (68:F2:1F:3D:41:D7) never appeared in any L6/a lookup in 455 events:
            //      L6/a (SupportConfigManager) and c9/a (WhitelistRepository) are two
            //      separate catalogs. Our injected clone lives in L6/a's list only.
            //  (2) because Ba/z holds the catalog's own List, stripEncoSubLevels on a
            //      shallow copy never touches the modes the UI reads — and clearing in
            //      place would corrupt the user's GENUINE Enco X3 page, since
            //      Ba/z:521 calls setChildrenMode() on those shared mode objects.
            //
            // So: intercept the getter, and return a list of per-mode shallow copies with
            // childrenMode cleared through the KEEP-NAMED public setter. The catalog is
            // never mutated; only this VO's view of it is. Guarded by detailPageIsBose()
            // (the intent MAC of the live DetailMainActivity), so the real Enco X3 keeps
            // its four-level picker. getChildrenMode/setChildrenMode/childrenMode are all
            // keep-named (s1dto WhitelistConfigDTO$NoiseReductionMode:1611/1781/97), and
            // getNoiseReductionModeList is public non-final, so it hooks.
            hookNamed(loader, "Ba/z", "getNoiseReductionModeList", 0, "noiseReductionVOList");
            // The fragment that the callback is supposed to populate. Watching its lifecycle
            // tells us whether it is created at all once the config resolves.
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainFragment",
                    "onCreateView", 3, "detailFragmentCreated");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "i", 1, "sonyCardImage");
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onBindViewHolder", 1, "sonyCardBind");
            // 通用设置 ANC three-state row (降噪/关闭/通透). Its onBindViewHolder is the only
            // place that exposes the DeviceControlWidget in field d, i.e. the row the user
            // sees; the slider has to be attached to that widget's parent. Unlike the detail
            // page this row is a plain RecyclerView item, not a Preference, so it can never
            // be reached through the preference-screen injection path.
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceNoisePreference", "onBindViewHolder", 1, "onespaceNoiseBind");
            // 0.5.67 — THE ROOT CAUSE OF "降噪效果 STILL THERE", found in smali, not guessed.
            //
            // res/cS.xml (0x7f140003, the 通用设置 screen) declares:
            //   OneSpaceNoisePreference      key=pref_noise_switch          <- the 3-state widget
            //   COUIPreferenceCategory       key=pref_noise_menu_category   <- field v
            //     COUIMenuPreference         key=pref_noise_menu            <- field w
            //                              android:title=@7F11038A = "降噪效果"
            // Both category and row default to isPreferenceVisible="false"; the ONLY things
            // that ever turn them on are two methods of OneSpaceListFragment
            // (smali2/com/oplus/melody/onespace/b.smali):
            //   w(ZZ)V        :2001  v.setVisible(p2)
            //   x(List,Z)V    :3144  "checkShowNoiseMenuItem set visible true!"
            //                      v.setVisible(childrenMode non-empty)
            // x() is invoked from OneSpaceListFragment$initObserver$1, a LiveData observer on
            // WhitelistConfigDTO reading dto.getFunction().getNoiseReductionMode().
            //
            // That list is non-empty FOR BOSE ONLY BECAUSE OF US: injectBoseCatalogEntry clones
            // the richest Enco entry (which carries a four-level noiseReductionMode) and
            // fillMissingChildren copies donor children into it. So our own injected catalog
            // row is what convinces the host that Bose supports 深度/均衡/轻度/智能切换.
            //
            // We must NOT clear noiseReductionMode instead: the very same list also drives
            // initObserver$1's "checkShowNoiseCardItem", which is what makes
            // pref_noise_switch (the three-state widget) visible. Clearing it would delete the
            // widget — exactly the 0.5.65 regression the user reported.
            //
            // So the fix is to let the host run and then re-hide only the menu category, on
            // every emit. Hiding the CATEGORY (not the switch) drops the "降噪效果" row from the
            // adapter's flattened list and leaves the three-state widget untouched.
            // hideAncStrengthPreference() already targets these two keys but only runs from
            // detailPreferenceAdd, i.e. once at XML inflate time — long before x() re-shows
            // them, which is why it never had any visible effect.
            hookNamed(loader, "com.oplus.melody.onespace.b", "x", 2, "onespaceNoiseMenuCheck");
            hookNamed(loader, "com.oplus.melody.onespace.b", "w", 2, "onespaceNoiseSwitchShow");
            // Product image. Confirmed against Melody 17.6.3 smali: b(String) is the
            // 3D-model loader and c()Z is a low-memory check — neither touches the photo,
            // which is why 0.5.x reported a successful replacement that never showed up.
            // The Glide call lives in a() (void), and e() — the branch taken when the
            // product has no detail source, i.e. every non-catalog device like Bose —
            // invokes a(). So a() is the one place that always runs.
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "a", 0, "sonyDetailImage");
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "d", 1, "sonyDetailPlaceholder");
            // 1.0.1: b(String) IS the 3D model loader (initModel). 17.6.3 smali: it
            // cross-fades the photo ImageView (field d) out, builds a
            // com.oplusos.vfxmodelviewer.view.ModelViewer (OPPO's Filament/gltfio
            // wrapper) and feeds the file bytes to ModelScene.loadSceneFromBuffer
            // (ByteBuffer) — so the loaded file is a plain glTF/GLB. d(F8/i) only
            // reaches b() when c() (device capability) passes AND the DTO's model
            // file exists (o.h), and for a Bose session the DTO is the stripped Enco
            // X3 one, so the host would play the X3 model. Swap the path to our
            // bundled Bose glb instead; the 2D photo (field d) is kept VISIBLE but
            // transparent so only the 3D model shows (see boseTransparentPhoto /
            // replaceBoseDetailImageNow).
            hookNamed(loader, "com.oplus.melody.ui.widget.MelodyDetailModelView", "b", 1, "boseDetailModel");
            // 2.0.5: keep the detail photo VISIBLE (the model only renders while field d
            // is VISIBLE) but pinned transparent. The host's b() sets d alpha 1 then
            // cross-fades to 0; we clamp every setAlpha on the current detail photo to
            // 0f so it never becomes visible. Single volatile-ref compare (no lock), so
            // the hot View.setAlpha path stays cheap even when active (ANR lesson #7).
            hookNamed(loader, "android.view.View", "setAlpha", 1, "bosePhotoAlphaClamp");
            // 2.0.5: detailSetVisibility additionally forces the same photo back to
            // VISIBLE if the host tries to GONE it (which would again blank the model).
            // 2.0.0 bug fix (3D model not loading): the host logs
            // "picFilePath not match, productId: 067410, colorId: -1" for a Bose
            // session — the device DTO carries the default colorId -1 while the
            // resource packages are keyed by (productId, colorId). The real X3 is
            // colorId 3 (the fetch*_067410_3 dirs prove its package exists), so the
            // Bose page must borrow color 3 to obtain a detail source at all; the
            // model path boseDetailModel swaps only materialises after that.
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getColorId", 0, "boseColorId");
            // 2.0.0 bug fix (通用设置 spinner behind the photo): the header widget
            // replays its loading Lottie when the page entrance animation ends —
            // OneSpaceHeaderPreference.onShowAnimationEnd sets field d VISIBLE and
            // playAnimation() AFTER our photo sweeps ran. The old opaque photo hid
            // it by luck; the transparent v3 cutout exposed it. Kill it here.
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceHeaderPreference", "onShowAnimationEnd", 0, "boseHeaderShowAnimationEnd");
            hookNamed(loader, "androidx.preference.PreferenceGroup", "f", 1, "detailPreferenceAdd");
            // 0.5.48: stop guessing who hides the detail content. setVisibility is the only
            // way a view goes from laid-out to invisible, so intercepting it and reporting the
            // caller for targets inside the detail container names the culprit directly.
            hookNamed(loader, "android.view.View", "setVisibility", 1, "detailSetVisibility");
            // 0.5.73 — the detail page vanishes seconds after rendering. The t+5000
            // container dump still shows a complete, fully VISIBLE content tree, and the
            // only hidden_by entries are DecorView INVISIBLE size 0x0 caller=no_app_frame:
            // the WINDOW is being torn down, i.e. the Activity is finishing — not a view
            // being hidden. Every hook that was supposed to intercept the host's state
            // checks (v9.a.getConnectionState, DetailMainViewModel.f) misses in 17.6.3,
            // so instead of guessing again: hook Activity.finish, log the host caller
            // chain, and block finish() on OUR detail activity when the caller is host
            // auto-close logic (lesson #3: capture the stack, don't read obfuscated code).
            hookNamed(loader, "android.app.Activity", "finish", 0, "detailActivityFinish");
            // 0.5.76. detail.finish raw stack shows finish() comes from
            // Activity.finishAfterTransition:7852 -> RequestFinishCallback.run, i.e. the
            // page is closed by finishAfterTransition(), not plain finish(). Hook the
            // trigger itself so the caller that decided to close the page is named.
            hookNamed(loader, "android.app.Activity", "finishAfterTransition", 0, "detailActivityFinishAfterTransition");
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
            // 2.0.12 bug1 (通用设置 side): that row writes through a "setgate" Intent, so it
            // never reaches nativeNoiseReductionClick/noiseModeWrite in this process, and it
            // paints only inside its LiveData observer (smali: $initObserver$1.invoke assigns
            // q and reads q.getMCurrentNoiseMode(); onBindViewHolder never touches the widget).
            // Capture that observer so we can replay it as soon as the mode is mirrored.
            hookNamed(loader, "com.oplus.melody.onespace.items.OneSpaceNoisePreference$b", "onChanged", 1, "oneSpaceNoiseObserver");
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
                // 0.5.71 HOT-PATH FIX. The ANR main-thread dump (2026-10-04 18:55) shows this
                // hook body executing on EVERY View.setVisibility call. isMelodyEarphoneLiveData
                // walks the whole class chain via readField, which throws a NoSuchFieldException
                // per level (fillInStackTrace is expensive). A View has no "l"/"b" field, so the
                // cost was paid dozens of times per setVisibility for a value only two LiveData
                // labels ever read. Compute it lazily, and only for those two labels.
                boolean liveDataLabel = "melodyEarphoneLiveDataRequest".equals(label)
                        || "melodyEarphoneLiveDataResponse".equals(label);
                boolean traceCall = shouldTrace(label, chain, arity);
                if (liveDataLabel) {
                    traceCall = isMelodyEarphoneLiveData(chain.getThisObject());
                }
                if (traceCall) {
                    log(Log.INFO, TAG, event(label + " before " + signature(method) + " args=" + describeArgs(chain, arity)));
                }
                try {
                    captureRepository(label, chain);
                    if ("sonyCardImage".equals(label)) {
                        // Bose-only build: OneSpaceHeaderPreference.i(Lf9/b;) is the
                        // 通用设置 product photo loader. 17.6.3 smali shows it reads
                        // getDetailImageRes(), which is empty for a device with no
                        // catalog entry, so the host never sets a drawable and the card
                        // stays blank. This branch puts our photo there.
                        if (replaceBoseOneSpaceHeaderImage(chain.getThisObject())) {
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
                    if ("boseDetailModel".equals(label)) {
                        Object modelOwner = chain.getThisObject();
                        Object detailViewModel = readField(modelOwner, "g");
                        String modelAddress = asString(readField(detailViewModel, "b"));
                        if (com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.matchesAddress(modelAddress)) {
                            File modelFile = materializeBoseModel();
                            if (modelFile != null) {
                                // 2.0.5: keep the 2D photo VISIBLE (model render condition)
                                // but transparent, so only the 3D model shows. Register
                                // field d (sibling ImageView of the model container); the
                                // setAlpha clamp + detailSetVisibility keep it VISIBLE+alpha0.
                                try {
                                    Object photo = readField(modelOwner, "d");
                                    if (photo instanceof ImageView) {
                                        makeBosePhotoTransparent((ImageView) photo);
                                    }
                                } catch (Throwable ignored) {
                                }
                                MLog.event("bose.model.swap",
                                        "size", modelFile.length(),
                                        "path", modelFile.getName());
                                return chain.proceed(new Object[]{modelFile.getAbsolutePath()});
                            }
                            MLog.event("bose.model.skip", "reason", "asset_unavailable");
                        }
                        // non-Bose session (the real Enco X3): host behavior unchanged.
                    }
                    if ("bosePhotoAlphaClamp".equals(label)) {
                        if (chain.getThisObject() == boseTransparentPhoto) {
                            // Pin the transparent photo: ignore the host's alpha value.
                            return chain.proceed(new Object[]{0f});
                        }
                        return chain.proceed();
                    }
                    if ("boseColorId".equals(label)) {
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            Object mac = self == null ? null : readField(self, "macAddress");
                            if (result instanceof Integer
                                    && mac instanceof String
                                    && com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE
                                            .matchesAddress((String) mac)) {
                                // 2.0.0: borrow the real Enco X3 color slot (3) so the
                                // (productId, colorId) resource lookup resolves — the host
                                // previously logged "picFilePath not match ... colorId: -1"
                                // and never built a detail source, which is why the 3D
                                // model path was never reached.
                                if (colorIdOverrideLogged.compareAndSet(false, true)) {
                                    MLog.event("bose.colorid.override",
                                            "was", result,
                                            "now", 3);
                                }
                                return 3;
                            }
                        } catch (Throwable t) {
                            MLog.event("bose.colorid.error", "error", MLog.compactThrowable(t));
                        }
                        return result;
                    }
                    if ("boseHeaderShowAnimationEnd".equals(label)) {
                        Object result = chain.proceed();
                        try {
                            if (boseBonded()) {
                                Object spinner = readField(chain.getThisObject(), "d");
                                if (spinner instanceof View) {
                                    ((View) spinner).setVisibility(View.GONE);
                                }
                                if (spinner != null) {
                                    invokeNoArg(spinner, "cancelAnimation");
                                }
                                MLog.event("bose.header.spinner_killed");
                            }
                        } catch (Throwable t) {
                            MLog.event("bose.header.spinner_error",
                                    "error", MLog.compactThrowable(t));
                        }
                        return result;
                    }
                    if ("sonyCardBind".equals(label)) {
                        Object result = chain.proceed();
                        // 0.5.73: thisObject IS the OneSpaceHeaderPreference whose bind is
                        // PROVEN to fire on the 通用设置 page (the row exists — the user sees
                        // the blank circle). i() ("sonyCardImage") is LiveData-observer
                        // driven and may never run for a device with no catalog entry, so
                        // this bind is the reliable moment to install the photo.
                        if (replaceBoseOneSpaceHeaderImage(chain.getThisObject())) {
                            return result;
                        }
                        return result;
                    }
                    if ("onespaceNoiseBind".equals(label)) {
                        // Runs on every RecyclerView rebind, so the attach is idempotent
                        // (guarded by a marker tag) and the slider is never duplicated.
                        Object result = chain.proceed();
                        attachCncSliderUnderOneSpaceNoise(chain.getThisObject(), chain.getArg(0));
                        // 0.5.67 third belt for the 降噪效果 suppression. This hook is the one
                        // path PROVEN to fire on the 通用设置 page in every recent version
                        // (the CNC slider has always attached through it), while the
                        // b.x/b.w hooks sit on final methods of a final class — 0.5.33 logged
                        // exactly that combination failing to hook once. Re-hiding the menu
                        // category from here survives both failure modes, and it re-runs on
                        // every rebind, i.e. after every LiveData-driven setVisible(true).
                        suppressNoiseMenuCategory(chain.getThisObject());
                        // 0.5.73: removed the applyBoseHeaderFromRowView fallback. Proven
                        // dead: OneSpaceHeaderPreference is a Preference (extends
                        // COUIPreference), never a View, so the View-tree DFS for a class
                        // named *OneSpaceHeaderPreference could not find anything and the
                        // whole body was swallowed by catch(Throwable ignored) — zero log
                        // events, exactly what the 20:16 capture shows. The real entry is
                        // sonyCardBind (OneSpaceHeaderPreference.onBindViewHolder, thisObject
                        // = the header itself), which now calls
                        // replaceBoseOneSpaceHeaderImage directly.
                        return result;
                    }
                    if ("onespaceNoiseMenuCheck".equals(label)
                            || "onespaceNoiseSwitchShow".equals(label)) {
                        // 0.5.67. OneSpaceListFragment.x(List,Z) = checkShowNoiseMenuItem and
                        // w(ZZ) = checkShowNoiseCardItem, both driven by the
                        // WhitelistConfigDTO LiveData. x() sets field v
                        // (pref_noise_menu_category) visible whenever the DTO carries a
                        // non-empty noiseReductionMode list — which our own injected catalog
                        // clone does. Let the host finish, then hide the category again by
                        // KEY so this survives every re-emit and every COUI notifyChanged.
                        // The three-state widget (pref_noise_switch, field u) is deliberately
                        // left alone: w(true)/initObserver show it from the same list, and
                        // hiding it caused the 0.5.65 regression.
                        Object result = chain.proceed();
                        try {
                            if (!boseBonded()) return result;
                            Object frag = chain.getThisObject();
                            int hidden = 0;
                            for (String field : new String[]{"v", "w"}) {
                                Object pref = readField(frag, field);
                                if (pref == null) continue;
                                String key = PrefRef.getKey(pref);
                                if (!"pref_noise_menu_category".equals(key)
                                        && !"pref_noise_menu".equals(key)) continue;
                                PrefRef.setVisible(pref, false);
                                hidden++;
                            }
                            if (hidden > 0 && ancMenuSuppressed.compareAndSet(false, true)) {
                                MLog.event("bose.anco.menu_suppressed",
                                        "label", label,
                                        "hidden", hidden,
                                        "visible_after",
                                        PrefRef.isVisible(readField(frag, "v")));
                            }
                        } catch (Throwable t) {
                            MLog.event("bose.anco.menu_suppress_error",
                                    "label", label,
                                    "error", MLog.compactThrowable(t));
                        }
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
                        // 0.5.52 regression fix: this dumps the whole DTO and the host calls
                        // it in a tight loop, so it was one of the contributors to the panel
                        // stall. Reported per distinct (mac, outcome) pair instead.
                        String wlKey = mac + "|" + (result == null ? "NULL" : "DTO") + "|" + isBoseTarget;
                        if (wl17Seen.add("old:" + wlKey)) {
                            MLog.event("bose.detail.whitelist",
                                    "mac", String.valueOf(mac),
                                    "config", result == null ? "NULL" : result.getClass().getSimpleName(),
                                    // 0.5.41: the lookup returns our entry, yet the page still
                                    // builds no sections. Dump the entry so we can see exactly
                                    // which fields the host would read.
                                    "dump", result == null ? "-" : describeDto(result),
                                    "bose", isBoseTarget);
                        }
                        if (isBoseTarget) pendingDetailMac = (String) mac;
                        if (result == null && isBoseTarget) {
                            // The catalog entry is added in whitelistConfigList. If the match
                            // still fails, the entry did not satisfy the matcher — report it
                            // rather than fabricating a DTO here, so the next round tells us
                            // what the matcher actually compared.
                            MLog.event("bose.detail.whitelist.still_null",
                                    "mac", String.valueOf(mac));
                        }
                        if (result != null && isBoseTarget) {
                            // 0.5.44: the lookup finally returns a real DTO, but it is the
                            // host's own OPPO Enco X3 entry and its children field is null.
                            // Whatever the host builds sections from, a null children yields
                            // none. Fill it from the richest catalog entry so there is
                            // something for the page to render.
                            boolean filled = fillMissingChildren(result, chain.getThisObject());
                            if (filled) {
                                MLog.event("bose.detail.children_filled",
                                        "into", String.valueOf(readField(result, "name")));
                            }
                        }
                        // 0.5.74 — THE ACTUAL ROOT CAUSE OF #4 (Enco 交互残留), and a
                        // trap I had to correct before shipping:
                        //
                        //   evt=bose.detail.whitelist mac=40:72:18:C7:75:70
                        //       config=WhitelistConfigDTO dump=...id=067410;name=OPPO Enco X3
                        //
                        // The Bose MAC (68:F2:1F:3D:41:D7) NEVER appears in any lookup in the
                        // whole 455-event capture, while the user's real Enco X3 does. So a
                        // guard of "boseBonded() && !isExcluded(mac)" would have fired on
                        // 40:72:18 as well (it is bonded-but-not-excluded only by luck) and
                        // stripped a GENUINE OPPO earphone's own four-level ANC picker.
                        // The only safe predicate is a POSITIVE one: strip only when the
                        // looked-up address is the Bose unit itself.
                        //
                        // Ba/m.smali:519-550 proves the mechanism: an empty childrenMode skips
                        // the Ba/x (four-level picker) and Ba/A (增强人声) construction entirely.
                        if (result != null && isBoseAddressArg(mac)) {
                            stripEncoSubLevels(result);
                            MLog.event("bose.detail.lookup_stripped",
                                    "mac", String.valueOf(mac),
                                    "into", String.valueOf(readField(result, "name")));
                        } else if (result != null && boseBonded()) {
                            // 0.5.74 diagnostic: the Bose MAC never appears in any lookup in
                            // the 455-event capture, so we cannot yet prove which DTO the
                            // Bose detail page renders from. Log the non-Bose lookups that
                            // happen while Bose is bonded, so the next round names the real
                            // source instead of guessing again.
                            if (wl17Seen.add("nb:" + mac)) {
                                MLog.event("bose.detail.lookup_nonbose",
                                        "mac", String.valueOf(mac),
                                        "name", String.valueOf(readField(result, "name")),
                                        "fn", String.valueOf(readField(result, "function") != null));
                            }
                        }
                        return result;
                    }
                    if ("noiseReductionVOList".equals(label)) {
                        Object result = chain.proceed();
                        return stripChildrenForBosePage(result);
                    }
                    if ("detailSetVisibility".equals(label)) {
                        // 0.5.71 REWRITE. The 2026-10-04 18:55 ANR main-thread dump proves the
                        // 0.5.69 body recursed without end: setVisibility(VISIBLE) inside the
                        // hook re-entered the hook, which set VISIBLE again, forever, freezing
                        // the main thread (DetailMainActivity). The 0.5.70 "no-op" guard was
                        // placed AFTER chain.proceed(), where getVisibility() always equals the
                        // requested value, so it silently disabled ALL arbitration (blank page
                        // would return). The robust fix is an explicit re-entry flag: every
                        // setVisibility WE issue from inside this hook is wrapped, so the
                        // re-entrant frame returns immediately. Recursion depth is bounded to 2
                        // regardless of how the host and our arbitration alternate.
                        View target = chain.getThisObject() instanceof View
                                ? (View) chain.getThisObject() : null;
                        Object arg = arity > 0 ? chain.getArg(0) : null;
                        int visibility = arg instanceof Integer ? (Integer) arg : -1;
                        boolean reentrant = Boolean.TRUE.equals(detailArbitrating.get());
                        Object result = chain.proceed();
                        if (target == null || reentrant || visibility < 0) return result;
                        try {
                            if (!isInsideDetailContainer(target)) return result;
                            if (visibility != View.VISIBLE) {
                                recordHideCaller(target, visibility);
                            }
                            if (!boseBonded()) return result;

                            // 2.0.5: the model only renders while the detail photo (field
                            // d) is VISIBLE, so keep the transparent photo VISIBLE (the
                            // setAlpha clamp makes it invisible). If the host tries to
                            // GONE/INVISIBLE it, force it back to VISIBLE. Re-entry guard
                            // (detailArbitrating) prevents recursion.
                            if (target == boseTransparentPhoto && visibility != View.VISIBLE) {
                                detailArbitrating.set(Boolean.TRUE);
                                try {
                                    target.setVisibility(View.VISIBLE);
                                    target.setAlpha(0f);
                                } finally {
                                    detailArbitrating.set(Boolean.FALSE);
                                }
                                return result;
                            }

                            // 0.5.72 ROLLBACK of the 0.5.69 page-level arbitration.
                            // Evidence trail: 0.5.71 stopped the ANR (dropbox shows no new
                            // melody crash after 18:55), but the user now reports a NEW
                            // regression #6 — a grey mask over 通用设置 that crashes on tap —
                            // plus the detail page still vanishing. The isFullSizeView /
                            // contentPathNow heuristic forcibly flipped ANY near-full-screen
                            // view to VISIBLE/GONE based on a guessed content test. Forcing an
                            // unknown full-size host view (mask / empty-state / scrim) VISIBLE
                            // or GONE is exactly the kind of "fix pushed further than the
                            // evidence" lesson #12 forbids, and it produces leftover masks and
                            // click crashes. There was never device proof of what the host
                            // hides a few seconds in. So: keep ONLY the single proven
                            // protection (the detail scroll container, whose GONE blanks the
                            // page — established since 0.5.25), keep the re-entry guard, and
                            // rely on bose.detail.hidden_by to finally name the real caller
                            // before any further change.
                            if ("melody_ui_detail_scrollview".equals(idName(target))
                                    && visibility != View.VISIBLE) {
                                detailArbitrating.set(Boolean.TRUE);
                                try {
                                    target.setVisibility(View.VISIBLE);
                                } finally {
                                    detailArbitrating.set(Boolean.FALSE);
                                }
                                if (scrollReviveLogged.compareAndSet(false, true)) {
                                    MLog.event("bose.detail.scrollview_revived",
                                            "blocked_visibility", visibility);
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    }
                    if ("detailActivityFinishAfterTransition".equals(label)) {
                        // 0.5.76b. The close-trigger fires TWICE per close: once at the
                        // DECISION point (host code calls finishAfterTransition), and once
                        // more from Activity$RequestFinishCallback.run (the transition
                        // pre-draw callback, which calls finishAfterTransition again; that
                        // second call is what actually reaches finish()). The raw stack of
                        // the SECOND call is dominated by Handler/Looper, which is why the
                        // real host caller (the first call) was never named. Distinguish
                        // them by the presence of RequestFinishCallback in the raw chain,
                        // and log the decision-point call with a deeper raw stack.
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            String raw = rawCallerChain(20);
                            boolean isCallback = raw.contains("RequestFinishCallback");
                            MLog.event("bose.detail.finish_after_transition",
                                    "seq", ++finishSeq,
                                    "self", self.getClass().getSimpleName(),
                                    "kind", isCallback ? "transition_callback" : "decision",
                                    "host", hostCallerChain(8),
                                    "raw", raw);
                        }
                        return chain.proceed();
                    }
                    if ("detailActivityFinish".equals(label)) {
                        // 0.5.74 ROLLBACK of the 0.5.73 interception — FORENSIC ONLY.
                        // The 0.5.73 log named the caller: DetailMainActivity.onCreate:60,
                        // which is the host's "finish previous instance" singleton guard
                        // (smali: WeakReference to the previous DetailMainActivity, finish()
                        // when a new one is created). Swallowing it broke the host's own
                        // self-consistency: the old instance stayed alive while a new one was
                        // created, producing the exact multi-instance fight the user saw as
                        // "flash -> blank -> reappear -> vanish" (7 consecutive no_app_frame
                        // finishes followed, once our 6-block cap was exhausted).
                        // Never block finish() again. Keep the caller chain in the log so the
                        // real teardown trigger stays observable.
                        Object self = chain.getThisObject();
                        if (self instanceof Activity && self == detailActivity) {
                            // 0.5.76. 0.5.75 装机日志 detail.finish 只有两条 caller=no_app_frame，
                            // 但 finishCallSites 的去重把"几秒后消失"的频率信息吞掉了——如果宿主每秒
                            // finish 一次，去重后也只剩一行。no_app_frame 还说明 finish 的栈里没有
                            // 宿主帧：要么是框架/系统层直接 finish，要么宿主通过 R8 短名 lambda
                            // （isHostFrame 会漏掉）异步调用。这里记三样东西：单调递增的 finish 序号
                            // （看频率）、过滤后的宿主链、以及未过滤的完整原始栈（前 10 帧，定位
                            // no_app_frame 到底是谁）。序号每进程每页都会单调增长，去重只作用于
                            // 完全相同的原始栈。
                            int seq = ++finishSeq;
                            String raw = rawCallerChain(10);
                            if (finishCallSites.add(raw)) {
                                MLog.event("bose.detail.finish",
                                        "seq", seq,
                                        "host", hostCallerChain(6),
                                        "raw", raw);
                            }
                        }
                        return chain.proceed();
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
                    if ("wl17Lookup".equals(label)) {
                        // a(String mac) -> WhitelistConfigDTO. Two paths inside the host:
                        //   registered -> c(productId, name);  otherwise -> T.b(device, g()).
                        Object mac = arity > 0 ? chain.getArg(0) : null;
                        Object result = chain.proceed();
                        // 0.5.51 logged this unconditionally and the host calls it dozens of
                        // times per second (46 in one measured second), which on its own was
                        // enough to stall the panel. Collapsed by signature: one line per
                        // distinct outcome instead of one per call.
                        String outcome = (result == null ? "null"
                                : ("fn=" + (readField(result, "function") == null)
                                        + " " + describeChildren(result)));
                        if (wl17Seen.add(outcome)) {
                            MLog.event("bose.wl17.lookup",
                                    "mac", String.valueOf(mac),
                                    "is_null", result == null,
                                    "function_null",
                                    result == null || readField(result, "function") == null,
                                    "children", result == null ? "-" : describeChildren(result),
                                    "dto", result == null ? "null" : describeDto(result));
                        }
                        return result;
                    }
                    if ("ancRowBind".equals(label)) {
                        // 0.5.66: the RecyclerView adapter callback, i.e. the only place a
                        // bound row view actually exists. arity 2 = (ViewHolder, position).
                        //
                        // 0.5.67 CRITICAL FIX — 0.5.66's body could NEVER work, proven from
                        // smali (androidx/preference/m.smali + h.smali:1704-1809):
                        //   * the ViewHolder m has fields a/b/c/d/e ONLY — there is no field
                        //     "f", so readField(holder,"f") was always null → title="" →
                        //     isEncoAncTitle never matched a single row;
                        //   * itemView is an inherited FIELD (RecyclerView$E.itemView), not a
                        //     method, so invokeNoArg(holder,"itemView") always threw → every
                        //     call exited through bind_noview.
                        // Correct reads, straight from h.onBindViewHolder itself:
                        //   :1712  invoke-virtual {p0, p2}, h->e(I)Preference  ← the row's pref
                        //   :1720  iget-object RecyclerView$E->itemView        ← the row's view
                        Object result = chain.proceed();
                        try {
                            Object holder = arity > 0 ? chain.getArg(0) : null;
                            if (holder == null || !boseBonded()) return result;
                            Object rowView = readField(holder, "itemView");
                            if (!(rowView instanceof View)) {
                                if (!ancBindNoView.compareAndSet(false, true)) {
                                    MLog.event("bose.anc.bind_noview",
                                            "holder", holder.getClass().getSimpleName());
                                }
                                return result;
                            }
                            View view = (View) rowView;
                            // adapter.e(position) is exactly what the host itself calls at
                            // :1712 to fetch the preference for this row.
                            Object pref = null;
                            Object posArg = arity > 1 ? chain.getArg(1) : null;
                            if (posArg instanceof Integer) {
                                pref = PrefRef.invoke1Arg(chain.getThisObject(), "e",
                                        int.class, posArg);
                            }
                            String title = "";
                            String key = "";
                            if (pref != null) {
                                title = String.valueOf(PrefRef.getTitle(pref));
                                key = String.valueOf(PrefRef.getKey(pref));
                            }
                            // 0.5.67: match on KEY first — smali (OneSpaceListFragment /
                            // res/cS.xml) proves the 通用设置 "降噪效果" row is
                            // pref_noise_menu inside pref_noise_menu_category, and its title
                            // resource is 0x7f11038a. The detail page's copy carries the same
                            // title, so the title check stays as the second matcher. The
                            // three-state widget (pref_noise_switch / OneSpaceNoisePreference)
                            // has a different key AND a different title, so it is never hit.
                            if ("pref_noise_menu".equals(key)
                                    || "pref_noise_menu_category".equals(key)
                                    || isEncoAncTitle(title)) {
                                // View-level ONLY. Preference.setVisible here would run
                                // notifyItemRemoved from inside the RecyclerView layout pass
                                // ("Cannot call this method while RecyclerView is computing a
                                // layout") and can crash the host. The preference-level hide
                                // is owned by suppressNoiseMenuCategory and the b.x/b.w
                                // hooks, which run outside the layout pass.
                                view.setVisibility(View.GONE);
                                view.setEnabled(false);
                                if (!ancBindHidden.compareAndSet(false, true)) {
                                    MLog.event("bose.anc.bind_hidden",
                                            "key", key,
                                            "title", title,
                                            "holder", holder.getClass().getSimpleName());
                                }
                            }
                        } catch (Throwable t) {
                            MLog.event("bose.anc.bind_error", "error", MLog.compactThrowable(t));
                        }
                        return result;
                    }
                    if (label.startsWith("detailFrag")) {
                        Object result = chain.proceed();
                        Object frag = chain.getThisObject();
                        // Reported only for the build step (t()) and the later view step.
                        // onCreate runs BEFORE the tree exists, so a null screen there is
                        // expected and reporting it only produced misleading output.
                        boolean report = "detailFragBuild".equals(label)
                                || "detailFragViewCreated".equals(label);
                        MLog.event("bose.frag",
                                "step", label,
                                "class", frag == null ? "null"
                                        : frag.getClass().getName());
                        if (report && frag != null) {
                            try {
                                Object screen = PrefRef.getPreferenceScreen(frag);
                                int n = screen == null ? -1 : PrefRef.getPreferenceCount(screen);
                                MLog.event("bose.frag.screen",
                                        "step", label,
                                        "screen", screen == null ? "null"
                                                : screen.getClass().getSimpleName(),
                                        "children", n,
                                        "view_attached", frag instanceof View
                                                && ((View) frag).isAttachedToWindow());
                                if (screen != null && n > 0) {
                                    MLog.event("bose.frag.keys",
                                            "step", label,
                                            "keys", describeChildKeys(screen, 0));
                                }
                                if (screen != null) {
                                    // 0.5.62: t() reports children=1, so at that moment the
                                    // tree holds only its root; the real sections are added by
                                    // the host afterwards through settingListChanged. Walking
                                    // here found nothing and never ran again. The walk is
                                    // therefore deferred and retried a few times.
                                    scheduleAncTreeSweep(screen);
                                }
                            } catch (Throwable t) {
                                MLog.event("bose.frag.screen_error",
                                        "step", label,
                                        "error", MLog.compactThrowable(t));
                            }
                        }
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
                    if ("noiseReductionItemDataChanged".equals(label)) {
                        Object result = chain.proceed();
                        lastAudioPreferenceAnchor = chain.getThisObject();
                        lastNoiseReductionVo = chain.getArg(0);
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
                    if ("oneSpaceNoiseObserver".equals(label)) {
                        Object result = chain.proceed();
                        if (chain.getArg(0) != null) {
                            oneSpaceNoiseObserver = chain.getThisObject();
                            oneSpaceNoiseVo = chain.getArg(0);
                        }
                        return result;
                    }
                    if ("repositoryDtoBuild".equals(label)) {
                        Object result = chain.proceed();
                        projectBoseBatteryIntoDto(chain.getArg(0), result);
                        projectBoseSpatialIntoDto(chain.getArg(0), result);
                        projectBoseMasterTuningIntoDto(chain.getArg(0), result);
                        return result;
                    }
                    if ("melodyEarphoneLiveDataResponse".equals(label)
                            && isMelodyEarphoneLiveData(chain.getThisObject())) {
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response dispatching"));
                        Object result = chain.proceed();
                        Object value = readLiveDataValue(chain.getThisObject());
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response published value="
                                + describe(value)));
                        return result;
                    }
                    if ("melodyEarphoneLiveDataRequest".equals(label)
                            && isMelodyEarphoneLiveData(chain.getThisObject())) {
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
                        } else if (boseHostConnected
                                && startSonyNoiseWrite(chain.getArg(2))) {
                            log(Log.INFO, TAG, event("routed target noise reduction write to Bose RFCOMM"));
                        } else {
                            log(Log.WARN, TAG, event("blocked target noise reduction write until Bose transport is ready"));
                        }
                        return null;
                    }
                    Object deviceName = arity > 2 ? chain.getArg(2) : null;
                    if ("whitelist".equals(label) && deviceName instanceof String
                            && (isRegisteredBoseName((String) deviceName)
                            || isBoseAddressArg(chain.getArg(0))
                            || isBoseAddressArg(chain.getArg(1)))) {
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
                    && isRegisteredBoseName((String) chain.getArg(2));
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
            if (targetBoseDevice != null && boseHostConnected) {
                boseTransport.setAncMode(domainMode);
            } else {
                log(Log.WARN, TAG, event("noise reduction write skipped: Bose transport not connected"));
                return false;
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose noise reduction mapping failed", t);
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
        if (targetBoseDevice != null && boseHostConnected) {
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
            // Publish at intercept time, not only after the BMAP confirm: the 通用设置 page
            // lives in :fg and refreshes from this file, so waiting for the device reply is
            // what made its highlight lag one full tap behind.
            writeSharedBoseState();
            BoseControlProviderBridge.refreshTile();
            boseTransport.setAncMode(domainMode);
            Object result = createSetCommandState(0);
            if (result != null) future.complete(result);
            else future.completeExceptionally(new IllegalStateException("Bose ANC result DTO unavailable"));
        } else {
            pendingAncMode = null;
            failPendingNoiseWrite("ANC command cannot start: Bose transport is not connected");
        }
        return future;
    }

    /**
     * 2.0.7 bug1 fix: after an optimistic ANC write the mirrored state is correct, but
     * the host's three mode buttons only re-read it on a rebind (which is why leaving
     * and re-entering the page showed the right highlight). Force that rebind in place
     * by calling androidx Preference.notifyChanged() on the captured ANC rows, so the
     * icons/highlight update immediately without leaving the page.
     */
    private void refreshAncRowsAfterWrite() {
        mainHandler.post(() -> {
            // 2.0.9 proved notifyChanged() runs (rows non-null, refresh_done fired) but
            // the icons still do not update -> Preference rebind is NOT the icon path.
            // The icons are set by NoiseReductionItem.onEarphoneDataChanged(Ba/z), which
            // reads Ba.z.getCurrentNoiseReductionModeIndex (hooked to our mirrored
            // state). Replay it directly on the live item with the cached VO.
            notifyPreferenceChanged(noiseEffectRow, "detail");
            notifyPreferenceChanged(oneSpaceNoiseEffectRow, "onespace");
            invokeOnEarphoneDataChanged();
            repaintOneSpaceNoiseRow("anc write intercepted");
        });
    }

    /** Replay the host's icon-update callback on the live NoiseReductionItem. */
    private void invokeOnEarphoneDataChanged() {
        Object item = lastAudioPreferenceAnchor;
        Object vo = lastNoiseReductionVo;
        if (item == null || vo == null) {
            MLog.event("bose.anc.icon_skip",
                    "item", item == null ? "null" : "ok",
                    "vo", vo == null ? "null" : "ok");
            return;
        }
        try {
            Method m = item.getClass().getMethod("onEarphoneDataChanged", vo.getClass());
            m.invoke(item, vo);
            MLog.event("bose.anc.icon_replayed", "via", item.getClass().getSimpleName());
        } catch (Throwable t) {
            // Fallback: match by arity (R8 may rename the param type reference).
            try {
                for (Method m : item.getClass().getMethods()) {
                    if (m.getName().equals("onEarphoneDataChanged") && m.getParameterCount() == 1) {
                        m.invoke(item, vo);
                        MLog.event("bose.anc.icon_replayed", "via", "arity");
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
            MLog.event("bose.anc.icon_fail", "error", MLog.compactThrowable(t));
        }
    }

    /**
     * Mirror the tapped ANC mode into this process' session states right away. The mirrored
     * value is what every hooked mode getter returns, so a repaint triggered now already
     * reports the new mode instead of waiting for the BMAP write to land (~1 s later).
     */
    private void mirrorAncOptimistically(int modeIndex, String reason) {
        try {
            com.melody.melodylink.domain.AncMode tapped = MelodyCommandBridge.INSTANCE.ancMode(modeIndex);
            if (tapped == null) {
                MLog.event("bose.anc.mirror_skip", "mode", modeIndex, "reason", reason);
                return;
            }
            EarbudsState optimistic = new EarbudsState(
                    com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getCapabilities(),
                    tapped, new java.util.HashMap<>());
            boseSessionState.acceptAnc(optimistic);
            sonySessionState.acceptAnc(optimistic);
            MLog.event("bose.anc.mirrored", "mode", modeIndex, "reason", reason);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 2.0.12 bug1 (通用设置 side): that row highlights a button only when its observer paints
     * — smali $initObserver$1.invoke assigns field q, then compares q.getMCurrentNoiseMode()
     * against each ModeItem's modeType. notifyChanged() and a rebind change nothing (0585 log:
     * onNoiseInfoChange fired on page open only, never after a write). Map the mirrored
     * protocol index to a modeType with the host's own helper, store it in the live VO and
     * replay the captured observer.
     */
    private void repaintOneSpaceNoiseRow(String reason) {
        mainHandler.post(() -> {
            Object observer = oneSpaceNoiseObserver;
            Object vo = oneSpaceNoiseVo;
            if (observer == null || vo == null) {
                MLog.event("bose.anc.onespace.skip", "reason", "no_observer");
                return;
            }
            EarbudsState anc = sonySessionState.getAnc();
            int protocolIndex = anc == null ? readSharedSonyModeIndex()
                    : MelodyStateBridge.INSTANCE.ancModeIndex(anc);
            if (protocolIndex < 0) {
                MLog.event("bose.anc.onespace.skip", "reason", "no_mode");
                return;
            }
            try {
                Object modeList = vo.getClass().getMethod("getMNoiseReductionModeList").invoke(vo);
                Method map = vo.getClass().getDeclaredMethod("getCurrentNoiseMode",
                        int.class, java.util.List.class);
                map.setAccessible(true);
                Object mapped = map.invoke(vo, protocolIndex, modeList);
                int modeType = mapped instanceof Integer ? (Integer) mapped : -1;
                if (modeType < 0) {
                    MLog.event("bose.anc.onespace.skip", "reason", "map_miss", "mode", protocolIndex);
                    return;
                }
                vo.getClass().getMethod("setMCurrentNoiseMode", int.class).invoke(vo, modeType);
                observer.getClass().getMethod("onChanged", Object.class).invoke(observer, vo);
                MLog.event("bose.anc.onespace.replayed", "mode", protocolIndex,
                        "mode_type", modeType, "reason", reason);
            } catch (Throwable t) {
                MLog.event("bose.anc.onespace.replay_fail", "error", MLog.compactThrowable(t));
            }
        });
    }

    private static void notifyPreferenceChanged(Object preference, String which) {
        if (preference == null) return;
        Class<?> c = preference.getClass();
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod("notifyChanged");
                m.setAccessible(true);
                m.invoke(preference);
                MLog.event("bose.anc.refresh_done", "row", which, "via", c.getSimpleName());
                return;
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        MLog.event("bose.anc.refresh_fail", "row", which, "reason", "notifyChanged not found");
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
        // 2.0.11 bug1 fix: the icon replay reads the mirrored state, but sonySessionState and
        // the shared file only update once the BMAP write lands ~1 s later, so the hooked
        // getter still returned the PREVIOUS mode. Mirror the tapped mode first.
        mirrorAncOptimistically(modeIndex, "detail tap");
        if (isPrimaryProcess()) {
            startSonyNoiseWriteFuture(modeIndex, loader);
        } else {
            forwardSonyNoiseWrite(modeIndex, loader);
        }
        // 2.0.9: refresh the ANC buttons in the :fg process that owns the row anchors
        // (2.0.8 proved the primary-process refresh had rows=null). 2.0.10: notifyChanged
        // isn't the icon path, so refreshAncRowsAfterWrite also replays onEarphoneDataChanged.
        refreshAncRowsAfterWrite();
    }

    /**
     * Bose-only build: opens the module APK as an asset path (the host process
     * reads bose/images + bose/model from it) and creates the cross-process
     * shared-state store. The historical name is kept because several call
     * sites predate the vendor strip.
     */
    private synchronized boolean initializeSonyConfig() {
        if (sonyConfigInitialized) return true;
        try {
            Application application = currentApplication();
            if (application == null) {
                log(Log.WARN, TAG, event("module configuration unavailable: target application not ready"));
                return false;
            }
            sharedStateStore = MelodySharedStateStore.from(application);
            ApplicationInfo moduleInfo = getModuleApplicationInfo();
            String moduleApkPath = moduleInfo.sourceDir;
            if (moduleApkPath == null || moduleApkPath.isEmpty()) {
                log(Log.ERROR, TAG, event("module configuration unavailable: module APK path is empty"));
                return false;
            }
            AssetManager moduleAssets = application.getAssets();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            Object cookie = addAssetPath.invoke(moduleAssets, moduleApkPath);
            if (!(cookie instanceof Integer) || ((Integer) cookie) == 0) {
                log(Log.ERROR, TAG, event("module configuration unavailable: cannot open module APK assets"));
                return false;
            }
            sonyModuleAssets = moduleAssets;
            sonyConfigInitialized = true;
            log(Log.INFO, TAG, event("module asset path ready: " + moduleApkPath));
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "module configuration initialization failed", t);
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
                        "anchor_class", describeParent());
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
        if (screen != null) {
            return PrefRef.findPreferenceRecursive(screen, BOSE_CNC_KEY) != null
                    || PrefRef.findPreferenceRecursive(screen, BOSE_CNC_CARD_KEY) != null;
        }
        // 0.5.49: the screen set is weak and mixes both pages, so after the user leaves and
        // re-enters a page the screen could be gone while the page's actual group is alive
        // — the slider then vanished on re-entry. Ask the same parent the installer writes
        // into, which is the only tree that matters.
        Object row = pickLiveAnchor();
        if (row == null) return false;
        Object parent = PrefRef.getParent(row);
        if (parent == null) return false;
        return PrefRef.findPreferenceRecursive(parent, BOSE_CNC_KEY) != null
                || PrefRef.findPreferenceRecursive(parent, BOSE_CNC_CARD_KEY) != null;
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
    /**
     * Chooses which page's anchor to inject against.
     *
     * <p>0.5.55. The old test was {@code isAttachedToWindow()}, and 0.5.54 logs prove that is
     * always false for the detail page: {@code anchor_attached=false parent_attached=false}
     * even though {@code children=6} shows the tree is fully built. A preference's view is
     * only created when the adapter binds it, and the detail page binds late, so the test
     * could never succeed and the generic-settings anchor always won. Worse, that anchor's
     * tree had already been torn down by {@code G9/Q.onDestroy} (which walks
     * {@code PreferenceGroup.c} calling {@code h(int)} on every child), so the row was
     * written into a dead tree and vanished on the next visit — exactly the reported symptom.
     *
     * <p>Selection is therefore by which page is in front, using the activity that is
     * actually resumed, with attach as a tie-breaker only. Both candidates are still
     * accepted when neither can be classified; injection is idempotent per tree, so trying
     * both is safe.
     */
    private Object pickLiveAnchor() {
        boolean detailFront = detailActivity != null
                && !detailActivity.isFinishing()
                && detailActivity.hasWindowFocus();
        Object preferred = detailFront ? noiseEffectRow : oneSpaceNoiseEffectRow;
        Object other = detailFront ? oneSpaceNoiseEffectRow : noiseEffectRow;
        if (preferred != null) return preferred;
        if (other != null) return other;
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
        // 0.5.49: on the detail page the anchor's parent is a bare COUIPreferenceCategory
        // card that is not attached to any window, so an insert there lands in an off-screen
        // subtree and the page stays blank even though landed=true. Walk up to the first
        // ancestor that IS on screen and inject there. This mirrors the original
        // melodylink-master approach, which always resolved a screen-level anchor
        // (findPreferenceByTitle on the PreferenceScreen) instead of trusting the row's
        // immediate parent.
        int before = PrefRef.getPreferenceCount(parent);
        Object screenLevel = attachedAncestor(parent);
        boolean promoted = screenLevel != null && screenLevel != parent;
        if (promoted) {
            MLog.event("bose.inject.promoted",
                    "from", parent.getClass().getSimpleName(),
                    "from_children", before,
                    "to", screenLevel.getClass().getSimpleName(),
                    "to_children", PrefRef.getPreferenceCount(screenLevel));
            parent = screenLevel;
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

        // 0.5.53 safety gate. Everything below MUTATES the host's preference tree (order
        // shifting plus real inserts). 0.5.52 proved the panel can be taken down entirely by
        // a bad container, so nothing is written until the container is proven sane: it must
        // expose a real child list, and it must have at least one child to make room for.
        int existing = PrefRef.getPreferenceCount(parent);
        if (existing <= 0) {
            MLog.event("bose.inject.abort",
                    "reason", "empty_parent",
                    "parent", parent.getClass().getSimpleName());
            return false;
        }
        int anchorOrder = PrefRef.getOrder(noiseRow);
        int target = anchorOrder < 0 ? 0 : anchorOrder + 1;
        // 0.5.61: the detail page orders its sections from a STATIC table on G9/Q
        // (field y == [disconnect, guide, product, battery, noise, devices, sound, ai,
        // control, game]). settingListChanged in A9/f does y.indexOf(key) and feeds the
        // result straight into setOrder(), so a key the table does not contain gets -1.
        // Registering our keys there makes the host place them like any native section
        // instead of leaving them outside the panel.
        if (detailPage) {
            // The table has to contain BOTH our own keys and the key of the anchor we are
            // inserting next to. 0.5.61 device logs show the detail page uses plain class
            // names as section keys ("NoiseReductionItem", "pref_device_info") while the
            // static table lists short names ("noise", "product", ...), so indexOf() on a
            // real section already returns -1 and the host orders everything to 0.
            // Registering the anchor's key too keeps relative ordering intact once our rows
            // are placed after it.
            registerSectionKeys(BOSE_CNC_KEY, BOSE_CNC_CARD_KEY, BOSE_EXTRA_CATEGORY_KEY,
                    PrefRef.getKey(noiseRow));
        }
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

        // 0.5.48: the install used to return true on the strength of the helpers' own return
        // value alone, and the run logged ok=true while bose.injected never appeared and the
        // group stayed empty. Read the tree back and only report success if our key is
        // physically present. A silent false positive here is what froze the detail page.
        boolean landed = PrefRef.findPreferenceRecursive(parent, BOSE_CNC_KEY) != null;
        // 0.5.61: 0.5.60 finally produced a real screen report and it exposes the ordering
        // rule. 17.6.3 DetailMainActivity's settingListChanged (A9/f) does:
        //     PreferenceGroup.f(child);
        //     child.setKey(key);
        //     G9/Q.y.indexOf(key) -> child.setOrder(result)
        // where G9/Q.y is a STATIC section table:
        //     [disconnect, guide, product, battery, noise, devices, sound, ai, control, game]
        // A key that is not in that table gets indexOf == -1, i.e. order = -1. Our rows use
        // "melodylink.*" keys, so they always land outside the host ordering and COUI can drop
        // them. This report shows the orders that actually stuck.
        MLog.event("bose.inject.verified",
                "landed", landed,
                "parent", parent.getClass().getSimpleName(),
                "children", PrefRef.getPreferenceCount(parent),
                "detail_page", detailPage,
                "target_order", target,
                "orders", describeChildOrders(parent),
                // 0.5.49: the detail page reported landed=true / page=detail every run and
                // was still blank, while 通用设置 worked. The difference is that the detail
                // page's parent is a bare COUIPreferenceCategory card: the insert succeeded
                // into a group that is not on screen. Anchor attachment separates "written
                // but off-screen" from "not written" in a single field.
                "anchor_attached", isPreferenceAttached(noiseRow),
                "parent_attached", isPreferenceAttached(parent),
                "activity_shown", activity != null && !activity.isFinishing());
        if (!landed) return false;
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
            // 0.5.57 CRITICAL FIX. This method is invoked from the detailPreferenceAdd hook for
            // EVERY preference the host adds, not just noise rows. 0.5.56 removed the class
            // filter in order to also hide the detail page copy, and that wiped out the entire
            // general-settings page:
            //   bose.anco.row.hidden key=pref_noise_switch
            //   bose.anco.row.hidden key=pref_noise_menu_category
            //   bose.anco.row.hidden key=pref_more_setting_category
            //   bose.anco.row.hidden key=pref_disconnect
            //   bose.anco.row.hidden key=footer_preference
            //   bose.anco.row.hidden key=pref_device_info        <- the earbud entry itself
            //   bose.anco.row.hidden key=melodylink.bose.cnc      <- our own slider
            // Only 3 rows survived. The class check is load-bearing and must never be removed.
            if (!isBoseNoiseRowClass(className)) return;
            String rowKey = PrefRef.getKey(noiseRow);
            // Belt and braces: never hide anything we injected ourselves.
            if (rowKey != null && rowKey.startsWith("melodylink.")) return;
            // 0.5.66 REGRESSION FIX: 0.5.65 hid the whole preference, and because the Enco
            // picker and the three-state widget (降噪 /关闭 / 通透) live in the SAME
            // preference, the user lost the widget. The preference must stay visible; only the
            // "降噪效果" label row is collapsed, and that happens in ancRowBind by title.
            if (NOISE_EFFECT_TITLE.equals(String.valueOf(PrefRef.getTitle(noiseRow)))) {
                return;
            }
            // 0.5.59: setVisible returning without error is not proof — 0.5.58 device logs show
            // bose.anco.row.hidden fired for pref_noise_switch yet the "降噪效果" title was
            // still drawn. Reading the flag back separates "the setter did nothing" from
            // "the setter worked but the row was re-shown later".
            MLog.event("bose.anco.row.hidden",
                    "key", rowKey,
                    "class", className,
                    "visible_after", PrefRef.isVisible(noiseRow),
                    "title", String.valueOf(PrefRef.getTitle(noiseRow)));
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
    /**
     * Field-by-field dump of a catalog entry.
     *
     * <p>0.5.41. The host returns our entry from its lookup but builds no sections from it, so
     * the entry must be missing whatever the page keys on. Printing the entry makes that
     * visible instead of guessable: collections show their size, so a null list or an empty
     * one stands out immediately.
     */
    private static String describeDto(Object dto) {
        if (dto == null) return "null";
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : allFieldsOf(dto.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(dto);
                sb.append(f.getName()).append('=');
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof java.util.Collection) {
                    sb.append('(').append(((java.util.Collection<?>) v).size()).append(')');
                } else if (v instanceof java.util.Map) {
                    sb.append('{').append(((java.util.Map<?, ?>) v).size()).append('}');
                } else {
                    sb.append(v);
                }
                sb.append(';');
            }
            String out = sb.toString();
            return out.length() > 700 ? out.substring(0, 700) : out;
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * True when this list already holds an entry we injected.
     *
     * <p>0.5.40/42. The one-shot {@code boseCatalogInjected} flag had to go: an exception
     * thrown before the assignment left it false forever (catalog 94 to 214), and once it was
     * set, the fresh list {@code b()} builds on every call returned without the entry at all
     * (lookup NULL). Checking the list itself is idempotent no matter what happened on a
     * previous attempt, and stays correct when the list is brand new each time.
     */
    private static boolean containsBoseEntry(java.util.List<?> list) {
        try {
            for (Object entry : list) {
                Object name = readField(entry, "name");
                if (name instanceof String && ((String) name).startsWith(BOSE_ENTRY_PREFIX)) {
                    return true;
                }
                Object brand = readField(entry, "brand");
                if (brand instanceof String && "Bose".equals(brand)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Counts the control entries on a catalog row.
     *
     * <p>0.5.39. Which sections the detail page shows is driven by the row's Control /
     * ControlList sub-objects. A row whose controls are empty contributes nothing visible.
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

    /**
     * Copies a populated children list into a catalog entry that has none.
     *
     * <p>0.5.44. The lookup returns the host's own OPPO Enco X3 entry with
     * {@code children=null}. If the detail page derives its sections from that field, null
     * means no sections — which matches the symptom exactly: a fully configured device and an
     * empty page.
     *
     * <p>The source is the richest entry we have seen in the catalog, remembered when
     * {@code b()} was last walked. Operates on the object the host is about to use, so the
     * sections come from real catalog data rather than anything we invented.
     *
     * @return true when children were filled in
     */
    private boolean fillMissingChildren(Object dto, Object manager) {
        java.lang.reflect.Field target = findField(dto.getClass(), "children");
        if (target == null) return false;
        try {
            target.setAccessible(true);
            Object existing = target.get(dto);
            if (existing instanceof java.util.Collection
                    && !((java.util.Collection<?>) existing).isEmpty()) {
                return false; // already populated; leave it alone
            }
        } catch (Throwable t) {
            return false;
        }

        // 0.5.46: b() is not a reliable source. It is only reached when
        // DeviceInfoManager.h(mac) returns null, so on some runs it is never called at all
        // (0.5.45 produced zero catalog events while the lookup ran five times). Call it
        // ourselves when we do not already have the catalog, so this path does not depend on
        // the host happening to take that branch.
        if (richestCatalogEntry == null && manager != null) {
            Object catalog = PrefRef.invokeNoArg(manager, "b");
            if (catalog instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) catalog;
                int best = 0;
                for (int i = 0; i < list.size(); i++) {
                    int c = countControls(list.get(i));
                    if (c > best) {
                        best = c;
                        richestCatalogEntry = list.get(i);
                    }
                }
                MLog.event("bose.catalog.fetched",
                        "size", list.size(),
                        "richest", best,
                        "richest_name", richestCatalogEntry == null ? "n/a"
                                : String.valueOf(readField(richestCatalogEntry, "name")));
            }
        }
        if (richestCatalogEntry == null) return false;
        try {
            java.lang.reflect.Field source = findField(
                    richestCatalogEntry.getClass(), "children");
            if (source == null) return false;
            source.setAccessible(true);
            Object donor = source.get(richestCatalogEntry);
            if (!(donor instanceof java.util.Collection)
                    || ((java.util.Collection<?>) donor).isEmpty()) {
                MLog.event("bose.detail.donor_empty",
                        "donor", String.valueOf(readField(richestCatalogEntry, "name")));
                return false;
            }
            target.set(dto, donor);
            return true;
        } catch (Throwable t) {
            MLog.event("bose.detail.children_error", "error", MLog.compactThrowable(t));
            return false;
        }
    }

    private Object injectBoseCatalogEntry(Object listResult, ClassLoader loader) {
        if (!(listResult instanceof java.util.List)) return listResult;
        java.util.List<?> list = (java.util.List<?>) listResult;
        try {
            // 0.5.42: the boolean guard that used to sit here was the reason the entry never
            // reached the page. L6/a.b() builds and returns a FRESH list on every call, so a
            // one-shot flag true after the first call made every later call return a list that
            // had no Bose entry in it at all — which is why the lookup reported
            // config=NULL for our MAC even though injection had "succeeded" once.
            //
            // Correctness now rests entirely on containsBoseEntry(): this exact list either
            // has the entry or it does not, so the check is idempotent per list instead of
            // once per process.
            if (containsBoseEntry(list)) return listResult;
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
            if (richestIndex >= 0) richestCatalogEntry = list.get(richestIndex);
            MLog.event("bose.catalog.probe",
                    "size", list.size(),
                    "with_controls", withControls,
                    "richest", richest,
                    "richest_name", richestIndex >= 0
                            ? String.valueOf(readField(list.get(richestIndex), "name")) : "n/a",
                    // 0.5.44: the entry the host actually returns has children=null. If the
                    // section list comes from children, that is the whole reason the page is
                    // empty. Dump the richest entry so we can see what a populated one holds.
                    "richest_dump", richestIndex >= 0
                            ? describeDto(list.get(richestIndex)) : "n/a",
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
            // 0.5.40: the previous version added to the list while iterating it, which
            // throws ConcurrentModificationException inside the try. The catch swallowed it,
            // so the completed marker was never set and the catalog grew on every call
            // (94 -> 214 in one session). Two fixes:
            //
            // 1. Collect the templates first, add afterwards. Never mutate while iterating.
            // 2. Gate on content, not on a flag. A flag can be skipped by an exception; a
            //    check for "does this list already contain our entry" cannot.
            if (containsBoseEntry(list)) return listResult;

            java.util.List<Object> templates = new java.util.ArrayList<>();
            for (int i = 0; i < list.size() && templates.size() < 3; i++) {
                Object template = list.get(i);
                if (template == null) continue;
                if (countControls(template) == 0) continue;
                templates.add(template);
            }
            if (templates.isEmpty() && !list.isEmpty()) {
                // Nothing in the catalog carries controls we can see; fall back to the
                // previous behaviour so the entry is at least present.
                templates.add(list.get(list.size() - 1));
            }
            int added = 0;
            for (Object template : templates) {
                Object clone = cloneCatalogEntry(template, loader);
                if (clone == null) continue;
                mutable.add(clone);
                added++;
            }
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
                    // 0.5.73: do NOT skip Parcelable fields. WhitelistConfigDTO$Function
                    // implements Parcelable, so the old `continue` dropped the entire
                    // function config from the clone. That is exactly why
                    // stripEncoSubLevels always saw function==null (the log printed
                    // bose.catalog.strip_skip why=no_function x12) and the Enco
                    // sub-levels (降噪强度 3档+智能 / 增强人声) survived into the Bose
                    // entry. Copy by reference instead; stripEncoSubLevels then
                    // shallow-copies the function->modes->children chain and clears it
                    // on the copies only, so the host's real Enco entries are never
                    // mutated. static fields (CREATOR etc.) are already excluded above.
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
            stripEncoSubLevels(copy);
            return copy;
        } catch (Throwable t) {
            MLog.event("bose.catalog.clone_error", "error", MLog.compactThrowable(t));
            return null;
        }
    }

    /**
     * 0.5.75 — the actual fix for #4 (Enco 四级降噪选择器 + 增强人声 leaking into the Bose
     * detail page).
     *
     * <p>Intercepts {@code Ba/z.getNoiseReductionModeList()} (NoiseReductionVO). The smali
     * chain that makes this the right place:
     * <pre>
     *   Ba/z.&lt;init&gt;(EarphoneDTO)                                   [Ba/z:41]
     *     cfg = c9/a.f().c(dto.getProductId(), dto.getName())        [Ba/z:207-214]
     *     mNoiseReductionModeList = cfg.getFunction()
     *                                  .getNoiseReductionMode()      [Ba/z:284-292]  (NO COPY)
     *   NoiseReductionItem: v2 = baZ.getNoiseReductionModeList()     [NRI:549, 4052]
     *   Ba/m.a(list, idx, baZ)                                       [Ba/m:218]
     *     if (mode.getChildrenMode() non-empty) -> new Ba/x (四级)    [Ba/m:475, 519-550]
     *                                           -> new Ba/A (增强人声) [Ba/m:646]
     * </pre>
     *
     * <p>Every earlier attempt failed for a concrete, now-proven reason:
     * <ul>
     *   <li>The lookup feeding this page is keyed by <b>productId + name</b>, not by MAC,
     *       and it lives in {@code c9/a} (WhitelistRepository) — a different catalog from
     *       {@code L6/a} (SupportConfigManager) where our clone is injected. That is why
     *       the Bose MAC never appeared in any of the 455 captured lookup events.</li>
     *   <li>{@code Ba/z} stores the catalog's own List, and {@code Ba/z:521} even calls
     *       {@code setChildrenMode()} back onto those shared mode objects. Clearing in
     *       place would therefore corrupt the user's GENUINE Enco X3 page — he owns both
     *       (40:72:18:C7:75:70 is a real OPPO Enco X3).</li>
     *   <li>{@code stripEncoSubLevels} on a shallow-copied catalog entry never reached the
     *       instances the UI reads.</li>
     * </ul>
     *
     * <p>This returns a list of per-mode shallow copies whose {@code childrenMode} is
     * cleared through the KEEP-NAMED public setter, so the catalog is never mutated — only
     * this VO's view of it. {@code Ba/m} then sees empty children and skips both extra
     * widgets entirely. Cached by list identity because the VO getter is polled during
     * layout, and allocation there is exactly the kind of hot-path cost that caused the
     * 0.5.69 ANR.
     */
    private Object stripChildrenForBosePage(Object list) {
        try {
            if (!(list instanceof java.util.List)) return list;
            if (!boseBonded()) return list;
            java.util.List<?> source = (java.util.List<?>) list;
            if (source.isEmpty()) return list;
            // Cheap test first, expensive guard second: this getter is polled during layout
            // (5 call sites, incl. onespace/b and OneSpaceListFragment$initObserver$2), so
            // reading the intent on every call would be a hot-path probe.
            boolean anyChildren = false;
            for (Object mode : source) {
                if (mode != null && readChildrenMode(mode) != null) {
                    anyChildren = true;
                    break;
                }
            }
            if (!anyChildren) {
                if (wl17Seen.add("vo:no_children")) {
                    MLog.event("bose.vo.no_children", "modes", source.size());
                }
                return list;
            }
            if (!detailPageIsBose()) {
                // 0.5.75 SAFETY. The user owns a GENUINE OPPO Enco X3 (40:72:18:C7:75:70)
                // whose four-level picker must survive. This getter is shared by the 通用设置
                // page too, and detailActivity can outlive the page it belonged to, so the
                // guard is the FOCUSED Bose detail page only. Logging the skip names which
                // page still shows children, instead of guessing and mutating a real Enco.
                if (wl17Seen.add("vo:skip_guard")) {
                    MLog.event("bose.vo.skip_guard", "modes", source.size(),
                            "detail", detailActivity == null ? "null"
                                    : (detailActivity.hasWindowFocus() ? "focused" : "background"));
                }
                return list;
            }

            synchronized (boseStrippedModeLists) {
                Object cached = boseStrippedModeLists.get(source);
                if (cached != null) return cached;
                java.util.List<Object> stripped = new java.util.ArrayList<>(source.size());
                int cleared = 0;
                for (Object mode : source) {
                    if (mode == null) continue;
                    Object children = readChildrenMode(mode);
                    Object copy = shallowCopyObject(mode);
                    if (copy == null) {
                        stripped.add(mode);
                        continue;
                    }
                    if (children != null && writeChildrenMode(copy, null)) {
                        cleared++;
                    }
                    stripped.add(copy);
                }
                if (boseStrippedModeLists.size() > 8) boseStrippedModeLists.clear();
                boseStrippedModeLists.put(source, stripped);
                MLog.event("bose.vo.stripped",
                        "modes", stripped.size(),
                        "cleared", cleared);
                return stripped;
            }
        } catch (Throwable t) {
            MLog.event("bose.vo.strip_error", "error", MLog.compactThrowable(t));
            return list;
        }
    }

    /** Original mode list -> stripped copy. Identity-keyed: the catalog list is shared. */
    private static final java.util.IdentityHashMap<Object, Object> boseStrippedModeLists =
            new java.util.IdentityHashMap<>();

    /**
     * Reads {@code NoiseReductionMode.getChildrenMode()}. Keep-named in 17.6.3
     * (s1dto WhitelistConfigDTO$NoiseReductionMode.smali:1611); resolved by signature so a
     * rename in a future host build degrades to "no children" instead of a crash.
     */
    private static Object readChildrenMode(Object mode) {
        for (Method candidate : allMethods(mode.getClass())) {
            if ("getChildrenMode".equals(candidate.getName())
                    && candidate.getParameterCount() == 0) {
                try {
                    candidate.setAccessible(true);
                    return candidate.invoke(mode);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Writes {@code setChildrenMode(List)} (keep-named, :1781). True when it landed. */
    private static boolean writeChildrenMode(Object mode, Object value) {
        for (Method candidate : allMethods(mode.getClass())) {
            if ("setChildrenMode".equals(candidate.getName())
                    && candidate.getParameterCount() == 1) {
                try {
                    candidate.setAccessible(true);
                    candidate.invoke(mode, value);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * True only when the live {@code DetailMainActivity} was opened FOR the Bose unit.
     *
     * <p>The page's MAC comes from its launch intent — {@code A9/r.smali:524} reads the
     * {@code "device"} extra (falling back to {@code "device_mac_info"}). A positive
     * predicate is mandatory here: {@code boseBonded()} alone is not enough, because the
     * user also owns a real OPPO Enco X3 whose page must keep its four-level picker.
     */
    private boolean detailPageIsBose() {
        try {
            Activity activity = detailActivity;
            if (activity == null || activity.isFinishing()) return false;
            // 0.5.75: focus is cheap and changes over time, so it is tested OUTSIDE the
            // cache — caching a "not focused" verdict would pin it forever and the page
            // would never be stripped once it did come to the foreground.
            if (!activity.hasWindowFocus()) return false;
            // 0.5.75 PERFORMANCE GUARD. getNoiseReductionModeList() is polled during the
            // detail page's layout, and reading the intent + iterating its extras on every
            // call is exactly the kind of expensive probe on a hot path that caused the
            // 0.5.69 ANR. Cache the intent verdict per Activity instance; a new page (or a
            // destroyed one) invalidates it automatically. The intent never changes for the
            // lifetime of an Activity, so caching it is sound.
            if (activity == detailPageBoseActivity) return detailPageBoseVerdict;
            boolean verdict = readDetailPageIsBose(activity);
            detailPageBoseActivity = activity;
            detailPageBoseVerdict = verdict;
            return verdict;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cached verdict + the Activity instance it belongs to. */
    private volatile Activity detailPageBoseActivity;
    private volatile boolean detailPageBoseVerdict;

    /** Reads the launch-intent MAC once per Activity. Caller has already checked focus. */
    private boolean readDetailPageIsBose(Activity activity) {
        try {
            android.content.Intent intent = activity.getIntent();
            if (intent == null) return false;
            android.os.Bundle extras = intent.getExtras();
            // One-shot forensic dump of every string extra: the page's MAC key was only
            // proven for A9/r ("device"), and guessing it again is what burned 0.5.44-0.5.74.
            if (extras != null && detailPageMacLogged.add("dump")) {
                StringBuilder sb = new StringBuilder();
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        sb.append(key).append('=').append(value).append(';');
                    }
                }
                MLog.event("bose.detail.intent_extras",
                        "all", sb.length() == 0 ? "none" : sb.toString());
            }
            for (String key : new String[]{"device", "device_mac_info", "second_navigation"}) {
                String value = intent.getStringExtra(key);
                if (value == null || value.isEmpty()) continue;
                if (detailPageMacLogged.add(key + "=" + value)) {
                    MLog.event("bose.detail.intent_mac", "key", key, "mac", value,
                            "bose", BoseDeviceConfig.INSTANCE.matchesAddress(value));
                }
                // The first readable key decides it: a non-Bose page must NOT be stripped,
                // because the user also owns a genuine OPPO Enco X3.
                return isTargetAddress(value)
                        || BoseDeviceConfig.INSTANCE.matchesAddress(value);
            }
            // Fallback: no known key present. Scan every string extra for a MAC-shaped
            // value so the guard still works if the host renamed the key in this build.
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object raw = extras.get(key);
                    if (!(raw instanceof String)) continue;
                    String value = (String) raw;
                    if (!MAC_PATTERN.matcher(value).find()) continue;
                    boolean bose = isTargetAddress(value)
                            || BoseDeviceConfig.INSTANCE.matchesAddress(value);
                    if (detailPageMacLogged.add("scan:" + value)) {
                        MLog.event("bose.detail.intent_mac", "key", "scan:" + key,
                                "mac", value, "bose", bose);
                    }
                    return bose;
                }
            }
            if (detailPageMacLogged.add("none")) {
                MLog.event("bose.detail.intent_mac", "key", "none");
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code AA:BB:CC:DD:EE:FF}, case-insensitive, anywhere inside the value. */
    private static final java.util.regex.Pattern MAC_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b");

    private static final java.util.Set<String> detailPageMacLogged =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * 0.5.69 — removes the Enco sub-level lists our clone inherited.
     *
     * <p>0.5.72 REWRITE: the field-name version never fired. #4 persisted on device because
     * R8 renamed the inner fields of NoiseReductionMode (a/b/c...), so the "name contains
     * child" match found nothing — and no diagnostic ever printed, proving it. The smali
     * call sites (Ba/m:355, Ba/A:475, onespace/b:3285) prove {@code getChildrenMode()} is a
     * KEEP-NAMED public getter; invoke it reflectively and clear the list IN PLACE (the
     * getter returns the live backing list). getModeType()==6 marks the 通透 mode whose
     * children drive 增强人声 (Ba/A) — clearing covers both extras uniformly.
     */
    private static void stripEncoSubLevels(Object dto) {
        try {
            Object function = readField(dto, "function");
            if (function == null) {
                MLog.event("bose.catalog.strip_skip", "why", "no_function");
                return;
            }
            Object modes = readField(function, "noiseReductionMode");
            if (!(modes instanceof java.util.List)) {
                MLog.event("bose.catalog.strip_skip", "why", "no_modes");
                return;
            }
            // SAFETY: cloneCatalogEntry copies fields BY REFERENCE, so dto.function and
            // every mode/children list are still the SAME objects the host's real Enco
            // entries use. Clearing in place would mutate the whole catalog. Copy the
            // chain first (function -> modes -> each mode), swap the copies into the dto,
            // then clear children on the copies only.
            Object functionCopy = shallowCopyObject(function);
            if (functionCopy == null) return;
            java.util.List<Object> modesCopy = new java.util.ArrayList<>();
            int cleared = 0;
            for (Object mode : (java.util.List<?>) modes) {
                if (mode == null) continue;
                Object modeCopy = shallowCopyObject(mode);
                if (modeCopy == null) {
                    modesCopy.add(mode);
                    continue;
                }
                Method getter = null;
                for (Method candidate : allMethods(mode.getClass())) {
                    if ("getChildrenMode".equals(candidate.getName())
                            && candidate.getParameterCount() == 0) {
                        getter = candidate;
                        break;
                    }
                }
                if (getter != null) {
                    getter.setAccessible(true);
                    Object children = getter.invoke(mode);
                    if (children instanceof java.util.List) {
                        // Locate the backing field by identity (R8 renames fields, so the
                        // name is unusable — that is exactly why 0.5.69 never fired).
                        for (java.lang.reflect.Field field
                                : allFieldsOf(modeCopy.getClass())) {
                            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                                continue;
                            }
                            field.setAccessible(true);
                            if (field.get(modeCopy) == children) {
                                field.set(modeCopy, new java.util.ArrayList<Object>());
                                cleared++;
                                break;
                            }
                        }
                    }
                }
                modesCopy.add(modeCopy);
            }
            writeFieldIfExists(functionCopy, "noiseReductionMode", modesCopy);
            writeFieldIfExists(dto, "function", functionCopy);
            MLog.event("bose.catalog.sublevels_stripped",
                    "modes", modesCopy.size(), "cleared", cleared);
        } catch (Throwable t) {
            MLog.event("bose.catalog.strip_error", "error", MLog.compactThrowable(t));
        }
    }

    /** No-arg-ctor + field-by-field copy of a host data object; null when impossible. */
    private static Object shallowCopyObject(Object source) {
        try {
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> candidate
                    : source.getClass().getDeclaredConstructors()) {
                if (candidate.getParameterCount() == 0) {
                    ctor = candidate;
                    break;
                }
            }
            if (ctor == null) return null;
            ctor.setAccessible(true);
            Object copy = ctor.newInstance();
            for (java.lang.reflect.Field field : allFieldsOf(source.getClass())) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(copy, field.get(source));
            }
            return copy;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeFieldIfExists(Object target, String name, Object value) {
        try {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
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

            // 0.5.67: the decisive device evidence for the blank page is
            //   bose.detail.sections dump=scroll[1] host0=id-1(LinearLayout)[1440x1529 vis=8]
            //       {2 kids: RelativeLayout#melody_ui_device_info[1440x1357 vis=8] ...}
            // i.e. the content IS built and measured (non-zero sizes) but the whole subtree
            // was switched to GONE. 0.5.25 already diagnosed the same thing from the decor
            // tree ("BOTH of those children are View.GONE"). The host hides what it cannot
            // populate for a device with no catalog entry; un-hiding is safe because an
            // empty row renders empty instead of crashing, and every retry tick re-applies it
            // after the host's own hide passes (300/800/2000/5000ms in detailActivityCreate).
            // Guarded by boseBonded(): detailActivityCreate fires for EVERY brand's detail
            // page, and un-hiding rows on a real Enco page would fight the host's own logic.
            if (boseBonded()) {
                int revived = reviveGoneDetailViews(container, 0);
                if (revived > 0) {
                    MLog.event("bose.detail.revived", "count", revived);
                }
            }

            // 0.5.47: the container holds a NestedScrollView, not a RecyclerView, so the
            // detail page is a custom layout rather than a PreferenceFragment. Its
            // LinearLayout has two children and the second one is where sections would go —
            // dump that one deep, because "empty" and "full but hidden" look identical from
            // the outside and imply completely different fixes.
            MLog.event("bose.detail.sections",
                    "dump", String.valueOf(dumpSectionHost(container)));
            if (filled > 0) {
                MLog.event("bose.detail.summary_filled", "count", filled);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose detail summary fill failed", t);
        }
    }

    /**
     * Switches GONE views back to VISIBLE inside the detail subtree.
     *
     * <p>0.5.67. Bounded walk (depth 10 / 600 nodes). Skips views with no measured size —
     * those were never laid out (off-screen templates) and flipping them would be noise.
     * Reports a one-line sample of what it revived so the next log shows whether the culprit
     * was the scroll view itself, the device-info header, or the section host.
     */
    private static int revivedReported = 0;

    /**
     * 0.5.68 — ids/classes that unambiguously identify detail-page CONTENT.
     *
     * <p>0.5.67 revived every GONE view with a measured size. That is indiscriminate: the
     * host's loading mask / click-intercept layer / empty-state panel are exactly such views,
     * and flipping them to VISIBLE put an invisible-to-us layer on top of the content — which
     * matches user reports #1 (page appears, then vanishes after a few seconds) and #5 ("bose按键"
     * / "bose电源" do not respond to taps). Only views on the path to real content are revived
     * now, and an overlay never is, because an overlay contains no content node.
     */
    private static final java.util.Set<String> DETAIL_CONTENT_IDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "melody_ui_detail_scrollview",
                    "melody_ui_device_info"));

    /** One-shot markers for the 0.5.69 page-level visibility arbitration. */
    private static final java.util.concurrent.atomic.AtomicBoolean overlayBlockLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean pageHideBlockLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 0.5.74: dedup set for the Activity.finish forensic log (replaces the 0.5.73
     * block counter — interception was rolled back after it was proven to break the
     * host's finish-previous-instance singleton guard).
     */
    private static final java.util.Set<String> finishCallSites =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * 0.5.71 re-entry guard for the detailSetVisibility hook. Set while WE call
     * setVisibility from inside the hook, so the re-entrant frame returns after proceed
     * instead of arbitrating again. Without it the host and our arbitration alternate
     * forever (the 2026-10-04 18:55 ANR). setVisibility runs on the main thread, so a
     * ThreadLocal is safe and needs no locking.
     */
    private static final ThreadLocal<Boolean> detailArbitrating =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * True when the view spans (nearly) the whole window, i.e. it is a page-level container
     * or a page-level mask — never a row. Row-sized views stay under the host's own control.
     */
    private static boolean isFullSizeView(View v) {
        try {
            View root = v.getRootView();
            if (root == null) return false;
            int rh = root.getHeight();
            int rw = root.getWidth();
            if (rh <= 0 || rw <= 0) return false;
            return v.getHeight() >= rh * 3 / 4 && v.getWidth() >= rw * 3 / 4;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when {@code v} is, or contains, known detail-page content (bounded walk). */
    private static boolean contentPathNow(View v) {
        return collectContentPath(v, 0,
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<View, Boolean>()),
                new int[]{0});
    }

    /** True when this view is itself known detail-page content (not an overlay/mask). */
    private static boolean isDetailContentView(View v) {
        if (v == null) return false;
        try {
            String id = idName(v);
            if (id != null && DETAIL_CONTENT_IDS.contains(id)) return true;
            String simple = v.getClass().getSimpleName();
            if (simple.contains("MelodyDetailModelView")) return true;
            // The COUI preference list that renders the section rows.
            if ("RecyclerView".equals(simple)) return true;
            if ("NestedScrollView".equals(simple)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int reviveGoneDetailViews(View root, int depthIgnored) {
        if (root == null) return 0;
        // Pass 1: collect the ancestor chain of every content node. Bounded walk.
        final java.util.Set<View> contentPath =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<View, Boolean>());
        collectContentPath(root, 0, contentPath, new int[]{0});
        if (contentPath.isEmpty()) return 0;
        // Pass 2: flip only the GONE views on that path.
        int revived = 0;
        for (View v : contentPath) {
            try {
                if (v.getVisibility() == View.GONE
                        && v.getWidth() > 0 && v.getHeight() > 0) {
                    v.setVisibility(View.VISIBLE);
                    revived++;
                    if (revivedReported < 8) {
                        revivedReported++;
                        MLog.event("bose.detail.revive_one",
                                "view", v.getClass().getSimpleName(),
                                "id", idName(v),
                                "size", v.getWidth() + "x" + v.getHeight());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return revived;
    }

    /** Marks {@code view} and all its ancestors when it (or a descendant) is content. */
    private static boolean collectContentPath(View view, int depth,
            java.util.Set<View> path, int[] budget) {
        if (view == null || depth > 12 || budget[0] > 800) return false;
        budget[0]++;
        boolean hit = isDetailContentView(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int n = Math.min(group.getChildCount(), 60);
            for (int i = 0; i < n; i++) {
                if (collectContentPath(group.getChildAt(i), depth + 1, path, budget)) {
                    hit = true;
                }
            }
        }
        if (hit) {
            for (View v = view; v != null; ) {
                if (!path.add(v)) break;
                Object p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
        }
        return hit;
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
            // 0.5.48 read the "no_anchor"/"COUIPreferenceCategory/0" pair from noiseEffectRow
            // while the install path had actually picked oneSpaceNoiseEffectRow, so the log
            // claimed a zero-child group on the wrong page. Report the anchor the installer
            // would really use.
            Object row = pickLiveAnchor();
            if (row == null) return "no_anchor";
            Object parent = PrefRef.getParent(row);
            if (parent == null) return "no_parent";
            return parent.getClass().getSimpleName() + "/"
                    + PrefRef.getPreferenceCount(parent)
                    + "/anchor=" + row.getClass().getSimpleName();
        } catch (Throwable t) {
            return "error:" + MLog.compactThrowable(t);
        }
    }

    /**
     * True when a preference's own view is currently attached to a window.
     *
     * <p>A preference only becomes a view once its group is bound, so a successful insert
     * into a group that is not on screen leaves {@code isAttachedToWindow()} false. This is
     * the discriminator between "written but invisible" and "never written" — the two
     * failure modes that 0.5.49 could not tell apart, which is why the detail page reported
     * {@code landed=true} on every run and stayed blank.
     */
    private static boolean isPreferenceAttached(Object preference) {
        if (preference == null) return false;
        try {
            Object context = PrefRef.invokeNoArg(preference, "getContext");
            if (!(context instanceof View)) return false;
            return ((View) context).isAttachedToWindow();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Walks up the preference tree and returns the first ancestor whose view is attached to a
     * window, or {@code null} when the whole chain is off-screen.
     *
     * <p>0.5.49: this is what separates the two pages. 通用设置 injected into
     * {@code PreferenceScreen} and the slider appeared; the detail page injected into a
     * {@code COUIPreferenceCategory} card that was never laid out, so the write went into a
     * detached subtree. Injecting one level up puts the row in the container the panel
     * actually renders. The chain is bounded so a cyclic or pathological parent link cannot
     * hang the main thread.
     */
    /**
     * Renders a DTO's {@code children} field for the 17.6.3 whitelist log.
     *
     * <p>17.6.3 splits rendering in two: {@code a(mac)} picks the entry by productId or MAC,
     * then {@code G9/H.u(String)} consults {@code getFunction()} before any section is built.
     * A DTO with {@code function == null} or an empty {@code children} list therefore renders
     * nothing — which is exactly the blank-page symptom, and it is visible here.
     */
    /**
     * Lists the keys of a group's direct children, depth-limited.
     *
     * <p>This is the first look at what the host actually put on the detail page. Every
     * earlier version worked from an empty or fabricated tree, so there was never a way to
     * tell "the host created sections" from "the host created nothing".
     */
    /**
     * Hides the Enco ANC rows directly in the assembled tree.
     *
     * <p>0.5.58. The previous approach relied on the {@code detailPreferenceAdd} hook, which
     * only fires for preferences the host adds programmatically. The detail page's
     * {@code NoiseReductionItem} is created by the runtime builder (A8/i.1), never through
     * {@code addPreference}, so the hook never saw it: 0.5.57 device logs contain zero
     * {@code bose.anco.row.hidden} events for it and the four-level ANC picker stayed on
     * screen. Walking the finished tree covers every construction path.
     *
     * <p>Only nodes whose class is one of the two known noise rows are touched, and our own
     * {@code melodylink.*} rows are never hidden. The walk is depth- and count-bounded.
     */
    /**
     * Retries the ANC sweep after the host has finished adding its sections.
     *
     * <p>0.5.62. At {@code detailFragBuild} the screen reports {@code children=1} (just the
     * root), because the sections are appended afterwards by {@code settingListChanged}.
     * A single walk at that point therefore had nothing to hide and was never repeated —
     * hence the four-level Enco ANC picker stayed on screen. A short bounded retry covers the
     * window in which the host finishes building the page.
     */
    private void scheduleAncTreeSweep(final Object screen) {
        if (ancSweepScheduled) return;
        ancSweepScheduled = true;
        // 0.5.63: last attempt ended at 1800 ms, but the host keeps appending sections for
        // as long as the whitelist content resolves, and the Enco ANC row is one of the last
        // ones. A couple of later, cheap retries close that window; each one only reads the
        // tree and hides matching classes, so an extra pass is harmless.
        long[] delays = {0L, 120L, 400L, 900L, 1800L, 3000L, 5000L};
        for (int i = 0; i < delays.length; i++) {
            final int attempt = i;
            mainHandler.postDelayed(() -> {
                try {
                    if (screen == null) return;
                    int before = PrefRef.getPreferenceCount(screen);
                    hideEncoAncRowsInTree(screen);
                    int after = PrefRef.getPreferenceCount(screen);
                    if (attempt == 0 || after > 0) {
                        MLog.event("bose.anc.sweep",
                                "attempt", attempt,
                                "children", after,
                                "changed", after != before);
                    }
                    if (after > 0) ancSweepScheduled = false;
                } catch (Throwable t) {
                    MLog.event("bose.anc.sweep_error",
                            "attempt", attempt, "error", MLog.compactThrowable(t));
                }
            }, delays[i]);
        }
    }

    private volatile boolean ancSweepScheduled;

    /** One-shot marker so the bind-time hide is reported once, not on every pass. */
    private static final java.util.concurrent.atomic.AtomicBoolean ancBindHidden =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-shot marker for the "holder has no itemView" case. */
    private static final java.util.concurrent.atomic.AtomicBoolean ancBindNoView =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-shot marker so the menu-category suppression is reported once per process. */
    private static final java.util.concurrent.atomic.AtomicBoolean ancMenuSuppressed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** One-shot marker so the scroll-view revive is reported once per process. */
    private static final java.util.concurrent.atomic.AtomicBoolean scrollReviveLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void hideEncoAncRowsInTree(Object group) {
        int[] hidden = {0};
        hideEncoAncRowsInTree(group, 0, hidden);
        if (hidden[0] > 0) {
            MLog.event("bose.anc.tree_hidden", "count", hidden[0]);
        }
    }

    private void hideEncoAncRowsInTree(Object group, int depth, int[] hidden) {
        if (group == null || depth > 6 || hidden[0] >= 8) return;
        int count;
        try {
            count = Math.min(PrefRef.getPreferenceCount(group), 64);
        } catch (Throwable t) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Object child;
            try {
                child = PrefRef.getPreference(group, i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) continue;
            // 0.5.68 REGRESSION FIX — the detail page lost its three-state widget
            // (user report #4: "3个耳机状态没了"). NoiseReductionItem.smali proves this
            // class IS the three-state control itself: it holds
            // mActionView:DeviceControlWidget plus mOnModeActionClickListener and calls
            // getModeList()/setEnable(). OneSpaceNoisePreference is the 通用设置
            // three-state. The old class-based setVisible(false) here therefore hid
            // exactly the widgets that must stay. It only surfaced now because 0.5.67
            // made the detail page visible for the first time.
            // The Enco "降噪效果" menu is suppressed by KEY (pref_noise_menu /
            // pref_noise_menu_category) in ancRowBind + suppressNoiseMenuCategory +
            // the b.x/b.w hooks, so this sweep no longer hides anything; the walk is
            // kept (recursion below) for future targeted fixes.
            hideEncoAncRowsInTree(child, depth + 1, hidden);
        }
    }

    /**
     * Lists each child's order value, so a wrong or negative order is visible.
     *
     * <p>0.5.61. The host orders the detail page from a static table (G9/Q.y); anything the
     * table does not know gets {@code indexOf == -1}. Reporting the real values is the only
     * way to tell "the row is present but ordered out of the panel" from "the row is absent".
     */
    /**
     * Appends our section keys to the host's static ordering table {@code G9/Q.y}.
     *
     * <p>0.5.61. The detail page sorts sections with {@code y.indexOf(key)} →
     * {@code setOrder}. Keys missing from the table receive {@code -1} and COUI drops or
     * misplaces them, which is why every injected row reported {@code landed=true} yet the
     * page stayed blank. The table is a {@code List<String>} static field, so the values are
     * added reflectively and are shared with the host for the rest of the process.
     *
     * <p>Order matters: the extra categories are appended after the native ones, so they
     * render at the bottom of the page rather than displacing a host section.
     */
    private static void registerSectionKeys(String... keys) {
        try {
            // 0.5.62: report the table as it stands, together with the keys we are about to
            // add. 0.5.61 device logs show the detail page's real section keys are plain
            // class names ("NoiseReductionItem", "pref_device_info") with order 0, while
            // G9/Q.y holds short names ("noise", "product", ...). If the two key spaces are
            // genuinely different, indexOf() can never match a real section and the host
            // orders every row to 0 — which is a different root cause from "our keys are
            // missing" and needs a different fix. This line decides between the two.
            logSectionTable("before", keys);
            // melodyClassLoader is an instance field; a static method cannot touch it.
            ClassLoader host = hostLoaderRef;
            if (host == null) {
                MLog.w("section key registration skipped: host loader unknown");
                return;
            }
            Class<?> type = Class.forName("G9.Q", false, host);
            Object table = null;
            for (Field f : type.getDeclaredFields()) {
                if (f.getType() != java.util.List.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof java.util.List) {
                        table = v;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!(table instanceof java.util.List)) {
                MLog.w("section table not found on G9.Q");
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) table;
            int added = 0;
            for (String key : keys) {
                if (key == null) continue;
                if (list.contains(key)) continue;
                try {
                    list.add(key);
                    added++;
                } catch (Throwable ignored) {
                }
            }
            if (added > 0) {
                MLog.event("bose.section_keys_added",
                        "count", added, "size", list.size());
            }
            logSectionTable("after", keys);
        } catch (Throwable t) {
            MLog.w("section key registration failed: " + MLog.compactThrowable(t));
        }
    }

    /** Dumps the host section table so the two key spaces can be compared directly. */
    private static void logSectionTable(String stage, String[] keys) {
        try {
            Class<?> type = Class.forName("G9.Q", false, hostLoaderRef);
            for (Field f : type.getDeclaredFields()) {
                if (f.getType() != java.util.List.class) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!(v instanceof java.util.List)) continue;
                MLog.event("bose.section_table",
                        "stage", stage,
                        "size", ((java.util.List<?>) v).size(),
                        "contents", String.valueOf(v),
                        "adding", String.valueOf(java.util.Arrays.toString(keys)));
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Host ClassLoader captured when the hooks were installed. */
    private static volatile ClassLoader hostLoaderRef;

    /**
     * True for the "降噪效果" label row, matched by its visible title.
     *
     * <p>0.5.66. The Enco four-level ANC picker and the three-state widget
     * (降噪 / 关闭 / 通透) live in the SAME preference, so hiding the preference
     * removed both — the user reported the widget disappearing. Only the label row
     * should go, so the match is on the title text and the view collapse happens
     * after the adapter has finished binding, never on the preference itself.
     */
    private static boolean isEncoAncTitle(String title) {
        if (title == null) return false;
        String t = title.trim();
        return NOISE_EFFECT_TITLE.equals(t) || "ANC \u6548\u679c".equals(t);
    }

    private static String describeChildOrders(Object group) {
        StringBuilder sb = new StringBuilder();
        try {
            int count = Math.min(PrefRef.getPreferenceCount(group), 24);
            for (int i = 0; i < count; i++) {
                Object child = PrefRef.getPreference(group, i);
                if (child == null) continue;
                String key = PrefRef.getKey(child);
                if (sb.length() > 0) sb.append(' ');
                sb.append(key == null ? "null" : key)
                  .append('=').append(PrefRef.getOrder(child))
                  // 0.5.63: the key alone is ambiguous because 0.5.61 logs showed names like
                  // "NoiseReductionItem" while G9/Q.y holds "noise". Printing the class too
                  // makes it unambiguous which of the two spaces a row lives in.
                  .append('@').append(child.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            sb.append("err:").append(t.getClass().getSimpleName());
        }
        return sb.toString().trim();
    }

    private static String describeChildKeys(Object group, int depth) {
        if (group == null || depth > 2) return "";
        StringBuilder sb = new StringBuilder();
        try {
            int count = PrefRef.getPreferenceCount(group);
            for (int i = 0; i < count && i < 30; i++) {
                Object child = PrefRef.getPreference(group, i);
                if (child == null) continue;
                String key = PrefRef.getKey(child);
                sb.append(i).append(':')
                  .append(child.getClass().getSimpleName())
                  .append('/').append(key == null ? "null" : key)
                  .append('(').append(PrefRef.getPreferenceCount(child)).append(')');
                if (depth < 2 && PrefRef.getPreferenceCount(child) > 0) {
                    sb.append(" {").append(describeChildKeys(child, depth + 1)).append('}');
                }
                sb.append(' ');
            }
        } catch (Throwable t) {
            sb.append("err:").append(t.getClass().getSimpleName());
        }
        return sb.toString().trim();
    }

    private static String describeChildren(Object dto) {
        try {
            Object v = readField(dto, "children");
            if (v == null) return "null";
            if (v instanceof java.util.Collection) {
                return "size=" + ((java.util.Collection<?>) v).size();
            }
            return v.getClass().getSimpleName();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static Object attachedAncestor(Object start) {
        // 0.5.56 REGRESSION FIX. 0.5.55 made this walk unconditionally to the topmost group
        // and it broke the panel: Melody froze when leaving the detail page and 通用设置 would
        // not reopen. The rewrite also contradicted its own evidence — 0.5.54 already showed
        // parent=COUIPreferenceCategory with children=6, i.e. the immediate parent is a real,
        // fully populated group that the host itself created. Promoting from there to
        // PreferenceScreen rewrote the root's order values and broke COUI's card grouping.
        //
        // Promotion is now limited to the case it was invented for: the immediate parent is
        // missing or empty, so there is nothing to write into. A non-empty parent is used
        // as-is, which is the behaviour that produced a working slider in 0.5.53-0.5.55.
        if (start == null) return null;
        if (PrefRef.getPreferenceCount(start) > 0) return null;
        try {
            Object next = PrefRef.getParent(start);
            if (next == null || next == start) return null;
            return PrefRef.getPreferenceCount(next) > 0 ? next : null;
        } catch (Throwable t) {
            return null;
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
        return targetBoseDevice != null && boseHostConnected;
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

    /**
     * 0.5.75 — read-back verification for #2 (通用设置 still shows no earphone photo even
     * though {@code bose.image.applied surface=onespace} fires repeatedly).
     *
     * <p>"Applied" only proves we called setImageURI on field {@code e}. It does NOT prove
     * the pixels are on screen: the view can be detached, zero-sized, GONE, or hidden
     * behind an ancestor that the host collapsed. Per the standing rule (a diagnostic must
     * read the value back, and report the artefact's real existence, not just the success
     * flag), this walks the parent chain and reports the FIRST ancestor that would make the
     * image invisible, plus the ImageView's own attached/size/visibility/drawable state.
     * Fires once per (surface, view) so it never joins the layout-pass hot path.
     */
    private void reportBoseImageViewTruth(ImageView view, String surface) {
        if (view == null) return;
        // Bind time is BEFORE layout, so an immediate read would always report 0x0 and
        // look like a false failure. Sample once shortly after settle and once late.
        for (long delay : new long[]{700L, 2500L}) {
            if (!boseImageTruthLogged.add(surface + "@" + delay)) continue;
            mainHandler.postDelayed(() -> readBoseImageViewTruth(view, surface, delay), delay);
        }
    }

    private void readBoseImageViewTruth(ImageView view, String surface, long delay) {
        try {
            if (!view.isAttachedToWindow()) {
                MLog.event("bose.image.truth", "surface", surface, "t", delay,
                        "attached", false);
                return;
            }
            String blocker = "none";
            View node = view;
            for (int depth = 0; depth < 12 && node != null; depth++) {
                ViewParent parent = node.getParent();
                if (!(parent instanceof View)) {
                    blocker = "root:" + (node.getClass().getSimpleName());
                    break;
                }
                View p = (View) parent;
                if (p.getVisibility() != View.VISIBLE) {
                    blocker = p.getClass().getSimpleName() + "@" + idName(p)
                            + ":vis=" + p.getVisibility();
                    break;
                }
                if (p.getWidth() == 0 || p.getHeight() == 0) {
                    blocker = p.getClass().getSimpleName() + "@" + idName(p) + ":0x0";
                    break;
                }
                node = p;
            }
            MLog.event("bose.image.truth",
                    "surface", surface,
                    "t", delay,
                    "attached", true,
                    "size", view.getWidth() + "x" + view.getHeight(),
                    "vis", view.getVisibility(),
                    "drawable", view.getDrawable() != null,
                    "tagged", BOSE_IMAGE_TAG.equals(view.getTag()),
                    "blocker", blocker);
        } catch (Throwable t) {
            MLog.event("bose.image.truth_error", "surface", surface,
                    "error", MLog.compactThrowable(t));
        }
    }

    private static final java.util.Set<String> boseImageTruthLogged =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Logs the colorId override once per process; getColorId() is a hot path. */
    private final java.util.concurrent.atomic.AtomicBoolean colorIdOverrideLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    private File materializeBoseImage() {
        Application application = currentApplication();
        if (application == null) return null;
        if (sonyModuleAssets == null) initializeSonyConfig();
        AssetManager assets = sonyModuleAssets;
        if (assets == null) return null;
        File directory = new File(application.getFilesDir(), "melodylink/bose-images");
        // 0.5.69: v2 = the user-supplied high-res product photo. The name is bumped so
        // devices that cached the old asset file pick the new one up.
        // 1.0.1: v3 = the same photo with the black studio background removed
        // (flood-fill alpha cutout) so it no longer draws a black rectangle on
        // pages whose own background is light.
        File output = new File(directory, "qc_ultra2_v3.png");
        try {
            if (output.isFile() && output.length() > 0L) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (java.io.InputStream input = assets.open("bose/images/qc_ultra2_v3.png");
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

    /** Exact byte length of the bundled bose/model/bose_qcue2.vfxms asset. */
    private static final long BOSE_MODEL_LENGTH = 13239210L;

    /**
     * 2.0.5: the detail page should show ONLY the 3D model, no 2D photo. Evidence
     * (2.0.1/2.0.2 logs + 2.0.3/2.0.4 screenshots): the model renders only while the
     * photo ImageView (field d) is VISIBLE — setting d GONE (2.0.3) or INVISIBLE (2.0.4)
     * left the product container blank. So we keep d VISIBLE (model render condition)
     * but fully transparent. The current detail photo is held in a single volatile ref
     * (not a Set) so the hot View.setAlpha clamp is a lock-free reference compare that
     * is cheap enough to stay active permanently (ANR lesson #7). Two hooks cooperate:
     *   - bosePhotoAlphaClamp pins setAlpha to 0f for that view (the host's b() sets
     *     alpha 1 then cross-fades; we neutralise it), and
     *   - detailSetVisibility forces it back to VISIBLE if the host tries to GONE it.
     */
    private static volatile View boseTransparentPhoto;

    /** Keep the current Bose detail photo VISIBLE (model render condition) but transparent. */
    private static void makeBosePhotoTransparent(View photo) {
        if (photo == null) return;
        boseTransparentPhoto = photo;
        try {
            photo.setVisibility(View.VISIBLE);
            photo.setAlpha(0f);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Copies the bundled Bose QC Ultra Earbuds 2 model into the host files dir and
     * returns it. 2.0.1: the host's ModelScene.loadSceneFromBuffer does NOT parse a
     * plain glb — it expects OPPO's .vfxms container (Head.read: 10 big-endian u32
     * offsets/sizes, then a JSON scene config, then glb/IBL/skybox blobs). A raw
     * glb made Head.read treat the "glTF" magic as configStart, the config read
     * underflowed and the whole load died inside a try/catch that only prints
     * through LogUtils — silently. The bundled asset is our glb wrapped in the
     * vfxms layout (X3's own config JSON + IBL/skybox KTX blobs for identical
     * lighting), so the file is now materialized and passed through unchanged.
     */
    private File materializeBoseModel() {
        Application application = currentApplication();
        if (application == null) return null;
        if (sonyModuleAssets == null) initializeSonyConfig();
        AssetManager assets = sonyModuleAssets;
        if (assets == null) return null;
        File directory = new File(application.getFilesDir(), "melodylink/bose-model");
        // 2.0.6: v4 asset REBUILT — the first v4 (2.0.3-2.0.5) had a corrupted glb BIN
        // chunk (make_vfxms4.py read binlen from inside the bin data and copied the bin
        // without its 8-byte header), so ModelScene.loadSceneFromBuffer failed and the
        // detail page rendered a black product area. Fixed offsets; whitened Moonstone
        // materials + modelScale 1.5 retained. Length change forces on-device refresh.
        File output = new File(directory, "bose_qcue2_v4.vfxms");
        try {
            if (output.isFile() && output.length() == BOSE_MODEL_LENGTH) return output;
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            try (java.io.InputStream input = assets.open("bose/model/bose_qcue2.vfxms");
                 FileOutputStream stream = new FileOutputStream(output, false)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
            return output.isFile() && output.length() == BOSE_MODEL_LENGTH ? output : null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Bose model materialization failed", t);
            return null;
        }
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
        if (loadingView instanceof View) {
            // 0.5.78 — #2 root cause. stopLoadingSpinners() walks the parent chain and
            // called hideLoadingView(parent) on EVERY ancestor, which GONE'd the product
            // image's own container (device_image_container / onespace_header_container),
            // blanking the photo on 通用设置. Only spinner-like views (Lottie / Progress /
            // Loading / Spin) should be hidden; a plain FrameLayout / RelativeLayout
            // container must be left untouched.
            String name = loadingView.getClass().getName();
            boolean isSpinner = name.contains("Lottie") || name.contains("Animation")
                    || name.contains("Loading") || name.contains("Progress")
                    || name.contains("Spin");
            if (!isSpinner) return;
            try {
                Method cancelAnimation = loadingView.getClass().getMethod("cancelAnimation");
                cancelAnimation.invoke(loadingView);
            } catch (Throwable ignored) {
            }
            ((View) loadingView).setVisibility(View.GONE);
        }
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
        // 2.0.5: the detail page shows ONLY the 3D model. Instead of installing a 2D
        // PNG into field d (which then "flashed"), keep d VISIBLE (the model only
        // renders while d is VISIBLE — proven by the 2.0.3/2.0.4 blank-model
        // regression) but fully transparent (alpha 0). a()/d() run before b(), so the
        // photo is transparent from the first frame; the setAlpha clamp +
        // detailSetVisibility hold it VISIBLE+alpha0 against the host.
        Object field = readField(owner, "d");
        if (field instanceof ImageView) {
            makeBosePhotoTransparent((ImageView) field);
            MLog.event("bose.image.skip", "surface", "detail", "reason", "model_only");
        }
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
            // 0.5.73 — TWO bugs fixed here, both proven from OneSpaceHeaderPreference.smali:
            // (a) owner is the OneSpaceHeaderPreference INSTANCE (a Preference, NOT a View),
            //     passed via chain.getThisObject() from sonyCardImage(i()) / sonyCardBind
            //     (onBindViewHolder). The old `owner instanceof View` guard therefore always
            //     returned false, which is why the log NEVER showed surface=onespace and the
            //     card stayed blank. The product ImageView lives in field e.
            // (b) the loading spinner is field d (LottieAnimationView), NOT e. i() itself does
            //     `iput d ... const/16 0x8 (GONE)` on d. Passing "e" as the loadingField made
            //     applyBoseImage GONE the product image it had just installed.
            if (owner == null) {
                MLog.event("bose.image.skip", "surface", "onespace", "reason", "null_owner");
                return false;
            }
            Object field = readField(owner, "e");
            if (!(field instanceof ImageView)) {
                MLog.event("bose.image.skip", "surface", "onespace", "reason", "no_image_view",
                        "owner", owner.getClass().getSimpleName());
                return false;
            }
            File file = materializeBoseImage();
            if (file == null) {
                MLog.event("bose.image.skip", "surface", "onespace", "reason", "asset_unavailable");
                return false;
            }
            applyBoseImage((ImageView) field, file, owner, "d");
            pinBoseImageView((ImageView) field);
            MLog.event("bose.image.applied", "surface", "onespace");
            reportBoseImageViewTruth((ImageView) field, "onespace");
            return true;
        } catch (Throwable t) {
            MLog.event("bose.image.error", "surface", "onespace",
                    "error", MLog.compactThrowable(t));
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
    /**
     * Hides the 通用设置 "降噪效果" category by walking the fragment that owns this row.
     *
     * <p>0.5.67. Three independent paths call this:
     * <ol>
     *   <li>{@code onespaceNoiseMenuCheck} — hooked straight onto
     *       OneSpaceListFragment.x(List,Z) ("checkShowNoiseMenuItem"), the method that
     *       turns the category visible on every WhitelistConfigDTO LiveData emit.</li>
     *   <li>{@code onespaceNoiseBind} — OneSpaceNoisePreference.onBindViewHolder, the one
     *       hook PROVEN to fire on this page in every recent version (the CNC slider has
     *       always attached through it).</li>
     *   <li>{@code hideAncStrengthPreference} — the old detailPreferenceAdd path, which
     *       fires only once at XML inflate time and is therefore too early on its own.</li>
     * </ol>
     * All three end here, so the category is re-hidden after every show, no matter which
     * hook actually resolved at runtime (0.5.33 proved final-class methods can fail to hook).
     *
     * <p>The walk is by KEY (pref_noise_menu_category / pref_noise_menu) over the screen the
     * row belongs to, never by field name of the fragment: keys survive R8 renames between
     * Melody releases, obfuscated field names do not. The three-state widget
     * (pref_noise_switch) is deliberately never touched — hiding it was the 0.5.65
     * regression.
     */
    private void suppressNoiseMenuCategory(Object preferenceRow) {
        try {
            if (preferenceRow == null || !boseBonded()) return;
            Object tree = findOneSpacePreferenceTree(preferenceRow);
            if (tree == null) return;
            // This runs from OneSpaceNoisePreference.onBindViewHolder, i.e. INSIDE the
            // RecyclerView layout pass. Preference.setVisible(false) on an attached row
            // fires notifyItemRemoved right there, which RecyclerView rejects with
            // "Cannot call this method while RecyclerView is computing a layout". The walk
            // is deferred one main-loop tick so the hide happens outside the pass.
            final Object root = tree;
            mainHandler.post(() -> {
                try {
                    int hidden = 0;
                    for (String key : new String[]{"pref_noise_menu_category", "pref_noise_menu"}) {
                        Object pref = PrefRef.findPreferenceRecursive(root, key);
                        if (pref == null) continue;
                        if (PrefRef.isVisible(pref)) {
                            PrefRef.setVisible(pref, false);
                            hidden++;
                        }
                    }
                    if (hidden > 0 && ancMenuSuppressed.compareAndSet(false, true)) {
                        MLog.event("bose.anco.menu_suppressed",
                                "path", "tree_walk",
                                "hidden", hidden);
                    }
                } catch (Throwable t) {
                    MLog.event("bose.anco.menu_suppress_error",
                            "path", "tree_walk",
                            "error", MLog.compactThrowable(t));
                }
            });
        } catch (Throwable t) {
            MLog.event("bose.anco.menu_suppress_error",
                    "path", "tree_walk",
                    "error", MLog.compactThrowable(t));
        }
    }

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
            // 2.0.13: same link gate as the detail-page slider.
            setPreferenceValue(seek, "setEnabled", isBoseLinkConnected());
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
            return isBoseDevice(device);
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
        return targetAddress != null
                && ((targetBoseDevice != null && boseHostConnected)
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

    /**
     * True when the earbuds currently have a live Bluetooth link. Uses the public
     * {@code BluetoothManager.getConnectedDevices} API so it works from every Melody
     * process (GATT is the ACL-level presence for earbuds; A2DP/HEADSET cover active
     * audio paths). The hidden {@code BluetoothDevice.getConnectionState()} probe from
     * {@link #isDeviceConnected} is a bonus path, never load-bearing: that hidden API
     * is blocked by reflection filters on some ColorOS builds.
     */
    @SuppressLint("MissingPermission")
    private boolean isBoseLinkConnected() {
        BluetoothDevice device = targetBoseDevice;
        if (device != null && isDeviceConnected(device)) return true;
        try {
            Application application = currentApplication();
            Object manager = application == null
                    ? null : application.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager instanceof android.bluetooth.BluetoothManager) {
                int[] profiles = {android.bluetooth.BluetoothProfile.GATT,
                        android.bluetooth.BluetoothProfile.A2DP,
                        android.bluetooth.BluetoothProfile.HEADSET};
                for (int profile : profiles) {
                    for (BluetoothDevice connected
                            : ((android.bluetooth.BluetoothManager) manager)
                            .getConnectedDevices(profile)) {
                        if (connected != null && isTargetAddress(connected.getAddress())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Mirror the live link state onto both CNC sliders (disabled when disconnected). */
    private long lastCncEnabledCheckAt;
    private Boolean lastCncEnabled;
    private void applyBoseCncEnabled(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastCncEnabledCheckAt < 2000L) return;
        lastCncEnabledCheckAt = now;
        boolean connected = isBoseLinkConnected();
        if (!force && lastCncEnabled != null && lastCncEnabled == connected) return;
        lastCncEnabled = connected;
        final boolean enabled = connected;
        mainHandler.post(() -> {
            for (Object target : new Object[]{boseCncPreference, boseCncOneSpacePreference}) {
                if (target == null) continue;
                setPreferenceValue(target, "setEnabled", enabled);
            }
            log(Log.INFO, TAG, event("Bose CNC sliders " + (enabled ? "enabled" : "disabled")
                    + " (link " + (enabled ? "up" : "down") + ")"));
        });
    }

    /**
     * Builds a ColorOS-styled dialog with the host's own COUI alert builder
     * (COUIAlertDialogBuilder; R8 renamed the class to W2/h in Melody 17.6.3 while
     * its setter names and create()/show() survive — verified in smali). Falls back
     * to the platform AlertDialog when the class cannot be resolved, so a Melody
     * upgrade degrades styling, never function.
     */
    private android.app.Dialog buildStyledDialog(Context context, String title, String message,
            String[] items, android.content.DialogInterface.OnClickListener itemClick,
            String positive, android.content.DialogInterface.OnClickListener positiveClick,
            String negative) {
        Object builder = null;
        ClassLoader loader = context.getClassLoader();
        for (String name : new String[]{
                "com.coui.appcompat.dialog.COUIAlertDialogBuilder", "W2/h"}) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                builder = cls.getConstructor(Context.class).newInstance(context);
                log(Log.INFO, TAG, event("using COUI dialog builder " + name));
                break;
            } catch (Throwable ignored) {
                // try the next candidate
            }
        }
        if (builder == null) {
            log(Log.WARN, TAG, event("COUI dialog builder unavailable; using platform style"));
            return buildPlatformDialog(context, title, message, items, itemClick,
                    positive, positiveClick, negative);
        }
        try {
            Class<?> cls = builder.getClass();
            if (title != null) {
                cls.getMethod("setTitle", CharSequence.class).invoke(builder, title);
            }
            if (message != null) {
                cls.getMethod("setMessage", CharSequence.class).invoke(builder, message);
            }
            if (items != null) {
                cls.getMethod("setItems", CharSequence[].class,
                        android.content.DialogInterface.OnClickListener.class)
                        .invoke(builder, items, itemClick);
            }
            if (positive != null) {
                cls.getMethod("setPositiveButton", CharSequence.class,
                        android.content.DialogInterface.OnClickListener.class)
                        .invoke(builder, positive, positiveClick);
            }
            if (negative != null) {
                cls.getMethod("setNegativeButton", CharSequence.class,
                        android.content.DialogInterface.OnClickListener.class)
                        .invoke(builder, negative, null);
            }
            return (android.app.Dialog) cls.getMethod("create").invoke(builder);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "COUI dialog build failed; falling back to platform style", t);
            return buildPlatformDialog(context, title, message, items, itemClick,
                    positive, positiveClick, negative);
        }
    }

    private static android.app.Dialog buildPlatformDialog(Context context, String title,
            String message, String[] items, android.content.DialogInterface.OnClickListener itemClick,
            String positive, android.content.DialogInterface.OnClickListener positiveClick,
            String negative) {
        android.app.AlertDialog.Builder fb = new android.app.AlertDialog.Builder(context);
        if (title != null) fb.setTitle(title);
        if (message != null) fb.setMessage(message);
        if (items != null) fb.setItems(items, itemClick);
        if (positive != null) fb.setPositiveButton(positive, positiveClick);
        if (negative != null) fb.setNegativeButton(negative, null);
        return fb.create();
    }







    private boolean writeSharedSonyCommand(String address, int modeIndex) {
        File file = sharedCommandFile();
        if (file == null) return false;
        boolean written = MelodySharedStateStore.writeCommand(file, address, modeIndex, Long.toString(System.nanoTime()));
        if (!written) log(Log.WARN, TAG, "shared Sony ANC command write failed");
        return written;
    }

    private void releaseSonySession(String reason) {
        clearSharedSonyCommand();
        clearSharedSonyBatteryCommand();
        log(Log.INFO, TAG, event(reason + "; releasing Bose BMAP session"));
        boseTransport.disconnect();
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
            }
            return;
        }
        writeSharedSonyBatteryCommand(address);
    }




    private boolean writeSharedSonyBatteryCommand(String address) {
        File file = sharedBatteryCommandFile();
        if (file == null) return false;
        boolean written = MelodySharedStateStore.writeBatteryCommand(file, address, Long.toString(System.nanoTime()));
        if (!written) log(Log.WARN, TAG, "shared Sony battery command write failed");
        return written;
    }


    private void clearSharedSonyBatteryCommand() {
        File file = sharedBatteryCommandFile();
        if (!MelodySharedStateStore.delete(file)) {
            log(Log.WARN, TAG, "shared Sony battery command delete failed");
        }
    }


    private static MelodySharedStateStore.SharedBatteryCommand readSharedSonyBatteryCommand() {
        return MelodySharedStateStore.readBatteryCommand(sharedBatteryCommandFile());
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
        // 2.0.13: a freshly created slider must not be draggable while disconnected.
        setPreferenceValue(seek, "setEnabled", isBoseLinkConnected());
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
                    // Callback a(I)V is VOID (smali-verified on
                    // MelodyPromptVolumeSeekBarPreference$b): null is the only safe return.
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
                    // 0.5.72 — the click callback is NOT named "onClick". The host's
                    // interface androidx.preference.Preference$d declares exactly one
                    // abstract method j(Preference)Z (verified in smali), so the old
                    // name check never matched and every tap silently fell into the
                    // default-return branch: bose按键/bose电源 did nothing, in every
                    // version since the rows were introduced. Detect the callback by
                    // SIGNATURE instead: the interface's own single-argument,
                    // boolean-returning method. Object methods (equals/hashCode/
                    // toString) were handled above and are excluded by declaringClass.
                    if (!(method.getReturnType() == boolean.class
                            && method.getParameterCount() == 1
                            && method.getDeclaringClass() != Object.class)) {
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                    showBoseActionPicker(row, event);
                    return Boolean.TRUE;
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
            android.app.Dialog dialog = buildStyledDialog(context,
                    "\u6309\u952e\u529f\u80fd", null, labels,
                    (d, which) -> {
                        chosen[0] = actions[which];
                        setPreferenceValue(row, "setSummary", labels[which]);
                        forwardBoseCommand(CMD_BUTTON, event, chosen[0], 0, 0);
                    },
                    null, null, "\u53d6\u6d88");
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
                    // Callback a(I)V is VOID (smali-verified): return null; a boxed
                    // Boolean would throw from the proxy on unbox-to-void.
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
                    // 0.5.72 — the click callback is NOT named "onClick". The host's
                    // interface androidx.preference.Preference$d declares exactly one
                    // abstract method j(Preference)Z (verified in smali), so the old
                    // name check never matched and every tap silently fell into the
                    // default-return branch: bose按键/bose电源 did nothing, in every
                    // version since the rows were introduced. Detect the callback by
                    // SIGNATURE instead: the interface's own single-argument,
                    // boolean-returning method. Object methods (equals/hashCode/
                    // toString) were handled above and are excluded by declaringClass.
                    if (!(method.getReturnType() == boolean.class
                            && method.getParameterCount() == 1
                            && method.getDeclaringClass() != Object.class)) {
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
                    return Boolean.TRUE;
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
                    // 0.5.72 — the click callback is NOT named "onClick". The host's
                    // interface androidx.preference.Preference$d declares exactly one
                    // abstract method j(Preference)Z (verified in smali), so the old
                    // name check never matched and every tap silently fell into the
                    // default-return branch: bose按键/bose电源 did nothing, in every
                    // version since the rows were introduced. Detect the callback by
                    // SIGNATURE instead: the interface's own single-argument,
                    // boolean-returning method. Object methods (equals/hashCode/
                    // toString) were handled above and are excluded by declaringClass.
                    if (!(method.getReturnType() == boolean.class
                            && method.getParameterCount() == 1
                            && method.getDeclaringClass() != Object.class)) {
                        return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                    confirmBosePowerOff();
                    return Boolean.TRUE;
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
            buildStyledDialog(context,
                    "\u5173\u673a",
                    "\u786e\u5b9a\u5173\u95ed\u8033\u673a\uff1f\u5c06\u65ad\u5f00\u84dd\u7259\u8fde\u63a5\u3002",
                    null, null,
                    "\u5173\u673a", (d, which) -> {
                        setPreferenceValue(boseExtraCategory, "setKey", BOSE_EXTRA_CATEGORY_KEY);
                        forwardBoseCommand(CMD_POWER_OFF, 0, 0, 0, 0);
                    },
                    "\u53d6\u6d88").show();
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



    /** Builds a host preference with the theming (Context, AttributeSet) constructor. */
    private static Object newPreference(ClassLoader loader, String typeName, Context context) {
        return PrefRef.create(loader, typeName, context);
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
        // 2.0.13: keep the injected CNC sliders' enabled state in sync with the real
        // Bluetooth link, so a disconnected device cannot drag 降噪等级.
        if (!isPrimaryProcess()) applyBoseCncEnabled(false);
        if (isPrimaryProcess()) {
            observeSharedSonyCommand();
            observeSharedSonyBatteryCommand();
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
                updateBoseCncSlider();
                applySharedAncMode(state.modeIndex);
            }
        }
        log(Log.INFO, TAG, event("foreground Sony state changed; requesting native Melody LiveData refresh"
                + " mode=" + (state == null ? -1 : state.modeIndex)));
        refreshTargetRepository("foreground shared Sony state changed");
    }

    /**
     * Adopt the mode the primary process published and repaint the 通用设置 row from it. That
     * row writes through a "setgate" Intent, so this process never sees the tap; the shared file
     * is the only channel that carries the new mode here.
     */
    private void applySharedAncMode(int modeIndex) {
        if (modeIndex < 0) return;
        try {
            com.melody.melodylink.domain.AncMode mode = MelodyCommandBridge.INSTANCE.ancMode(modeIndex);
            if (mode == null) return;
            EarbudsState state = new EarbudsState(
                    com.melody.melodylink.bose.BoseDeviceConfig.INSTANCE.getCapabilities(),
                    mode, new java.util.HashMap<>());
            if (modeIndex == MelodyStateBridge.INSTANCE.ancModeIndex(sonySessionState.getAnc())) {
                repaintOneSpaceNoiseRow("shared mode already applied");
                return;
            }
            boseSessionState.acceptAnc(state);
            sonySessionState.acceptAnc(state);
            repaintOneSpaceNoiseRow("shared ANC state changed");
        } catch (Throwable ignored) {
        }
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

    /**
     * True when this view lives inside the detail page's content container.
     *
     * <p>0.5.48. Walks up the parent chain looking for the container id, so it catches
     * descendants at any depth rather than only direct children.
     */
    private static boolean isInsideDetailContainer(View view) {
        try {
            View v = view;
            for (int guard = 0; v != null && guard < 40; guard++) {
                String id = idName(v);
                if ("melody_ui_fragment_container".equals(id)) return true;
                // 0.5.49: requiring the fragment container lost the very events we wanted.
                // DetailMainActivity hides its NestedScrollView during teardown/rebuild, when
                // the view is no longer under melody_ui_fragment_container, so the detail
                // page produced no hidden_by line at all while 通用设置 did. The host's own
                // detail ids are matched directly instead.
                if ("melody_ui_detail_scrollview".equals(id)) return true;
                if (v.getClass().getSimpleName().contains("MelodyDetailModelView")) return true;
                Object parent = v.getParent();
                if (parent instanceof View) {
                    v = (View) parent;
                } else {
                    return v.getClass().getSimpleName().contains("DecorView");
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Logs who hid a view, naming the call site responsible.
     *
     * <p>0.5.48. This is the question every previous version was guessing at. The stack is
     * filtered to app frames so the answer names the class and method responsible, and repeats
     * are collapsed by signature — the host hides rows in loops, and without deduplication one
     * culprit produces dozens of identical lines.
     */
    /**
     * Distinct outcomes already reported by the 17.6.3 whitelist probe.
     *
     * <p>0.5.51 regression: {@code whitelist.a.a(String)} is on the host's hot path and was
     * logged on every call (46 events in one second), which stalled the panel badly enough
     * that 通用设置 stopped opening. The probe now reports each distinct outcome once.
     */
    private static final java.util.Set<String> wl17Seen =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * Builds a "<- "-joined chain of up to {@code max} host stack frames, skipping the
     * hook runtime trampolines. Extracted 0.5.73 from recordHideCaller so the Activity
     * finish probe can name the caller too (lesson #3: hook the system API and read the
     * stack instead of guessing from obfuscated code).
     */
    private static String hostCallerChain(int max) {
        StringBuilder chain = new StringBuilder();
        int appFrames = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (!isHostFrame(cls)) continue;
            if (chain.length() > 0) chain.append(" <- ");
            chain.append(cls).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++appFrames >= max) break;
        }
        return chain.length() == 0 ? "no_app_frame" : chain.toString();
    }

    /**
     * 0.5.76. Unfiltered stack, for the {@code no_app_frame} finish diagnosis. Unlike
     * {@link #hostCallerChain}, this keeps framework frames AND R8 short-name lambdas
     * (the host's Kotlin lambdas compile to classes like {@code A8/A}, whose name has a
     * slash in smali but a dot at runtime), so it names the real trigger when the host
     * calls finish() asynchronously off a lambda. Bounded to {@code max} frames.
     */
    private static String rawCallerChain(int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("sun.")
                    || cls.startsWith("com.melody.melodylink")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(cls).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++n >= max) break;
        }
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    private int finishSeq;

    private static void recordHideCaller(View target, int visibility) {
        try {
            // 0.5.48 reported caller=j2.intercept for every single call site. That class is
            // NOT in the Melody APK: a full scan of both decompiled dex trees (tools/smali1,
            // tools/smali2) finds no j2.smali — only "je". "j2" is libxposed's own hook
            // trampoline, so the walk stopped on the framework's bridging frame and never
            // reached the host.
            // 0.5.67: the same failure repeated with caller=l.proceed:35 — "l" is another
            // trampoline, and the blacklist ("not java./android.") let it through. Verified
            // against the decompiled trees: the host APK has ZERO root-level (no-package)
            // classes — every host class lives in a package (com.oplus.*, com.coui.*, or an
            // R8 package like Ba/G9/A9/l9). So the filter is finally a WHITELIST as lesson #9
            // demands: a frame is host code only if its class name contains a package
            // separator and is not a known framework/module prefix. Single-token names
            // (j2, l, c2 without dot…) can never match a host class.
            String where = hostCallerChain(4);
            String raw = rawCallerChain(12);
            String cls = target.getClass().getSimpleName();
            // 0.5.78 — DecorView INVISIBLE is the "blank page" trigger. Its caller is always
            // no_app_frame because the real trigger hides behind an android.*-named synthetic
            // host class (e.g. android.telephony.RensGlaent), which isHostFrame drops. Record a
            // MONOTONIC sequence (not de-duped) so the next log shows the true frequency, and
            // keep the raw (unfiltered) chain so the real trigger is finally named.
            boolean isDecor = "DecorView".equals(cls);
            if (isDecor) {
                if (hideSeq.incrementAndGet() > 20) return;
            } else {
                String key = where + "@" + cls + "/v" + visibility;
                if (!hideCallSites.add(key)) return;
                if (hideCallSites.size() > 24) return; // the useful set is small
            }
            MLog.event("bose.detail.hidden_by",
                    "view", cls,
                    "id", idName(target),
                    "size", target.getWidth() + "x" + target.getHeight(),
                    "visibility", visibility,
                    "caller", where,
                    "raw", raw);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whitelist test for "this stack frame belongs to the Melody host APK".
     *
     * <p>0.5.67. The host has no root-level classes (verified over tools/smali1+smali2), so
     * a frame is host code only when the class name has a package part and that package is
     * neither framework nor our own module nor the hook runtime. R8 packages are typically
     * 1-2 lower/upper-case tokens (Ba, G9, A9, l9, d3…), everything else is com.oplus/com.coui.
     */
    private static boolean isHostFrame(String cls) {
        if (cls == null) return false;
        int dot = cls.lastIndexOf('.');
        if (dot <= 0) return false;                    // root-level class: trampoline, not host
        if (cls.startsWith("java.") || cls.startsWith("javax.")
                || cls.startsWith("android.") || cls.startsWith("androidx.")
                || cls.startsWith("dalvik.") || cls.startsWith("libcore.")
                || cls.startsWith("com.android.") || cls.startsWith("sun.")
                || cls.startsWith("kotlin.") || cls.startsWith("kotlinx.")
                || cls.startsWith("io.github.libxposed")
                || cls.contains("melodylink")
                || cls.contains("lspd") || cls.contains("xposed")
                || cls.contains("Proxy") || cls.contains("$$")) {
            return false;
        }
        return true;
    }

    /** Collapses repeated hide call sites so one culprit yields one line. */
    private static final java.util.Set<String> hideCallSites =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** 0.5.78 — monotonic sequence for DecorView INVISIBLE events (the blank-page trigger). */
    private static final java.util.concurrent.atomic.AtomicInteger hideSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Walks to the view that would host the detail sections and describes it in depth.
     *
     * <p>0.5.47. The container's subtree is a NestedScrollView with a LinearLayout holding
     * two children. The first is the device-info header (model view, status views). The
     * second is where the configurable sections go, and it has been empty every time. This
     * reports its class, id, size, visibility and a deep listing of what it contains, so we
     * can tell "never built" apart from "built and then hidden".
     */
    private static String dumpSectionHost(View view) {
        try {
            if (!(view instanceof ViewGroup)) return "not_a_group";
            ViewGroup group = (ViewGroup) view;
            // Descend to the first NestedScrollView we find.
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getName();
                if (name.contains("NestedScrollView")) {
                    if (!(child instanceof ViewGroup)) break;
                    ViewGroup scroll = (ViewGroup) child;
                    StringBuilder sb = new StringBuilder();
                    sb.append("scroll[").append(scroll.getChildCount()).append("] ");
                    for (int j = 0; j < scroll.getChildCount(); j++) {
                        View inner = scroll.getChildAt(j);
                        if (inner == null) continue;
                        sb.append("host").append(j).append('=')
                          .append(idName(inner)).append('(')
                          .append(inner.getClass().getSimpleName()).append(')')
                          .append('[').append(inner.getWidth())
                          .append('x').append(inner.getHeight())
                          .append(" vis=").append(inner.getVisibility()).append(']');
                        if (inner instanceof ViewGroup) {
                            ViewGroup hg = (ViewGroup) inner;
                            sb.append('{').append(hg.getChildCount()).append(" kids: ");
                            for (int k = 0; k < hg.getChildCount() && k < 10; k++) {
                                View kid = hg.getChildAt(k);
                                if (kid == null) continue;
                                sb.append(kid.getClass().getSimpleName());
                                if (kid.getId() != View.NO_ID) sb.append('#').append(idName(kid));
                                sb.append('[').append(kid.getWidth()).append('x')
                                  .append(kid.getHeight()).append(" vis=")
                                  .append(kid.getVisibility()).append("] ");
                            }
                            sb.append('}');
                        }
                        sb.append(" | ");
                    }
                    return sb.toString();
                }
                // keep descending one level in case the scroll view is nested deeper
                String deeper = dumpSectionHost(child);
                if (deeper != null && !"not_a_group".equals(deeper) && !deeper.isEmpty()) {
                    return deeper;
                }
            }
            return "";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** Resource entry name for a view id, or the raw id when unresolvable. */
    private static String idName(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable t) {
            return "id" + view.getId();
        }
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


    /**
     * Melody 17.6.3 keeps battery levels on EarphoneDTO (leftBattery/rightBattery/
     * boxBattery + isBatteryInfoReceived) instead of the old per-address V map, so
     * publish through the DTO build hook.
     */
    private void projectBoseBatteryIntoDto(Object address, Object dto) {
        if (targetBoseDevice == null || !boseHostConnected) return;
        if (!isTargetAddress(address) || dto == null) return;
        // 2.0.13: the DTO's `connectionState` field holds the host's REAL Bluetooth
        // state (2 = connected; the same value EarphoneControlProvider compares against).
        // Our dtoConnectionState hook fakes 2 toward getters, but the field keeps host
        // truth — and EarphoneDTO is an immutable data class, so once written our battery
        // values survive through later copy() rebuilds. The header renders the numbers
        // unconditionally, which is how a disconnected device still showed 电量: project
        // only when the host itself sees the link up, and actively clear otherwise.
        int hostConnectionState = readIntField(dto, "connectionState", -1);
        if (hostConnectionState != 2) {
            // writeIntField cannot distinguish "changed" from "already 0", and the DTO
            // rebuild fires often — only touch fields (and log) when something is stale.
            boolean stale = readIntField(dto, "leftBattery", 0) != 0
                    || readIntField(dto, "rightBattery", 0) != 0
                    || readIntField(dto, "boxBattery", 0) != 0;
            Object received = readField(dto, "isBatteryInfoReceived");
            stale |= Boolean.TRUE.equals(received);
            if (stale) {
                writeIntField(dto, "leftBattery", 0);
                writeIntField(dto, "rightBattery", 0);
                writeIntField(dto, "boxBattery", 0);
                writeBooleanField(dto, "isBatteryInfoReceived", false);
                log(Log.INFO, TAG, event("cleared Bose battery from disconnected device DTO"
                        + " connectionState=" + hostConnectionState));
            }
            return;
        }
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

    private static int readIntField(Object object, String fieldName, int fallback) {
        Object value = readField(object, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
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
