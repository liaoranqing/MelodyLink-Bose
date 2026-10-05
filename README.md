# MelodyLink Bose Adapter

## 版本标识

- 项目版本：`MelodyLink-Bose 1.0.0`（versionCode 105）
- 目标设备：Bose QC Ultra Earbuds 2（镇海司，MAC `68:F2:1F:3D:41:D7`）
- 目标 Melody：17.6.3（`com.oplus.melody`，已实机验证）
- 当前状态：正式发布，核心功能已在 OPPO Find X8 Ultra / ColorOS 上实机验证

## 已实现

在 Melody 原生系统级耳机页面中为 Bose QC Ultra Earbuds 2 提供支持：

- 设备卡片与详情页识别（MAC 正向判据，不误伤真 OPPO Enco X3）
- 详情页与通用设置页产品图替换
- 关闭 / 降噪 / 通透三态切换
- CNC 0–10 降噪等级（详情页）
- Enco X3 四级降噪选择器与「增强人声」残留剥离
- 左耳、右耳、充电盒电量显示
- 针对 Melody 版本差异（混淆类名/方法名）的 Hook 降级

## 实现原则

参考 MelodyLink 的架构，不再只向 `melody_ui_detail_container` 直接塞一个独立 View：

1. 通过 Melody 的 DeviceInfo / EarphoneDTO / Repository 状态链让系统认为 Bose 是已连接且支持 SPP 的设备。
2. 在 `androidx.preference.g.onViewCreated`、详情 Preference 宿主和 `PreferenceGroup.f(Preference)` 路径注入原生 Preference。
3. 由 Bose BMAP RFCOMM channel 2 作为底层控制后端。
4. 保留对旧版类名、混淆方法名和新版页面结构的诊断与降级。

## 版本策略

每次功能改动必须同时更新：

- 模块 `versionCode`
- 模块 `versionName`
- 本 README 的项目版本
- 发布包名中的版本标识（如果构建产物采用带版本后缀的命名）

## 构建与发布

- CI（GitHub Actions）：`.github/workflows/build.yml`，push 到 `main` 自动构建 release APK，产物见 Actions artifact `melodylink-bose-release`。
- 版本策略：每次功能改动同步更新 `versionCode`、`versionName`（`app/build.gradle.kts`）与本 README 的项目版本。

## 说明

本模块是独立适配工作线，不替换系统 Melody APK，不使用测试签名覆盖系统应用；以 LSPosed 模块形式加载，仅对 `com.oplus.melody` 进程做运行时 Hook。
