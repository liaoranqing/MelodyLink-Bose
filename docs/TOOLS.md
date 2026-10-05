# 工具清单（TOOLS）

本项目实施过程中用到的全部工具，按用途分类。

---

## 1. 开发 / 构建

| 工具 | 用途 | 说明 |
|---|---|---|
| Android Studio | 代码编辑 | 可选，本机实际未装 SDK，靠 CI |
| JDK 21 | 编译 | CI 用 Temurin 21；本地反编译用 JRE 17 |
| Gradle wrapper | 构建 | `./gradlew`，版本在 `gradle/wrapper/` |
| GitHub Actions | 云端构建 APK | `.github/workflows/build.yml` |

CI 关键点：`compileSdk 37` 需手动 `sdkmanager --install "platforms;android-37.0"`（37 未进稳定通道）；release 用 debug key 签名（LSPosed 模块侧载，免私钥）。

---

## 2. 反编译 / 逆向

| 工具 | 用途 | 命令示例 |
|---|---|---|
| apktool | APK 解包/重打包 | `apktool d melody.apk -o out/` |
| baksmali（bsm.jar） | dex → smali | `java -jar bsm.jar d classes.dex -o smali/` |
| JRE 17 | 运行 baksmali | `jdk-17.0.20.1+1-jre/bin/java.exe` |
| androguard | 解析 AXML/ARSC | `AXMLPrinter(data).get_xml()` |
| jadx（可选） | dex → java（比 smali 可读） | 用于快速看逻辑 |

---

## 3. 排查 / 取证

| 工具 | 用途 |
|---|---|
| adb（platform-tools） | 安装、抓日志、dump 系统状态 |
| `adb logcat` | 抓模块事件日志 |
| `adb shell dumpsys dropbox` | ANR/闪退取证（比 logcat 保留更久） |
| `adb shell dumpsys bluetooth_manager` | 蓝牙连接状态 |
| `adb shell pm path` | 定位 APK 路径 |

---

## 4. 本仓库脚本（tools/）

| 脚本 | 用途 |
|---|---|
| `find_resid.py` | 按资源名字符串反查资源 ID（手写 arsc 解析） |
| `find_resid_rev.py` | 按资源 ID 反查名字 |
| `find_hide_calls.py` | 扫描 smali 里的 setVisibility/hide 调用点 |
| `dexquery.py` | dex 查询辅助 |

> 注意：脚本里的 APK 路径是绝对路径，使用前改成你自己的路径。

---

## 5. 依赖项目 / 协议参考

| 项目 | 用途 |
|---|---|
| Melody.Az100_Impl | 移植起点（AZ100 音量面板） |
| melodylink-master | 多品牌适配框架 |
| bosectl | Bose BMAP 协议参考（https://github.com/simonmicro/bosectl） |

---

## 6. 环境变量 / 版本一览

| 项 | 值 |
|---|---|
| 目标 Melody | 17.6.3（`com.oplus.melody`） |
| 系统 | ColorOS 17 / Android 17 / SDK 37 |
| LSPosed | v2.2.0 |
| Root | KernelSU |
| libxposed API | compileOnly（Maven） |
| 项目版本 | 1.0.0 / versionCode 105 |
