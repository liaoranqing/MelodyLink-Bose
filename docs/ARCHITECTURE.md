# 架构与设计思路（ARCHITECTURE）

本文说明 MelodyLink-Bose 的**整体设计思路、技术路径**，以及移植到其他耳机时需要理解的核心机制。

---

## 1. 设计思路

### 1.1 为什么用 LSPosed Hook，而不是改 APK？

Melody（`com.oplus.melody`）是系统预装应用，直接改 APK 需要：
- 重新签名（会破坏系统签名，无法覆盖安装/无法通过系统校验）
- 每次系统 OTA 都要重做

而 LSPosed 模块是在**运行时** Hook Melody 进程，不碰 APK 本体：
- 升级 Melody 后只需适配混淆名
- 开关可控（LSPosed 里随时禁用）
- 保留原 APK 签名与系统完整性

### 1.2 核心隐喻：把第三方耳机"伪装"成 Melody 认识的设备

Melody 内部有一套面向 OPPO/一加 耳机的数据流：

```
蓝牙连接 → 设备目录查询(WhitelistConfigDTO) → EarphoneDTO → ViewModel → 详情页/设置页渲染
```

第三方耳机断在"设备目录查询"这一环——目录里没有它，后面的 DTO、渲染全都拿不到。所以模块的思路是**在目录查询这一环注入 Bose 条目**，让后续整条链路"以为"这是一台被支持的设备。

### 1.3 分层

| 层 | 职责 | 主要文件 |
|---|---|---|
| Hook 层 | 挂到 Melody 类上，拦截方法、注入数据 | `hook/HookModule.java` |
| 反射工具层 | Preference 树操作、字段读写、方法签名解析 | `hook/PrefRef.java` |
| 设备配置层 | Bose MAC/名称/模式常量 | `bose/BoseDeviceConfig.kt` |
| 传输层 | RFCOMM 连接与帧收发 | `transport/`、`bose/BoseTransport.java` |
| 协议层 | BMAP 编解码 | `bose/BoseBmap.java` |
| 厂商适配层 | 各品牌差异封装（移植模板） | `vendor/`、`sony/`、`huawei/` 等 |

---

## 2. 技术路径（Hook 链路详解）

所有 Hook 通过统一入口注册：

```java
hookNamed(loader, "L6/a", "a", 1, "detailWhitelistLookup");
//             类名      方法   参数个数  事件label
```

然后在统一分发点按 `label` 处理返回值。这种"注册点 + label 分发"的写法让每个 hook 都能打独立的诊断事件（`MLog.event`），是排查的关键。

### 2.1 设备目录注入（识别 Bose）

Melody 17.6.3 有**两套独立的设备目录**，这是本项目踩过最大的坑：

| 类 | 角色 | 查询键 |
|---|---|---|
| `L6/a` | SupportConfigManager | **MAC 地址** |
| `c9/a` | WhitelistRepository | **Product ID + 蓝牙名** |

早期版本只在 `L6/a` 注入克隆条目，却发现 Bose 详情页根本不走这条路——因为详情页实际走的是 `c9/a.c(productId, name)`（按 productId+name 查，不按 MAC）。这解释了"Bose MAC 从不出现在任何 lookup 日志"的诡异现象。

**结论**：移植时先确认目标页面到底走哪套目录，用日志取证（hook 目录查询方法 + 打 MAC/name 参数），不要凭经验假设。

### 2.2 产品图替换

两个页面的图片来源不同：

| 页面 | 图片宿主 | 字段/位置 |
|---|---|---|
| 详情页 | `MelodyDetailModelView` | 异步加载，三级降级 model→webp→pic |
| 通用设置页 | `OneSpaceHeaderPreference` | `field e`（ImageView） |

模块在 bind 时机用内置 PNG 替换，并做多轮 pin（`pinBoseImageView`：定时 + onLayoutChange + onAttach 多重兜底），因为宿主会异步加载原图覆盖。

**移植要点**：图片位置必须从 smali 里确认字段名，不能猜。`field e` 是 ImageView 而不是 loading spinner——早期把 `loadingField` 写成 `e`，导致把刚装上的产品图又设成 GONE。

### 2.3 降噪子级剥离（去掉 Enco 交互残留）

数据链路（smali 坐实）：

```
NoiseReductionItem:549 getNoiseReductionModeList()
  → Ba/m.a(list, idx, Ba/z)
  → 遍历 list，某 mode 的 getChildrenMode() 非空
  → 才构建 Ba/x（四级降噪选择器）+ Ba/A（增强人声）
```

所以只要让 Bose 的 mode 列表里 `childrenMode` 为空，宿主的四级选择器/增强人声就根本不会创建。剥离在 catalog 数据源头做（`detail.lookup_stripped`），只对 Bose MAC 生效，避免误伤用户的真 OPPO Enco X3。

### 2.4 三态降噪与 CNC

- 三态（关闭/降噪/通透）：复用 Melody 的 `OneSpaceNoisePreference`（`pref_noise_switch`）。
- CNC 0–10 降噪等级：注入 `MelodyPromptVolumeSeekBarPreference`（详情页挂在「降噪效果」行下）。

---

## 3. 移植到其他耳机的步骤

1. **确定识别方式**：目标耳机如何在 Melody 里被识别？MAC？名称？先 hook 目录查询方法，取证实际查询参数。
2. **找图片位置**：反编译详情页/设置页，定位产品图 ImageView 的字段或资源 ID。
3. **确定降噪交互**：目标耳机的降噪模式是几档？是否需要剥离宿主的错误子级？是否需要注入自定义滑条？
4. **底层控制**：目标耳机的控制协议（Bose=BMAP，Sony/Huawei/Xiaomi/Samsung 各有 SPP/RFCOMM 协议，参考 `vendor/` 与 `assets/*/config/`）。
5. **混淆适配**：每个 Melody 版本的 R8 混淆名都不同，务必在目标版本的 smali 里重新验证类名/方法名（见 [docs/REVERSING.md](REVERSING.md)）。

---

## 附录：Bose BMAP 协议要点

> 来源：bosectl（https://github.com/simonmicro/bosectl）

| 项 | 值 |
|---|---|
| 设备代号 | edith |
| Product ID | 0x4062 |
| 传输 | BMAP over RFCOMM，channel 2 |
| 服务 UUID | `00000000-deca-fade-deca-deafdecacaff` |

操作码：

```
0x00 SET        （需云端认证）
0x01 GET        （公开）
0x02 SETGET     （部分可用）
0x03 STATUS     （设备响应）
0x04 ERROR
0x05 START      （触发动作，无需认证）
0x06 RESULT
0x07 PROCESSING
```

关键地址：

| 功能 | Block | Function | 操作码 |
|---|---|---|---|
| 当前模式 | 31 | 3 | GET / START |
| 音频设置（CNC/ANC） | 31 | 10 | GET / SETGET |
| 电量 | 2 | 2 | GET（4 字节/组件） |
| EQ | 1 | 7 | GET / SETGET |
| 语音提示 | 1 | 3 | GET / SETGET |
| 摘戴暂停 | 1 | 24 | GET / SETGET |

> 注意：部分 `SET` 操作需要 Bose 云端 ECDH 握手认证，本项目用 `START` / `SETGET` 规避，但重启耳机后部分设置会还原。
