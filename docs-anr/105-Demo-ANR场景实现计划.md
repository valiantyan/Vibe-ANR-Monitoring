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
| 4 | Sync Barrier 泄漏 / nativePollOnce | 点击按钮反射插入 Sync Barrier 且故意不移除 | `SYNC_BARRIER_STUCK` | `pendingQueue.messages[0].isBarrierLike=true`、`barrierEvidence.alignedWithPendingBarrier=true`、`nativePollOnceRecords` | 已实现，待手动验收 |
| 5 | 主线程锁等待 | 子线程持锁，主线程等待锁 | 当前慢消息加等待栈证据 | `mainThread.stackFrames` 中锁等待业务帧 | 待实现 |
| 6 | BroadcastReceiver 超时 | 发送显式广播，Receiver 阻塞 | Broadcast 组件超时 | `systemAnr.anrType`、Receiver 相关 ActivityThread 消息 | 待实现 |
| 7 | Service 超时 | 启动阻塞 Service | Service 组件超时 | `systemAnr.anrType`、Service 相关 ActivityThread 消息 | 待实现 |
| 8 | ContentProvider 阻塞 | 查询阻塞 Provider | Provider 组件阻塞 | Provider 调用栈和系统组件证据 | 待实现 |
| 9 | Binder 跨进程阻塞 | 主进程调用远端阻塞服务 | `BINDER_BLOCK_SUSPECTED` | `binderBlock.suspected`、主线程 Binder 栈 | 待实现 |
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
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/b027eea9-1c62-48a9-938c-ee1e1f28da0d.json
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
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/65ec033f-7411-4fe4-a5b2-2d92dee348ef.json
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
adb -s <device-id> exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<event-id>.json
```

实际执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.valiantyan.vibeanrmonitoring/.MainActivity
adb -s emulator-5554 shell input tap 540 1044
adb -s emulator-5554 shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb -s emulator-5554 exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/2b47738a-806b-4b22-a136-b71cd5c37416.json
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

- [ ] `./gradlew :app:testDebugUnitTest --tests com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenarioTest` 通过。
- [ ] `./gradlew :app:compileDebugKotlin` 通过。
- [ ] 真机或模拟器点击“Sync Barrier 泄漏 ANR”后生成 JSON。
- [ ] JSON 中 `attribution.primary=SYNC_BARRIER_STUCK`。
- [ ] JSON 中 `pendingQueue.messages[0].isBarrierLike=true`。
- [ ] JSON 中 `barrierEvidence.alignedWithPendingBarrier=true`。
- [ ] JSON 中 `barrierEvidence.stuckTokens[].postStack` 能定位到 `SyncBarrierLeakScenario.run`。

## 后续批次顺序

后续按锁等待、Broadcast、Service、Provider、Binder、IO、线程池、GC、CPU 竞争的顺序逐个实现。每个批次都需要独立测试、独立文档更新和至少一次手动 JSON 验收。
