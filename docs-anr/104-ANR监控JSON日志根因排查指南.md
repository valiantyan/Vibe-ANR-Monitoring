# ANR 监控 JSON 日志根因排查指南

本文面向第一次接触 ANR 监控 SDK 报告的新人同学，目标是帮助你拿到一份 JSON 文件后，能够一步一步读懂它、验证证据链，并写出可信的 ANR 根因结论。

配套阅读：

- [103-ANR监控SDK使用说明.md](./103-ANR监控SDK使用说明.md)
- [102-ANR监控SDK服务端消费协议.md](./102-ANR监控SDK服务端消费协议.md)
- [99-ANR监控SDK设计开发文档.md](./99-ANR监控SDK设计开发文档.md)

## 1. 先建立正确读法

拿到 JSON 后不要从第一行读到最后一行。ANR 报告应该像一本书一样看：

1. 先看目录，知道每个节点负责回答什么问题。
2. 再看 SDK 摘要，拿到初始判断。
3. 然后看主线程现场，确认表象是什么。
4. 再看 Pending 队列和专项证据，寻找真正把主线程卡住的原因。
5. 最后看 CPU、Binder、系统环境，把干扰项排除掉。

新人最容易犯的错误是：看到主线程栈顶是 `nativePollOnce`，就直接写“根因是 nativePollOnce”。这是不对的。`nativePollOnce` 通常只是主线程当前停留的位置，它经常是表象，不一定是根因。真正的根因要看它为什么停在这里，比如 Sync Barrier 泄漏、队列消息被挡住、系统没有可执行消息，或者证据不足需要继续结合系统 traces。

## 2. JSON 报告目录

一份报告通常由这些顶层节点组成：

| 节点 | 它回答的问题 | 新人重点看什么 |
| --- | --- | --- |
| `schemaVersion` | 这份报告使用哪个协议版本 | 通常只用于兼容判断，不参与根因分析 |
| `event` | 这份报告是谁、什么时候、在哪个环境生成的 | `eventId`、`eventType`、`appId`、`environment` |
| `systemAnr` | Android 系统是否已经确认 ANR | `isConfirmedAnr`、`anrType`、`componentTimeoutMs`、`shortMsg`、`longMsg` |
| `attribution` | SDK 给出的初始归因结论 | `primary`、`confidence`、`evidence`、`missingEvidence`、`suggestions` |
| `mainThread` | 主线程当时在做什么 | `current`、`history`、`stackFrames`、`stackSamples` |
| `pendingQueue` | 主线程后面还排着什么消息 | `messages[0]` 队头、`blockedMs`、`isBarrierLike`、`isCriticalComponent` |
| `barrierEvidence` | 是否存在 Sync Barrier 和 `nativePollOnce` 证据 | `stuckTokens`、`nativePollOnceRecords`、`alignedWithPendingBarrier` |
| `binderBlock` | 是否疑似 Binder 或跨进程阻塞 | `suspected`、`mainThreadInBinder`、`binderThreadWaitsMain` |
| `threadCpu` | 是否有线程 CPU 竞争 | `topThreads` |
| `checktime` | Watchdog 自己是否被系统调度拖慢 | `maxDelayMs`、`severeDelayCount` |
| `environmentSnapshot` | 当时外部环境是否异常 | 内存、存储、I/O、设备、ROM、失败原因 |
| `sdkDiagnostics` | SDK 自身采集是否完整 | `collectorFailures`、`missingEvidenceCount`、`privacyMode` |

## 3. 十步定位法

### 第 1 步：确认这是一份什么报告

先看 `event`：

```json
{
  "eventId": "09b26c90-bb7b-4f6a-9192-cf34dba094ee",
  "eventType": "SUSPECT_ANR",
  "appId": "vibe-anr-demo",
  "environment": "debug",
  "timeUptimeMs": 3128424628
}
```

你要记录四件事：

| 字段 | 怎么理解 |
| --- | --- |
| `eventId` | 本次报告唯一 ID，后续查日志、查本地文件、查服务端都用它 |
| `eventType` | 报告阶段，`SUSPECT_ANR` 表示 SDK 提前抓到疑似 ANR |
| `appId` | 哪个应用或业务标识 |
| `environment` | debug、gray、release 等环境 |

如果 `eventType = SUSPECT_ANR`，不要因为它不是系统确认 ANR 就丢弃。SDK 的价值之一就是提前保存现场，因为等系统真正 ANR 时，关键现场可能已经变化。

### 第 2 步：确认系统是否也判定了 ANR

看 `systemAnr`：

```json
{
  "available": true,
  "isConfirmedAnr": false,
  "anrType": "UNKNOWN",
  "componentTimeoutMs": null
}
```

判断方式：

| 情况 | 说明 |
| --- | --- |
| `isConfirmedAnr = true` | 系统也确认了 ANR，优先结合 `anrType`、`shortMsg`、`longMsg` 和系统 traces 分析 |
| `isConfirmedAnr = false` | SDK 提前采集的疑似 ANR，继续看主线程和队列现场 |
| `available = false` | 系统确认信息不可用，不代表没有 ANR，只代表这个证据缺失 |

### 第 3 步：先读 SDK 给出的摘要，但不要只信摘要

看 `attribution`：

```json
{
  "primary": "SYNC_BARRIER_STUCK",
  "confidence": "HIGH",
  "evidence": [
    "pending queue head is Sync Barrier",
    "first synchronous message blocked 3022ms"
  ],
  "missingEvidence": [],
  "suggestions": [
    "检查 postSyncBarrier/removeSyncBarrier 配对和 UI 调度清理逻辑。"
  ]
}
```

这里先拿一个初始方向：

| 字段 | 怎么理解 |
| --- | --- |
| `primary` | SDK 当前认为最可能的主因 |
| `secondary` | 可能共同参与的辅因 |
| `confidence` | 置信度，`HIGH` 才适合直接推动修复，`LOW` 或 `MEDIUM` 需要补证据 |
| `evidence` | 支撑主因的证据摘要 |
| `missingEvidence` | 影响判断的缺失证据 |
| `suggestions` | 治理建议，不等同于最终修复方案 |

注意：`attribution` 是入口，不是终点。后面必须用 `mainThread`、`pendingQueue`、`barrierEvidence` 等节点复核。

### 第 4 步：看主线程当前状态

看 `mainThread.stackFrames` 和 `mainThread.current`。

如果 `current` 不为空，说明 SDK 抓到了一条正在 dispatch、还没有结束的主线程消息。优先看：

- `targetClass`
- `callbackClass`
- `wallMs`
- `cpuMs`
- `sampleStackIds`
- 对应的 `stackSamples`

如果 `current = null`，不要马上认为没有问题。主线程可能已经不在 Java 消息 dispatch 内，而是停在 Looper 取消息、Binder 等待、锁等待或 native 层等待中。

例如：

```json
"stackFrames": [
  "android.os.MessageQueue.nativePollOnce(Native Method)",
  "android.os.MessageQueue.next(MessageQueue.java:339)",
  "android.os.Looper.loopOnce(Looper.java:179)"
]
```

这说明主线程当前表象是停在 `nativePollOnce`。下一步必须去看 Pending 队列，判断它是正常空闲，还是被 Sync Barrier 或其他条件卡住。

### 第 5 步：看历史消息有没有慢任务或消息风暴

看 `mainThread.history`：

| 字段 | 怎么理解 |
| --- | --- |
| `seq` | 消息顺序，越大越接近 ANR 现场 |
| `kind` | `HISTORY` 表示已完成历史消息，`AGGREGATED` 表示归并后的短消息 |
| `targetClass` | Handler 目标类 |
| `callbackClass` | Runnable 或回调类 |
| `wallMs` | 墙钟耗时，表示这条消息从开始到结束花了多久 |
| `cpuMs` | 主线程 CPU 耗时，帮助区分忙等和等待 |
| `count` | 归并消息数量，常用于消息风暴 |
| `sampleStackIds` | 慢消息采样栈 ID |

判断思路：

| 现象 | 可能归因 |
| --- | --- |
| 某条历史消息 `wallMs` 很高，且业务栈明确 | `HISTORY_MESSAGE_SLOW` |
| `wallMs` 高但 `cpuMs` 低 | 可能是等待、锁、I/O、Binder |
| `wallMs` 高且 `cpuMs` 也高 | 可能是主线程 CPU 忙等或计算过重 |
| 很多短消息连续出现，或者 `AGGREGATED.count` 很大 | `MESSAGE_STORM` |

### 第 6 步：看 Pending 队列，找被堵住的消息

`pendingQueue.messages` 是新人最应该重点看的节点之一。它告诉你采集时主线程后面还排着哪些消息。

先看 `messages[0]`，也就是队头：

```json
{
  "index": 0,
  "blockedMs": 3039,
  "targetClass": null,
  "arg1": 20,
  "isBarrierLike": true
}
```

字段解释：

| 字段 | 怎么理解 |
| --- | --- |
| `index` | 队列顺序，`0` 是队头 |
| `whenUptimeMs` | 消息原本计划执行时间 |
| `delayMs` | 相对采集时刻还有多久执行，负数表示已经过期 |
| `blockedMs` | 已经过期但还没执行的时间，可理解为被堵了多久 |
| `targetClass` | Handler target 类名；Sync Barrier 常见为空 |
| `callbackClass` | Runnable 类名，能帮助定位业务入口 |
| `objClass` | Message.obj 的类型 |
| `isAsynchronous` | 是否异步消息 |
| `isBarrierLike` | 是否疑似 Sync Barrier |
| `isCriticalComponent` | 是否是系统关键组件消息，比如 Activity、Service、Receiver、Input |

关键判断：

| 队列现象 | 说明 |
| --- | --- |
| 队头 `isBarrierLike = true` | 优先怀疑 Sync Barrier 阻塞 |
| 队头不是 Barrier，但 `blockedMs` 很大 | 可能有队头消息迟迟未执行，需要结合主线程栈 |
| 关键组件消息 `isCriticalComponent = true` 且 `blockedMs` 很大 | 可能触发系统组件超时，比如 Service、Broadcast、Input |
| 多条相同 `callbackClass` 堆积 | 可能是业务重复投递、消息风暴或被 Barrier 挡住 |

### 第 7 步：如果看到 Barrier 或 nativePollOnce，马上看 Barrier 专项证据

看 `barrierEvidence`：

```json
{
  "repeatedInfinitePollCount": 1,
  "alignedWithPendingBarrier": true,
  "stuckTokens": [
    {
      "token": 20,
      "removeUptimeMs": null,
      "aliveMs": 3015
    }
  ],
  "nativePollOnceRecords": [
    {
      "timeoutMillis": -1,
      "isInfiniteWait": true,
      "isInFlight": true,
      "source": "STACK_INFERENCE"
    }
  ]
}
```

重点字段：

| 字段 | 怎么理解 |
| --- | --- |
| `available` | Barrier 增强证据是否可用 |
| `stuckTokens[].token` | `postSyncBarrier()` 返回的 token |
| `stuckTokens[].removeUptimeMs` | token 是否被移除，`null` 表示未观察到移除 |
| `stuckTokens[].aliveMs` | Barrier 已经存活多久 |
| `stuckTokens[].postStack` | 插入 Barrier 的调用栈，通常是定位责任代码的关键 |
| `nativePollOnceRecords[].timeoutMillis` | `-1` 表示无限等待 |
| `nativePollOnceRecords[].isInFlight` | 是否仍停留在这次 native poll 中 |
| `nativePollOnceRecords[].source` | `HOOK` 表示探针直接记录，`STACK_INFERENCE` 表示由主线程栈和队列现场推断 |
| `alignedWithPendingBarrier` | nativePollOnce 证据是否和 Pending 队头 Barrier 对齐 |

如果同时满足下面几条，基本可以高置信判断为 Sync Barrier 泄漏：

1. 主线程栈顶是 `MessageQueue.nativePollOnce`。
2. `pendingQueue.messages[0].isBarrierLike = true`。
3. `pendingQueue.messages[0].arg1` 能和 `stuckTokens[].token` 对上。
4. `stuckTokens[].removeUptimeMs = null`。
5. Barrier 后面的同步消息 `blockedMs` 持续增长。
6. `alignedWithPendingBarrier = true`。

### 第 8 步：看 Binder 是否参与

看 `binderBlock`：

```json
{
  "available": true,
  "suspected": false,
  "mainThreadInBinder": false,
  "binderThreadWaitsMain": false
}
```

判断方式：

| 情况 | 说明 |
| --- | --- |
| `suspected = false` | 当前没有发现 Binder 阻塞证据 |
| `mainThreadInBinder = true` | 主线程停在 Binder transact 相关栈 |
| `binderThreadWaitsMain = true` | Binder 线程疑似等待主线程或锁 |
| `available = false` | Binder 证据不可用，不能据此排除 Binder |

如果 `BINDER_BLOCK_SUSPECTED` 是主因，通常还要结合系统 traces、对端进程日志和业务调用链复核。SDK 这里输出的是疑似证据，不应直接写成“确认跨进程死锁”。

### 第 9 步：看 CPU、Checktime 和环境，排除干扰项

看 `threadCpu`：

```json
"topThreads": [
  {
    "threadName": "beanrmonitoring",
    "totalCpuMs": 840
  }
]
```

看法：

- 如果某个业务线程 CPU 很高，可能主线程被 CPU 竞争影响。
- 如果主线程 CPU 很高，且当前消息 `cpuMs` 也高，可能是主线程忙等或计算过重。
- 如果 CPU 没有异常，根因更可能在等待、队列阻塞、Binder、I/O 或 Barrier。

看 `checktime`：

```json
{
  "maxDelayMs": 2,
  "severeDelayCount": 0
}
```

看法：

- `maxDelayMs` 很低，说明 Watchdog 自己没有明显被系统调度拖慢。
- `severeDelayCount` 很高，说明当时系统调度压力可能较大，SDK 报告时间也可能被拖慢。

看 `environmentSnapshot`：

| 字段 | 怎么理解 |
| --- | --- |
| `memory.availableBytes` | 可用内存 |
| `memory.isLowMemory` | 是否低内存 |
| `availableStorageBytes` | 可用存储 |
| `processIo` | 当前进程 I/O 读写情况 |
| `androidVersion`、`manufacturer`、`model` | 设备和 ROM 背景 |
| `failureReasons` | 哪些环境证据采集失败 |

注意：`failureReasons` 里出现权限错误不一定是 SDK 错误。例如部分 ROM 不允许读取 `/proc/loadavg`，这只代表 CPU load 证据缺失，不代表本次 ANR 是 CPU load 导致。

### 第 10 步：写出根因结论

不要只写“发生 ANR”或“主线程 nativePollOnce”。正确结论应该包含：

1. 表象是什么。
2. 真正阻塞主线程推进的原因是什么。
3. 哪些字段支撑这个结论。
4. 哪些干扰项已经排除。
5. 应该去哪里改代码。

推荐模板：

```text
报告 ID：
报告阶段：
SDK 主因：
置信度：

主线程表象：
队列证据：
专项证据：
源码定位：
排除项：

根因结论：
修复建议：
还需要补充的证据：
```

## 4. 常见主因怎么判断

### 4.1 CURRENT_MESSAGE_SLOW

判断要点：

- `attribution.primary = CURRENT_MESSAGE_SLOW`。
- `mainThread.current` 不为空。
- `mainThread.current.wallMs` 超过慢消息阈值。
- `mainThread.stackFrames` 或 `stackSamples` 能看到业务方法。

结论写法：

```text
主线程正在执行某条消息，消息 wall time 已超过阈值，栈中定位到业务方法 xxx，因此根因是当前消息执行过慢。
```

### 4.2 HISTORY_MESSAGE_SLOW

判断要点：

- `attribution.primary = HISTORY_MESSAGE_SLOW`。
- `mainThread.current` 可能为空，或者当前栈不是根因。
- `mainThread.history` 中存在明显慢消息。
- 慢消息的 `targetClass`、`callbackClass` 或 `sampleStackIds` 能定位业务入口。

结论写法：

```text
当前采集时主线程已经离开慢消息现场，但历史消息中 xxx 曾耗时 xxx ms，且采样栈指向 xxx，因此根因应回溯到历史慢消息。
```

### 4.3 MESSAGE_STORM

判断要点：

- `attribution.primary = MESSAGE_STORM`。
- `mainThread.history` 中有大量短消息连续出现。
- 或者存在 `kind = AGGREGATED` 且 `count` 较大。
- 单条消息不一定慢，但累计占满主线程窗口。

结论写法：

```text
没有单条特别慢的消息，但短消息在窗口内大量堆积，主线程持续被小任务占用，因此根因是消息风暴。
```

### 4.4 SYNC_BARRIER_STUCK

判断要点：

- `attribution.primary = SYNC_BARRIER_STUCK`。
- 主线程栈常见为 `MessageQueue.nativePollOnce`。
- `pendingQueue.messages[0].isBarrierLike = true`。
- Barrier 后面的同步消息 `blockedMs` 较大。
- `barrierEvidence.stuckTokens[].removeUptimeMs = null`。
- `barrierEvidence.alignedWithPendingBarrier = true`。

结论写法：

```text
主线程表面停在 nativePollOnce，但 Pending 队头是未移除的 Sync Barrier，后续同步消息已阻塞 xxx ms，Barrier token 的创建栈指向 xxx，因此根因是 Sync Barrier 泄漏。
```

### 4.5 BINDER_BLOCK_SUSPECTED

判断要点：

- `attribution.primary = BINDER_BLOCK_SUSPECTED`。
- `binderBlock.suspected = true`。
- `mainThread.stackFrames` 命中 Binder transact、proxy 调用或等待相关栈。
- `binderBlock.mainThreadEvidence` 或 `binderBlock.binderThreadEvidence` 有证据。

结论写法：

```text
主线程疑似停在 Binder 调用链，SDK 已命中 Binder 阻塞证据。当前只能判断为跨进程阻塞疑似，需要继续结合系统 traces 和对端进程日志确认。
```

### 4.6 UNKNOWN_INSUFFICIENT_EVIDENCE

判断要点：

- `attribution.primary = UNKNOWN_INSUFFICIENT_EVIDENCE`。
- `confidence` 较低。
- `missingEvidence` 不为空。
- 多个关键节点 `available = false` 或现场不完整。

结论写法：

```text
当前报告证据不足，暂不能给出可信根因。缺失证据包括 xxx，需要补充系统 traces、logcat、业务埋点或打开 SDK 增强采集后复现。
```

## 5. nativePollOnce 专项排查法

看到主线程栈顶是：

```text
android.os.MessageQueue.nativePollOnce(Native Method)
```

先不要下结论。按下面顺序排查：

1. 看 `pendingQueue.available` 是否为 `true`。
2. 看 `pendingQueue.messages[0]` 是否存在。
3. 如果队头 `isBarrierLike = true`，优先走 Sync Barrier 证据链。
4. 如果队头不是 Barrier，但有关键组件消息阻塞，继续看主线程历史和系统 ANR 类型。
5. 看 `barrierEvidence.nativePollOnceRecords` 是否存在。
6. 如果 `timeoutMillis = -1` 且 `isInFlight = true`，说明主线程正处在无限等待窗口。
7. 看 `source`：
   - `HOOK`：来自 hook、调试入口或灰度探针直接记录。
   - `STACK_INFERENCE`：来自主线程栈和 Pending 队列现场推断。
8. 看 `alignedWithPendingBarrier` 是否为 `true`。

可以用下面这句话帮助自己判断：

```text
nativePollOnce 只是主线程等待位置；Pending 队列和专项证据才负责解释为什么等在这里。
```

## 6. 真实样例走读：09b26c90-bb7b-4f6a-9192-cf34dba094ee.json

本节用一份真实 Demo 报告演示完整分析过程。

### 6.1 报告基本信息

```text
eventId = 09b26c90-bb7b-4f6a-9192-cf34dba094ee
eventType = SUSPECT_ANR
appId = vibe-anr-demo
environment = debug
```

解释：

- 这是 SDK 抓到的疑似 ANR。
- 环境是 debug。
- 后续所有日志和文件都可以用 `eventId` 对齐。

### 6.2 系统是否确认

```text
systemAnr.isConfirmedAnr = false
systemAnr.anrType = UNKNOWN
```

解释：

- 系统当时还没有确认 ANR。
- 这不影响 SDK 报告价值，因为它已经保存了卡顿现场。

### 6.3 SDK 初始结论

```text
attribution.primary = SYNC_BARRIER_STUCK
attribution.confidence = HIGH
```

SDK 给出的证据包括：

```text
pending queue head is Sync Barrier
first synchronous message blocked 3022ms
barrier stuck token=20 alive=3015ms
nativePollOnce infiniteWaitCount=1 alignedWithPendingBarrier=true
```

初始判断：这不是普通慢消息，而是 Sync Barrier 卡住了主线程消息队列。

### 6.4 主线程表象

主线程栈：

```text
android.os.MessageQueue.nativePollOnce(Native Method)
android.os.MessageQueue.next(MessageQueue.java:339)
android.os.Looper.loopOnce(Looper.java:179)
android.os.Looper.loop(Looper.java:344)
```

表象：主线程停在 `nativePollOnce`。

注意：这里还不能直接写根因。下一步要看它为什么停在这里。

### 6.5 Pending 队列证据

队头消息：

```text
pendingQueue.messages[0].isBarrierLike = true
pendingQueue.messages[0].targetClass = null
pendingQueue.messages[0].arg1 = 20
pendingQueue.messages[0].blockedMs = 3039
```

解释：

- 队头是一个疑似 Sync Barrier。
- token 是 `20`。
- 它已经阻塞队列约 `3039ms`。

Barrier 后面的同步消息：

```text
pendingQueue.messages[1].callbackClass = SyncBarrierLeakScenario$$ExternalSyntheticLambda0
pendingQueue.messages[1].blockedMs = 3022
```

还可以看到关键系统组件消息也被堵住：

```text
ActivityThread$CreateServiceData blockedMs = 3018
ActivityThread$ServiceArgsData blockedMs = 3017
```

解释：

- 业务同步消息被挡住了。
- 系统 Service 创建和 Service 参数消息也被挡住了。
- 队列不是正常空闲，而是无法推进。

### 6.6 Barrier 专项证据

```text
barrierEvidence.stuckTokens[0].token = 20
barrierEvidence.stuckTokens[0].removeUptimeMs = null
barrierEvidence.stuckTokens[0].aliveMs = 3015
barrierEvidence.alignedWithPendingBarrier = true
```

解释：

- `stuckTokens[0].token = 20` 和 Pending 队头 `arg1 = 20` 对上了。
- `removeUptimeMs = null` 表示没有观察到移除。
- `aliveMs = 3015` 表示这个 Barrier 已经存活约 3 秒。
- `alignedWithPendingBarrier = true` 表示专项证据和 Pending 队头 Barrier 对齐。

### 6.7 nativePollOnce 增强证据

```text
timeoutMillis = -1
isInfiniteWait = true
isInFlight = true
source = STACK_INFERENCE
```

解释：

- `timeoutMillis = -1` 表示无限等待。
- `isInFlight = true` 表示采集时主线程仍在 native poll 中。
- `source = STACK_INFERENCE` 表示 SDK 是通过主线程栈和 Pending 队列推断出来的。

这说明 `nativePollOnce` 这条表象和队头 Barrier 能对上。

### 6.8 源码定位

Barrier 的创建堆栈：

```text
SyncBarrierLeakScenario.run(SyncBarrierLeakScenario.kt:32)
MainActivity.runSyncBarrierLeak(MainActivity.kt:80)
MainActivity.onCreate$lambda$4(MainActivity.kt:40)
```

解释：

- 责任入口不是 `nativePollOnce`。
- 真正入口是 Demo 中点击按钮后调用 `runSyncBarrierLeak()`。
- 代码里插入了一个 Sync Barrier，并且没有移除。

### 6.9 排除其他方向

Binder：

```text
binderBlock.suspected = false
mainThreadInBinder = false
```

说明当前没有 Binder 阻塞证据。

Checktime：

```text
checktime.maxDelayMs = 2
checktime.severeDelayCount = 0
```

说明 Watchdog 自己没有明显被系统调度拖慢。

环境：

```text
memory.availableBytes = 3580780544
availableStorageBytes = 183775485952
```

说明这份样例里没有明显低内存或低存储证据。

### 6.10 最终结论

可以这样写：

```text
本次报告是 SDK 在 debug 环境采集到的疑似 ANR。主线程表象停在 MessageQueue.nativePollOnce，但 Pending 队头是 Sync Barrier，token=20，后续同步消息和系统 Service 组件消息已经阻塞约 3 秒。Barrier 专项证据显示 token=20 的 removeUptimeMs 为 null，aliveMs 约 3015ms，并且 nativePollOnce 证据与 Pending 队头 Barrier 对齐。Barrier 创建栈定位到 SyncBarrierLeakScenario.run 和 MainActivity.runSyncBarrierLeak。因此根因是 Demo 场景故意插入 Sync Barrier 后未移除，导致主线程消息队列假死。
```

修复方向：

```text
检查 postSyncBarrier/removeSyncBarrier 是否严格配对。任何插入 Barrier 的路径都必须保证 finally 或生命周期清理中移除 token，避免同步消息长期被阻塞。
```

## 7. 新人分析记录模板

每分析一份 JSON，建议按下面模板输出：

```text
报告 ID：
文件路径：
分析人：
分析时间：

1. 报告阶段
- eventType：
- systemAnr.isConfirmedAnr：
- anrType：

2. SDK 初始归因
- primary：
- confidence：
- evidence：
- missingEvidence：

3. 主线程表象
- current 是否存在：
- 栈顶：
- 是否出现 nativePollOnce / Binder / 锁等待 / 业务方法：

4. Pending 队列
- 队头消息：
- 队头 blockedMs：
- 是否 Barrier：
- 是否有关键组件消息阻塞：
- 是否有大量重复业务消息：

5. 专项证据
- barrierEvidence：
- binderBlock：
- nativePollOnceRecords：

6. 资源和环境
- CPU TopN 是否异常：
- checktime 是否异常：
- 内存、存储、I/O 是否异常：
- SDK 采集是否有 failureReason：

7. 排除项
- 是否排除 Binder：
- 是否排除 CPU 竞争：
- 是否排除系统调度压力：
- 是否排除低内存或低存储：

8. 根因结论
- 一句话结论：
- 证据链：
- 责任代码位置：
- 修复建议：
- 还缺什么证据：
```

## 8. 常用查看命令

如果本机安装了 `jq`，可以用这些命令快速查看关键节点。

查看报告目录：

```bash
jq 'keys' anr-report.json
```

查看 SDK 归因：

```bash
jq '.attribution' anr-report.json
```

查看系统确认信息：

```bash
jq '.systemAnr' anr-report.json
```

查看主线程栈：

```bash
jq '.mainThread.stackFrames' anr-report.json
```

查看 Pending 队头：

```bash
jq '.pendingQueue.messages[0]' anr-report.json
```

查看被堵住的关键组件消息：

```bash
jq '.pendingQueue.messages[] | select(.isCriticalComponent == true)' anr-report.json
```

查看 Barrier 证据：

```bash
jq '.barrierEvidence' anr-report.json
```

查看 Binder 证据：

```bash
jq '.binderBlock' anr-report.json
```

查看 CPU 排名：

```bash
jq '.threadCpu.topThreads' anr-report.json
```

查看 SDK 自检失败原因：

```bash
jq '.sdkDiagnostics.collectorFailures' anr-report.json
```

如果没有 `jq`，也可以用 Android Studio、VS Code 或任意 JSON Viewer 打开，按节点折叠阅读。最常搜索的关键词是：

```text
attribution
stackFrames
pendingQueue
isBarrierLike
blockedMs
barrierEvidence
nativePollOnceRecords
binderBlock
threadCpu
failureReason
```

## 9. 常见误区

| 误区 | 正确做法 |
| --- | --- |
| 看到 `nativePollOnce` 就写根因是 nativePollOnce | `nativePollOnce` 多数是表象，要继续看 Pending 队列和专项证据 |
| `systemAnr.isConfirmedAnr = false` 就认为报告没价值 | 这通常是 SDK 提前采集，现场可能更完整 |
| `mainThread.current = null` 就认为主线程没卡 | 主线程可能停在 Looper、native poll、Binder 或锁等待中 |
| `available = false` 当成没有问题 | `available = false` 代表证据不可用，不代表问题不存在 |
| 只看 `attribution.primary` 不看证据 | 必须用 `evidence`、`pendingQueue`、`stackFrames` 复核 |
| 把 `failureReason` 都当成根因 | 很多 `failureReason` 是采集限制，比如 ROM 权限，不一定参与 ANR |
| 多份 JSON 都当成多个独立问题 | 先比较 `primary`、Barrier token、创建栈、时间窗口，可能是同一次卡顿的重复采样或历史文件 |

## 10. 评审检查清单

提交 ANR 分析结论前，用下面清单自检：

- 是否写清楚 `eventId` 和报告阶段。
- 是否说明系统是否确认 ANR。
- 是否列出 SDK 主因和置信度。
- 是否解释主线程表象，而不是把表象当根因。
- 是否检查了 Pending 队列队头。
- 如果涉及 `nativePollOnce`，是否解释了它为什么等待。
- 如果涉及 Sync Barrier，是否对齐 token、队头消息、`removeUptimeMs` 和创建栈。
- 是否检查了 Binder、CPU、Checktime、环境证据。
- 是否说明哪些方向被排除。
- 是否定位到业务代码入口或明确说明还缺什么证据。
- 是否给出可执行修复建议。
