# 错误排查与解决方案（TROUBLESHOOTING）

本文记录项目实施过程中真实踩过的错误、根因与解决方案，以及一套可复用的排查铁律。

---

## 1. 典型 Bug 案例（现象 → 根因 → 解决）

### 1.1 通用设置页耳机图片不显示

- **现象**：日志显示 `image.applied` 成功，但用户看不到图。
- **定位**：加了回读诊断 `image.truth`（采样 attached/size/vis/drawable/blocker），发现 `blocker=FrameLayout@device_image_container:vis=8`。
- **根因**：**自己的代码误伤**。`applyBoseImage → stopLoadingSpinners(imageView)` 遍历父链时对每个祖先调用 `hideLoadingView(parent)`，而 `hideLoadingView` 无条件 `setVisibility(GONE)`，把 `device_image_container` 这些普通容器当 loading 设 GONE 了。
- **解决**：`hideLoadingView` 加类型判断，只对 spinner 类（Lottie/Progress/Loading/Spin）设 GONE。
- **教训**：破坏性操作（setVisibility GONE）必须白名单守卫；「写进去」≠「看得见」，成功事件要回读校验。

### 1.2 详情页闪一下 → 空白 → 再出现 → 几秒消失

- **现象**：详情页反复 onCreate→finish，或 DecorView 被 INVISIBLE。
- **根因（第一阶段）**：拦截了 `Activity.finish`，但 `DetailMainActivity.onCreate:60` 的 finish 是宿主「finish previous instance」单例守卫，吞掉它制造了多实例互杀。
- **解决**：finish 拦截回滚为纯取证（恒 `chain.proceed()`）。
- **根因（第二阶段）**：`DecorView INVISIBLE` 是 `ActivityThread.handleResumeActivity` 触发的**系统 resume 正常中间态**（先 INVISIBLE→addView→再 VISIBLE），根本不是 bug。
- **教训**：早期反常数据必须当真；把修复推得比证据更远会制造新 bug。

### 1.3 详情页仍有 Enco X3 的降噪交互（四级降噪 + 增强人声）

- **现象**：Bose 详情页显示「降噪强度选择（3档+1智能）」和「增强人声」。
- **排查链**：宿主有两套 catalog（`L6/a` 按 MAC、`c9/a` 按 productId+name），早期只在 `L6/a` 注入，而详情页走的是 `c9/a`。
- **根因（之一）**：`cloneCatalogEntry` 里 `if (value instanceof Parcelable) continue` 静默丢弃了 `WhitelistConfigDTO$Function`（它 implements Parcelable），导致克隆条目的 function 恒为 null。
- **解决**：删掉 Parcelable 跳过；并在 `detail.lookup_stripped` 里对 Bose MAC 正向判据剥离子级。
- **教训**：克隆/拷贝时要检查是否有「instanceof 跳过」这类静默丢弃；跨版本类名必须重新验证。

### 1.4 详情页 ANR（主线程卡死）

- **现象**：setVisibility hook 无限递归，主线程冻死。
- **根因**：hook 系统 API 内部再调同一 API，值比较守卫放在 `chain.proceed()` 之后无效（proceed 已写入请求值，`getVisibility()==visibility` 恒真）。
- **解决**：用 ThreadLocal 重入标志，重入帧直接返回。
- **取证**：`adb shell dumpsys dropbox --print | grep data_app_anr` 直接给出递归/阻塞点（logcat 常被冲掉，dropbox 保留更久）。

### 1.5 图片装进「正确字段」但看不到

- **根因**：`instanceof View` 守卫恒 false（owner 是 Preference 不是 View）；`loadingField` 传错成 `e`（e 是产品图自己，loading 在 `d`）。
- **教训**：字段类型判断用 `isAssignableFrom` 而非 `List.equals(type)`；改字段前先读 smali 确认真实类型。

---

## 2. 排查铁律（浓缩）

1. **早期反常数据必须当真**；未被验证的假设不能作为后续修复依据。
2. **同一方向连续 3 轮无进展 = 方向错了**，换观察维度，不是加更细的探针。
3. **hook 系统 API 抓调用栈 > 读混淆代码猜逻辑**（hook `View.setVisibility` 直接拿隐藏者）。
4. **「写进去」≠「看得见」**：注入后验证 attached，沿 parent 链找屏幕上真实祖先；判断在屏看 Activity 焦点/生命周期，判断树建好看子项数。
5. **诊断返回值必须回读校验**；成对输出「结果标志 + 产物是否存在」。
6. **R8 重命名方法名但保留类型/字段名**：反射找方法必须动手前看 smali 真实签名。
7. **破坏性宿主操作必须白名单式守卫**，明确列出允许的类名/key；改完自问「最坏会隐藏什么」。
8. **诊断 hook 本身会成为故障源**：热路径方法必须去重/采样，先估频率再上线。
9. **hook 注册成功 ≠ 会被调用**；长期零触发先怀疑「根本没挂上」或「调用链入口不在这」。
10. **区分「没触发」与「触发后无效」**：先查事件计数，计数=0 查调用路径，有事件但结果不对才是逻辑问题。
11. **跨版本宿主混淆名会变**：抄来的类名必须在目标版本 smali 重新验证。
12. **日志矛盾当根因线索**（如 ok=true + 无 injected + 无 add_failed ⇒ 锁定短路点）。
13. **分阶段修复一次只引入一个变量**；多改动叠加无法定位回归。

---

## 3. 日志实操

```bash
# 抓日志（ColorOS 下必须用户手动打开 Melody，monkey/resolve-activity 拉不起）
adb logcat -c && adb shell am force-stop com.oplus.melody
# 用户手动打开 App → 重现
adb logcat -d | grep melodylink > logs.txt

# 先看事件分布
grep -oE "evt=[a-z_.]+" logs.txt | sort | uniq -c

# ANR/闪退取证
adb shell dumpsys dropbox --print | grep data_app_anr
```

关键事件速查：

| 事件 | 含义 |
|---|---|
| `image.applied` / `image.truth` | 图替换 / 回读校验（blocker 点名遮挡者） |
| `detail.lookup_stripped` | 降噪子级剥离（#4 生效） |
| `inject.verified` | 条目注入结果 |
| `detail.finish_after_transition` | 关页触发点（kind=decision 是决策点） |
| `hook.miss` | hook 未命中（R8 方法名对不上） |

---

## 4. 移植时最容易踩的坑速查

1. 目录查错了（两套 catalog）→ 注入「成功」但页面不认。
2. 字段类型搞错（Preference vs View）→ 守卫恒 false，死代码。
3. 克隆时 instanceof 跳过 → 关键字段静默丢失。
4. 自己 hook 里误伤宿主容器 → 现象和「宿主隐藏」一模一样。
5. 从旧版本抄混淆名 → 在新版本失效，必须重新验证。
