# 0.4.4 变更 Summary

**提交**：`eec73a1` · **versionCode 20 / versionName 0.4.4-bose** · 4 个文件，+95 / -23

> ⚠️ 本地已 commit，**尚未 push**。需你在 GitHub Desktop 点 Push origin，CI 绿后安装。

---

## 背景：0.4.3 你反馈的三个问题

| 现象 | 根因（日志实锤） |
|---|---|
| Melody 里"降噪效果"还在、滑条不出现 | 注入靠事后遍历 preference 树找标题，**跑在 `NoiseReductionItem` 入树之前** → 必然 null，静默跳过 |
| 详情页还是显示"降噪效果" | 同上，同一处代码 |
| 图片先转圈、随后变通用耳机图 | Melody **异步**加载自家产品图，最后一次 `setImageDrawable` 覆盖了我们的图 |

关键日志对比：`12:08:09` 注入成功有日志；`12:08:21` 重开页面 `onViewCreated` 触发了，但**既无注入日志也无异常日志** → 证明是被守卫静默 return，不是崩了。

---

## 三处修复

### ① 滑条改用 addPreference 钩子可靠注入

不再"事后搜树"，改成在 `detailPreferenceAdd` 钩子里**按类名认出行对象**：

```
captureNoiseEffectRow(preference)
  └ 类名 == com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem
      └ 取它的 getParent() 当锚点
          └ 延迟 80ms / 400ms 两次注入（避开 addPreference 调用栈，防 ConcurrentModification）
              └ 以 BOSE_CNC_KEY 幂等去重
```

- 原 `installAdvancedSettingsFromPreferenceFragment` 里的树遍历**降级为兜底**：只负责把"降噪效果"这行隐藏掉
- 页面守卫天然成立：隐私保护中心没有 `NoiseReductionItem`，不会误注入
- `addBoseCncPreference` 改为返回 `boolean`，add 被拒会打日志

### ② 产品图换成 Bose 官方高清图

你提供的官方图是**已带透明通道的 PNG**（800×800，74.9% 透明），无需抠图，只需按 alpha bbox 裁边 → **587×729** 直接入资源。金属质感与 BOSE logo 完整保留。

### ③ 延迟重贴盖过原生异步加载

设图后按 **250 / 900 / 2000 / 4000 ms** 四次重贴 `applyBoseImage`，每轮同时：

- `setImageURI(Uri.fromFile(...))`
- `cancelAnimation()` + `setVisibility(GONE)` 掉 loading 视图

→ 转圈不会再回来，最终状态一定是 Bose 图。

---

## 已知上一版遗留修复（1c6d588，0.4.3 里带的）

- **滑条写入 100% 静默失败**：`boseTransport.connect()` 只在 ANC 连接分发被调；磁贴/滑条走的 `resolveBoseForTile()` 从不给 transport 绑设备 → `openSocket` 必抛 `no Bose device selected`。已加 `setDevice()` 幂等绑定
- `runSettingWrite` 失败路径原本**一行日志都不打**，现已补上写入成功/失败上报

---

## 装完请验证（1→4 逐项回报即可）

1. 详情页"降噪效果"行**消失**、原位置出现"降噪等级"滑条 —— **反复退出重进 3~4 次，每次都该在**（这条是本轮重点，之前就是时有时无）
2. 拖滑条 → 耳机降噪强度**实际变化**
3. 顶部大图 → 稳定官方 Bose 图，**不转圈**、不变通用图，多开关几次都稳
4. 音量面板磁贴快速连点 → 保持已修好的状态（0.4.2 的 600ms settle 生效，你已确认 ✅）

若第 1 条仍偶发，日志里搜 `installed Bose CNC slider` / `Bose CNC slider install failed` 可直接分辨是"没抓到行"还是"add 被拒"。

---

## 下一轮预告：抗风噪（未提交，等你确认后再做）

**不需要破解** —— bosectl 项目 NOTES.md 实锤：`[31.10]` 5 字节寄存器布局为
`[cnc, autoCNC, spatial, wind, anc]`，**索引 3 就是风噪开关**。

与现在能用的 CNC（索引 0）、空间音频（索引 2）**同一个寄存器、同一个 operator**。Bose 官方 App 不给这个开关，但固件一直开着，只是 UI 没暴露入口 —— 产品层面隐藏，非加密/认证。

⚠️ **关键听感互斥**（bosectl 实测记录）：风噪开启会**屏蔽 CNC DSP 通路**，即开了风噪后降噪等级 0 与 10 听感相同。两个功能不能同时享受。

**已完成（存根 `windblock-wip.patch`，未提交）**：
- `BoseDeviceConfig`：`SETTING_RESERVED = 3` → `SETTING_WIND = 3`
- `BoseTransport`：风噪缓存 + 乐观更新（同 CNC/空间模式）
- `MelodySharedStateStore`：跨进程状态/命令文件扩展风噪字段（兼容旧格式）

**待做**：详情页滑条旁加"抗风噪"开关（Melody SwitchPreference）+ 与降噪等级的互斥提示。

**bosectl 里其他可移植项**：3 段 EQ、按键重映射、自定义模式槽位 5-10。要哪个一并说。
