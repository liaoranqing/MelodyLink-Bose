# 移植实操教程（PORTING）

本文是一份**可照着做**的移植分步教程：把一款新耳机接入 MelodyLink。

项目有两条移植路径，先判断你走哪条：

| 路径 | 适用场景 | 工作量 | 例子 |
|---|---|---|---|
| **A. 声明式（JSON）** | 新耳机属于已有协议族（Sony / Huawei / Xiaomi / Samsung），协议是 SPP 系 | 加 JSON + 图片即可 | Sony WF-1000XM5 |
| **B. 代码式（Java/Kotlin）** | 全新协议（如 Bose BMAP），或需要深度 Hook 定制 | 写 DeviceConfig + 协议 + Hook | Bose QC Ultra |

> 绝大多数情况下先试路径 A——如果目标耳机的控制协议和某个已有厂商一致，一份 JSON 就能搞定。

---

## 路径 A：声明式移植（以 Sony WF-1000XM5 为例）

### 第 1 步：拿到设备识别信息

移植前先拿到目标耳机的三个识别特征，用于写 `match`：

1. **蓝牙名称**：手机蓝牙设置里看到的设备名（如 `WF-1000XM5`）。
2. **模态（modalias）**：Linux 风格设备标识，可用命令抓：
   ```bash
   adb shell cat /sys/class/bluetooth/*/modalias
   # 或 dumpsys bluetooth_manager 里查
   ```
3. **产品图**：一张透明背景 PNG（推荐 1000×1000 左右）。

### 第 2 步：写设备配置 JSON

在 `app/src/main/assets/sony/config/` 新建 `WF-1000XM5.json`（结构参考已有设备）：

```json
{
  "schemaVersion": 1,
  "id": "sony.wf_1000xm5",
  "name": "WF-1000XM5",
  "family": "WF",
  "modelType": "TWS",
  "match": {
    "exactNames": ["WF-1000XM5"],
    "namePatterns": [".*WF-1000XM5.*"],
    "modaliasPrefixes": ["v054Cp0E63"]
  },
  "protocol": {
    "versions": ["V1", "V2"],
    "preferredVersion": "V2",
    "rfcommUuids": {
      "V1": "96cc203e-5068-46ad-b32d-e316f5e069ba",
      "V2": "956c7b26-d49a-4ba8-b03f-b17d393cb6e2"
    },
    "handshakeRequired": true
  },
  "battery": { "layout": "LEFT_RIGHT_CASE" },
  "capabilities": {
    "ancModes": ["OFF", "NOISE_CANCELING", "AMBIENT_SOUND"],
    "ambientLevel": { "min": 1, "max": 20 },
    "equalizerBands": 6
  },
  "image": "sony/images/wf-1000xm5.png"
}
```

字段含义：

| 字段 | 说明 |
|---|---|
| `match.*` | 识别规则：名称精确/正则、modalias 前缀。`SonyConfigRegistry.findBest()` 按它打分匹配 |
| `protocol.rfcommUuids` | 该耳机 RFCOMM 服务 UUID（决定用哪条 SPP 通道） |
| `battery.layout` | 电池布局（决定详情页怎么排电量） |
| `capabilities.ancModes` | 支持的降噪模式（决定三态开关显示什么） |
| `image` | 产品图相对路径 |

### 第 3 步：放产品图

把 PNG 放到 `app/src/main/assets/sony/images/wf-1000xm5.png`。

### 第 4 步：注册到 registry

编辑 `app/src/main/assets/sony/registry.json`，在 `devices` 数组加一行：

```json
"devices": [ "...", "sony/config/WF-1000XM5.json" ]
```

### 第 5 步：构建 + 验证

构建安装后，连上耳机，看日志确认被识别：

```bash
adb logcat -d | grep -i melodylink | grep -iE "sony|WF-1000XM5|matched"
```

**如果识别成功但协议不通**（RFCOMM UUID 不对、握手失败），回到第 2 步核对 `protocol` 字段——这是声明式移植最常见的失败点。

---

## 路径 B：代码式移植（Bose 为例）

当新耳机协议是全新的（Bose 用 BMAP，不是 Sony 的 SPP），或需要深度 Hook 时走这条路。以本项目 Bose 移植为例：

### 第 1 步：逆向协议

参考 [docs/REVERSING.md](REVERSING.md) 反编译目标耳机的官方 App 或现有开源工具（Bose 参考了 [bosectl](https://github.com/simonmicro/bosectl)），确定：
- 传输方式（RFCOMM channel / UUID）
- 帧格式、操作码、地址表

### 第 2 步：写设备常量

新建 `bose/BoseDeviceConfig.kt`：

```kotlin
object BoseDeviceConfig {
    val KNOWN_MACS = setOf("68:F2:1F:3D:41:D7")  // 目标耳机 MAC
    fun matchesAddress(addr: String?) = KNOWN_MACS.contains(addr?.uppercase())
}
```

### 第 3 步：写协议编解码 + 传输

- `BoseBmap.java`：BMAP 帧编解码
- `BoseTransport.java` / `transport/RfcommTransport.kt`：RFCOMM 收发

### 第 4 步：在 HookModule 注入识别 + 产品图 + 降噪

这是代码式最重的部分，三个关键点（详见 [docs/ARCHITECTURE.md](ARCHITECTURE.md)）：

1. **目录注入**：Hook `L6/a` / `c9/a` 的查询方法，注入/克隆设备条目。
2. **产品图**：定位详情页/设置页的 ImageView 字段，替换图片。
3. **降噪子级**：剥离宿主错误的子级（如 Bose 上残留 Enco 的四级降噪）。

### 第 5 步：验证

用 `MLog.event` 打诊断事件，装机后 `grep evt=` 看分布，逐项确认。

---

## 移植检查清单

移植任何耳机后，逐项确认：

- [ ] 设备能被识别（详情页不再是「未知设备」）
- [ ] 产品图正确显示
- [ ] 电量（左/右/盒）显示正确
- [ ] 降噪/通透/关闭 三态可切换且生效
- [ ] 没有残留宿主的错误交互（如 Enco 的四级降噪）
- [ ] 真 OPPO 耳机页面不受影响（回归测试）
- [ ] 日志无异常（`hook.miss` 正常，`image.truth` 无 blocker）

---

## 常见坑速查

1. **识别失败**：先确认走的哪套 catalog（按 MAC 还是 productId+name），日志取证，别凭假设。
2. **协议不通**：核对 RFCOMM UUID / channel，抓包看握手。
3. **图不显示**：看 `image.truth` 的 `blocker`，可能是容器被 GONE（含自己的 hook 误伤）。
4. **跨版本失效**：Melody 升级后 R8 混淆名变了，必须在目标版本 smali 重新验证。
