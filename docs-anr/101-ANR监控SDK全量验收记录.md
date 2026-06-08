# ANR 监控 SDK 全量验收记录

验收日期：2026-06-07

## 验收范围

本记录用于确认 ANR 监控 SDK 已覆盖 `docs-anr/01-第一篇-设计原理及影响因素.md` 到 `docs-anr/04-第四篇-Barrier导致主线程假死.md` 的核心需求输入。`docs-anr/05-第五篇-告别SharedPreference等待.md` 仅作为 SharedPreferences ANR 实战分析案例，不参与 SDK API、归因码、报告字段、Demo 入口或基础需求验收。阶段一到阶段四只表示交付顺序，不表示能力裁剪。

## 构建与自动化验证

| 验收项 | 命令 | 状态 |
| --- | --- | --- |
| SDK 全量单元测试 | `./gradlew :anr-monitor-sdk:testDebugUnitTest` | PASS |
| App Debug 构建 | `./gradlew :app:assembleDebug` | PASS |
| 全量验收矩阵守护测试 | `./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.acceptance.FullAcceptanceMatrixTest` | PASS |

## 全量能力矩阵

| 能力 | 来源 | SDK 证据与验证方式 | 状态 |
| --- | --- | --- | --- |
| 当前消息慢 | 第一篇、第二篇 | `currentMessage.wallMs`、主线程栈、`CURRENT_MESSAGE_SLOW`；demo 入口 `Current Slow Message` 和 `Current Busy Loop` | 已接入 |
| 历史消息慢 | 第一篇、第二篇、第三篇 | `historyMessages` 环形缓冲、慢消息阈值、`HISTORY_MESSAGE_SLOW` 单元测试 | 已接入 |
| 消息风暴 | 第二篇、第三篇 | Pending 队列重复 target/callback 证据、`MESSAGE_STORM` 归因；demo 入口 `Message Storm` | 已接入 |
| Pending 队列快照 | 第二篇、第三篇 | `PendingQueueSnapshot` 记录 `available`、`failureReason`、队头与重复消息摘要 | 已接入 |
| Barrier 疑似 | 第四篇 | Pending 队头 `target == null`、同步消息被屏障阻塞、`SYNC_BARRIER_STUCK` 归因 | 已接入 |
| Barrier token | 第四篇 | `BarrierTokenTracker` 记录 post/remove token、停留时长和未移除 token | 已接入 |
| nativePollOnce | 第四篇 | `NativePollOnceMonitor` 记录进入/退出和停留时长，辅助区分假死与真实阻塞 | 已接入 |
| 慢消息堆栈采样 | 第二篇、第三篇 | `SlowMessageStackSampler` 输出 stack hash、hit count 和采样栈 | 已接入 |
| 线程 CPU | 第一篇、第二篇 | `ThreadCpuSnapshotter` 输出 Top N 线程 CPU 排名，辅助判断 CPU 抢占 | 已接入 |
| Checktime | 第一篇、第二篇 | `ChecktimeSummary.maxDelayMs` 和 `severeDelayCount` 记录 Watchdog 调度延迟 | 已接入 |
| 系统环境 | 第一篇、第二篇 | 负载、内存、存储、进程 I/O 和证据可得性字段 | 已接入 |
| Binder 阻塞疑似 | 第三篇 | 主线程 BinderProxy/transact 栈、Binder 线程栈、锁等待栈，归因 `BINDER_BLOCK_SUSPECTED`；demo 入口 `Binder Like Wait` 辅助观察等待现场 | 已接入 |
| 报告治理 | 第二篇、第三篇 | 本地报告数量、总大小、保留时长、gzip 入队、限频、采样、失败重试 | 已接入 |
| SDK 自监控 | 第二篇 | `sdkDiagnostics.selfMetrics`、`reportBuildCostMs`、缺失证据数量和隐私模式 | 已接入 |

## Demo 手动场景入口

| 场景 | 按钮 | 目标证据 |
| --- | --- | --- |
| 当前慢消息 | `Current Slow Message` | 主线程当前消息超过疑似 ANR 阈值 |
| 消息风暴 | `Message Storm` | Pending 队列重复消息与消息风暴归因 |
| 当前忙等 | `Current Busy Loop` | 当前消息慢且 CPU 占用较高 |
| 等待类阻塞 | `Binder Like Wait` | 等待类主线程栈，辅助 Binder/跨进程阻塞复核 |

## 性能验收预算

| 指标 | 预算 | 证据入口 | 状态 |
| --- | --- | --- | --- |
| 常驻 CPU | 空闲态 SDK 线程累计 CPU 应低于宿主进程 1%，不得出现忙轮询 | Watchdog 心跳间隔默认 `1000ms`，demo debug 为 `500ms` | 需真机复测 |
| 主线程单消息额外耗时 | Looper 打点和环形缓冲追加应为 O(1)，单消息额外耗时目标小于 `1ms` | `MainLooperTimelineCollector`、`MessageRingBuffer` 单元测试 | 已有代码级验证 |
| 快照耗时 | 单次报告构建目标小于 `200ms`，异常时记录缺失证据而不是阻塞主线程 | `sdkDiagnostics.reportBuildCostMs`、`report_build_cost_ms` | 已接入 |
| 报告大小 | 单条 JSON 建议小于 `256KB`，gzip 后建议小于 `64KB`；本地最多 `30` 条且总量不超过 `10MB` | `ReportRetentionPolicy`、`ReportRetryQueue` | 已接入 |
| 采样频率 | 慢消息堆栈采样默认 `500ms`，单消息最多 `10` 条，上传采样率通过 `sampleRate` 归一化 | `stackSampleIntervalMs`、`maxStackSamplesPerMessage`、`normalizedSampleRate` | 已接入 |

## 隐私与风险验收

- 栈和类名通过 `AnrPrivacyMode` 控制，报告中记录隐私模式。
- `Message.obj` 等潜在业务对象内容不进入 JSON。
- Pending 队列、系统环境、Barrier、Binder 等核心证据失败时必须记录 `available=false` 或缺失证据，不能影响宿主业务。
- SharedPreferences 风险治理不属于本 SDK 基础验收；如业务需要治理，应转交存储治理专项，不在 ANR SDK 中通过包装 API、文件扫描或 `QueuedWork` 绕过实现。
- Barrier token 与 `nativePollOnce` 属于增强证据，默认可按配置关闭以控制 hook 风险。

## 结论

当前 SDK 已建立覆盖 01 到 04 文档核心需求的全量验收矩阵：基础 ANR 设计因素、监控工具与分析思路、实例归因、Barrier 假死均有对应代码证据、报告字段、测试或 demo 入口。第五篇的 SharedPreferences 内容仅作为监控完成后的外部实战分析案例，用于验证通用证据链复盘能力，不作为基础 SDK 需求完成标准。性能部分已经具备预算和字段出口，最终线上性能结论仍应在目标设备与灰度流量下复测。
