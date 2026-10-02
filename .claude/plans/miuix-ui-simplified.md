# MelodyLink UI 实现计划（简化版）

## 项目范围

参照 **HuaweiPods** 实现，仅保留核心功能：

### 保留的页面
1. ✅ **模块首页** - 主要功能界面
2. ✅ **设置页面** - 仅主题选项和关于选项
3. ✅ **基础主题系统** - Material You 动态取色

### 移除的功能
- ❌ 设备列表页面
- ❌ 设备详情页面
- ❌ 完整设置选项（连接、通知等）
- ❌ 自定义动画（使用 Android 原生）
- ❌ 多语言支持（后续可选）

---

## 实施计划

### 阶段 1: 依赖配置 (1-2 小时)

#### 1.1 添加 miuix 依赖

**文件**: `gradle/libs.versions.toml`

```toml
[versions]
# 现有版本...
miuix = "1.2.0"
navigation3 = "1.0.0"

[libraries]
# 现有库...
# MIUIX 核心库
miuix = { group = "io.github.miuix", name = "miuix", version.ref = "miuix" }
miuix-preference = { group = "io.github.miuix", name = "miuix-preference", version.ref = "miuix" }
miuix-icons = { group = "io.github.miuix", name = "miuix-icons", version.ref = "miuix" }
miuix-blur = { group = "io.github.miuix", name = "miuix-blur", version.ref = "miuix" }
miuix-navigation3-ui = { group = "io.github.miuix", name = "miuix-navigation3-ui", version.ref = "miuix" }

# Navigation3
navigation3-runtime = { group = "io.github.miuix", name = "navigation3-runtime", version.ref = "navigation3" }
```

**文件**: `app/build.gradle.kts`

```kotlin
dependencies {
    // 现有依赖...
    
    // MIUIX
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.navigation3.ui)
    
    // Navigation3
    implementation(libs.navigation3.runtime)
}
```

**文件**: `settings.gradle.kts`

添加 Maven 仓库：
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")  // 添加这行
    }
}
```

---

### 阶段 2: 主题系统 (2-3 小时)

#### 2.1 创建主题文件

**文件**: `app/src/main/java/com/melody/melodylink/ui/theme/Theme.kt`

参照 HuaweiPods 实现：

```kotlin
package com.melody.melodylink.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun MelodyLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) 
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

#### 2.2 更新 MainActivity

**文件**: `app/src/main/java/com/melody/melodylink/MainActivity.kt`

```kotlin
package com.melody.melodylink

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.melody.melodylink.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val prefs = remember { 
                getSharedPreferences("melody_prefs", Context.MODE_PRIVATE) 
            }
            val themeMode = remember { 
                mutableStateOf(prefs.getInt("theme_mode", 0)) 
            }
            
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false  // 强制浅色
                2 -> true   // 强制深色
                else -> systemDark  // 跟随系统
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, 
                        Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT, 
                        Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose {}
            }

            App(
                darkMode = darkMode,
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                }
            )
        }
    }
}
```

---

### 阶段 3: 应用结构 (3-4 小时)

#### 3.1 创建 App 容器

**文件**: `app/src/main/java/com/melody/melodylink/ui/App.kt`

```kotlin
package com.melody.melodylink.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.melody.melodylink.ui.screens.HomeScreen
import com.melody.melodylink.ui.screens.SettingsScreen
import com.melody.melodylink.ui.theme.MelodyLinkTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun App(
    darkMode: Boolean,
    themeMode: MutableState<Int>,
    onThemeModeChange: (Int) -> Unit
) {
    MelodyLinkTheme(darkTheme = darkMode) {
        val navController = rememberNavController()
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "MelodyLink"
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home"
            ) {
                composable("home") {
                    HomeScreen(
                        padding = padding,
                        onNavigateToSettings = {
                            navController.navigate("settings")
                        }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        padding = padding,
                        themeMode = themeMode.value,
                        onThemeModeChange = onThemeModeChange,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}
```

---

### 阶段 4: 首页实现 (4-5 小时)

#### 4.1 创建首页

**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/HomeScreen.kt`

```kotlin
package com.melody.melodylink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    padding: PaddingValues,
    onNavigateToSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // 模块状态卡片
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "模块状态",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Xposed 模块已激活",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        
        item {
            // 支持的设备品牌
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "支持的设备",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• Sony 耳机")
                    Text("• Samsung Galaxy Buds")
                    Text("• Huawei FreeBuds")
                    Text("• Bose 耳机")
                }
            }
        }
        
        item {
            // 快捷操作
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "快捷操作",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("打开设置")
                    }
                }
            }
        }
        
        item {
            // 使用说明
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "使用说明",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. 确保 Xposed 模块已激活\n" +
                              "2. 重启系统使模块生效\n" +
                              "3. 连接支持的蓝牙耳机\n" +
                              "4. 在系统蓝牙设置中查看耳机信息",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
```

---

### 阶段 5: 设置页面 (2-3 小时)

#### 5.1 创建设置页面

**文件**: `app/src/main/java/com/melody/melodylink/ui/screens/SettingsScreen.kt`

```kotlin
package com.melody.melodylink.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melody.melodylink.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        // 返回按钮
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回"
            )
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 外观设置
            item {
                Text(
                    text = "外观",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showThemeDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "主题模式",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = when (themeMode) {
                                    1 -> "浅色"
                                    2 -> "深色"
                                    else -> "跟随系统"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // 关于
            item {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp, top = 16.dp)
                )
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showAboutDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "应用信息",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "版本 ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("选择主题") },
            text = {
                Column {
                    RadioOption(
                        text = "跟随系统",
                        selected = themeMode == 0,
                        onClick = {
                            onThemeModeChange(0)
                            showThemeDialog = false
                        }
                    )
                    RadioOption(
                        text = "浅色",
                        selected = themeMode == 1,
                        onClick = {
                            onThemeModeChange(1)
                            showThemeDialog = false
                        }
                    )
                    RadioOption(
                        text = "深色",
                        selected = themeMode == 2,
                        onClick = {
                            onThemeModeChange(2)
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 关于对话框
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于 MelodyLink") },
            text = {
                Column {
                    Text("版本: ${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("MelodyLink 是一个 Xposed 模块，用于增强多品牌无线耳机的系统集成。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("支持的品牌:")
                    Text("• Sony")
                    Text("• Samsung")
                    Text("• Huawei")
                    Text("• Bose")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
private fun RadioOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text)
        RadioButton(
            selected = selected,
            onClick = onClick
        )
    }
}
```

---

## 文件清单

### 需要修改的文件
1. ✅ `gradle/libs.versions.toml` - 添加依赖版本
2. ✅ `settings.gradle.kts` - 添加 Maven 仓库
3. ✅ `app/build.gradle.kts` - 添加依赖实现
4. ✅ `MainActivity.kt` - 重写主入口

### 需要创建的文件
1. ✅ `ui/theme/Theme.kt` - 主题系统
2. ✅ `ui/App.kt` - 应用容器
3. ✅ `ui/screens/HomeScreen.kt` - 首页
4. ✅ `ui/screens/SettingsScreen.kt` - 设置页

### 可以删除的文件
- ❌ `ui/theme/Color.kt` (可选，保留或删除)
- ❌ `ui/theme/Type.kt` (可选，保留或删除)

---

## 实施顺序

### 第一步: 配置依赖 (30 分钟)
1. 修改 `gradle/libs.versions.toml`
2. 修改 `settings.gradle.kts`
3. 修改 `app/build.gradle.kts`
4. 同步项目，确保依赖下载成功

### 第二步: 创建主题 (1 小时)
1. 创建 `ui/theme/Theme.kt`
2. 更新 `MainActivity.kt`

### 第三步: 实现页面 (3-4 小时)
1. 创建 `ui/App.kt`
2. 创建 `ui/screens/HomeScreen.kt`
3. 创建 `ui/screens/SettingsScreen.kt`

### 第四步: 测试验证 (1 小时)
1. 编译运行
2. 测试主题切换
3. 测试页面导航
4. 测试边到边显示

---

## 预计时间

**总计: 6-8 小时**

- 依赖配置: 0.5 小时
- 主题系统: 1 小时
- 首页实现: 2 小时
- 设置页面: 1.5 小时
- 测试调试: 1 小时

---

## 与 HuaweiPods 的差异

### 简化的地方
- ❌ 没有复杂的设备管理
- ❌ 没有电池显示组件
- ❌ 没有 ANC 控制
- ❌ 没有多语言支持
- ❌ 没有自定义动画

### 保留的精华
- ✅ Material You 动态取色
- ✅ 主题切换系统
- ✅ 边到边显示
- ✅ miuix 风格组件
- ✅ 简洁的导航结构

---

**计划版本**: v2.0 (简化版)  
**创建日期**: 2026-08-11  
**预计完成**: 1 天内
