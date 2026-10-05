# MelodyLink-Bose

让 **Bose QuietComfort Ultra Earbuds 2** 在 OPPO/一加 的 **Melody**（`com.oplus.melody`）耳机管理应用中正常工作。

Melody 原生只识别 OPPO/一加 系耳机，第三方耳机（Bose、Sony、华为、小米等）连上后只能当普通蓝牙设备，无法看到产品图、降噪开关、电量等。本项目是一个 **LSPosed 模块**，通过运行时 Hook 把第三方耳机"伪装"成 Melody 认识的设备，从而复用 Melody 原生的详情页、降噪控制、电量显示等能力。

> 项目版本：**1.0.0**（versionCode 105）· 目标 Melody：**17.6.3** · 已实机验证机型：OPPO Find X8 Ultra / ColorOS 17

---

## 目录

1. [功能特性](#功能特性)
2. [实现原理](#实现原理)
3. [环境搭建](#环境搭建)
4. [快速开始](#快速开始)
5. [项目目录结构](#项目目录结构)
6. [依赖项目](#依赖项目)
7. [文档索引](#文档索引)
8. [常见问题排查](#常见问题排查)
9. [免责声明](#免责声明)

---

## 功能特性

在 Melody 详情页 / 通用设置页为 Bose QC Ultra Earbuds 2 提供：

- ✅ 设备卡片与详情页识别（MAC 正向判据，不误伤真 OPPO Enco X3）
- ✅ 详情页与通用设置页产品图替换
- ✅ 关闭 / 降噪 / 通透 三态切换
- ✅ CNC 0–10 降噪等级（详情页滑条）
- ✅ Enco X3 四级降噪选择器与「增强人声」残留剥离
- ✅ 左耳、右耳、充电盒电量显示
- ✅ 针对 Melody 版本差异（R8 混淆类名/方法名）的 Hook 降级

代码还保留了 **Sony / Huawei / Xiaomi / Samsung** 的参考适配层（`vendor/` 与 `assets/*/config/`），可作为移植其他品牌耳机时的模板。

---

## 实现原理

核心思路一句话概括：**不替换系统 Melody APK，而是在 LSPosed 里 Hook Melody 进程，把 Bose 的数据"喂"进 Melody 已有的展示与交互链路。**

关键技术点（详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)）：

1. **设备识别**：Melody 维护一个 `WhitelistConfigDTO` 设备目录（按 Product ID + 蓝牙名查），第三方耳机没有条目 → 详情页拿不到数据。模块通过 Hook 目录查询方法，注入/克隆一条 Bose 条目。
2. **两套目录**：Melody 17.6.3 存在两套独立 catalog —— `L6/a`（SupportConfigManager，按 MAC）与 `c9/a`（WhitelistRepository，按 productId+name）。这是排查"注入不生效"时最容易踩的坑。
3. **产品图**：详情页图片由 `MelodyDetailModelView` 异步加载；通用设置页图片在 `OneSpaceHeaderPreference` 的 `field e`（ImageView）。模块在 bind 时机替换为内置 PNG，并做多轮 pin 防止被宿主异步加载覆盖。
4. **降噪子级剥离**：宿主的 `NoiseReductionItem → Ba/m` 链在 `getChildrenMode()` 非空时才构建 Enco 的四级降噪选择器/增强人声。模块在 catalog 数据源头把这些子级清空，让 Bose 只显示三态降噪。
5. **BMAP 控制**：Bose 的底层控制走 BMAP over RFCOMM（channel 2），编解码在 `BoseBmap.java` / `BoseTransport.java`（协议细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 附录）。

---

## 环境搭建

### 硬件/系统

| 项 | 要求 |
|---|---|
| 手机 | OPPO/一加，已 root（本项目实测 KernelSU） |
| 框架 | LSPosed（v2.2.0+） |
| 系统 | ColorOS 17（Android 17 / SDK 37） |
| 目标应用 | Melody `com.oplus.melody` 17.6.3 |

### 构建环境

- **JDK**：21（CI 用 Temurin 21；本地开发可用 17+）
- **Android SDK**：`compileSdk 37`，需手动装 `platforms;android-37.0`（37 尚未进稳定通道）
- **Gradle**：项目自带 wrapper（`./gradlew`）

> 注意：本机 Windows 环境没有 Android SDK/Gradle，**构建全部交给 GitHub Actions CI**（`.github/workflows/build.yml`）。本地只做代码改动 + 静态检查，编译在云端完成。

---

## 快速开始

### 1. 构建 APK（CI）

push 到 `main` 即触发 CI 自动构建 release APK：

- Actions 产物名：`melodylink-bose-release`
- 或直接下载已发布的 Release：https://github.com/liaoranqing/MelodyLink-Bose/releases

本地手动构建（需 Android Studio / SDK）：

```bash
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

### 2. 安装与激活

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. 打开 LSPosed 管理器 → 模块 → 启用 **MelodyLink**
2. 勾选作用域：`com.oplus.melody`
3. 重启 Melody 进程（或重启手机）

### 3. 验证日志

```bash
adb logcat -c
adb logcat -d | grep -i melodylink > logs.txt
grep -oE "evt=[a-z_.]+" logs.txt | sort | uniq -c   # 先看事件分布
```

关键事件：`image.applied`（图替换）、`detail.lookup_stripped`（子级剥离）、`inject.verified`（条目注入）。

---

## 项目目录结构

```
MelodyLink-Bose/
├── .github/workflows/build.yml      # CI：Java 21 + SDK 37 构建 release APK
├── app/
│   ├── build.gradle.kts             # 版本号、依赖、签名（release 用 debug key）
│   └── src/main/
│       ├── AndroidManifest.xml      # xposedmodule 元数据 + 蓝牙权限
│       ├── assets/
│       │   ├── bose/images/         # Bose 产品图（qc_ultra2_v2.png）
│       │   ├── sony/ huawei/        # 其他品牌参考资源与设备目录 JSON
│       └── java/com/melody/melodylink/
│           ├── MainActivity.kt      # 模块自带设置界面（Compose）
│           ├── bose/                # Bose 设备常量 + BMAP 协议 + RFCOMM 传输
│           ├── hook/                # ★ 核心 Hook 逻辑
│           │   ├── HookModule.java  # 主模块（~8000 行，所有 hook 注册与分发）
│           │   ├── PrefRef.java     # Preference 树反射工具
│           │   ├── MLog.java        # 统一日志（事件 key 化，便于 grep）
│           │   └── ...              # 状态桥、命令桥、共享状态存储等
│           ├── vendor/              # 各品牌 VendorAdapter（移植模板）
│           ├── sony/ huawei/ xiaomi/ samsung/  # 品牌专属传输/协议层
│           └── transport/           # 通用 RFCOMM 传输抽象
├── docs/                            # 详细文档（本仓库新增）
├── tools/                           # 反编译/排查辅助脚本（本仓库新增）
└── README.md
```

核心是 `app/src/main/java/com/melody/melodylink/hook/HookModule.java` —— 所有 Hook 都注册在这里，通过 `hookNamed(loader, class, method, arity, label)` 挂到 Melody 的类上，再在统一分发点按 `label` 处理。

---

## 依赖项目

| 项目 | 作用 | 来源 |
|---|---|---|
| **Melody.Az100_Impl** | 移植起点（Technics EAH-AZ100 音量面板模块） | 本地参考实现 |
| **melodylink-master** | 原始 MelodyLink 项目（多品牌适配框架） | 本地参考实现 |
| **bosectl** | Bose BMAP 协议参考（Python 实现） | https://github.com/simonmicro/bosectl |
| **libxposed-api** | LSPosed 开发 API | Maven（compileOnly） |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 整体设计思路、技术路径、hook 链路、BMAP 协议附录 |
| [docs/REVERSING.md](docs/REVERSING.md) | Melody 固件编译/反编译方法（apktool/baksmali/androguard/资源 ID 反查） |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 实施过程中踩过的错误及解决方案 + 排查铁律 |
| [docs/PORTING.md](docs/PORTING.md) | 移植实操分步教程（声明式 JSON 移植 + 代码式移植） |
| [docs/TOOLS.md](docs/TOOLS.md) | 全部工具清单与用法 |

---

## 常见问题排查

**模块不生效？** 依次检查：

1. LSPosed 已启用模块，作用域勾选了 `com.oplus.melody`，并重启了进程
2. `adb logcat -d | grep -i melodylink` 有输出（有 `evt=bose.*` 事件说明模块已注入）
3. 事件分布里 `hook.miss` 是否异常（R8 混淆导致方法名对不上）

**图替换了但看不到？** 看 `image.truth` 事件的 `blocker` 字段——它会点名是哪个祖先容器把图挡住了（本项目 #2 的根因正是自己的 `hideLoadingView` 误伤容器）。

**降噪显示 Enco 交互？** 看 `detail.lookup_stripped` 是否出现（子级剥离是否命中）。

详细排查见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

---

## 免责声明

本项目仅用于个人学习与设备适配研究，不替换系统 Melody APK、不使用测试签名覆盖系统应用。请遵守当地法律法规与设备保修条款。Bose、OPPO 等商标归各自权利人所有。
