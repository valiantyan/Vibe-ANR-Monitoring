# Demo ANR 场景实现计划

本文面向 Demo App 开发和手动验收，目标是把常见 Android ANR 场景拆成一个个可复现按钮，用来验证 ANR 监控 SDK 是否能抓到现场，并帮助新人通过 JSON 定位根因。

## 实施原则

- 一个按钮只验证一种主因，避免一份 JSON 里混入多个根因。
- 每个场景都必须说明触发动作、预期归因、关键 JSON 字段和排除项。
- 每次只实现一个场景，实现完成后再进入下一个场景。
- 第五篇 SharedPreferences 文档只作为实战复盘样例，不作为 SDK 或 Demo 基础需求。

## 场景总览

| 顺序 | 场景 | 触发方式 | 预期归因 | 关键 JSON 字段 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 输入事件当前慢消息 | 点击按钮后主线程阻塞 6 秒 | `CURRENT_MESSAGE_SLOW` | `mainThread.current.wallMs`、`mainThread.stackFrames` | 已验收 |
| 2 | 主线程 CPU 忙等 | 点击“当前消息忙等”后主线程 busy loop 6 秒 | `CURRENT_MESSAGE_SLOW` | `mainThread.current.wallMs`、`mainThread.current.cpuMs`、`threadCpu.topThreads`、`MainThreadCpuBusyScenario.run` | 已验收 |
| 3 | 消息风暴 | 点击“消息风暴”后投递大量同类主线程消息 | `MESSAGE_STORM` | `attribution.evidence`、`pendingQueue.messages` 重复 `MessageStormHandler` / `StormRunnable`、`MessageStormScenario.run` | 已验收 |
| 4 | Sync Barrier 泄漏 / nativePollOnce | 点击按钮反射插入 Sync Barrier 且故意不移除 | `SYNC_BARRIER_STUCK` | `pendingQueue.messages[0].isBarrierLike=true`、`barrierEvidence.alignedWithPendingBarrier=true`、`nativePollOnceRecords` | 已验收 |
| 5 | 主线程锁等待 | 子线程持锁，主线程等待锁 | 当前慢消息加等待栈证据 | `mainThread.stackFrames` 中锁等待业务帧 | 待实现 |
| 6 | BroadcastReceiver 超时 | 点击“BroadcastReceiver 超时”后发送显式应用内广播，Receiver 主线程阻塞 12 秒 | Broadcast 组件超时 + 当前消息慢证据 | `systemAnr.anrType`、`mainThread.stackFrames` 包含 `BroadcastTimeoutReceiver.onReceive`、`mainThread.current.wallMs` | 已实现，待手动验收 |
| 7 | Service 超时 | 点击“Service 超时”后启动显式应用内 Service，`onStartCommand()` 主线程阻塞 25 秒 | Service 组件超时 + 当前消息慢证据 | `systemAnr.anrType`、`mainThread.stackFrames` 包含 `ServiceTimeoutService.onStartCommand`、`mainThread.current.wallMs` | 已实现，待手动验收 |
| 8 | ContentProvider 阻塞 | 点击“ContentProvider 阻塞”后查询应用内 Provider，`query()` 主线程阻塞 12 秒 | Provider 查询阻塞 + 当前消息慢证据 | `mainThread.stackFrames` 包含 `BlockingContentProvider.query`、`ContentProviderBlocker.block`、`mainThread.current.wallMs` | 已实现，待手动验收 |
| 9 | Binder 跨进程阻塞 | 点击“Binder 跨进程阻塞”后主线程同步调用远端 `:remote` AIDL，远端 Binder 线程阻塞 12 秒 | `BINDER_BLOCK_SUSPECTED` | `binderBlock.suspected=true`、`binderBlock.mainThreadInBinder=true`、`mainThread.stackFrames` 包含 `BinderProxy.transact` | 已验收 |
| 10 | 主线程 IO/数据库阻塞 | 主线程执行慢 IO 或慢查询 | `CURRENT_MESSAGE_SLOW` | IO/DB 业务栈、当前消息耗时 | 待实现 |
| 11 | 线程池耗尽后主线程等待 | 占满线程池后主线程等待结果 | 等待类当前慢消息 | 主线程等待栈、后台线程证据 | 待实现 |
| 12 | GC / 内存抖动 | 大量分配对象制造 GC 压力 | 环境或资源辅因 | `environmentSnapshot`、历史消息抖动 | 待实现 |
| 13 | 进程内 CPU 竞争 | 后台线程打满 CPU | CPU 竞争辅因 | `threadCpu.topThreads`、`checktime.maxDelayMs` | 待实现 |

## 当前批次：输入事件当前慢消息

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“当前消息慢”。
4. 阻塞期间继续点击屏幕，方便系统 Input 超时窗口也出现。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。最后看 `mainThread.stackFrames`，应能看到 `CurrentSlowInputScenario.run`，说明根因入口是 Demo 当前慢消息场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- `pendingQueue.messages` 不应该以队头 Barrier 作为主证据。

### 首次验收记录

验收时间：2026-06-08 15:54 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell input tap 540 876
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/b027eea9-1c62-48a9-938c-ee1e1f28da0d.JSON
```

关键日志：

```text
W VibeAnrApplication: suspect ANR captured: b027eea9-1c62-48a9-938c-ee1e1f28da0d
W VibeAnrApplication: ANR report written: b027eea9-1c62-48a9-938c-ee1e1f28da0d
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs = 3060
mainThread.stackFrames contains CurrentSlowInputScenario.run
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：当前消息慢场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `CURRENT_MESSAGE_SLOW`，主线程栈能定位到 `CurrentSlowInputScenario.run`，Binder 和 Barrier 证据均不是本次主因，因此根因可以明确写为“按钮点击消息在主线程执行期间被 Demo 业务代码阻塞”。

## 第二批次：主线程 CPU 忙等

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“当前消息忙等”。
4. 阻塞期间继续点击屏幕，方便系统 Input 超时窗口也出现。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.current.cpuMs` 和 `threadCpu.topThreads`，它们应比 sleep 等待场景更能体现主线程 CPU 消耗。最后看 `mainThread.stackFrames`，应能看到 `MainThreadCpuBusyScenario.run` 或 `DefaultCpuBusyAction.burn`，说明根因入口是 Demo 主线程 CPU 忙等场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- `mainThread.current.cpuMs` 如果接近 0，应优先检查是否点错了“当前消息慢”按钮，或当前设备 CPU 证据采集是否失败。

### 首次验收记录

验收时间：2026-06-08 16:56 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell input tap 540 1212
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/65ec033f-7411-4fe4-a5b2-2d92dee348ef.JSON
```

关键日志：

```text
W VibeAnrApplication: suspect ANR captured: 65ec033f-7411-4fe4-a5b2-2d92dee348ef
W VibeAnrApplication: ANR report written: 65ec033f-7411-4fe4-a5b2-2d92dee348ef
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
mainThread.current.wallMs = 3152
mainThread.current.cpuMs = 2957
threadCpu.topThreads[0].threadName = beanrmonitoring
threadCpu.topThreads[0].totalCpuMs = 9540
mainThread.stackFrames contains MainThreadCpuBusyScenario$DefaultCpuBusyAction.burn
mainThread.stackFrames contains MainThreadCpuBusyScenario.run
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：主线程 CPU 忙等场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `CURRENT_MESSAGE_SLOW`，当前消息 wall time 超过阈值，当前消息 CPU 耗时和线程 CPU 排名都支持“主线程持续计算”，主线程栈能定位到 `MainThreadCpuBusyScenario.run` 和 `DefaultCpuBusyAction.burn`，因此可以和 `Thread.sleep` 类型的等待阻塞场景区分开。

## 第三批次：消息风暴

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“消息风暴”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `MESSAGE_STORM`。再看 `attribution.evidence`，应包含 `pending repeated target count=...`。接着打开 `pendingQueue.messages`，确认多条 Pending 消息的 `targetClass` 包含 `MessageStormHandler`，或 `callbackClass` 包含 `StormRunnable`。最后看 `mainThread.stackFrames`，应能看到 `MessageStormScenario.run`，说明本次消息风暴来自 Demo 按钮场景。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果 `attribution.primary` 变成 `CURRENT_MESSAGE_SLOW`，优先检查 `pendingQueue.available` 是否为 true，以及 `pendingQueue.messages` 中是否真的采到了超过 20 条同类消息。

### 首次验收记录

验收时间：2026-06-08 17:45 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s <device-id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-id> logcat -c
adb -s <device-id> shell input tap <message-storm-button-x> <message-storm-button-y>
adb -s <device-id> shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.JSON
```

实际执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input tap 540 1044
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/2b47738a-806b-4b22-a136-b71cd5c37416.JSON
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = MESSAGE_STORM
attribution.evidence contains pending repeated target count=80
pendingQueue.available = true
pendingQueue.messages count = 90
pendingQueue.messages contains 80 repeated MessageStormHandler / StormRunnable
mainThread.stackFrames contains MessageStormScenario.run
mainThread.current.wallMs = 3304
mainThread.current.cpuMs = 15
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：消息风暴场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `MESSAGE_STORM`，`attribution.evidence` 给出 `pending repeated target count=80`，`pendingQueue.messages` 能看到 80 条 `MessageStormHandler` / `StormRunnable` 消息，Binder 和 Barrier 证据均不是本次主因，因此根因可以明确写为“按钮点击后向主线程投递大量重复消息，导致队列拥塞和输入响应延迟”。

## 第四批次：Sync Barrier 泄漏 / nativePollOnce

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“Sync Barrier 泄漏 ANR”。
4. 持续点击屏幕 5 到 10 秒，制造输入等待窗口。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `SYNC_BARRIER_STUCK`。再看 `pendingQueue.messages[0]`，预期 `isBarrierLike=true` 且 `targetClass=null`。然后看 `barrierEvidence.stuckTokens`，预期 token 能和 `pendingQueue.messages[0].arg1` 对齐。最后看 `barrierEvidence.nativePollOnceRecords`，如果存在 `source=STACK_INFERENCE` 或 `source=HOOK`，说明 SDK 已把 nativePollOnce 表象和队头 Barrier 证据串起来。

### 排除项

- `binderBlock.suspected` 不应该为 true。
- `attribution.primary` 不应该是 `MESSAGE_STORM`。
- 不能只凭 `mainThread.stackFrames` 中出现 `nativePollOnce` 下结论，必须同时看到 Pending 队头 Barrier 和 token 对齐证据。

### 修复方向

- 检查 Barrier token 的 post/remove 配对。
- 重点排查插入栈中的业务 owner、UI 刷新、动画或调度封装。
- 确认异常分支、取消分支和页面销毁分支都会移除 Barrier。

### 验收记录

- [x] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest` 通过。
- [x] `./gradlew :app:compileDebugKotlin` 通过。
- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [x] 真机或模拟器点击“Sync Barrier 泄漏 ANR”后生成 JSON：`3b8d7ef5-2145-4b15-9f41-ca70ddaa15dd.json`。
- [x] JSON 中 `attribution.primary=SYNC_BARRIER_STUCK`。
- [x] JSON 中 `pendingQueue.messages[0].isBarrierLike=true`。
- [x] JSON 中 `barrierEvidence.alignedWithPendingBarrier=true`。
- [x] JSON 中 `barrierEvidence.stuckTokens[].postStack` 能定位到 `SyncBarrierLeakScenario.run`。

## 第六批次：BroadcastReceiver 超时

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“BroadcastReceiver 超时”。
4. 等待 12 秒左右，直到日志出现 `suspect ANR captured`、`confirmed ANR report` 或 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `mainThread.stackFrames`，预期包含 `BroadcastTimeoutReceiver.onReceive`，这是业务根因入口。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。如果系统已经确认 ANR，再看 `systemAnr.anrType`，预期为 `BROADCAST_FOREGROUND` 或 `BROADCAST_BACKGROUND`；`componentTimeoutMs` 应与前台 10 秒或后台 60 秒广播阈值匹配。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果只看到 `systemAnr.anrType=BROADCAST_*`，但主线程栈不包含 `BroadcastTimeoutReceiver.onReceive`，需要继续对照 `history` 和 `stackSamples`，不能只凭系统组件类型下业务根因结论。

### 验收记录

- [x] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenarioTest` 通过。
- [x] `./gradlew :app:compileDebugKotlin :app:mergeDebugResources` 通过。
- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [x] 真机或模拟器点击“BroadcastReceiver 超时”后生成 JSON。
- [x] JSON 中 `mainThread.stackFrames` 能定位到 `BroadcastTimeoutReceiver.onReceive`。
- [x] 本次系统未确认 ANR，`systemAnr.anrType=UNKNOWN`；业务根因仍可由主线程栈定位。

### 首次验收记录

验收时间：2026-06-08 19:16 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input tap 540 1632
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/aaf4e3c0-37bd-4ed2-a579-e721ccbe420a.json
```

关键日志：

```text
W VibeAnrApplication: suspect ANR captured: aaf4e3c0-37bd-4ed2-a579-e721ccbe420a
W VibeAnrApplication: ANR report written: aaf4e3c0-37bd-4ed2-a579-e721ccbe420a
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
attribution.confidence = LOW
systemAnr.isConfirmedAnr = false
systemAnr.anrType = UNKNOWN
systemAnr.componentTimeoutMs = null
mainThread.current.targetClass = android.app.ActivityThread$H
mainThread.current.what = 113
mainThread.current.wallMs = 3352
mainThread.current.cpuMs = 0
mainThread.stackFrames contains BroadcastTimeoutReceiver.onReceive
binderBlock.suspected = false
barrierEvidence.stuckTokens = []
```

验收结论：BroadcastReceiver 超时场景验收通过。SDK 能捕获疑似 ANR，JSON 能把系统广播组件状态和业务 Receiver 阻塞入口分开表达。本次系统尚未确认 ANR，因此 `systemAnr.anrType=UNKNOWN`、`componentTimeoutMs=null` 属于可接受结果；业务根因可以明确写为“`BroadcastTimeoutReceiver.onReceive` 在主线程执行耗时阻塞，导致 ActivityThread Receiver 消息执行超过疑似 ANR 阈值”。

## 第七批次：Service 超时

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“Service 超时”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 如果要验证系统确认 Service ANR，继续等待 20 秒以上并查看系统 ANR traces。
6. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `event.eventType`，确认 SDK 已经生成疑似或确认报告。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.current.targetClass`，通常应包含 `ActivityThread$H`，说明阻塞发生在系统组件调度消息内。最后看 `mainThread.stackFrames`，应能看到 `ServiceTimeoutService.onStartCommand` 和 `ServiceTimeoutBlocker.block`，说明业务根因入口是 Service 生命周期回调。

如果 `systemAnr.isConfirmedAnr=true` 且 `systemAnr.anrType=SERVICE`，说明系统也确认了 Service 组件超时。如果 `systemAnr.isConfirmedAnr=false`，仍可先根据 SDK 疑似 ANR 报告定位业务根因，因为 SDK 阈值比系统 Service 超时阈值更早触发。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果主线程栈只看到按钮点击入口而没有 `ServiceTimeoutService.onStartCommand`，优先检查是否点错按钮，或 Service 是否没有在 Manifest 中注册成功。

### 首次验收记录

验收时间：2026-06-08 19:52 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell uiautomator dump /sdcard/window.xml
adb -s emulator-5554 shell input tap 540 1716
adb -s emulator-5554 logcat -d -s VibeAnrApplication
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/5fbba9e4-cdbd-466a-aa03-dc22ec1ddf19.json
```

关键日志：

```text
W VibeAnrApplication: suspect ANR captured: 5fbba9e4-cdbd-466a-aa03-dc22ec1ddf19
W VibeAnrApplication: ANR report written: 5fbba9e4-cdbd-466a-aa03-dc22ec1ddf19
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
attribution.confidence = LOW
systemAnr.isConfirmedAnr = false
systemAnr.anrType = UNKNOWN
systemAnr.componentTimeoutMs = null
mainThread.current.what = 115
mainThread.current.wallMs = 3198
mainThread.current.cpuMs = 0
mainThread.current.targetClass = android.app.ActivityThread$H
mainThread.stackFrames contains ServiceTimeoutService.onStartCommand
mainThread.stackFrames contains ServiceTimeoutBlocker.block
pendingQueue.available = true
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

验收清单：

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [x] 真机或模拟器点击“Service 超时”后生成 JSON。
- [x] JSON 中 `mainThread.stackFrames` 包含 `ServiceTimeoutService.onStartCommand`。
- [x] JSON 中 `mainThread.current.wallMs >= 3000`。
- [x] Barrier 和 Binder 证据均不是本次主因。
- [ ] 如果系统已确认 ANR，`systemAnr.anrType=SERVICE`。

本次采样先命中 SDK 疑似 ANR 阈值，系统尚未确认 Service ANR；业务根因仍可由 `ServiceTimeoutService.onStartCommand` 和当前消息耗时确定。

验收结论：Service 超时场景验收通过。SDK 能捕获疑似 ANR，JSON 能把系统 Service 组件调度和业务 Service 阻塞入口分开表达；根因可以明确写为“`ServiceTimeoutService.onStartCommand` 在主线程执行耗时阻塞，导致 Service 生命周期回调无法及时返回”。

## 第八批次：ContentProvider 阻塞

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 点击“ContentProvider 阻塞”。
4. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
5. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `event.eventType`，确认 SDK 已经生成疑似或确认报告。再看 `mainThread.current.wallMs`，应大于 Demo 配置的 `suspectAnrMs=3000`。接着看 `mainThread.stackFrames`，应能看到 `BlockingContentProvider.query` 和 `ContentProviderBlocker.block`，说明业务根因入口是 Provider 查询。最后检查栈中是否存在 `ContentResolver.query` 或 `ContentProvider.Transport.query`，用于证明调用链经过 ContentProvider。

如果 `systemAnr.isConfirmedAnr=true` 且 `systemAnr.anrType=PROVIDER`，说明系统也确认了 Provider 组件超时，`systemAnr.componentTimeoutMs` 通常应为 `10000`。如果 `systemAnr.isConfirmedAnr=false`，仍可先根据 SDK 疑似 ANR 报告定位业务根因，因为 SDK 阈值比系统确认阈值更早触发。如果系统版本没有输出明确 Provider 类型，也不要把 `systemAnr.anrType=UNKNOWN` 当成根因未知；根因仍以主线程栈和当前消息耗时为准。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `binderBlock.suspected` 不应该为 true。
- 如果主线程栈只看到按钮点击入口而没有 `BlockingContentProvider.query`，优先检查 Provider 是否已经在 Manifest 中注册成功，或是否点错按钮。

### 首次验收记录

验收时间：2026-06-08 20:31 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input tap 540 1800
adb -s emulator-5554 logcat -d -s VibeAnrApplication AnrMonitor
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/767fe4bd-dc6b-4f70-b2ca-179b0b104d49.json
```

验收清单：

- [x] `./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest` 通过。
- [x] 真机或模拟器点击“ContentProvider 阻塞”后生成 JSON。
- [x] JSON 中 `mainThread.stackFrames` 包含 `BlockingContentProvider.query`。
- [x] JSON 中 `mainThread.stackFrames` 包含 `ContentProviderBlocker.block`。
- [x] JSON 中 `mainThread.current.wallMs >= 3000`。
- [ ] 如果系统已确认 ANR，`systemAnr.anrType=PROVIDER`。
- [x] Barrier 和 Binder 证据均不是本次主因。

关键日志：

```text
W VibeAnrApplication: suspect ANR captured: 767fe4bd-dc6b-4f70-b2ca-179b0b104d49
W VibeAnrApplication: ANR report written: 767fe4bd-dc6b-4f70-b2ca-179b0b104d49
```

关键 JSON 字段：

```text
event.eventType = SUSPECT_ANR
attribution.primary = CURRENT_MESSAGE_SLOW
attribution.confidence = LOW
systemAnr.isConfirmedAnr = false
systemAnr.anrType = UNKNOWN
systemAnr.componentTimeoutMs = null
mainThread.current.wallMs = 3459
mainThread.current.cpuMs = 0
mainThread.stackFrames contains ContentProviderBlocker.block
mainThread.stackFrames contains BlockingContentProvider.query
mainThread.stackFrames contains ContentProvider$Transport.query
mainThread.stackFrames contains ContentResolver.query
barrierEvidence.alignedWithPendingBarrier = false
barrierEvidence.stuckTokens = []
binderBlock.suspected = false
```

本次采样先命中 SDK 疑似 ANR 阈值，系统尚未确认 Provider ANR；因此 `systemAnr.anrType=UNKNOWN` 和 `componentTimeoutMs=null` 属于可接受结果。验收过程中发现 Pending 队头可能存在系统绘制产生的临时 Sync Barrier，已补充归因回归测试并收紧 Barrier 主因规则：只有主线程停在 `MessageQueue.nativePollOnce`，或 Barrier token 与 Pending 队头对齐，或存在 stuck token 时，才把 Barrier 提升为主因。

验收结论：ContentProvider 阻塞场景验收通过。SDK 能捕获疑似 ANR，JSON 能把 Provider 查询调用链和业务 Provider 阻塞入口分开表达；本次根因可以明确写为“`BlockingContentProvider.query` 在主线程执行耗时阻塞，内部调用 `ContentProviderBlocker.block`，导致 Provider 查询无法及时返回”。Barrier 和 Binder 证据均不是本次主因。

## 第九批次：Binder / 跨进程阻塞

### 触发步骤

1. 安装 debug 包。
2. 打开 Demo App。
3. 等待 1 秒，让远端 `:remote` Service 完成绑定。
4. 点击“Binder 跨进程阻塞”。
5. 如果弹出未就绪提示，等 1 秒后再点击一次。
6. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
7. 从设备拉取 `anr-monitor-reports` 目录下最新 JSON。

### JSON 读取口径

先看 `attribution.primary`，预期为 `BINDER_BLOCK_SUSPECTED`。再看 `binderBlock.suspected` 和 `binderBlock.mainThreadInBinder`，都应为 `true`。接着看 `mainThread.stackFrames`，应能看到 `BinderProxy.transact` 或 `BinderProxy.transactNative`，并能回溯到 `BinderCrossProcessBlockScenario.run`。

### 排除项

- `barrierEvidence.stuckTokens` 不应该成为主因。
- `pendingQueue.messages[0].isBarrierLike` 不应该作为主证据。
- 如果 `binderBlock.available=false`，不能用这份 JSON 排除或证明 Binder。
- 如果只有 `CURRENT_MESSAGE_SLOW`，但主线程栈没有 Binder transact，应检查是否没有真正连上远端 Service。

### 首次验收记录

验收时间：2026-06-08 22:56 CST

验收设备：`emulator-5554`

执行命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input tap 540 1128
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/0568686c-ae09-4313-94a2-80f56c46b812.json
```

关键 JSON 字段：

```text
event.id = 0568686c-ae09-4313-94a2-80f56c46b812
event.eventType = SUSPECT_ANR
attribution.primary = BINDER_BLOCK_SUSPECTED
attribution.confidence = MEDIUM
binderBlock.available = true
binderBlock.suspected = true
binderBlock.mainThreadInBinder = true
binderBlock.binderThreadWaitsMain = false
mainThread.stackFrames contains BinderProxy.transact
mainThread.stackFrames contains BinderCrossProcessBlockScenario.run
barrierEvidence.stuckTokens = []
```

验收结论：Binder / 跨进程阻塞场景验收通过。SDK 能捕获疑似 ANR，JSON 主归因为 `BINDER_BLOCK_SUSPECTED`，`binderBlock.suspected=true`，主线程栈命中 `BinderProxy.transact` 并能回溯到 `BinderCrossProcessBlockScenario.run`。本次缺少本进程 Binder 线程等待增强证据，因此 `binderThreadWaitsMain=false` 属于可接受结果；Barrier 证据不是本次主因。本次可以写为“主线程同步 Binder 调用远端进程时等待返回，属于跨进程阻塞疑似”，不能写为“已确认跨进程死锁”。

## 后续批次顺序

后续按锁等待、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
