# MelodyLink-Bose 项目交接文档（HANDOFF）

> **写给接手的下一个 AI / 开发者。**>   
> 生成时间：2026-10-05 19:50（前任 workbuddy 模型）· **2026-10-06 00:10 由 Qoder 更新** · 当前版本 **2.0.12 / versionCode 212** · HEAD=origin/main=`ff50c2f`（CI run 37337125325 success）**设备仍装 2.0.11**。模型已修好（2.0.6）；bug1 耳机设置侧已由 2.0.11 修好并经用户确认，通用设置侧由 2.0.12 修复待装机验证。实时状态与下一步判定见 `opslog/STATUS.md`。>   
> 这份文档是**技术权威交接入口**，接手后先通读全文，再动手。**接管流程/日志规范先看工作区根目录 `ONCALL-PROTOCOL.md` 与 `opslog/STATUS.md`**（当前状态快照以 STATUS.md 为准）。>   
> **交接状态**：前任 workbuddy 模型 token 耗尽，2026-10-05 起由 Qoder 模型接管；2026-10-06 00:40 Qoder 完成阶段性交接（bug1 两页全部结案）。**完整自包含交接全书见 `docs/HANDOVER-2026-10-06.md`**（背景/环境/选型理由/架构/文件结构/版本时间线/禁忌/方法论/移植指南/下一步）。遗留 bug2(内容偶发消失，需现场复现取证，任务 #9)；模型入场动画略卡（#8，用户明确后置）。最高优先级见 §8。

---

## 0. 一页速览（30 秒上手）

- **项目是什么**：一个 LSPosed 模块，让 Bose QC Ultra Earbuds 2 在 OPPO/一加的耳机管理应用 Melody（`com.oplus.melody` 17.6.3）里原生工作（识别、产品图、降噪三态、CNC、电量）。
- **当前状态**：2.0.0 起为 Bose 专用精简版（删光 Sony/Huawei/Xiaomi/Samsung 适配层、Compose UI、约 8200 行）。1.0.0 已发布；1.0.1→2.0.1 已 push（origin/main=`929ccad`）；**2.0.2（`8db6c45`）本地领先 1 提交，未 push**。
- **最优先未完成事项（2.0.2 待复验）**：2.0.1 已装机、3D 模型可出（vfxms 方案有效）。用户实测遗留三瑕疵，2.0.2 已修：详情页照片闪现（提前淡出）、模型偏小、过曝。详见 §8。
- **最重要的 5 件事**（比旧版多 2 条，都是血的教训）：
  1. 所有 Hook 在 `hook/HookModule.java`（~8000 行），按 `label` 统一分发。
  2. 宿主有**两套设备目录**（`L6/a` 按 MAC、`c9/a` 按 productId+name），查错目录 = 白干。
  3. 进程有**主进程 + `:fg` 两个进程**，跨进程状态不能用内存字段。
  4. **宿主模型格式是 `.vfxms` 容器，不是裸 glb**（2.0.0 取证）：`ModelScene.loadSceneFromBuffer` → `Head.read` 解析 10 个大端 u32（configStart/Length、glbStart/Length、iblStart/Length、skyboxStart/Length、animationStart/Length）→ 之后是 JSON 场景配置 + glb + KTX IBL + KTX skybox。裸 glb 会让 "glTF" magic 被读成 configStart(≈17亿)，配置读取越界，被 try/catch 静默吞掉 → 空白页。参考文件已拉到本地 `bose3d/detail_model.vfxms`（X3 的），打包脚本 `bose3d/make_vfxms2.py`。
  5. **Bose 会话 colorId 必须伪装成 3**（2.0.0 修复）：宿主按 (productId, colorId) 索引资源包，Bose 默认 colorId=-1 查不到任何资源 → detailSource=null → 3D/图片全不走。hook `EarphoneDTO.getColorId()` 对 Bose MAC 返回 3。副作用：宿主会第一次拿到 X3 全套资源并主动渲染 X3，我们的替换必须全部就绪才能盖住它。
- **文档体系**：README + docs/（ARCHITECTURE / REVERSING / TROUBLESHOOTING / PORTING / TOOLS）已在 GitHub 上。

---

## 1. 协作铁律（违反过、被纠正过，绝不能再犯）

1. **不本地编译 APK**：本机无 Android SDK/Gradle，构建全走 GitHub Actions（`.github/workflows/build.yml`）。本地只改代码 + 静态检查 + 提交。
2. **严禁 adb reboot / 任何重启手机**：用户 OPPO Find X8 Ultra 是**临时 root**（漏洞提权，重启即失效且极难重新获取）。诊断只用 force-stop、读日志、查数据库。
3. **每次改动递增版本号**：`app/build.gradle.kts` 的 `versionCode` + `versionName`。
4. **push 靠用户 GitHub Desktop**：本仓库已加进 GitHub Desktop（D 盘路径）。`github.com` 直连被墙，但 GitHub Desktop 内部走自己的代理能 push（本次 1.0.1/2.0.0 都推成功）。命令行 `git push` 会失败。若用户不在，才退回 Git Data API（api.github.com 可达，见 §9.3）。
5. **不要猜契约**：用户多次因猜测翻车。正确节奏 = dexdump/日志取证 → 小步修复 → 用户实测 → 迭代。交付时写清楚版本号、待推送提交、验证清单。
6. CI 失败时用户会下载 Actions 日志 zip → 解压看构建步骤日志。
7. **源码文件严禁用 Windows PowerShell 的 Get-Content/Set-Content 编辑**：5.1 按 GBK 读 UTF-8 中文文件再写回会①全部中文注释变乱码 ②吞换行，甚至把代码挤进注释（曾致 CI `cannot find symbol`）。读用 Edit/Read 工具或 python utf-8 读，写用 Edit/Write 工具或 python `io.open(...,encoding='utf-8')`。PowerShell 只用来做无害的查询（行数、Select-String 只读）。
8. **删代码后必须验证"顺手重试/维护状态"的隐性依赖还在**：2.0.0 剥离 whitelist 分支的 `isRegisteredSonyName()` 时，顺带删掉了它作为 initializeSonyConfig 重试者的副作用，导致 onPackageReady 初始化失败后再无人重试、所有资产不可用。删任何调用点前先问"这个调用点保障了什么状态"。
9. **剥离/批量删除用脚本时**：多行字段（匿名监听器）和注册语句（多行 hookAny）要单独处理；正则删分支容易误切内层 `}` 造成失衡；删完跑 `static_check.py` + 残留引用扫描（`bose3d/dangling_refs.py`）+ 乱码/符号检查（`bose3d/final_verify.py`）三道闸。
10. **详情页 3D 模型黑屏 ≠ 照片可见性问题（曾误判，已纠正）**：2.0.3~2.0.5 模型黑屏的**真凶是 v4 资产的 glb BIN chunk 损坏**（make_vfxms4.py 从 bin 数据内部读 binlen、且漏拷 8 字节 BIN 头 → glTF 解析失败 → TextureView 在但不画）。我一度误判为"模型渲染依赖照片 field d 处于 VISIBLE"并据此改了 GONE/INVISIBLE/alpha，全部无效。**教训：模型黑屏先字节级校验 .vfxms 容器**（10 个大端 u32 偏移、glb 的 BIN chunk 头 type 必须 =0x4E4942、glb 切片长度必须等于头声明值），用 `python` 逐字段比对一个已知好的旧资产，再怀疑 hook/视图。视图层级可用 `adb shell uiautomator dump`（需 `MSYS_NO_PATHCONV=1` 防 git-bash 路径转换）确认 TextureView 是否在、尺寸是否正常——在且尺寸正常就说明问题在资产/加载，不在视图可见性。
11. **蓝牙"连接"判据必须绑定具体 profile，且 Melody 的"断开"必须应用层锁存（2.0.13~2.0.20 血泪链）**：GATT_SERVER（断开后 BLE 常驻）、隐藏 `BluetoothDevice.getConnectionState()`（ACL 常驻）都把"用户已断开"误报成连接，先后被 0590/0591/0592 日志证伪；唯一可信正源 = A2DP/HEADSET profile proxy 的按设备列表（`getProfileProxy` 绑定，新绑 proxy 前 4s 列表为空需信任窗）。但 TWS 断开后 ~12s 自动回连，**任何探测最终都会说"已连接"**——Melody 的"断开连接"是应用层动作，必须应用层锁存（`.melodylink_bose_user_disconnect` 持久化文件），CONNECTED 广播 ≥60s 才清除。proxy 刚绑定时 `getConnectedDevices` 也可能短暂为空，判定要 fail-open。
12. **"静默 return"必须留痕（2.0.19 教训）**：环境性失败（currentApplication()==null、广播注册失败等）只 return 不打日志，整个特性静默失效且装机日志"看起来一切正常"，排查耗一轮装机。一次性诊断日志 + 失败可重试 + 失败必留痕，三者缺一不可。另外：泛型/类型类编译错误 static_check 抓不到（只查括号与 null 比较），改泛型签名后 CI 是唯一闸。

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

| 文件                                           | 职责                                                                      |
| -------------------------------------------- | ----------------------------------------------------------------------- |
| `hook/HookModule.java`（~8000 行，**核心**）       | 所有 hook 注册 + 按 label 分发：设备伪装、catalog 注入、产品图、ANC 路由、Preference 注入、CNC 滑条 |
| `hook/PrefRef.java`                          | Preference 树反射工具（findPreferenceRecursive / getOrder / shiftOrders）      |
| `hook/MLog.java`                             | 统一日志，`MLog.event("evt=...", "k", v)` 事件 key 化，便于 grep                   |
| `hook/BoseControlProviderBridge.java`        | EarphoneControlProvider 的 query/call hook（音量面板磁贴）                       |
| `hook/MelodySharedStateStore.java`           | 跨进程文件通道（主进程 ↔ :fg）                                                      |
| `hook/MelodyStateBridge.kt`                  | AncMode→Melody 索引映射                                                     |
| `bose/BoseTransport.java`                    | BMAP 短会话 RFCOMM                                                         |
| `bose/BoseBmap.java`                         | 帧编解码（从 A 版移植，勿动）                                                        |
| `bose/BoseDeviceConfig.kt`                   | MAC 白名单、模式常量、capabilities                                               |
| `vendor/*/`、`sony/ huawei/ xiaomi/ samsung/` | 各品牌适配层（移植模板，声明式 JSON 驱动）                                                |

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

| 类      | 角色                   | 查询键             |
| ------ | -------------------- | --------------- |
| `L6/a` | SupportConfigManager | MAC 地址          |
| `c9/a` | WhitelistRepository  | productId + 蓝牙名 |

早期只在 `L6/a` 注入克隆条目，却发现 Bose 详情页根本不走这条路——详情页实际走 `c9/a.c(productId, name)`。**#4（Enco 降噪残留）的真正修复是 0.5.74 在 `detail.lookup_stripped` 里按 Bose MAC 正向判据剥离子级**，而不是 0.5.75 的 `Ba/z` getter hook（那是死代码，`bose.vo.*` 事件 0 次）。

---

## 6. 17.6.3 混淆重定位表（dexdump 实证，合法干货）

> 这是逆向工程的事实信息（hook 点对照），不含 OPPO 代码，可安全发布。**注意：Melody 反编译 smali 源码、APK、dex、dexdump 全文都属于 OPPO 版权物，不能上传 GitHub。**

| 功能                   | 16.8.3             | 17.6.3                                                          | 证据                           |
| -------------------- | ------------------ | --------------------------------------------------------------- | ---------------------------- |
| whitelist            | common.util.V.a    | **T.a**(Collection,String,String)→WhitelistConfigDTO            | 签名唯一命中                       |
| nativeConnectDevice  | E7.c.b             | **e7.c.c**                                                      | 日志 getDeviceOrCreateDevice   |
| directConnectSpp     | E7.c.a             | **e7.c.a**(DeviceInfo,Z)                                        | 日志 m_spp_le.directConnectSpp |
| repositoryGet        | U.y                | **J.y**(S)→EarphoneDTO                                          | —                            |
| repositoryObserve    | U.z                | **J.A**(S)→LiveData（J.z 是 getEarphoneIdentity，别认错）              | 方法体 const-string             |
| detailInfo 三件套       | v9.C1576a          | **G9.a**(getConnectionState/getHeadsetConnectionState/getIsSpp) | 结构一致                         |
| noiseReductionModeVO | pa.C1405p          | **Ba.z**.getCurrentNoiseReductionModeIndex                      | —                            |
| noiseModeWrite       | U.s0               | **J.v0**(I,S)→CF                                                | 实测生效                         |
| 电量状态类                | V$a                | **earphone.K$a**                                                | 异常栈实锤                        |
| ANC 结果 DTO           | SetCommandStateDTO | **earphone.O**(address,setCommandStatus)                        | —                            |

**方法论**：`hookNamed` 返回 boolean + `hookAny(label, "类#方法#参数个数", ...)` 多候选绑定，旧名优先新名兜底。定位手段 = 官方 `dexdump.exe -d`（build-tools 37.0.0）+ 方法体 const-string 日志比对。工作区已有现成产物：`dexdump1.txt`/`dexdump2.txt`（17.6.3 全量反汇编，640 万行）、`dexquery.py`、`melody1763.zip`。**不要再手写 DEX 解析器。**

---

## 7. 已解决结论（防止回退）

**三个核心 bug 的最终真相：**

| #  | 现象                  | 真凶                                                                  | 修复                                                       |
| -- | ------------------- | ------------------------------------------------------------------- | -------------------------------------------------------- |
| #4 | 详情页有 Enco 四级降噪+增强人声 | 克隆条目 `instanceof Parcelable continue` 静默丢弃 Function + 两套 catalog 查错 | 0.5.74 删 Parcelable 跳过 + `detail.lookup_stripped` 正向判据剥离 |
| #2 | 通用设置无耳机图            | **我们自己的 `hideLoadingView` 无条件 setVisibility(GONE) 误伤容器**            | 0.5.78 加类型判断（只隐藏 Lottie/Progress/Loading/Spin）           |
| #1 | 详情页闪→空白→消失          | 第一阶段=finish 拦截制造多实例互杀；第二阶段=DecorView INVISIBLE 是系统 resume 正常中间态     | 0.5.74 回滚 finish 拦截；确认非 bug                              |

**其他已确认结论：**

- 磁贴显示前置：SystemUI 锁存 `notifyChange(baseUri, 0x500|1)`，每次查询都要确保发过。
- ancModeIndex：OFF→0、NOISE_CANCELING→1、TRANSPARENCY/AMBIENT→**2**（写 3 会越界钳回 0）。
- 电量：投影进 EarphoneDTO 字段（leftBattery/rightBattery/boxBattery/isBatteryInfoReceived），已实测 ✅。
- RFCOMM 交替失败：会话关闭后立即重开会 `read ret: -1`，需 600ms settle + 相同目标去重。

---

## 8. 当前待办（按优先级，2026-10-06 12:40 由 ZCode 更新）

### 8.0 🔴 最高优先 · 2.0.29 待做：注入行引用是「旧页面实例的幽灵对象」（已定性，含判据）

- **现象**（用户截图 19:37，装机 2.0.28）：耳机已连接（电量 50/L90/R90、三态=关闭、宿主的 空间音频/大师调音/耳机操控 都在），但**耳机设置里我们注入的 Bose 分组一个都没有**；退出重进就回来。
- **证据**（同窗口 logcat，逐条在案）：
  ```
  evt=bose.sections.visibility visible=true rows=14 touched=27
  evt=bose.inject.on_link_up ok=true
  evt=bose.inject.verified landed=true
  evt=bose.injected page=detail
  ```
  矛盾点：`rows=14 → touched=27`（14 行 + 13 张父卡片）**却对屏幕毫无影响**。
- **根因**：这 14 个引用属于**已销毁的旧页面实例**的 Preference 对象；而 `installBoseIntoLiveScreen()` 在新树里被 `isBoseInjected()` 判定"已注入"而短路 —— 既不重建行，也不把新页的行登记进我们持有的列表。于是隐藏/恢复/enable 门控全部作用在幽灵对象上。
- **修法（三步，缺一不可）**：
  1. 可见性与 `setEnabled` 门控**不再使用缓存字段**，改为按 key 从当前 live screen 现场解析（复用 `boseLiveScreen()` 与 `findPreferenceByKeyRecursive`）；
  2. 每次页面重建时**置换持有的行列表**：凡列表里的行 `getParent() == null` 即视为失效，清空后由 `add*Card` 重新登记；
  3. `isBoseInjected()` 的"已注入"判定必须绑定**当前树实例**（而不是"曾经注入过"），否则短路逻辑会一直骗过重建。

> **🔧 校正与根因下钻（2026-10-06 20:05，继任 Qoder 逐行实测 `HookModule.java` 7894 行；上面三步的方向不变，但两处细节不可用）**
>
> **幽灵树为什么清不掉（自我强化闭环，逐行实证）**：
> (a) `boseInjectedScreens`(2310) 虽是基于 WeakHashMap 的集合，但我们**强**引用着 `boseCncPreference`/`boseExtraCategory`/`boseEqSliders`/`boseButtonDropdowns`/`boseModeSlotSliders`，行 →`getParent()`→ 祖先链把已销毁页面的 screen 一直吊活，弱引用永不失效；
> (b) `boseLiveScreen()`(2054) 的存活判据只有 `getPreferenceCount>0`，**不看它属于哪个 resumed Activity**，于是把死 screen 当活的交出去；
> (c) `isBoseInjected()`(2030) 在这棵死树上查到 `BOSE_CNC_KEY` 即返回 true ⇒ `scheduleBoseInjection` 在 2000 行 `return`，**重建根本不跑**；
> (d) 2.0.26 的 down→up 边直调 `installBoseIntoLiveScreen()`（绕过该 guard，所以仍打回 `ok=true`），但 `pickLiveAnchor()`(2096) 只是在两个**旧** anchor 字段间二选一、无任何存活判据 ⇒ 2152 行在幽灵 parent 里 `findPreferenceRecursive(BOSE_CNC_KEY)!=null` 直接 `return true`。
> ⇒ 这就是 `on_link_up ok=true` + `inject.verified landed=true` + `sections.visibility rows=14 touched=27` 三者齐飞而界面毫无变化的完整解释。行的 `clear()` 只发生在各自动态建好处（6127/6143/6161），而这条路径在短路下永不执行 ⇒ **`rows=14` 恒定不变正是"列表从未换代"的指纹**。
>
> **对三步的更正**：
> ①第 1 步点名的 `findPreferenceByKeyRecursive` 返回 **boolean**（5954；4626 行注释自己写明"answers with a boolean, not a node"），取不到行对象。按 key 现场解析请改用 **`PrefRef.findPreferenceRecursive(screen, key)`**（返回 Object）或本地 **`findPreference(group, key)`**(6722)。
> ②第 2 步的 `getParent()==null` **对本例不成立**：脱离窗口但仍存活的 Preference 其 parent 指针依然在；且 §0.5.55 已证 `isAttachedToWindow()` 对 preference view 不可靠（详情页建行时恒为 false）。可用的存活判据是「anchor 所属 screen 是否等于**当前 resumed Activity** 的 screen」——现成范式在 `screenForAnchor()`(2262)：`anchor.getContext()`→Activity→fragment manager→`readFragmentList`(2287)→`PrefRef.getPreferenceScreen(fragment)`；注意它当前**只返回已含我们 key 的 screen**，需要一个「返回当前 live screen（没有我们的行也返回）」的变体，否则第 3 步的重建判定仍会落空。
> ③第 3 步落点具体化为：`isBoseInjected()` 与 `installBoseIntoLiveScreen()` 的幂等检查(2152)都必须以 **(b) 修正后的 live screen** 为查询根，二者共用同一个解析函数，避免又一处「两套树」。
>
> **补充时间戳警告**：本文档与 `opslog/` 里 2026-10-06 晚些 Entry 的小时标签比本机/设备真实时钟**快约 19 分钟**（自称"20:10"的文件实测 mtime 19:51；本机 `date` 与 `adb shell date` 一致）。日志排序以追加位置为准，引用旧 Entry 时间时须换算，**不要据此对齐 logcat 时间轴**。
- **可判定验收标准**：`evt=bose.sections.visibility` 必须同时满足 `rows>0 && touched>0` **且界面真的变化**；若 `rows=0` ⇒ 重建后没再登记，去查 `add*Card` 的登记时机；若 `touched>0` 但界面不变 ⇒ 解析到的仍不是当前页对象。
- **临时规避**：退出该页重进，或杀 Melody 重开。

### 8.1 其余排队项

1. 通用设置那条「降噪等级」是宿主的行、不吃我们的门控（`bose.cnc.onespace.skip reason=no_tree`、`onespace_row=null` 恒为 null）——与 8.0 同源（引用/查找失效），可在同一轮解决。
2. 清理死代码：`latchBoseUserDisconnectOnProbeDown()`（2.0.27 已停止调用，方法体仍在）。
3. 3D 模型入场动画卡顿（#8）。
4. bug2：Bose 内容偶发消失（#9，需现场复现取证）。


> 实时状态以 `opslog/STATUS.md` 为准。**2.0.20（`14544a8`）已由用户 push、CI success（2026-10-06 13:10 api 核实），当前唯一未结=设备仍装 2.0.19，待装机验证断开意图锁存机制（见 §1.11 与下方第 3 条清单）。**
>
> ⚠️ **上面这句已过期**（2026-10-06 20:05 继任 Qoder 实测校正）：设备实装 **2.0.28/228**（`su 0 dumpsys package com.melody.melodylink` → `versionCode=228 versionName=2.0.28`，与 HEAD `5f479bc` 一致）；断开意图锁存链已由用户复验结案——**假离线在 2.0.27 结案**（用户原话「这条修好了」），其根因正是 2.0.20 加的第二条锁存路径「探测瞬时 down 即用户断开」，2.0.27（`e763a42`）已删除该调用。当前唯一开口缺陷见本节开头 §8.0（任务 #12 → 2.0.29）。

1. **✅ 已结案（2.0.6，用户复验通过）：模型黑屏**。真凶=v4 资产 glb 的 BIN chunk 头损坏（make_vfxms4.py 偏移读错），不是照片可见性（红鲱鱼，§1.10 已纠正）。模型黑屏先字节级校验 .vfxms（10 个大端 u32、BIN type=0x4E4942），别动视图可见性。
2. **✅ 已结案（2026-10-06，用户确认）：bug1 ANC 三态图标不即时刷新**。详情页=2.0.11 点击时乐观镜像；通用设置=2.0.12 捕获 `OneSpaceNoisePreference$b.onChanged` 重绘。**关键教训：两页是两套宿主类、两条绘制路径，显示用 modeType(1/5/2)、写入用协议索引(0/1/2)，映射交回宿主 `l9/b.getCurrentNoiseMode()`，不能猜。**
3. **✅ 已结案（2.0.27，用户复验）→ 原文：🔴 最高优先（2.0.20 待验证）：断开状态显示链（2.0.13~2.0.20 系列）**。用户需求：点"断开连接"后内容消失+显示未连接，且重启 Melody 保持。已实锤的事实链（细节 opslog/2026-10.md 2.0.13~2.0.19 各 Entry）：
   - Melody 的"断开连接"只掉一次 profile，**TWS 耳机 ~12s 自动回连，系统蓝牙始终显示已连接**（dumpsys `active_a2dp_devices`）——任何蓝牙探测都无法区分；
   - 蓝牙探测正源只有 **A2DP/HEADSET profile proxy 的按设备列表**（GATT_SERVER、隐藏 `BluetoothDevice.getConnectionState()` 均被日志证伪，勿再启用）；
   - 解法=**应用层锁存断开意图**：A2DP/HFP `CONNECTION_STATE_CHANGED` 广播 DISCONNECTED → 持久化标志 `.melodylink_bose_user_disconnect`（跨重启）；CONNECTED 广播**仅在 ≥60s 后**清除（12s 自动回连不洗白）；`isBoseUiConnectedCached()`=探测&&未锁存，是全部 9 个消费点的唯一连接语义源；写路径的 `isSonyConnected()` 未动（bug1 依赖）。
   - **2.0.19 失败教训（§1.12）**：接收器注册时 `currentApplication()` 为 null（hook setup 早于 Application.attach）静默返回不重试 → 整机制失效且日志"看起来正常"。2.0.20 改为幂等重试（watcher 每拍）+ 第二锁存路径（可信探测 up→down 跳变直接落标志）。
   - 下一任验证清单：①启动日志必现 `registered Bose link broadcast latch`；②断开 → `Bose user-disconnect latch -> true`、内容消失；③12s 回连/重启 Melody 不恢复；④耳机重新开关机（≥60s CONNECTED）→ `Bose reconnect accepted` → 恢复。
4. **bug2：Bose 注入内容偶发消失**：无法稳定复现，挂起，需现场日志（禁止凭猜测改注入链）。
5. **模型入场动画流畅度**（#8，用户明确后置）。
6. **死诊断清理**：`evt=bose.anc.sweep ... changed=false` 不参与刷新路径，单独提交删除。
7. **已知边界（若用户反馈再修）**：①断开后 60s 内耳机重新开关机，锁存未清；②耳机系统已连+锁存时点 Melody"连接"按钮不产生 CONNECTED 广播，需重新开关机耳机才恢复；③通用设置电量显示依赖主动拉取（≤10s），若仍延迟查 `repository first observed; pulling Bose battery` / `Bose link came up`。
8. 通用设置图片/转圈：已修复；**CNC 通用设置滑条** `cnc.onespace.skip reason=no_tree`：不主动改。
9. 2.0.0 剥离后 vendor 等已删；加回其他品牌参考 `git show 009def5~1:app/src/main/java/com/melody/melodylink/vendor`。



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
2.0.1 (versionCode 201)   模型资产改 .vfxms 容器（裸 glb 会让 Head.read 把 glTF magic 误当 configStart 越界）；缓存名 v2.vfxms，长度 13239307。待装机验证
2.0.0 (versionCode 200)   Bose 专用精简版：删 Sony/Huawei/Xiaomi/Samsung 全部适配层+Compose UI（-8200 行，266 文件）；
                          hook EarphoneDTO.getColorId→3（colorId=-1 致 detailSource=null）；hook onShowAnimationEnd 杀转圈；
                          initializeSonyConfig 失败重试（onPackageReady 早于 attach，剥离后失去顺手重试）。本地未 push
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

- 本地 git 仓库（`MelodyLink-Bose/`）**已与 remote 同步**：main = origin/main = `9cbcb2b`（2026-10-05 核实，用户已完成 push）。github.com 直连仍被墙，后续 `git fetch` 同步仍靠用户 GitHub Desktop Pull origin。
- 工作区根目录 `opslog/`（操作日志+状态快照）与 `ONCALL-PROTOCOL.md`（AI 接管协议）**不在仓库内、不提交**。
- 反编译产物（smali/dex/dexdump）在仓库外的工作区根目录（`tools/smali*`、`melody1763/`、`dex1763/`、`dexdump*.txt`），**不提交、不上传**（版权）。

---

## 14. 交接铁律（2026-10-06 用户指令，对所有接棒 AI 永久生效）

1. **每个 bug 修完并经用户复验通过后，立刻完成一次完整交接准备**，不要攒到 token 耗尽那轮再做。内容包括：
   - 整理并输出项目当前状态：核心功能、代码结构、关键文件、开发进度（重写 `opslog/STATUS.md`）；
   - 撰写/更新详细交接文档：未完成任务、已知问题、注意事项、禁忌与踩过的弯路（本节 + `docs/HANDOVER-*.md`）；
   - 按五要素写操作日志：每次操作记录**调用的方法、执行步骤、查询结果、出现的错误与结论/待办**（`opslog/YYYY-MM.md`，规范见 `opslog/README.md`）；
   - git 操作记 SHA，push/CI/装机状态变化必须实测核实（`api.github.com/.../commits/<sha>`、`dumpsys package`）后再写，不得凭记忆。
2. **嘱咐并继承待命义务**：每一位继任 AI 都必须保持**响应式待命**——以文档为唯一事实来源，用户任一指令都要能在 5 分钟内恢复上下文继续干，不重新摸索、不问文档里已有答案的问题；并且**同样在每个 bug 结案后重复第 1 条**。
3. 交接链要写清（谁 → 谁、时间点、当任做的事与遗留），见 `opslog/STATUS.md` 末节与 `ONCALL-PROTOCOL.md`。
