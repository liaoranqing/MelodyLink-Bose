# MelodyLink-Bose 项目交接文档（HANDOFF）

> **写给接手的下一个 AI / 开发者。**
> 生成时间：2026-10-05 12:47 · 当前版本 **2.0.0 / versionCode 200**（Bose 专用精简版）· 1.0.0 已发布 GitHub Release；1.0.1/2.0.0 待推送。  
> 这份文档是**唯一权威交接入口**，接手后先通读全文，再动手。配套文档在仓库 `docs/` 目录与本地 `.workbuddy/memory/` 日志。

---

## 0. 一页速览（30 秒上手）

- **项目是什么**：一个 LSPosed 模块，让 Bose QC Ultra Earbuds 2 在 OPPO/一加的耳机管理应用 Melody（`com.oplus.melody` 17.6.3）里原生工作（识别、产品图、降噪三态、CNC、电量）。
- **当前状态**：三个历史 bug（#1 详情页闪屏 / #2 通用设置无图 / #4 Enco 降噪残留）**全部修复并实机验证**，已发布 1.0.0 正式版。
- **唯一遗留**：通用设置页的「降噪等级」滑条（`cnc.onespace.skip reason=no_tree`），低优先级，用户未报，见 §8。
- **最重要的 3 件事**：
  1. 所有 Hook 在 `hook/HookModule.java`（~8000 行），按 `label` 统一分发。
  2. 宿主有**两套设备目录**（`L6/a` 按 MAC、`c9/a` 按 productId+name），查错目录 = 白干。
  3. 进程有**主进程 + `:fg` 两个进程**，跨进程状态不能用内存字段。
- **文档体系**：README + docs/（ARCHITECTURE / REVERSING / TROUBLESHOOTING / PORTING / TOOLS）已在 GitHub 上。

---

## 1. 协作铁律（违反过、被纠正过，绝不能再犯）

1. **不本地编译 APK**：本机无 Android SDK/Gradle，构建全走 GitHub Actions（`.github/workflows/build.yml`）。本地只改代码 + 静态检查 + 提交。
2. **严禁 adb reboot / 任何重启手机**：用户 OPPO Find X8 Ultra 是**临时 root**（漏洞提权，重启即失效且极难重新获取）。诊断只用 force-stop、读日志、查数据库。
3. **每次改动递增版本号**：`app/build.gradle.kts` 的 `versionCode` + `versionName`。
4. **push 走 GitHub API（不是 git push）**：`github.com` 直连和沙箱代理都被墙（502），只有 `api.github.com` 稳定可达（200）。所以 push 必须用 **Git Data API**（见 §9.3），不能用 `git push`。这是 2026-10-05 新确立的方式，取代旧的「用户手动 push」铁律。
5. **不要猜契约**：用户多次因猜测翻车。正确节奏 = dexdump/日志取证 → 小步修复 → 用户实测 → 迭代。交付时写清楚版本号、待推送提交、验证清单。
6. CI 失败时用户会下载 Actions 日志 zip → 解压看构建步骤日志。

---

## 2. 项目背景与两条版本线

- **A 版**（已废弃）：独立模块 `com.tosasitill.bosemelody`，目录 `../Melody.Az100_Impl`，自绘控制面板（用户嫌丑）。其 provider hook 与 BMAP 编解码已移植进 B 版。**不要再改 A 版。**
- **B 版**（主线，本仓库）：`MelodyLink-Bose`，基于上游 [jerry-2009/MelodyLink](https://github.com/jerry-2009/MelodyLink)（Kotlin/Compose + libxposed，原作者只适配到 Melody 16.8.3），目标让 Bose **原生融入** Melody 17.6.3。
- 上游停在 2026-08-11，**没有 17.6.3 适配**，全部混淆重定位是我们自己做的（§6）。

### 关键设备/协议参数（A 版实机验证，沿用至今）

- Bose MAC：`68:F2:1F:3D:41:D7`（`BoseDeviceConfig.KNOWN_MACS`，蓝牙名是用户改的中文「镇海司皓色深寂冑」，**识别必须靠 MAC 不靠名**）
- 用户的**真 OPPO Enco X3 MAC = `40:72:18:C7:75:70`**，绝不能误判成 Bose（正向判据，白名单式守卫）
- BMAP：RFCOMM **channel 2**、必须 `createInsecureRfcommSocket(2)`、codename `edith`、Product ID `0x4062`
- ColorOS 磁贴模式：关闭=1、通透=2、降噪=5
- BMAP `[31.10]` 5 字节 `[cnc(0-10), autoCNC, spatial, reserved, anc]`；模式切换 `[31.3]` OP_START；电量 `[2.2]`

---

## 3. 架构地图（app/src/main/java/com/melody/melodylink/）

| 文件 | 职责 |
|---|---|
| `hook/HookModule.java`（~8000 行，**核心**） | 所有 hook 注册 + 按 label 分发：设备伪装、catalog 注入、产品图、ANC 路由、Preference 注入、CNC 滑条 |
| `hook/PrefRef.java` | Preference 树反射工具（findPreferenceRecursive / getOrder / shiftOrders） |
| `hook/MLog.java` | 统一日志，`MLog.event("evt=...", "k", v)` 事件 key 化，便于 grep |
| `hook/BoseControlProviderBridge.java` | EarphoneControlProvider 的 query/call hook（音量面板磁贴） |
| `hook/MelodySharedStateStore.java` | 跨进程文件通道（主进程 ↔ :fg） |
| `hook/MelodyStateBridge.kt` | AncMode→Melody 索引映射 |
| `bose/BoseTransport.java` | BMAP 短会话 RFCOMM |
| `bose/BoseBmap.java` | 帧编解码（从 A 版移植，勿动） |
| `bose/BoseDeviceConfig.kt` | MAC 白名单、模式常量、capabilities |
| `vendor/*/`、`sony/ huawei/ xiaomi/ samsung/` | 各品牌适配层（移植模板，声明式 JSON 驱动） |

---

## 4. 进程模型（最重要的坑）

`com.oplus.melody` 有**主进程**和 **`:fg`** 前台进程：

- **Provider / 蓝牙会话在主进程**；**详情页 PreferenceFragment 绑定在 :fg**。
- 一切跨进程判断**不能用内存字段**，必须走 bond 探测或共享文件（`MelodySharedStateStore`）。
- 早期很多「注入成功但详情页不显示」的问题，根源就是判断逻辑跑在了错误进程。

---

## 5. 伪装机制 + 两套 catalog（#4 的核心教训）

Bose 被注册为 **Enco X3（productId 0x67410=422928）** 让 Melody 原生 UI 渲染，状态读写 hook 里拦截转发到 BMAP。

**Melody 17.6.3 有两套独立设备目录**，这是本项目踩过最大的坑：

| 类 | 角色 | 查询键 |
|---|---|---|
| `L6/a` | SupportConfigManager | MAC 地址 |
| `c9/a` | WhitelistRepository | productId + 蓝牙名 |

早期只在 `L6/a` 注入克隆条目，却发现 Bose 详情页根本不走这条路——详情页实际走 `c9/a.c(productId, name)`。**#4（Enco 降噪残留）的真正修复是 0.5.74 在 `detail.lookup_stripped` 里按 Bose MAC 正向判据剥离子级**，而不是 0.5.75 的 `Ba/z` getter hook（那是死代码，`bose.vo.*` 事件 0 次）。

---

## 6. 17.6.3 混淆重定位表（dexdump 实证，合法干货）

> 这是逆向工程的事实信息（hook 点对照），不含 OPPO 代码，可安全发布。**注意：Melody 反编译 smali 源码、APK、dex、dexdump 全文都属于 OPPO 版权物，不能上传 GitHub。**

| 功能 | 16.8.3 | 17.6.3 | 证据 |
|---|---|---|---|
| whitelist | common.util.V.a | **T.a**(Collection,String,String)→WhitelistConfigDTO | 签名唯一命中 |
| nativeConnectDevice | E7.c.b | **e7.c.c** | 日志 getDeviceOrCreateDevice |
| directConnectSpp | E7.c.a | **e7.c.a**(DeviceInfo,Z) | 日志 m_spp_le.directConnectSpp |
| repositoryGet | U.y | **J.y**(S)→EarphoneDTO | — |
| repositoryObserve | U.z | **J.A**(S)→LiveData（J.z 是 getEarphoneIdentity，别认错） | 方法体 const-string |
| detailInfo 三件套 | v9.C1576a | **G9.a**(getConnectionState/getHeadsetConnectionState/getIsSpp) | 结构一致 |
| noiseReductionModeVO | pa.C1405p | **Ba.z**.getCurrentNoiseReductionModeIndex | — |
| noiseModeWrite | U.s0 | **J.v0**(I,S)→CF | 实测生效 |
| 电量状态类 | V$a | **earphone.K$a** | 异常栈实锤 |
| ANC 结果 DTO | SetCommandStateDTO | **earphone.O**(address,setCommandStatus) | — |

**方法论**：`hookNamed` 返回 boolean + `hookAny(label, "类#方法#参数个数", ...)` 多候选绑定，旧名优先新名兜底。定位手段 = 官方 `dexdump.exe -d`（build-tools 37.0.0）+ 方法体 const-string 日志比对。工作区已有现成产物：`dexdump1.txt`/`dexdump2.txt`（17.6.3 全量反汇编，640 万行）、`dexquery.py`、`melody1763.zip`。**不要再手写 DEX 解析器。**

---

## 7. 已解决结论（防止回退）

**三个核心 bug 的最终真相：**

| # | 现象 | 真凶 | 修复 |
|---|---|---|---|
| #4 | 详情页有 Enco 四级降噪+增强人声 | 克隆条目 `instanceof Parcelable continue` 静默丢弃 Function + 两套 catalog 查错 | 0.5.74 删 Parcelable 跳过 + `detail.lookup_stripped` 正向判据剥离 |
| #2 | 通用设置无耳机图 | **我们自己的 `hideLoadingView` 无条件 setVisibility(GONE) 误伤容器** | 0.5.78 加类型判断（只隐藏 Lottie/Progress/Loading/Spin） |
| #1 | 详情页闪→空白→消失 | 第一阶段=finish 拦截制造多实例互杀；第二阶段=DecorView INVISIBLE 是系统 resume 正常中间态 | 0.5.74 回滚 finish 拦截；确认非 bug |

**其他已确认结论：**
- 磁贴显示前置：SystemUI 锁存 `notifyChange(baseUri, 0x500|1)`，每次查询都要确保发过。
- ancModeIndex：OFF→0、NOISE_CANCELING→1、TRANSPARENCY/AMBIENT→**2**（写 3 会越界钳回 0）。
- 电量：投影进 EarphoneDTO 字段（leftBattery/rightBattery/boxBattery/isBatteryInfoReceived），已实测 ✅。
- RFCOMM 交替失败：会话关闭后立即重开会 `read ret: -1`，需 600ms settle + 相同目标去重。

---

## 8. 当前待办（按优先级）

1. **CNC 通用设置滑条**（低优先级，唯一遗留）：`cnc.onespace.skip reason=no_tree` —— 通用设置页降噪等级滑条没挂载，`findOneSpacePreferenceTree` 遍历 fragment 找含 `pref_noise_switch` 的 screen 返回 null。详情页降噪控制正常，用户未报。**不主动改**（铁律：一次只引入一个变量）。
2. 若用户后续要求：Sony WF-1000XM5 等具体机型的「移植实操」已写了通用框架（docs/PORTING.md），可按需展开。

---

## 9. 诊断工具箱（全部本机可用）

### 9.1 设备与日志

- adb：`platform-tools/adb.exe`
- 模块日志：`adb logcat -c` → 用户手动打开 Melody → `adb logcat -d | grep melodylink > logs.txt`
- **先看事件分布**：`grep -oE "evt=[a-z_.]+" logs.txt | sort | uniq -c`
- ANR/闪退取证：`adb shell dumpsys dropbox --print | grep data_app_anr`（比 logcat 保留更久）
- ColorOS 下 monkey/resolve-activity 拉不起 Melody，必须用户手动点开。

### 9.2 反编译工具

- baksmali：`tools/jre/jdk-17.0.20.1+1-jre/bin/java.exe -jar tools/bsm.jar d classes.dex -o smali_out/`
- androguard：`C:/Users/liaoran/.workbuddy/binaries/python/envs/default/Scripts/python.exe`（`AXMLPrinter(data).get_xml()` 解析布局，需 `logging.disable(logging.CRITICAL)`）
- 资源 ID 反查：`tools/find_resid.py`（名字→ID）、`tools/find_resid_rev.py`（ID→名字），已参数化到仓库 `tools/`，用环境变量 `MELODY_APK` 指定 APK。

### 9.3 GitHub 发布（github.com 被墙，用 API）

- `github.com` 直连/代理均 502；`api.github.com` 稳定 200。
- **push 用 Git Data API**：GET commit→tree sha → POST blobs → POST trees(base_tree+变更) → POST commits(parent=head) → PATCH refs/heads/main。
- **下载 CI artifact**：zip 303 重定向到 Azure blob（直连卡），用 `curl -C -` 断点续传 + `--connect-timeout 8` + 循环重试。
- **上传 Release asset**：POST uploads.github.com（可达），Content-Type `application/vnd.android.package-archive`。
- 用户给的 GitHub token 用完应提醒撤销。

---

## 10. 文档体系索引（都在 GitHub remote）

| 文件 | 内容 |
|---|---|
| `README.md` | 主入口：背景/功能/原理/环境/快速开始/目录结构/依赖/文档索引/排查 |
| `docs/ARCHITECTURE.md` | 设计思路 + 技术路径 + BMAP 协议附录 |
| `docs/REVERSING.md` | 反编译方法（apktool/baksmali/androguard/资源 ID） |
| `docs/TROUBLESHOOTING.md` | 5 个 bug 案例 + 13 条排查铁律 + 日志实操 |
| `docs/PORTING.md` | 移植实操（声明式 JSON + 代码式） |
| `docs/TOOLS.md` | 工具清单 |
| `tools/` | find_resid.py / find_resid_rev.py / find_hide_calls.py |

---

## 11. 提交时间线（B 版关键节点）

```
2.0.0 (versionCode 200)   Bose 专用精简版：删除 Sony/Huawei/Xiaomi/Samsung 全部适配层/assets/Compose UI（-8200 行）；
                          修 3D 模型（根因=Bose 会话 colorId=-1，hook EarphoneDTO.getColorId()→3 借用 X3 color 3 资源包）；
                          修通用设置转圈（根因=onShowAnimationEnd 重播 Lottie，透明图暴露；hook 杀掉）
1.0.1 (versionCode 106)   详情页 3D 模型移植：hook b(String) 换内置 Bose glb（Filament/gltfio）+ 产品图抠透明背景（v3）
1.0.0 (versionCode 105)   正式版发布：README+docs+tools 文档体系、Release v1.0.0
0.5.78 (104)              #2 真修复 hideLoadingView 类型判断 + #1 DecorView raw 栈
0.5.77 (103)              finishAfterTransition 区分 decision/transition_callback
0.5.76 (102)              finish raw 栈 + finishAfterTransition hook
0.5.75 (101)              Ba/z getter hook（死代码，未生效）
0.5.74 (100)              finish 回滚纯取证 + detail.lookup_stripped 正向判据（#4 真正修复）
0.5.73 (99)               cloneCatalogEntry 删 Parcelable 跳过 + 头图 + finish 拦截(误判)
0.4.2 及更早              见旧 HANDOFF.md 的 §7 与 .workbuddy/memory/2026-10-01~03.md
```

---

## 12. 用户话术偏好

- 中文、直接、要「日志实锤」不要猜。
- 反复强调「省 token」「快动手」「你来决定」——但决定前必须基于证据，不能凭假设。
- 版本号逐次递增，交付时清楚写版本号、待推送提交、验证清单。
- 用户会通过截图指出问题，要求「修复前先全面诊断确认唯一异常、修复后呈现校验结果」。

---

## 13. 排查铁律（31 条浓缩，全文见 .workbuddy/memory/MEMORY.md）

1. 早期反常数据必须当真；未验证的假设不能当根因。
2. 同一方向连续 3 轮无进展 = 方向错，换观察维度。
3. hook 系统 API 抓调用栈 > 读混淆代码猜逻辑。
4. 「写进去」≠「看得见」；成功事件要回读校验。
5. R8 重命名方法名但保留类型/字段名，反射先看 smali 真实签名。
6. 破坏性宿主操作（setVisible/GONE/remove）必须白名单守卫 + 自问「最坏会隐藏什么」。
7. 诊断 hook 本身会成为故障源，热路径必须去重/采样。
8. hook 注册成功 ≠ 会被调用，零触发先怀疑「没挂上」或「入口不在这」。
9. 区分「没触发」与「触发后无效」，先查事件计数。
10. 跨版本混淆名会变，抄来的类名必须重新验证。
11. 分阶段修复一次只引入一个变量。
12. 日志矛盾当根因线索（ok=true + 无 injected + 无 add_failed ⇒ 锁定短路点）。
13. 「宿主把我容器 GONE 了」可能是「我自己 GONE 的」。

---

## 附录：本地工作区状态说明

- 本地 git 仓库（`MelodyLink-Bose/`）当前停在旧 commit `5d033f6`，**落后 remote 3 个提交**（1.0.0 + 文档）。因为 github.com 被墙，本地无法 `git fetch`，同步只能靠用户 GitHub Desktop **Pull origin**。
- remote main 最新 SHA = `33182eaa`，本地落后，接手后**先让用户 pull 一次**再动代码。
- 反编译产物（smali/dex/dexdump）在仓库外的工作区根目录（`tools/smali*`、`melody1763/`、`dex1763/`、`dexdump*.txt`），**不提交、不上传**（版权）。
