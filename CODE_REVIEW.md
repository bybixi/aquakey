# AquaKey 代码审查报告与修复方案

| | |
|---|---|
| 审查日期 | 2026-10-15 |
| 审查范围 | `app/src` 全部 30 个 Kotlin 文件（4394 行）· 构建脚本 · AndroidManifest · ProGuard |
| 审查方式 | 逐模块人工走查 + 索引边界验算 + 死代码/疑点扫描 |
| 结论 | **26 项发现：🔴 高危 3 · 🟠 中危 7 · 🟡 低危 16** |
| 修复任务 | 已登记为任务 **#8–#18**（共 11 个） |
| 基线提交 | `d310d44`（main） |

---

## 修复状态

**26 项已全部修复，版本 `2.2.0`（versionCode 2）**，测试从 6 个增加到 30 个。

| 编号 | 状态 | 修复要点 | 验证方式 |
|---|---|---|---|
| H1 | ✅ | 三个扫描集合换成 `ConcurrentHashMap`，首见序号改用 `putIfAbsent` | 代码级 + 列表稳定性回归；**50ms 压测待补** |
| H2 | ✅ | `startWith` 加 stage 门控；顶栏按钮仅 IDLE 可点 | **真机**：会话中点击顶栏蓝牙按钮不再弹出面板 |
| H3 | ✅ | `startSession` 整体 try/catch，失败统一 `teardown()` | 代码级 + 非水控设备连接的报错路径；**未在真机复现裸泄漏场景** |
| M1 | ✅ | DataStore 加 `.catch`，返回 `emptyPreferences()` | 代码级 |
| M2 | ✅ | `fail()` 同时 complete `endDeferred`；结束流程不再 `armTimeout()` | **真机**：结束会话无多余错误弹窗 |
| M3 | ✅ | 记账统一到 `accountSession()`，两条结束路径都汇聚 | **真机**：异常退出与正常结束都正确写入记录 |
| M4 | ✅ | `tryAutoReconnect` 改为 suspend 并 `settings.first()` | 代码级 |
| M5 | ✅ | 扫描 30 秒自动停止；列表上限 50 | **真机**：30 秒后自动停止且面板保留结果 |
| M6 | ✅ | `release()` 里 `scope.cancel()` | 代码级 |
| M7 | ✅ | 抽出纯函数 `reactToNotification`，新增 24 个用例 | **24 个用例全绿**（含协议向量端到端锚定） |
| 低 1–13 | ✅ | 见第三节 | 编译 + 真机回归 |

修复过程中额外发现并处理的两个问题：

- `WaterViewModel.startWith` 的 catch 对 `WaterCtlException` 不调用任何清理 —— 即使 BLE 层已经
  `teardown()`，ViewModel 侧的状态仍可能停在 CONNECTING。已由 H3 的统一清理覆盖。
- 新增的失败日志原本用 `e.javaClass.simpleName`，release 经 R8 混淆后输出变成单个字母
  （日志里出现"启动失败: Y"），对排查毫无帮助。已改为输出 `WaterCtlErrorKind`。

---

## 阅读指引

- 每条发现按 **编号 / 位置 / 问题描述与影响 / 根本原因 / 解决步骤 / 验证方法** 组织
- 编号规则：`H` = 高危，`M` = 中危，`L` = 低危
- 「位置」以**符号名为主、行号为辅** —— 行号会随编辑漂移，符号名不会
- 低危项按关联性归并为 13 组（覆盖 16 条原始发现），每组同样包含四要素
- 推荐的修复顺序见文末「五、修复顺序」

---

## 总览

| 严重程度 | 数量 | 编号 | 涉及模块 |
|---|---|---|---|
| 🔴 高危 | 3 | H1 H2 H3 | `core/ble` · `domain` |
| 🟠 中危 | 7 | M1 M2 M3 M4 M5 M6 M7 | `data/prefs` · `core/ble` · `domain` |
| 🟡 低危 | 16 | L1–L16 | 全部模块 |

一个直观的规律：**协议层（`core/protocol`，433 行）零问题** —— 因为它有 22 组测试向量锁定；
而**问题几乎全部集中在 `core/ble`（648 行，零测试）和 `domain`（430 行，零测试）**。
这不是巧合，修复完高/中危后，请优先做 M7（补测试）。

---

## 一、🔴 高危

### H1 · 扫描状态非线程安全，可能崩溃或 CPU 100%

**位置**　`WaterCtlBle.kt`
- 集合声明：L118–129（`found` / `firstSeen` / `sortKey`）
- 写入侧：L153–163（`onScanResult`）
- 读取侧：L179（ticker 调 `snapshot()`）、L188–191（`snapshot()` 本体）

**问题描述与影响**

BLE 扫描的 `onScanResult` 由系统在 **binder 线程**回调；而我们为避免列表跳动，
改成"每 1 秒推送一次快照"，快照由 `scope.launch`（默认 `Dispatchers.Default`）生成。
于是出现了两个线程同时访问同一组集合：

| 线程 | 动作 | 集合 |
|---|---|---|
| binder 线程 | 写入 | `found`(LinkedHashMap) / `firstSeen` / `sortKey`(HashMap) |
| Default 线程 | 遍历 + 排序 | 同上三个集合 |

`HashMap` / `LinkedHashMap` 均非线程安全，且两侧**没有任何同步**。后果按概率排序：

1. `ConcurrentModificationException` —— 遍历时结构被修改
2. **读取死循环，CPU 100%** —— `HashMap` 扩容期间被并发读，读到半迁移的桶链（经典故障，JDK 7 时代就出过）
3. 列表出现重复或丢失条目

扫描越久、附近设备越多，触发概率越高。属于"低概率、一旦发生极难排查"的故障。

**根本原因**

引入"1 秒推送一次快照"时，把读取从回调线程挪到了协程线程，
但没有同步引入线程隔离或同步原语 —— 写侧仍在 binder 线程直接改共享集合。

**解决步骤**

推荐方案（改动最小、无锁竞争）：三个集合全部换成并发容器。

```kotlin
import java.util.concurrent.ConcurrentHashMap

private val found = ConcurrentHashMap<String, WaterDevice>()
private val firstSeen = ConcurrentHashMap<String, Int>()
private val sortKey = ConcurrentHashMap<String, Int>()
```

同时修正首见序号的写入方式（`firstSeen[address] = firstSeen.size` 在并发下有竞态）：

```kotlin
// onScanResult 内
if (firstSeen.putIfAbsent(address, firstSeen.size) == null) {
    sortKey.putIfAbsent(address, result.rssi)
}
found[address] = WaterDevice(name, address, result.rssi, result.device)
```

> 序号在极端并发下可能重复，但排序第二关键字本来就有 `sortKey` 兜底，
> 序号重复只影响个别顺序，不影响稳定性。

**验证方法**

1. **压测**：临时把 `SCAN_UI_INTERVAL_MS` 从 `1_000L` 改成 `50L`，把读写碰撞频率放大 20 倍，
   连续扫描 5 分钟，观察 logcat 是否出现 `ConcurrentModificationException`
2. **功能回归**：设备列表仍稳定不跳动（这是上周专门修过的）、新设备正常追加
3. 改回 `1_000L` 后再完整回归一次

---

### H2 · 会话进行中可再次发起会话，旧 GATT 永不释放

**位置**
- `WaterViewModel.startWith()` L156——**缺少状态门控**
- `AppRoot.kt` / `ControlScreen.kt` —— 顶栏蓝牙按钮在所有阶段均可点击

**问题描述与影响**

顶栏蓝牙图标按钮在任何阶段都可点击，点开设备选择器后选中设备会调用 `startWith()`，
而该函数没有任何状态检查。后果链：

1. `sessionJob?.cancel()` 取消当前协程
2. `ble.startSession()` 再次执行 `connectGatt()`，**`gatt` 字段被新连接覆盖**
3. 旧 `BluetoothGatt` 从此无法被 `close()` —— 系统层连接泄漏
   （Android 对同一设备的未关闭 GATT 连接数量有上限，通常 4–7 个）
4. **旧设备没有收到结束协议（B3/B4）** —— 对水控器而言，上一次会话可能仍在计费/计时

**根本原因**

`beginSession()` 有 `if (stage != IDLE) return` 门控，但 `startWith()` 是另一个公开入口，
绕过了这道门控。属于"入口暴露但防线只修了一半"。

**解决步骤**

1. `WaterViewModel.startWith()` 开头加门控：

```kotlin
fun startWith(device: WaterDevice) {
    if (_state.value.control.stage != ControlStage.IDLE) return   // ← 新增
    closePicker()
    // ...
}
```

2. `ControlScreen` 给顶栏按钮加启用条件（仅 IDLE 可点）：

```kotlin
AppTopBar(
    title = "蓝牙水控器",
    action = {
        CircleIconButton(
            icon = Icons.Outlined.Bluetooth,
            contentDescription = "选择水控器",
            enabled = state.stage == ControlStage.IDLE,   // ← 新增
            onClick = onPickDevice,
        )
    },
)
```

（`CircleIconButton` 需要相应加一个 `enabled: Boolean = true` 参数。）

**验证方法**

1. 开启会话进入 ACTIVE → 点顶栏蓝牙按钮 → **按钮应无响应或置灰**
2. 用 `adb shell dumpsys bluetooth_manager` 观察 GATT 连接数
3. 压测：循环「连接 → 结束 → 连接」十次，`dumpsys` 里不应出现累积的残留连接

---

### H3 · 协议握手失败时 GATT 不释放

**位置**
- `WaterCtlBle.prepareCharacteristics()` L301——`servicesDeferred?.await()` 抛异常的路径
- `WaterCtlBle.onServicesDiscovered` L577–587——错误分支只 `fail()`，不 `close()`
- `WaterViewModel.startWith()` L179–184——对 `WaterCtlException` 不调 `abort()`

**问题描述与影响**

`onServicesDiscovered` 返回非 `GATT_SUCCESS` 时走 `completeExceptionally`，
而 `prepareCharacteristics()` 里的 `await()` 会把这个异常**原样向上抛**：

```kotlin
val services = withTimeoutOrNull(SERVICE_TIMEOUT_MS) { servicesDeferred?.await() }
if (services == null) {           // ← 只处理了"超时"
    teardown()
    throw ...
}
// ← 抛异常时不会走到这里，也不会走到任何 teardown()
```

异常直接穿透 `prepareCharacteristics` → `startSession` → `startWith`，
而 `startWith` 的 catch 对 `WaterCtlException` **不做任何清理**。

结果：用户看到"不支持的机型"错误弹窗，但 **`BluetoothGatt` 保持连接不释放**。
反复尝试（比如在蓝牙设备多的教室里误连别的设备）会累积泄漏，
最终触发 Android 的 GATT 连接上限，之后**所有蓝牙连接都会失败**，只能重启蓝牙。

**根本原因**

清理逻辑分散在各子步骤的**成功路径**上（每个 `if-throw` 前手动 `teardown()`），
异常路径没有统一兜底。这是典型的"手动资源清理漏一条分支"。

**解决步骤**

1. `WaterCtlBle.startSession()` 整体包一层兜底：

```kotlin
@SuppressLint("MissingPermission")
suspend fun startSession(device: BluetoothDevice, nameHint: String? = null) {
    try {
        // ……现有全部逻辑……
    } catch (e: Throwable) {
        teardown()      // ← 任何失败路径都保证释放
        throw e
    }
}
```

2. 删掉 `prepareCharacteristics()` / `enableNotifications()` / `connect()` 里
   **散落的 `teardown()` 调用**（避免语义混乱；`teardown()` 本身已是幂等的，重复调用无害，
   但集中在一处可读性更好）

3. 保持 `catch (e: CancellationException)` 先 rethrow 的既有约定
   —— 注意 `teardown()` 仍要先执行（释放资源），再 rethrow

**验证方法**

1. 选一台**没有 `0xF1F0` 服务**的 BLE 设备（耳机、手环都行）测试连接 → 应报"不支持的机型"
2. **立刻**再连真水控器 → 应能正常连上（证明旧连接已被释放）
3. `adb shell dumpsys bluetooth_manager` 确认没有残留连接
4. 覆盖另外两条路径：
   - 断开蓝牙再连 → 应报 UNSTABLE 且无泄漏
   - 连接后立即退到后台再回来 → 状态应一致

---

## 二、🟠 中危

### M1 · DataStore 读取失败会让应用崩溃

**位置**　`SettingsRepository.kt` L32（`store.data.map { ... }`，无 `.catch`）

**问题描述与影响**

DataStore 的 `data` flow 在 Preferences 文件损坏（写入中断、磁盘满、进程被杀）时会抛
`IOException`。`WaterViewModel.init` 里的 `prefs.settings.collect { ... }` **没有捕获**：

- 协程内未捕获异常 → **应用崩溃**
- 即使不崩，`settings` flow 也会永久终止 —— 之后所有设置变更都不再生效

**根本原因**

DataStore 官方就要求调用方自行处理读取异常，这里漏了。

**解决步骤**

```kotlin
val settings: Flow<AppSettings> = store.data
    .catch { e ->
        if (e is IOException) {
            // 文件损坏时回落到默认值，而不是崩溃
            emit(emptyPreferences())
        } else {
            throw e
        }
    }
    .map { prefs -> AppSettings( /* ……现有映射…… */ ) }
```

**验证方法**

1. 临时往 `/data/data/dev.waterctl.app/files/datastore/waterctl.settings.preferences_pb`
   写入垃圾字节（`adb shell` + `run-as`），或直接构造一个非法文件
2. 启动应用 → **不应崩溃**，且设置显示为默认值
3. 在设置里改一个开关 → 应能正常持久化

---

### M2 · 结束会话时会弹多余的超时错误

**位置**
- `WaterCtlBle.endSession()` L247–255
- `WaterCtlBle.armTimeout()` L392–398
- `WaterCtlBle.fail()` L413–416

**问题描述与影响**

`armTimeout()` 的 15 秒 `fail()` 只 complete **`sessionDeferred`**，
而 `endSession()` 在等的是 **`endDeferred`**：

```kotlin
suspend fun endSession() {
    val d = CompletableDeferred<Unit>()
    endDeferred = d
    write(Payloads.END_PROLOGUE)
    armTimeout()                                  // ← 15 秒后 fail()
    withTimeoutOrNull(END_WAIT_TIMEOUT_MS) { d.await() }   // ← 等 20 秒
    ...
}
```

于是：断开流程超过 15 秒（设备响应慢很常见）→ `fail()` 触发 `onError` →
**用户先看到一个 TIMEOUT 错误弹窗**，但流程还要继续等到 20 秒才收尾。
正常（只是慢）的断开也会报错。

**根本原因**

`fail()` 假定"只有一个正在等待的结果"，实际上开启和结束是两条独立的等待链。

**解决步骤**

方案一（最小改动）：`fail()` 同时 complete 两个 deferred。

```kotlin
private fun fail(error: WaterCtlException) {
    sessionDeferred?.completeExceptionally(error)
    endDeferred?.completeExceptionally(error)     // ← 新增
    listener?.onError(error)
}
```

并让 `endSession` 捕获后**不要**再抛：

```kotlin
withTimeoutOrNull(END_WAIT_TIMEOUT_MS) { d.await() }
```
→ 改为
```kotlin
try { withTimeoutOrNull(END_WAIT_TIMEOUT_MS) { d.await() } }
catch (e: WaterCtlException) { /* 已由 fail() 上报，这里静默收尾 */ }
```

方案二（更清晰）：把"要完成的 deferred"作为参数传给 `armTimeout(target)`，
开启和结束各自只完成自己的目标。

**验证方法**

1. 用一台响应慢的设备（或临时把设备端响应延迟）做结束操作，
   观察第 15–20 秒之间**不应**弹出错误弹窗
2. 正常结束（<15 秒）仍应立即回 IDLE 并生成记录

---

### M3 · 设备主动结束会话时，用水记录会丢失

**位置**　`WaterCtlBle.handleNotification` 0xB3 分支 L513–519

**问题描述与影响**

```kotlin
0xB3 -> {
    runCatching { write(Payloads.END_EPILOGUE) }
    intentionalDisconnect = true
    teardown()
    endDeferred?.complete(Unit)        // ← 设备主动断开时它是 null
    listener?.onSessionEnded(0)
}
```

记账逻辑只写在 `WaterViewModel.endSession()` 里（那里读 `sessionStartedAt` 算时长、插记录）。
**如果水控器自己结束会话**（比如水控器的超时保护、或有人在水控器端复位），
`endDeferred` 是 null，`endSession()` 根本没被调用 →
**不写记录、`sessionStartedAt` 不清零**。用户实实在在用了一次水，记录里却没有。

**根本原因**

"会话结束"有两个入口（用户主动 `endSession()` / 设备主动 B3），
但记账只挂在其中一个入口上。

**解决步骤**

把记账统一到 `onSessionEnded` 这个回调里：

1. BLE 层 0xB3 分支改为上报**真实时长**：

```kotlin
0xB3 -> {
    runCatching { write(Payloads.END_EPILOGUE) }
    val dur = if (startedAt > 0L) ((System.currentTimeMillis() - startedAt) / 1000L).toInt() else 0
    intentionalDisconnect = true
    teardown()
    endDeferred?.complete(Unit)
    listener?.onSessionEnded(dur)      // ← 不再传 0
}
```

2. `WaterViewModel.onSessionEnded(durationSeconds)` 改为**唯一记账点**：
   插入记录（用 `sessionStartedAt` 与上报的时长）、`sessionStartedAt = 0`、`stopTimer()`

3. `WaterViewModel.endSession()` 里**删掉**记账逻辑，只保留「发结束协议 + 等待」，
   记账交给 `onSessionEnded` 回调

4. 注意防重：`onSessionEnded` 可能被调用两次（用户主动 + 设备也回了 B3）。
   加一个 `private var sessionAccounted = false`，记账后置位并在 `onSessionStarted` 重置。

**验证方法**

1. 正常「开启 → 结束」→ 记录应出现，且**只有一条**
2. 连接成功后直接给水控器断电 → 应用应自动回 IDLE，**且生成一条记录**
3. 结束过程中发生蓝牙异常 → 不应生成时长为 0 的垃圾记录

---

### M4 · 自动重连存在 DataStore 初始化竞态

**位置**　`WaterViewModel.tryAutoReconnect()` + `AppRoot.kt` L70–72

**问题描述与影响**

```kotlin
LaunchedEffect(permission.ready) {
    if (permission.ready) vm.tryAutoReconnect()
}
```

而 `tryAutoReconnect()` 读的是 `_state.value.settings`。
问题在于 **DataStore 的首次读盘是异步的** —— `LaunchedEffect` 完全可能在
`prefs.settings` 的第一个值到达之前触发，此时 `lastDeviceAddress == null` →
直接 return，且 `autoReconnectTried = true` 之后不再尝试。

结果：**设置里打开了「自动重连」也永远不生效**。

**根本原因**

触发点订阅的是"权限就绪"这个信号，但依赖的数据（设置）来自另一条异步流，
两者没有建立先后关系。

**解决步骤**

让 `tryAutoReconnect` 自己等待设置到位：

```kotlin
suspend fun tryAutoReconnect() {
    if (autoReconnectTried) return
    autoReconnectTried = true

    val s = prefs.settings.first()          // ← 等 DataStore 首值，而非读缓存的 state
    if (!s.autoReconnect || !s.rememberDevice) return
    if (s.lastDeviceAddress == null) return
    if (_state.value.control.stage != ControlStage.IDLE) return
    beginSession()
}
```

（`AppRoot` 里已是 `LaunchedEffect` 调用，天然是协程上下文，无需改动。）

**验证方法**

1. 有历史记录的前提下，设置里打开「自动重连」
2. **冷启动**应用（`adb shell am force-stop` 后 `am start`）→ 应自动进入连接流程
3. 关闭「自动重连」→ 冷启动 → 不应自动连接
4. 把手机时间/时区改一下再冷启动 → 仍应生效（验证与时间无关）

---

### M5 · 扫描没有自动停止，列表无上限

**位置**　`WaterCtlBle.startScan` L176–181（ticker 无限循环）、L118–129（集合无上限）

**问题描述与影响**

- 选择面板开着就**一直扫描**：持续耗电
- Android 会在约 30 分钟后强制停止未停止的扫描（`onScanFailed` 错误码 1），
  之后列表不再更新，但界面仍在转圈 —— 用户以为在搜，其实早就停了
- `found` 只增不减，超长扫描会无限累积（内存）

**根本原因**

`startScan` 只启动、无生命周期管理；`stopScan` 只在面板关闭时被调用。

**解决步骤**

1. 在 `startScan` 里加扫描超时：

```kotlin
scanTimeoutJob = scope.launch {
    delay(SCAN_TIMEOUT_MS)          // 建议 30_000L
    stopScan()
    scanOnUpdate?.invoke(snapshot())   // 推一次最终快照
    scanOnUpdate?.invoke(emptyList())  // 或者用一个专门的"扫描已结束"信号
}
```

2. 列表加上限（如 50 条）：

```kotlin
if (found.size >= MAX_SCAN_RESULTS && !found.containsKey(address)) return
```

3. `stopScan()` 里记得 `scanTimeoutJob?.cancel()`

**验证方法**

1. 打开选择器静置 30 秒 → 扫描应自动停止（界面给出明确提示，不再无限转圈）
2. 面板里放入 60 个假设备 → 列表应封顶在 50

---

### M6 · `WaterCtlBle.scope` 永不取消

**位置**　L76（`CoroutineScope(SupervisorJob())`）、`release()` L267–270

**问题描述与影响**

`release()` 调用 `stopScan()` 和 `abort()`，但**从不 `scope.cancel()`**。
`onCleared` 之后，`handlerMutex` 里在途的协程、`startEpilogueJob` 等仍可能存活，
并通过 `listener` 回调到一个已经被清除的 ViewModel。

**根本原因**

创建 `CoroutineScope` 时没有配套的生命周期管理 —— 这是 Android 协程最常见的泄漏模式。

**解决步骤**

```kotlin
fun release() {
    startScanStop()
    abort()
    scope.cancel()      // ← 新增
}
```

注意：`teardown()` 里没有用到 scope，取消后仍安全；
但 `setStage` / `log` / `fail` 这些 listener 回调必须容忍"取消后再被调用"，
目前它们只是空操作更新 StateFlow，安全。

**验证方法**

1. 开启会话后直接退出应用 → logcat 里不应再出现 `RXD:` / `TXD:` 日志
2. `adb shell dumpsys meminfo dev.waterctl.app` 观察协程相关对象是否回落

---

### M7 · BLE 状态机与 ViewModel 完全没有测试

**位置**　`app/src/test` 仅 1 个文件（`DeputyTest`，协议层）；`app/src/androidTest` 0 个

**问题描述与影响**

本次审查发现的 H2、M2、M3 全部落在**没有任何测试覆盖**的区域。
协议层因为有测试向量锁定所以零问题 —— 这恰恰说明了测试的价值。

**根本原因**

蓝牙层与 Android 框架（`BluetoothGatt`）强耦合，没有为测试留接缝。

**解决步骤**

1. 把「通知字节 → 应发出的写请求 / 应进入的状态」这段**纯逻辑**抽成可独立测试的函数：

```kotlin
// 返回值描述"接下来要做什么"，而不是直接执行
sealed interface Reaction {
    data class Write(val data: ByteArray, val stage: ConnectionStage) : Reaction
    data class ScheduleEpilogue(val delayMs: Long) : Reaction
    data class Fail(val error: WaterCtlException) : Reaction
    data class SessionReady(val startedAt: Long) : Reaction
    object Ignore : Reaction
    data class EndSession(val durationSeconds: Int) : Reaction
}

internal fun reactToNotification(
    payload: ByteArray,
    deviceName: String,
    stage: ConnectionStage,
): Reaction
```

2. `WaterCtlBle` 只负责执行这个 `Reaction`
3. 为以下分支补测试：`B0/B1`、`AE`、`AF(0x55/0x01/0x02/0x04/其它)`、`B2`、`B3`、`BA`、`BC`、`C8`、
   未知类型、**短帧（<10 字节的 AE）**、**漏前导字节的修复逻辑**、**AT 命令忽略**
4. `WaterViewModel` 的记账逻辑（M3 修复后）同样补测试

**验证方法**

`./gradlew :app:testDebugUnitTest` 全绿，且新增用例覆盖上述全部分支。

---

## 三、🟡 低危（13 组，覆盖 16 条原始发现）

### 低-1 · 错误信息脱敏（对应 L1、L2）

| | |
|---|---|
| 位置 | `Solvers.kt` L72 · `WaterCtlError.kt` L105 |
| 问题 | `require(unlockRequest.size >= 10)` 抛 `IllegalArgumentException`，最终以 UNHANDLED 呈现 `java.lang.IllegalArgumentException: WATERCTL INTERNAL Bad unlock request`；UNHANDLED 的文案直接拼接 `error.toString()`，内部类名外泄给用户 |
| 影响 | 用户看到无意义的英文技术串，观感差，也暴露实现细节 |
| 根因 | 缺少"预期内的坏输入 → 语义化错误"的映射 |
| 步骤 | ① `Solvers.makeUnlockResponse` 的 `require` 改为 `throw WaterCtlException(WaterCtlErrorKind.UNKNOWN_RX)`；② `ErrorResolver` 的 UNHANDLED 文案改为固定描述 + `error.javaClass.simpleName`，不要整串 `toString()` |
| 验证 | 构造一个 5 字节的 AE 帧 → 应报「接收到未知数据」而非英文异常 |

### 低-2 · 死代码清理（对应 L3）

| | |
|---|---|
| 位置 | `WaterRecordDao.latest()` · `WaterViewModel.pendingDevice` · `WaterViewModel.cancelConnecting()` · `ControlUiState.bluetoothUnavailable` · `ErrorDialog` 里 `AppTypo.labelMedium.copy(...)` |
| 问题 | 均为只写不读或无调用点 |
| 影响 | 增加阅读成本，`cancelConnecting()` 未接入还意味着「连接中无法取消」这个 UX 缺口被掩盖 |
| 根因 | 迭代过程中方案调整，旧代码没有同步清理 |
| 步骤 | 二选一：① 直接删除；② 把 `cancelConnecting()` 接入 UI（见低-10）。其余删除 |
| 验证 | `./gradlew :app:assembleDebug` 编译通过，行为不变 |

### 低-3 · 扫描列表无上限（对应 L4）

见 **M5** 的解决步骤第 2 步，二者一并处理。
**验证**：模拟 60 个设备，列表封顶。

### 低-4 · Android 6-11 定位服务关闭时扫描静默无结果（对应 L5）

| | |
|---|---|
| 位置 | `BlePermission.kt`（`requiredBlePermissions()` 只返回权限，不检查定位服务开关） |
| 问题 | Android 6–11 上 BLE 扫描需要**定位服务处于开启状态**，仅有 `ACCESS_FINE_LOCATION` 权限不够；关着的话扫描静默无结果 |
| 影响 | 这类用户会看到"没有找到水控器"，无从排查 |
| 根因 | Android 的历史设计，权限与服务开关是两个独立条件 |
| 步骤 | `refresh()` 里增加 `LocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)` 或网络定位的判断；为 false 时在 `BleBlockReason` 增加 `LOCATION_OFF`，`PermissionScreen` 给出"请同时开启定位服务"的指引 |
| 验证 | Android 9/10/11 真机关闭定位服务 → 应出现明确指引而非空白列表 |

### 低-5 · 备份规则未显式声明（对应 L6）

| | |
|---|---|
| 位置 | `AndroidManifest.xml` L32 `allowBackup="true"` |
| 问题 | 未配置 `dataExtractionRules`（Android 12+）与 `fullBackupContent`（Android 11-），默认会把 Room（用水记录、设备名）与 DataStore（设备 MAC 地址）纳入云备份与设备间迁移 |
| 影响 | 数据本身不敏感，但"设备 MAC + 用水时间"属于用户行为数据，被默默上传云盘不妥 |
| 根因 | 建项目时用了默认值，未做数据分级 |
| 步骤 | ① 新增 `res/xml/data_extraction_rules.xml` 与 `res/xml/backup_rules.xml`，把 `database/` 与 `datastore/` 排除或标记 `cloud-backup` 之外的域；② Manifest 引用两者 |
| 验证 | `adb shell bmgr backupnow dev.waterctl.app` 后检查备份内容不含数据库 |

### 低-6 · 构建脚本现代化（对应 L7、L9）

| | |
|---|---|
| 位置 | `app/build.gradle.kts` L61 `kotlinOptions {}`、L25 `versionCode = 1` |
| 问题 | `kotlinOptions` 在 Kotlin 2.x 已废弃；`versionCode` 恒为 1，第二次发版无法覆盖安装 |
| 影响 | 构建告警；发版流程易出错 |
| 根因 | 沿用了 Kotlin 1.x 的写法；版本管理没有流程化 |
| 步骤 | ① 迁移到 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`；② `versionCode` 至少改为每次 Release 手工递增，长期可接 Git 提交数 |
| 验证 | 构建通过且无废弃告警；`aapt2 dump badging` 显示 versionCode 递增 |

### 低-7 · 图标库迁移（对应 L8）

| | |
|---|---|
| 位置 | `gradle/libs.versions.toml` L26 `material-icons-extended` |
| 问题 | 该库已被官方标记废弃，且把 debug 包撑到 52MB（release 2.2MB） |
| 影响 | 构建慢、APK 体积、未来升级断供 |
| 根因 | 初版图省事直接引了全量图标库 |
| 步骤 | 全工程实际只用了 12 个图标 —— 逐一替换为自绘 `ImageVector` 或迁移到 `material-symbols-outlined`，然后删除该依赖 |
| 验证 | 编译通过、界面图标逐一比对、debug 包体积显著下降 |

### 低-8 · 弹窗风格不统一（对应 L10）

| | |
|---|---|
| 位置 | `SettingsScreen.kt` 清除确认用 M3 默认 `AlertDialog` |
| 问题 | 与 `ErrorDialog`（自绘、对齐设计稿：332 宽 / 圆角 28 / 内边距 24）不一致 |
| 影响 | 视觉不统一 |
| 根因 | 偷懒用了现成组件 |
| 步骤 | 抽一个 `AppAlertDialog`（复用 `ErrorDialog` 的骨架：宽 332 / 圆角 28 / padding 24），支持 title / body / 确认 / 取消，两处共用 |
| 验证 | 视觉比对设计稿的对话框规格 |

### 低-9 · 开始时间精度（对应 L11）

| | |
|---|---|
| 位置 | `ControlScreen.kt` `sessionStartOf()` |
| 问题 | 由 `elapsedSeconds` 反推开始时间，计时器 500ms 一跳，最多差 1 秒 |
| 影响 | 「开始时间 · HH:MM」最多偏差 1 分钟，通常察觉不到 |
| 根因 | UI 从展示值反推状态，而不是读真实状态 |
| 步骤 | `ControlUiState` 增加 `startedAt: Long`，由 `onSessionStarted` 写入，UI 直接读取 |
| 验证 | 连接成功后立即查看开始时间，应精确到分钟 |

### 低-10 · 连接中无法取消（对应 L12）

| | |
|---|---|
| 位置 | `WaterViewModel.cancelConnecting()`（已实现但未接入）+ `ControlScreen` |
| 问题 | CONNECTING 阶段主按钮无响应，用户只能干等 15 秒超时 |
| 影响 | 误连别的设备时体验很差 |
| 根因 | 设计稿里没有"取消"这个状态，实现时也没接 |
| 步骤 | CONNECTING 阶段把主按钮文字改为「取消」（用 `surfaceContainerHighest` 底色，复用连接中的视觉），点击调 `cancelConnecting()` |
| 验证 | 连接中点击主按钮 → 立即回 IDLE，无错误弹窗，无 GATT 泄漏 |

### 低-11 · 注释与实现不符（对应 L13、L14）

| | |
|---|---|
| 位置 | `WaterCtlBle.kt` L353（CCCD 超时）、L473（0xB0/B1 无状态门控） |
| 问题 | L353 注释说"部分机型仍能收到通知"——实际没写 CCCD 就收不到通知；L473 的无状态门控是有意为之但没有说明 |
| 影响 | 误导后续维护者 |
| 根因 | 写注释时的猜测没有回填 |
| 步骤 | ① L353 注释改为"超时继续的原因：由后续 15 秒响应超时统一兜底"；② L473 加注释"与原版一致：状态机本身无门控，依赖上层 stage 判断" |
| 验证 | 纯文档改动，肉眼确认 |

### 低-12 · MTU 约束未说明（对应 L15）

| | |
|---|---|
| 位置 | `WaterCtlBle.write()` |
| 问题 | 所有报文依赖默认 MTU 23（20 字节有效载荷），未做协商也未注明约束 |
| 影响 | 现状正确；但未来若加超过 20 字节的报文会**静默失败**，很难排查 |
| 根因 | 与原版一致，原版也没协商 |
| 步骤 | 在 `write()` 开头加 `require(data.size <= 20)` 并注释原因；若未来需要更长报文，先 `requestMtu(185)` |
| 验证 | `./gradlew :app:testDebugUnitTest` + 真机回归 |

### 低-13 · 无 CI（对应 L16）

| | |
|---|---|
| 位置 | 仓库根目录（无 `.github/workflows`） |
| 问题 | 每次发版靠手工构建，`versionCode` 靠手工递增，测试靠自觉跑 |
| 影响 | 出错概率随迭代次数上升 |
| 根因 | 项目刚起步 |
| 步骤 | 新增 `.github/workflows/release.yml`：推 `v*` tag 时用 `ubuntu-latest` 跑 `assembleRelease`（需要把签名密钥放进仓库 Secrets）并自动创建 Release |
| 验证 | 推一个测试 tag，观察 Actions 是否产出 APK |

---

## 四、修复顺序建议

```
第 1 批（一次改完一次验证，都是蓝牙会话链路）
  #9  H2  startWith 加状态门控          —— 3 行改动
  #10 H3  startSession 失败统一 teardown —— 结构性调整
  #16 低1/低2/低3 错误脱敏 + 死代码清理   —— 顺手做

第 2 批（记账与生命周期）
  #12 M3  设备主动结束时记账
  #11 M2  结束会话超时语义
  #15 M5/M6 扫描超时 + scope 取消

第 3 批（独立小修）
  #13 M1  DataStore 兜底                —— 一行改动收益最大
  #14 M4  自动重连竞态
  #18 低5 备份规则

第 4 批（补短板，可与功能迭代并行）
  #17 M7  BLE 状态机测试
  低6/低7/低13  构建现代化 + CI
```

---

## 五、整体验证清单

修复完成后，按这个顺序做一次完整回归：

1. **单元测试**：`./gradlew :app:testDebugUnitTest` —— 全绿（含新增的 BLE 状态机用例）
2. **构建**：`./gradlew :app:assembleDebug :app:assembleRelease` —— 无错误、无废弃告警
3. **真机功能链路**：
   - 权限引导页 → 授权 → 主界面
   - 扫描 → 选择器列表稳定（不跳动、不超过上限、30 秒自动停止）
   - 连接 → 三步进度 → 出水（真实水控器）
   - 结束 → 自动回 IDLE → **记录出现且仅一条**
   - 设备主动断开 → 自动回 IDLE → **记录同样出现**
   - 连接一台非水控设备 → 报"不支持的机型" → **再连真水控器仍能成功**（验证 H3）
   - ACTIVE 时点顶栏蓝牙 → 无响应（验证 H2）
   - 连接中点主按钮 → 立即取消（验证低-10）
4. **资源检查**：`adb shell dumpsys bluetooth_manager` —— 无残留 GATT 连接
5. **稳定性**：开关深浅色、旋转屏幕、退到后台再回来 → 会话与计时不受影响
6. **发布**：递增 `versionCode` → 打 tag → Release 附 APK 与 SHA-256

---

## 附：本次审查确认没有问题的部分

- **`core/protocol`（433 行）**：零问题。22 组向量锁定，`Deputy.makeKey` 的全部索引边界
  （`a` 最大 256 → `1040+256=1296` ✓；`c ≤ 255` → `1552+255=1807` ✓；
  `iB ≤ 129` → `1808+129=1937` ✓）重新验算一遍，全部安全
- **`CancellationException` 的处理**：所有 catch 都先 rethrow —— 这是 Android 协程最常踩的坑，这里全对
- **权限模型**：`neverForLocation`、分版本请求、`runCatching` 包住 `adapter.isEnabled`
- **字体子集化**：4 个字重 59MB → 1.5MB，release 2.2MB
- **分层**：protocol / ble / data / domain / ui 边界清晰，没有越层访问
