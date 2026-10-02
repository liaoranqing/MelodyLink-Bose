# MelodyLink 现代化 UI 实现计划

## 项目概述

**目标**: 为 MelodyLink（一个支持 Sony、Samsung、Huawei 等品牌无线耳机的 Xposed 模块）创建现代化的用户界面

**技术栈**:
- UI 框架: [miuix](https://github.com/compose-miuix-ui/miuix) - MIUI/HyperOS 风格的 Compose UI 库
- 设计语言: Material You (Material Design 3)
- 动态取色: Android 12+ Dynamic Color 支持
- 架构: Jetpack Compose + Navigation3

## 当前状态分析

### 已有功能
- ✅ Xposed hook 基础设施完整
- ✅ 多品牌耳机支持 (Sony/Samsung/Huawei/Bose)
- ✅ 蓝牙连接和状态管理
- ✅ ANC 模式控制
- ✅ 电池状态监控
- ✅ 设备配置加载系统

### UI 现状
- ❌ 仅有占位符界面 (Hello Android)
- ❌ 未实现设备管理界面
- ❌ 未实现设置页面
- ❌ 未集成 miuix 库
- ❌ 缺少导航结构

## 实施计划

### 阶段 1: 依赖配置与基础架构 (优先级: 高)

#### 1.1 添加 miuix 依赖
**文件**: `app/build.gradle.kts`, `gradle/libs.versions.toml`

**任务**:
1. 在 `libs.versions.toml` 中添加 miuix 相关版本声明
   - miuix (核心库)
   - miuix-preference (设置界面)
   - miuix-icons (图标系统)
   - miuix-blur (毛玻璃效果)
   - miuix-navigation3-ui (导航组件)
   - navigation3-runtime (导航运行时)

2. 在 `build.gradle.kts` 中添加实现依赖
   ```kotlin
   implementation(libs.miuix)
   implementation(libs.miuix.preference)
   implementation(libs.miuix.icons)
   implementation(libs.miuix.blur)
   implementation(libs.miuix.navigation3.ui)
   implementation(libs.navigation3.runtime)
   ```

3. 添加 Maven 仓库配置
   - 检查 `settings.gradle.kts` 是否包含 jitpack 等必要仓库
   - 参考 HuaweiPods 项目的仓库配置

4. 同步并验证依赖下载成功

**预期结果**: 所有 miuix 依赖正确导入，构建通过

---

#### 1.2 创建主题系统
**目录**: `app/src/main/java/com/melody/melodylink/ui/theme/`

**任务**:
1. **创建 `MiuixTheme.kt`** - 主题配置文件
   - 实现 `MelodyLinkTheme` Composable
   - 集成 Material You 动态取色
   - 支持三种主题模式:
     - 跟随系统 (默认)
     - 强制浅色
     - 强制深色
   - 支持多种强调色模式:
     - 动态取色 (Android 12+)
     - 预设颜色方案 (兼容性)
   - 参考 HuaweiPods 的主题实现

2. **更新 `Color.kt`** - 颜色定义
   - 移除占位符颜色 (Purple80, Pink40 等)
   - 定义应用特定的语义化颜色
   - 添加电池电量指示颜色 (充电中、正常、低电量)
   - 添加连接状态颜色 (已连接、连接中、断开)
   - 添加 ANC 模式颜色

3. **保留 `Type.kt`** - 字体排版
   - 使用 miuix 默认字体系统
   - 可选：自定义标题/正文字体大小

**预期结果**: 完整的主题系统，支持动态取色和暗黑模式切换

---

#### 1.3 更新 MainActivity
**文件**: `app/src/main/java/com/melody/melodylink/MainActivity.kt`

**任务**:
1. 实现边到边 (Edge-to-Edge) 显示
   ```kotlin
   enableEdgeToEdge(
       statusBarStyle = SystemBarStyle.auto(...),
       navigationBarStyle = SystemBarStyle.auto(...)
   )
   ```

2. 添加 SharedPreferences 配置管理
   - 主题模式偏好 (theme_mode)
   - 强调色模式 (accent_mode)
   - 浮动底栏开关 (floating_bottom_bar)
   - 底栏毛玻璃效果 (blur_bottom_bar)
   - 语言设置 (app_language)

3. 使用 `remember` + `mutableStateOf` 管理主题状态
4. 用 `DisposableEffect` 响应主题变化
5. 设置 `window.isNavigationBarContrastEnforced = false`

**预期结果**: MainActivity 正确初始化主题和窗口属性

---

### 阶段 2: 核心 UI 结构 (优先级: 高)

#### 2.1 设计导航架构
**目录**: `app/src/main/java/com/melody/melodylink/ui/navigation/`

**任务**:
1. **创建 `NavigationGraph.kt`** - 导航图定义
   - 定义所有屏幕路由
   ```kotlin
   sealed class Screen(val route: String) {
       object DeviceList : Screen("device_list")
       object DeviceDetail : Screen("device_detail/{address}")
       object Settings : Screen("settings")
       object About : Screen("about")
   }
   ```

2. **创建底部导航栏**
   - 使用 miuix-navigation3-ui 组件
   - 三个主要标签:
     - 🎧 设备 (DeviceList)
     - ⚙️ 设置 (Settings)
     - ℹ️ 关于 (About)
   - 支持浮动模式和毛玻璃效果
   - 平滑切换动画

**预期结果**: 完整的导航系统框架

---

#### 2.2 创建 App 主容器
**文件**: `app/src/main/java/com/melody/melodylink/ui/App.kt`

**任务**:
1. 创建 `App` Composable 作为根容器
2. 集成 miuix Scaffold
3. 配置底部导航栏
4. 管理全局状态:
   - 主题偏好
   - 连接状态
   - 当前选中的设备
5. 处理系统返回导航
6. 参考 HuaweiPods 的 `App.kt` 实现

**预期结果**: 可运行的应用框架，包含导航和空白页面

---

### 阶段 3: 设备管理界面 (优先级: 高)

#### 3.1 设备列表页面
**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/DeviceListScreen.kt`

**任务**:
1. **页面布局**:
   - 使用 miuix `TopAppBar` 作为标题栏
   - 标题: "我的设备"
   - 支持下拉刷新 (扫描蓝牙设备)

2. **设备卡片设计**:
   - 使用 miuix `Card` 组件
   - 每个卡片显示:
     - 设备图标 (从 assets 加载品牌图片)
     - 设备名称 (如 "FreeBuds 5")
     - 连接状态徽章 (已连接/未连接)
     - 电池状态 (左、右、充电盒)
     - 快捷操作按钮 (连接/断开)
   - Material You 动态圆角和阴影
   - 点击进入设备详情

3. **空状态**:
   - 未发现设备时显示占位图
   - "点击扫描按钮搜索设备"提示
   - 使用 miuix 空状态组件

4. **扫描按钮**:
   - 悬浮操作按钮 (FAB)
   - 扫描时显示加载动画
   - 自动发现附近支持的设备

5. **数据层集成**:
   - 调用 `EarbudsFacade` 获取设备列表
   - 监听蓝牙状态变化
   - 显示已配对的支持设备
   - 支持多设备管理

**预期结果**: 功能完整的设备列表界面，可发现和管理设备

---

#### 3.2 设备详情页面
**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/DeviceDetailScreen.kt`

**任务**:
1. **页面布局**:
   - 带返回按钮的 TopAppBar
   - 标题显示设备名称
   - 滚动内容区域

2. **设备信息卡片**:
   - 大尺寸设备图片 (居中显示)
   - 设备型号和品牌
   - 蓝牙地址
   - 固件版本 (如可用)
   - 连接状态指示器

3. **电池状态区域**:
   - 三个电池指示器 (左耳、右耳、充电盒)
   - 使用 miuix 进度条组件
   - 显示百分比数字
   - 充电状态图标 (⚡)
   - Material You 动态颜色:
     - 绿色: > 50%
     - 橙色: 20-50%
     - 红色: < 20%
   - 支持不同电池布局 (单耳机、双耳机、带充电盒)

4. **ANC 模式控制**:
   - 使用 miuix `SegmentedButton` 或 `RadioGroup`
   - 四种模式切换:
     - 关闭 (OFF)
     - 主动降噪 (NOISE_CANCELING)
     - 环境音 (AMBIENT_SOUND)
     - 通透模式 (TRANSPARENCY)
   - 根据设备能力动态显示可用选项
   - 切换时显示加载动画
   - 成功/失败 Toast 提示

5. **高级设置区域** (Sony 设备专用):
   - miuix `PreferenceGroup` 组件
   - DSEE 增强开关
   - 语音聚焦开关
   - 环境音等级调节 (Slider)
   - 每项设置带说明文字

6. **操作按钮**:
   - "刷新电池状态" 按钮
   - "断开连接" 按钮
   - "忘记设备" 按钮 (确认对话框)

7. **数据层集成**:
   - 通过 `EarbudsFacade.Listener` 监听状态变化
   - 调用 `setAncMode()` 切换 ANC
   - 调用 `refreshBattery()` 更新电池
   - 调用 `writeSetting()` 修改高级设置
   - 错误处理和用户反馈

**预期结果**: 功能完整的设备控制界面，可实时显示状态和控制设备

---

### 阶段 4: 设置页面 (优先级: 中)

#### 4.1 应用设置页面
**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/SettingsScreen.kt`

**任务**:
1. **页面结构**:
   - 使用 miuix-preference 库
   - 分组设置项

2. **外观设置组**:
   - **主题模式** (ListPreference)
     - 跟随系统
     - 浅色模式
     - 深色模式
   - **强调色** (ListPreference)
     - 动态取色 (Android 12+)
     - 蓝色
     - 绿色
     - 紫色
     - 红色
   - **浮动底栏** (SwitchPreference)
   - **毛玻璃效果** (SwitchPreference)
   - 每项变更立即生效并保存到 SharedPreferences

3. **连接设置组**:
   - **自动连接** (SwitchPreference)
     - 检测到已知设备时自动连接
   - **低延迟模式** (SwitchPreference)
   - **连接超时** (SeekBarPreference)
     - 范围: 5-30 秒

4. **通知设置组**:
   - **电池低电量提醒** (SwitchPreference)
   - **连接状态通知** (SwitchPreference)
   - **通知优先级** (ListPreference)

5. **高级设置组**:
   - **调试日志** (SwitchPreference)
   - **开发者选项** (跳转到子页面)

6. **关于设置组**:
   - **应用版本** (只读，点击显示更新日志)
   - **开源许可** (跳转到许可证页面)
   - **GitHub 仓库** (外部链接)
   - **检查更新** (ClickablePreference)

**预期结果**: 完整的设置界面，所有偏好正确保存和加载

---

#### 4.2 关于页面
**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/AboutScreen.kt`

**任务**:
1. **应用信息卡片**:
   - 应用图标
   - 应用名称: MelodyLink
   - 版本号
   - 构建日期

2. **项目描述**:
   - 简短介绍项目功能
   - 支持的设备品牌列表

3. **贡献者/致谢**:
   - 项目维护者
   - 主要贡献者
   - 使用的开源库列表

4. **快捷链接**:
   - GitHub 仓库
   - 问题反馈
   - 文档
   - 捐赠 (如有)

**预期结果**: 信息完整的关于页面

---

### 阶段 5: 共享组件与工具 (优先级: 中)

#### 5.1 通用 UI 组件
**目录**: `app/src/main/java/com/melody/melodylink/ui/components/`

**任务**:
1. **创建 `BatteryIndicator.kt`** - 电池指示器
   - 参数: 电量百分比、充电状态、标签
   - 显示电池图标 + 百分比 + 充电图标
   - 动态颜色根据电量变化

2. **创建 `ConnectionStatusBadge.kt`** - 连接状态徽章
   - 参数: 连接状态枚举
   - 显示彩色圆点 + 状态文字
   - 支持动画 (连接中时脉冲)

3. **创建 `DeviceImage.kt`** - 设备图片加载器
   - 从 assets 加载设备图片
   - 占位符和错误处理
   - 圆角和阴影效果

4. **创建 `LoadingDialog.kt`** - 加载对话框
   - miuix 样式加载动画
   - 可配置提示文字
   - 不可取消/可取消选项

5. **创建 `ConfirmDialog.kt`** - 确认对话框
   - 标题、内容、确认/取消按钮
   - Material You 样式
   - 回调处理

**预期结果**: 可复用的 UI 组件库

---

#### 5.2 ViewModel 和状态管理
**目录**: `app/src/main/java/com/melody/melodylink/ui/viewmodel/`

**任务**:
1. **创建 `DeviceListViewModel.kt`**
   - 管理设备列表状态
   - 处理扫描逻辑
   - 监听蓝牙状态变化
   - 提供连接/断开方法

2. **创建 `DeviceDetailViewModel.kt`**
   - 管理单个设备状态
   - 处理 ANC 切换
   - 处理电池刷新
   - 处理设置修改
   - 实现 `EarbudsFacade.Listener`

3. **创建 `SettingsViewModel.kt`**
   - 管理应用设置状态
   - 提供设置读写方法
   - 主题切换逻辑

4. **状态类定义**:
   ```kotlin
   data class DeviceListUiState(
       val devices: List<DeviceInfo> = emptyList(),
       val isScanning: Boolean = false,
       val error: String? = null
   )
   
   data class DeviceDetailUiState(
       val device: DeviceInfo? = null,
       val state: EarbudsState? = null,
       val isConnecting: Boolean = false,
       val isLoading: Boolean = false,
       val error: String? = null
   )
   ```

**预期结果**: 清晰的状态管理层，UI 和业务逻辑分离

---

### 阶段 6: 动画与交互优化 (优先级: 低)

#### 6.1 页面转场动画
**任务**:
1. 使用 Navigation3 的转场动画
2. 配置淡入淡出效果
3. 共享元素动画 (设备图片)

#### 6.2 微交互动画
**任务**:
1. 按钮点击波纹效果
2. 列表项滑动删除
3. 电池指示器数字变化动画
4. 连接状态变化动画 (脉冲效果)
5. ANC 切换滑动动画

#### 6.3 加载状态反馈
**任务**:
1. Skeleton 骨架屏 (加载时)
2. Shimmer 闪烁效果
3. 下拉刷新动画
4. 空状态插图

**预期结果**: 流畅的动画体验，符合 Material You 设计规范

---

### 阶段 7: 本地化与无障碍 (优先级: 低)

#### 7.1 国际化支持
**目录**: `app/src/main/res/values-*/`

**任务**:
1. **创建字符串资源**:
   - `values/strings.xml` (英文)
   - `values-zh-rCN/strings.xml` (简体中文)
   - `values-ja/strings.xml` (日语，可选)

2. **提取所有硬编码文字**:
   - UI 标签和按钮文字
   - 错误消息
   - 设置描述
   - Toast 提示

3. **语言切换功能**:
   - 在设置中添加语言选择
   - 应用重启后生效
   - 参考 HuaweiPods 的 `AppLocale` 实现

**预期结果**: 支持多语言的应用

---

#### 7.2 无障碍优化
**任务**:
1. 为所有 UI 元素添加 `contentDescription`
2. 确保颜色对比度符合 WCAG 标准
3. 支持 TalkBack 屏幕阅读器
4. 键盘导航支持
5. 动态字体大小支持

**预期结果**: 符合无障碍规范的应用

---

### 阶段 8: 测试与优化 (优先级: 中)

#### 8.1 功能测试
**任务**:
1. 测试所有导航路径
2. 测试主题切换 (浅色/深色/动态取色)
3. 测试设备连接/断开
4. 测试 ANC 模式切换
5. 测试电池状态更新
6. 测试设置保存和恢复
7. 测试 Xposed 模块激活状态检测

#### 8.2 兼容性测试
**任务**:
1. Android 12 (minSdk 35) 测试
2. Android 13 测试
3. Android 14/15 测试
4. 不同屏幕尺寸测试
5. 折叠屏适配测试
6. 平板布局测试

#### 8.3 性能优化
**任务**:
1. 使用 Baseline Profile 优化启动速度
2. LazyColumn 性能优化
3. 图片加载优化 (缓存)
4. 减少重组 (remember/derivedStateOf)
5. 内存泄漏检查

**预期结果**: 稳定、流畅的应用体验

---

## 技术决策

### 为什么选择 miuix？
1. **原生 HyperOS 风格**: 与小米/红米设备系统 UI 一致
2. **Material You 兼容**: 支持动态取色
3. **丰富组件**: 提供 Preference、Navigation、Blur 等开箱即用组件
4. **良好文档**: 有活跃的社区和示例项目
5. **已验证**: HuaweiPods 项目成功使用

### 架构选择
- **单 Activity 架构**: 使用 Jetpack Compose 和 Navigation3
- **MVVM 模式**: ViewModel 管理状态，UI 层无业务逻辑
- **Repository 模式**: `EarbudsFacade` 作为数据层抽象
- **依赖注入**: 手动注入 (项目较小，暂不引入 Hilt)

### 状态管理
- **本地状态**: `remember` + `mutableStateOf`
- **跨页面状态**: ViewModel + StateFlow
- **持久化**: SharedPreferences (轻量级配置)

---

## 风险与挑战

### 技术风险
1. **miuix 版本兼容性**: 确保使用稳定版本
2. **Xposed 环境限制**: UI 需要在非 hook 环境下也能运行
3. **蓝牙权限**: Android 12+ 需要运行时权限请求

### 缓解措施
1. 参考 HuaweiPods 的成功实践
2. 使用 try-catch 处理 Xposed API 调用
3. 实现完整的权限请求流程

---

## 交付物

### 阶段性成果
1. **阶段 1-2**: 可运行的空白应用框架
2. **阶段 3**: 功能完整的设备管理界面
3. **阶段 4**: 完整的设置系统
4. **阶段 5-6**: 优化和润色
5. **阶段 7-8**: 多语言和测试

### 最终交付
- ✅ 功能完整的 MelodyLink UI
- ✅ Material You 动态主题
- ✅ 多语言支持 (中英文)
- ✅ 完整的设备管理功能
- ✅ 详细的代码文档
- ✅ 用户使用文档

---

## 时间估算

| 阶段 | 工作量 | 优先级 |
|------|--------|--------|
| 阶段 1 | 2-3 小时 | 高 |
| 阶段 2 | 4-6 小时 | 高 |
| 阶段 3 | 8-10 小时 | 高 |
| 阶段 4 | 4-6 小时 | 中 |
| 阶段 5 | 4-6 小时 | 中 |
| 阶段 6 | 3-4 小时 | 低 |
| 阶段 7 | 3-4 小时 | 低 |
| 阶段 8 | 4-6 小时 | 中 |
| **总计** | **32-45 小时** | - |

---

## 下一步行动

### 立即开始
1. ✅ 创建本计划文档
2. ⏳ 添加 miuix 依赖配置
3. ⏳ 创建主题系统
4. ⏳ 实现基础导航结构

### 等待确认
- [ ] UI 设计细节 (配色、布局)
- [ ] 优先实现的功能模块
- [ ] 多语言支持的语言列表
- [ ] 测试设备和环境

---

## 参考资源

- [miuix GitHub](https://github.com/compose-miuix-ui/miuix)
- [Material You 设计指南](https://m3.material.io/)
- HuaweiPods 项目实现 (参考)
- [Jetpack Compose 文档](https://developer.android.com/jetpack/compose)
- [Navigation3 文档](https://developer.android.com/guide/navigation)

---

**计划版本**: v1.0  
**创建日期**: 2026-08-11  
**最后更新**: 2026-08-11
