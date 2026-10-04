# MelodyLink-Bose 项目交接文档（HANDOFF）

> 写给接手的下一个模型/开发者。**2026-10-04 23:40 更新指针：当前版本 0.5.75-bose / versionCode 101，提交 `4a1e970`，工作区干净，待用户推送+装机验证。§5 的 0.4.2 待办已过时（多数已解决），最新完整交接见 `.workbuddy/memory/2026-10-04.md` 末尾「★ 模型切换交接」段（三个 bug 状态、#4 smali 证据链、风险点、验证清单、日志实操、文件索引全在那里）。浓缩铁律 31 条见 `.workbuddy/memory/MEMORY.md`。**
>
> 原始生成时间：2026-10-03 11:42（当时版本 0.4.2-bose / versionCode 18，提交 `4de704a`）。以下 §0-§8 中 §0/§2/§3/§6/§8 仍然有效，§4/§5/§7 已被后续版本部分取代。

---

## 0. 与用户协作的铁律（违反过、被严厉纠正过，绝不能再犯）

1. **绝不 `git push`**。用户网络不稳，我只做本地 commit，用户在 GitHub Desktop 手动点 Push origin。
2. **绝不在本地编译 APK**。构建全部走 GitHub Actions 云端。本地只准备源码+workflow。
3. **严禁 adb reboot / 任何重启手机的操作**。用户的 OPPO Find X8 Ultra 是临时 root（漏洞提权，重启即失效且极难重新获取）。曾误重启导致 root 丢失，用户非常愤怒。诊断只用：force-stop、读日志、查数据库、`content query`。
4. **每次改动必须递增版本号**：`app/build.gradle.kts` 的 `versionCode` + `versionName`（格式 `0.4.x-bose`）。
5. CI 失败时用户会下载 Actions 日志 zip 发过来 → 解压看 `build/6_Build debug APK.txt`。
6. 用户设备：Bose QC Earbuds Ultra 2，**蓝牙名被改成个性中文"镇海司皓色深寂冑"**，识别必须靠 MAC。
7. 用户说"我来手动推送"之后，等他推+装+实测反馈，不要抢跑。

## 1. 项目背景与两条版本线

- **A 版**（维护状态，不再是重点）：独立模块 `com.tosasitill.bosemelody`（Bose Melody Control），目录 `../Melody.Az100_Impl`，仓库 `liaoranqing/Melody-Bose-ColorOS`。自绘控制面板（用户嫌丑）。1.7.1-control。它的 provider hook 逻辑已移植进 B 版。
- **B 版**（主线，本仓库）：`MelodyLink-Bose`，基于上游 `jerry-2009/MelodyLink`（Kotlin/Compose + libxposed，原作者只适配到 Melody 16.8.3），目标：让 Bose 耳机**原生融入** ColorOS 耳机 App（com.oplus.melody 17.6.3）——设备卡片、三模式、电量、CNC、空间音频、音量面板磁贴。
- 上游停在 2026-08-11，**没有 17.6.3 适配**，全部混淆重定位是我们自己做的。

### 关键设备/协议参数（A 版实机验证）
- MAC：`68:F2:1F:3D:41:D7`（`BoseDeviceConfig.KNOWN_MACS`）
- BMAP：RFCOMM **channel 2**、必须 `createInsecureRfcommSocket(2)`（安全 socket 会被拒）、codename `edith`、Product ID `0x4062`
- ColorOS 磁贴模式常量：关闭=1、通透=2、降噪=5；supports 是 **JSON 列表字符串**（noise `"[1,5,2]"`、spatial `"[0,1,2]"`）
- BMAP `[31.10]` AudioModesSettingsConfig 5 字节：`[cnc(0-10), autoCNC, spatial(0=off/1=room/2=head), reserved, anc(0/1)]`；模式切换 `[31.3]` OP_START；电量 `[2.2]`
- 写流程：GET → 改目标字节 → SETGET → 回读确认
- **短会话策略**：channel 2 单客户端（Bose Music 会抢），每次操作开-关会话，不与 Bose Music 争用；UI"已连接"权威标记是 A2DP（`boseHostConnected`，小米模式）

## 2. 架构地图（app/src/main/java/com/melody/melodylink/）

| 文件 | 职责 |
|---|---|
| `hook/HookModule.java`（~3300 行，核心） | 所有 Melody hook：设备伪装、whitelist、连接、ANC 路由、DTO 投影、Preference 注入、provider 桥接安装 |
| `hook/BoseControlProviderBridge.java` | EarphoneControlProvider 的 query/call hook（音量面板磁贴） |
| `bose/BoseTransport.java` | BMAP 短会话 RFCOMM：合并 worker、generation、settle、ANC/电量/settings 读写 |
| `bose/BoseBmap.java` | 帧编解码/粘包解析（从 A 版移植，勿动） |
| `bose/BoseDeviceConfig.kt` | MAC 白名单、模式常量、[31.10] 索引、capabilities |
| `hook/MelodySharedStateStore.java` | 跨进程文件通道（主进程↔:fg）：state/command 文件 |
| `hook/MelodyStateBridge.kt` | AncMode→Melody 索引映射（**TRANSPARENCY 必须是 2**，见 §4） |
| `vendor/bose/BoseVendorAdapter.kt` | 厂商注册表条目（次要） |

### 进程模型（最重要的坑）
`com.oplus.melody` 有主进程和 `:fg` 前台进程。**Provider/蓝牙会话在主进程；详情页 PreferenceFragment 绑定在 :fg**。一切跨进程判断不能用内存字段，必须走 bond 探测或共享文件。

### 伪装机制
Bose 被注册为 **Enco X3（productId 0x67410=422928）** 让 Melody 原生 UI 渲染；状态读写在 hook 里拦截转发到 BMAP。

## 3. 17.6.3 混淆重定位成果表（dexdump 实证，hookAny 多候选机制）

| 功能 | 16.8.3 | 17.6.3 | 证据 |
|---|---|---|---|
| whitelist | common.util.V.a | **T.a**(Collection,String,String)→WhitelistConfigDTO | 签名唯一命中 |
| nativeConnectDevice | E7.c.b | **e7.c.c** | 日志 getDeviceOrCreateDevice |
| directConnectSpp | E7.c.a | **e7.c.a**(DeviceInfo,Z) | 日志 m_spp_le.directConnectSpp |
| nativeConnectionState | E7.c.e | **e7.c.f** | — |
| repositoryGet | U.y | **J.y**(S)→EarphoneDTO | — |
| repositoryObserve | U.z | **J.A**(S)→LiveData（J.z 是 getEarphoneIdentity，**别认错**） | 方法体 const-string |
| repositoryNotify | U.x1 | **J.B1** | 日志 notifyEarphoneChanged |
| repositoryDtoBuild | U.g1 | **J.k1** | — |
| detailInfo 三件套 | v9.C1576a | **G9.a**(getConnectionState/getHeadsetConnectionState/getIsSpp) | 结构一致 |
| noiseReductionModeVO | pa.C1405p | **Ba.z**.getCurrentNoiseReductionModeIndex | — |
| noiseModeWrite | U.s0 | **J.v0**(I,S)→CF | 实测生效 |
| noiseWrite | U.L0 | **J.o0**(I,S,S)→CF | 实测生效 |
| 电量状态类 | V$a | **earphone.K$a**（K 是旧 V 容器） | 异常栈实锤 |
| ANC 结果 DTO | SetCommandStateDTO | **earphone.O**(address,setCommandStatus) | — |
| 未定位（无害） | A7.h/C7.b/V7.v/V7.u/HeadsetCoreService.m0 等 | — | 17.6.3 不存在或改名未查 |

**方法论**：`hookNamed` 返回 boolean + `hookAny(label, "类#方法#参数个数", ...)` 多候选绑定，旧名优先新名兜底。定位手段=官方 `dexdump.exe -d`（build-tools 37.0.0，本机 `C:/Users/liaoran/.workbuddy/binaries/android-sdk/`）+ 方法体 const-string 日志字符串比对。工作区已有现成产物：**`dexdump1.txt`/`dexdump2.txt`（17.6.3 全量反汇编，640 万行）**、`dexquery.py`（查询脚本）、`melody1763.zip`（原始 APK）。**不要再手写 DEX 解析器，直接用这两个 dump 文件。**

## 4. 已解决问题的关键结论（防止回退）

1. **降噪按钮消失**：17.6.3 首次无条件查询 noise_reduction（无 selection/args），必须放行。
2. **磁贴显示前置**：SystemUI 锁存 `notifyChange(baseUri, 0x500|1)`（wear hint 广播），每次查询都要确保发过。0x100=连接、0x200=模式。SystemUI **不查 control_wear**（0.2.5 猜错过一次，别再接管 wear 查询）。
3. **磁贴 type 列来源**：noise/spatial 两条查询在 Bose 在位时**始终由桥接应答**（0.3.3 结论）；active_device 保持 stock 优先。spatial 的 DTO 投影（spatialSoundStatus/headsetSpatialType）也做了双保险。
4. **ancModeIndex 映射**：OFF→0、NOISE_CANCELING→1、TRANSPARENCY/AMBIENT→**2**（Enco X3 白名单只有 0/1/2，写 3 会越界钳回 0 导致"只有两态"）。
5. **电量**：17.6.3 电量在 EarphoneDTO 字段（leftBattery/rightBattery/boxBattery/isBatteryInfoReceived，int），在 repositoryDtoBuild 时投影（`projectBoseBatteryIntoDto`）。已实测 ✅ 显示 90/L40/R40。
6. **空间三态**：0.3.3 起用户确认 ✅ 正常。
7. **误报"切换失败"**：乐观回执（点击立即 complete future + 镜像状态）。
8. **RFCOMM 交替失败**（0.4.2 新发现）：会话关闭后立即重开 → `read ret: -1`。ancWorker 加了 600ms `SESSION_SETTLE_MS` + 相同目标去重。**注意：writeSetting（CNC/空间）路径没有走这个 settle，如果 CNC 连拖出问题，把 settle 也加到 runSettingWrite。**

## 5. 当前状态与待办（0.4.2 已提交、未验证）

0.4.2 修了四件事，**用户还没装过这个版本**（上一条消息用户直接要交接了）：

1. **CNC 滑条**：根因=androidx SeekBarPreference 的 mMax 被 R8 改名成 `c`（默认 100），写 `mMax` 静默失败 → 0..100 区间。改用 Melody 自家 `MelodyPromptVolumeSeekBarPreference`（公开 setBarMaxValue/setProgress/setOnTrackChangeListener，监听接口 `$b` 只有方法 `a(I)V`，Proxy 返回 null）。**风险**：该控件内部可能按百分比显示（已 setPromptVolumePercent=false，未实测）；布局可能带图标 ImageView（iconImg，未设置图，可能空白/异常）。
2. **滑条位置**：找"降噪效果"行插正下方（隐私页无此行天然跳过）。**风险**：`findPreferenceByTitle` 匹配的是 getTitle().toString()，若 17.6.3 该行标题不是精确"降噪效果"四字（可能是资源字符串带空格/异体），会静默不注入——看日志 `installed Bose CNC level slider under noise-effect row` 是否出现。
3. **磁贴连点 settle**：见 §4.8。
4. **Bose 产品图**：`assets/bose/images/qc_ultra2.jpg` → materialize → replaceBoseProductImage（地址匹配即换）。**风险**：`sonyDetailImage` hook 的宿主类字段名（g/b/c/d/e）是按 16.8.3 索尼路径写的，17.6.3 的 MelodyDetailModelView 字段可能不同 → 可能不生效。日志看 `replaced Bose detail product image`。

### 用户已知不满（下版优先）
- 磁贴连点"还是不行"（0.4.1 实测失败，0.4.2 的 settle 修复未验证——**这是最高优先级验证项**）
- 若 0.4.2 滑条仍不对，备选方案：自绘一个 View 通过 `setWidgetLayoutResource` 塞进 Preference（模块 APK 已 addAssetPath，资源可引用），或干脆用 `MelodyCompatSectionSeekBar` 自己搭。

### 其他待办
- 空间音频"头部跟踪"若需 3 态以上文案映射，用户定义：固定=room(1)、头部跟踪=head(2)、关闭=off(0)。
- A 版建议用户卸载（验证 B 版稳定后）。
- 版本约定：下一版 0.4.3 / versionCode 19。

## 6. 诊断工具箱（全部本机可用）

- adb：`C:/Users/liaoran/WorkBuddy/2026-09-30-12-08-31/platform-tools/adb.exe`（设备 3B1F5LEADK1TP13K 已连）
- **模块日志**（比 logcat 可靠，logcat 缓冲会被冲掉）：
  `adb shell "su -c 'grep -h \"com.melody.melodylink\" /data/adb/lspd/log/modules_*.log | grep -iE \"关键词\" | tail -30'"`
  常用关键词：`volume-panel ANC click` / `Bose ANC write` / `query path=` / `installed Bose CNC` / `replaced Bose` / `not found:` / `no candidate`
- **LSPosed 启用状态取证**：`su cp /data/adb/lspd/config/modules_config.db*`（**必须连 -wal/-shm 一起拉**，否则读旧快照）→ adb pull（Git Bash 需 `export MSYS_NO_PATHCONV=1`）→ sqlite3 查 modules_state。
- **provider 直查**：`su -c 'content query --uri content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_noise_reduction'`
- **重新注入**：`am force-stop com.oplus.melody`（常驻进程不吃新模块，无需重启手机）
- CI workflow：`.github/workflows/build.yml`（Java 21 + SDK 37 平台包名 `platforms;android-37.0` + gradlew 需 `git update-index --chmod=+x gradlew`）
- 相关技能：`~/.workbuddy/skills/github-ci-android-build/SKILL.md`（CI 排障+LSPosed 排查清单，持续更新中）、`lsposed-module-ci`

## 7. 完整提交时间线（B 版，从新到旧）

```
4de704a 0.4.2 CNC滑条改COUI控件+挂降噪效果下方/连点settle+Bose产品图   ← 当前，未验证
913c6c3 0.4.1 连点合并worker + 滑条跨进程(bond守卫+命令文件)
5d33643 0.4.0 首次注入滑条(androidx版,有max bug) + 自推进(错误方案)
8e97b7f 0.3.3 noise/spatial始终覆盖应答(空间✅用户确认)
2c44b6c 0.3.2 空间DTO投影 + generation早退(副作用=连点丢任务)
6ee8806 0.3.1 TRANSPARENCY索引3→2 + spatial supports改JSON列表
ad9d48e 0.3.0 磁贴点击补状态回灌链
c2e6f2a 0.2.9 wear广播挂每次查询
9b50ff6 0.2.8 wear广播规则+spatial接管(后证伪)
09938b0 0.2.7 透传优先+契约采集
c988bac 0.2.6 回退wear/spatial猜测
42eb6ec 0.2.5 wear/spatial接管(回归,已回退)
4b030ec 0.2.3+ import修复
6f34e39 0.2.4 bond探测可见性
ef9d0c4      registerReceiver参数修复
9fd1a7f 0.2.3 provider桥接移植
0ee25b6/27f8cda 0.2.2 乐观回执+去回读提速
7edefaa      状态镜像+DTO电量投影(电量✅)
2007fc9      结果DTO=earphone.O
4ffadcd      ANC路由J.o0/J.v0
678134a      电量类=K$a
e06f9f6      17.6.3重定位第一批(hookAny机制)
1660fdc      MAC白名单识别
191d891      gradlew +x
0fc6021      初始导入MelodyLink master+Bose层
```

每日工作日志：`C:/Users/liaoran/WorkBuddy/2026-09-30-12-08-31/.workbuddy/memory/2026-10-01.md ~ 10-03.md`（含每轮日志取证细节、A 版历史坑）。

## 8. 用户话术偏好

中文、直接、要"日志实锤"不要猜。多次因猜测契约翻车（0.2.5、0.4.0），**正确节奏 = dexdump/日志取证 → 小步修复 → 用户实测反馈 → 迭代**。每轮交付时清楚写版本号、待推送提交、验证清单。
