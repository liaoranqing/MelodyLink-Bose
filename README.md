# MelodyLink Bose Adapter

## 版本标识

- 项目版本：`Bose-MelodyLink 0.1.0`
- 目标设备：Bose QC Earbuds Ultra 2（edith，Product ID 0x4062）
- 目标 Melody：16.8.3 基线 + 最新版兼容层
- 当前状态：架构适配准备阶段，尚未宣称可用

## 目标

在 MelodyLink 的原生系统级耳机页面中加入 Bose QC Earbuds Ultra 2 支持：

- 设备卡片与详情页识别
- 左耳、右耳、充电盒电量
- 关闭、降噪、通透
- CNC 0-10
- ANC 与空间音频
- 针对 Melody 版本差异提供 Hook 降级

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

## 当前说明

该目录是独立适配工作线，不替换系统 Melody APK，不使用测试签名覆盖系统应用。正式实现应在完成目标 Melody 版本的实机验证后再发布。
