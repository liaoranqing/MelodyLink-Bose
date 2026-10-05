# Melody 固件反编译方法（REVERSING）

本文说明如何反编译 Melody APK、定位混淆类/方法/资源，以及相关工具用法。这是移植适配最关键的一步。

---

## 1. 获取 Melody APK

在已 root 的 OPPO/一加 手机上：

```bash
adb shell pm path com.oplus.melody
# 输出 package:/system/priv-app/xxx/xxx.apk
adb pull <上面的路径> melody.apk
```

或从系统 OTA 包中提取。本项目分析的版本为 **17.6.3**，APK 含 2 个 dex（`classes.dex`、`classes2.dex`）。

---

## 2. 工具准备

| 工具 | 用途 | 获取 |
|---|---|---|
| apktool | APK 解包/重打包（含资源） | https://ibotpeaches.github.io/Apktool/ |
| baksmali | dex → smali | https://github.com/skylot/jadx（或 baksmali 单独 jar） |
| jre 17 | 运行 baksmali | Adoptium |
| androguard | 解析 AXML/ARSC（Python） | `pip install androguard` |
| 本仓库 tools/ | 资源 ID 反查脚本 | `tools/find_resid*.py` |

> Windows 下 baksmali 有个坑：文件系统大小写不敏感，`c9/a`（小写包）与 `c9/A`（R8 合成类）会冲突，后者抢占 `A.smali`，前者被写成 `c9.1/a.smali`。找小写类先看 `.1` 后缀目录。

---

## 3. dex 反编译为 smali

```bash
# baksmali（本项目用 bsm.jar + jre）
java -jar bsm.jar d classes.dex  -o smali_out/
java -jar bsm.jar d classes2.dex -o smali2_out/
```

反编译后得到大量 `.smali` 文件，按包名目录组织。R8 混淆后的类名是短名（如 `Ba/m`、`G9/Q`、`L6/a`、`c9/a`），方法名也常被重命名为单字母。

### 3.1 从调用点反查类身份

看到 `invoke-static ... Lc9/a;->c(...)` 这种调用，要确认 `c9/a` 到底是什么，三步验证：

1. `grep -rl "Lc9/a;" smali_out/` 看引用点
2. 看 `c9/a.smali` 的 `.super` / `.source`（`.source` 常保留原文件名，如 `WhitelistRepository.kt`）
3. 看字段引用（`iget` / `iput`）判断它操作什么 DTO

> 注意：`.source` 保留原类名 ≠ 类就是宿主。R8 混淆时 `.source` 可能不准确，必须结合实例化点、字段类型、方法签名交叉验证。

### 3.2 方法签名（R8 会重命名方法名，但保留类型）

铁律：**R8 重命名方法名但保留类型/字段名**。反射找方法时，必须先看 smali 里方法的真实签名（方法名 + 返回类型 + 参数类型），按签名特征解析，且特征必须唯一。

---

## 4. APK 解包（查看资源/布局）

```bash
apktool d melody.apk -o melody_dec/
```

解包后 `res/` 目录有布局、字符串等资源。但布局是二进制 AXML，直接用 androguard 解析：

```python
from androguard.core.axml import AXMLPrinter
import zipfile, logging
logging.disable(logging.CRITICAL)

z = zipfile.ZipFile('melody.apk')
data = z.read('res/gj.xml')  # 某个布局文件
ap = AXMLPrinter(data)
print(ap.get_xml().decode('utf-8', 'replace'))
```

这样可以拿到布局的完整 XML（含 view 层级、id、visibility 属性），用于定位"图片装在哪个容器里""哪个容器默认 GONE"。

---

## 5. 资源 ID 反查

Melody 混淆后，代码里只有资源 ID（如 `0x7f0901fc`），要反查它对应哪个名字（如 `device_image`）。本仓库提供两个脚本：

```bash
# 按 ID 反查名字
python tools/find_resid_rev.py 0x7f0901fc
# 输出：('0x7f0901fc', 'id', 'device_image', ...)

# 按名字反查 ID
python tools/find_resid.py "device_image"
```

原理：手写解析 `resources.arsc` 的字符串池与资源表（不依赖第三方库）。脚本里的 APK 路径需改成你自己的。

---

## 6. 定位 Hook 点的方法论

1. **从 UI 现象反推**：详情页图不显示 → 找 `MelodyDetailModelView` → 看它字段里哪个是 ImageView。
2. **hook 系统 API 抓调用栈**：hook `View.setVisibility` / `Activity.finish` 等系统方法，记录调用栈，直接拿到"谁隐藏了 X"。
3. **全量搜实例化点**：`grep -rl "new-instance.*LBa/z;" smali_out/` 找谁创建了这个类。
4. **查字段引用**：`grep -rn "->add" ` 看某个"可写列表"是否有写入点，区分"运行时注册表"和"编译期常量表"。

---

## 7. 常见混淆陷阱

| 陷阱 | 表现 | 应对 |
|---|---|---|
| 方法名被改 | `hookNamed("onViewCreated")` miss | 看 smali 真实方法名（如 `t`），按签名 hook |
| 类名跨版本变 | 从旧版抄的类名失效 | 在目标版本 smali 重新验证 |
| 伪装 android.* 包 | 宿主类叫 `android.telephony.RensGlaent` | 抓栈用白名单（只收宿主包名），别用排除法 |
| lambda 类名含 `$$` | 被当成框架帧过滤 | 诊断时用不过滤的 raw 栈 |

详见 [docs/TROUBLESHOOTING.md](TROUBLESHOOTING.md) 的排查铁律。
