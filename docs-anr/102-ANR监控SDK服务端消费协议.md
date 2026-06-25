# ANR 监控 SDK 服务端消费协议

本文定义 ANR 监控 SDK 报告在服务端的消费口径，目标是让聚类、报告页面、治理建议和设计追溯都能从同一份端侧 JSON 协议出发。服务端可以做二次派生，但不能把派生结论反写成端侧已经确认的事实。

## 1. 协议边界

- 端侧报告协议版本：`schemaVersion=1`。
- 端侧只输出已脱敏证据、归因码、置信度、缺失证据和 SDK 自监控指标。
- 服务端负责聚类、owner 映射、版本/设备分布、趋势统计、告警去重和治理闭环。
- 服务端派生维度可以用于看板聚合，但必须保留原始端侧字段，方便评审回放证据链。

## 2. 端侧字段映射

| JSON 节点 | 服务端用途 | 关键字段 |
| --- | --- | --- |
| `event` | 主索引和环境分流 | `eventId`、`eventType`、`appId`、`environment`、`timeUptimeMs` |
| `systemAnr` | 系统确认 ANR 和组件阈值解释 | `isConfirmedAnr`、`anrType`、`componentTimeoutMs`、`shortMsg`、`longMsg` |
| `mainThread` | 当前 Trace、当前消息、历史消息、慢消息价值保留和短消息风暴摘要 | `current`、`history`、`slowHistory`、`aggregatedBursts`、`stackId`、`stackFrames`、`sampleStackIds`、`retention` |
| `pendingQueue` | Pending 队列、同步屏障和消息风暴聚类 | `available`、`truncated`、`maxDepth`、`messages` |
| `barrierEvidence` | Barrier token 和 nativePollOnce 增强证据 | `stuckTokens`、`nativePollOnceRecords`、`nativePollOnceRecords[].source`、`alignedWithPendingBarrier` |
| `binderBlock` | Binder 和跨进程等待疑似识别 | `suspected`、`mainThreadInBinder`、`binderThreadWaitsMain` |
| `threadCpu` | 线程 CPU TopN 和进程内资源竞争 | `topThreads` |
| `checktime` | Watchdog 调度延迟和系统调度压力 | `maxDelayMs`、`severeDelayCount`、`recentDelayMs` |
| `environmentSnapshot` | 系统环境、内存、存储和进程 I/O 背景 | `loadAverage1m`、`memory`、`availableStorageBytes`、`processIo`、`androidVersion`、`manufacturer` |
| `attribution` | 端侧结论和证据缺口 | `primary`、`secondary`、`confidence`、`evidence`、`missingEvidence`、`suggestions` |
| `sdkDiagnostics` | SDK 自监控和采集降级说明 | `reportBuildCostMs`、`collectorFailures`、`privacyMode`、`selfMetrics` |

### 2.1 `mainThread` 兼容扩展字段

| 字段 | 类型 | 必填 | 消费口径 |
| --- | --- | --- | --- |
| `mainThread.slowHistory` | array | 否 | 与 `history` 相同的 `MessageRecord` 结构；用于服务端优先消费历史慢消息和关键慢组件消息。 |
| `mainThread.aggregatedBursts` | array | 否 | `kind=AGGREGATED` 的短消息风暴摘要，`count` 表示聚合消息数，`wallMs/cpuMs` 表示聚合范围内累计耗时。 |
| `mainThread.retention` | object | 否 | 主线程证据保留状态，包含 `historyLimit`、`slowHistoryLimit`、`aggregatedBurstLimit`、`stackSampleLimit`、`historyDroppedCount`、`slowHistoryDroppedCount`、`aggregatedMessageCount`、`aggregationEnabled`、`truncated`。 |

服务端归因消费顺序建议：`current` 和 `stackFrames` 先判断当前消息；当前现场不足时优先读取 `slowHistory` 及其 `sampleStackIds` 对应的 `stackSamples`；再读取 `aggregatedBursts` 判断消息风暴；最后用 `history` 补齐最近时间线。`retention.truncated=true` 表示至少一个窗口因容量限制丢弃过证据，或连续短消息已经被聚合折叠，服务端展示时应避免写成“证据完整”。只有 `retention.truncated = true` 或 `retention.aggregatedMessageCount > 0` 时，才提示时间线存在不完整逐条回放或折叠证据；`retention.aggregatedMessageCount` 表示被折叠进聚合摘要的原始短消息数量。单独存在 `slowHistory` 只表示报告保留了更高价值的历史慢消息，不等价于证据已被折叠。

## 3. 聚类维度

端侧归因码必须来自当前 SDK 枚举。服务端不能为第 5 篇外部案例反向新增端侧归因码，也不能假定端侧报告存在 SharedPreferences 专项字段。

- `CURRENT_MESSAGE_SLOW`
- `HISTORY_MESSAGE_SLOW`
- `MESSAGE_STORM`
- `SYNC_BARRIER_STUCK`
- `BINDER_BLOCK_SUSPECTED`
- `UNKNOWN_INSUFFICIENT_EVIDENCE`

服务端派生维度用于补充业务治理，不作为端侧一等归因码：

- `PROCESS_IO_PRESSURE`：来自 `environmentSnapshot.processIo`、线程 CPU、Checktime 和报告构建耗时的组合判断。
- `EXTERNAL_SYSTEM_LOAD`：来自 load average、内存、存储、Checktime 大延迟和设备/ROM 分布的组合判断。
- `BUSINESS_OWNER_HINT`：来自当前栈 hash、历史慢消息栈 hash、Pending target/callback hash、页面、进程名和 mapping 还原结果。

基础聚类键建议按优先级组合：

1. 端侧归因码 + ANR 类型：Input、Service、Broadcast、Provider、Activity、Finalizer。
2. 当前栈 hash、历史慢消息栈 hash、Pending target/callback hash。
3. Barrier token、Binder 证据签名、nativePollOnce 模式和 `source`。
4. 设备、ROM、Android 版本、App 版本、灰度批次、页面、进程名。
5. 缺失证据类型，例如 Pending 反射失败、环境采集失败、隐私模式裁剪。

## 4. 报告页面

1. 结论卡片：展示端侧归因码、置信度、ANR 类型、业务可治理性和服务端派生维度。
2. 证据链：按系统 Reason、当前 Trace、当前消息、历史消息、Pending、线程 CPU、Checktime、Barrier、Binder 展示，不把单一 Trace 当作根因。
3. 时间线：联动过去历史消息、当前 dispatch、未来 Pending 三段证据，标记 slow、storm、barrier、binder 事件。
4. 专项卡片：Barrier token、nativePollOnce、Binder 阻塞疑似、环境负载。
   nativePollOnce 卡片必须展示 `source`：`HOOK` 表示端侧探针直接记录，`STACK_INFERENCE` 表示由主线程栈和 Pending 队头推断。
5. 缺失证据：展示 collector 失败、权限限制、ROM 限制、隐私模式裁剪和 `UNKNOWN_INSUFFICIENT_EVIDENCE` 的原因。
   服务端必须保留并展示 `slowHistory` 和 `aggregatedBursts` 两类主线程证据面；`mainThread.retention` 用于解释完整性，`historyDroppedCount` 表示最近历史中因容量裁剪或聚合折叠而无法逐条回放的记录数，`aggregatedMessageCount` 用于说明其中被折叠进聚合摘要的原始短消息规模，`slowHistoryDroppedCount` 表示慢历史窗口被裁剪的记录数。`retention.truncated=true` 可能来自窗口淘汰，也可能来自聚合折叠导致无法逐条回放。
6. 治理建议：展示 owner hint、版本分布、设备分布、灰度批次、回滚建议和 Barrier 修复建议；SharedPreferences 治理建议归入存储治理专项，不作为通用 ANR 看板默认项。

## 5. 隐私和权限

- 服务端只消费类名、hash、枚举、耗时、计数、设备维度和脱敏栈；不展示 `Message.obj.toString()`、持久化 key/value 或业务参数。
- mapping 还原应在有权限的服务端任务内完成，报告页面默认展示脱敏后的类名和 hash。
- owner hint 只用于内部治理，不作为公开字段透出给无权限角色。
- 当 `sdkDiagnostics.privacyMode` 不为空时，看板必须显式提示证据被裁剪，避免误判为“没有证据”。

## 6. 全量设计追溯

| 来源文档 | 服务端消费要求 | 对应端侧证据 |
| --- | --- | --- |
| 第一篇：设计原理及影响因素 | ANR 是系统超时结论，服务端不能只看当前 Trace；必须展示 ANR 类型、组件阈值、Reason 和证据链 | `systemAnr`、`componentTimeoutMs`、`mainThread`、`attribution` |
| 第二篇：监控工具与分析思路 | 支持当前、历史、Pending、线程 CPU、Checktime 和环境多证据联判 | `mainThread`、`pendingQueue`、`threadCpu`、`checktime`、`environmentSnapshot` |
| 第三篇：实例剖析集锦 | 支持当前消息慢、历史消息慢、消息风暴、Binder 等典型场景聚类和复核 | `CURRENT_MESSAGE_SLOW`、`HISTORY_MESSAGE_SLOW`、`MESSAGE_STORM`、`BINDER_BLOCK_SUSPECTED` |
| 第四篇：Barrier 导致主线程假死 | 支持 Barrier token、Pending 队头、同步消息等待和 nativePollOnce 联动展示 | `SYNC_BARRIER_STUCK`、`pendingQueue`、`barrierEvidence` |
| 第五篇：告别 SharedPreference 等待 | 仅作为 SharedPreferences ANR 实战分析案例，不产生端侧字段、归因码、看板卡片或基础需求；服务端只能用通用证据链辅助人工复盘 | `mainThread`、`pendingQueue`、`threadCpu`、`checktime`、`environmentSnapshot`、`attribution.missingEvidence` |

## 7. 验收口径

- 服务端协议必须能直接消费 `AnrReportJsonEncoder` 输出的 JSON 字段。
- 聚类维度必须同时覆盖端侧归因码和服务端派生维度，不能把 `PROCESS_IO_PRESSURE`、`EXTERNAL_SYSTEM_LOAD` 误认为 SDK 当前枚举。
- 报告页面必须展示缺失证据和 SDK 自监控指标，避免把采集失败解释成业务无问题。
- 全量追溯必须区分 01 到 04 的 SDK 需求输入和第 5 篇的 SharedPreferences 实战分析样例，并能回指 `docs-anr/99-ANR监控SDK设计开发文档.md` 的服务端消费建议。
