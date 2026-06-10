# Vibe ANR Monitoring

Vibe ANR Monitoring 是一个 Android ANR 监控 SDK 与 Demo 验证项目。项目目标不是只告诉你“发生了 ANR”，而是尽量在端侧把现场证据整理成可阅读的 JSON：当前主线程消息、历史消息、Pending 队列、Barrier 证据、主线程栈、线程 CPU、系统环境、Binder 疑似阻塞和系统 ANR 信息，帮助开发者定位 `nativePollOnce`、当前消息慢、消息风暴、组件超时等常见 ANR 根因。

## 项目结构

```text
Vibe-ANR-Monitoring
├── anr-monitor-sdk/        ANR 监控 SDK 模块
├── app/                    Demo App，用按钮复现不同 ANR 场景
├── docs-anr/               ANR 文章总结、SDK 设计、使用说明、JSON 排查指南
├── docs/superpowers/plans/ SDK 与 Demo 场景分阶段实施计划
├── SDK案例分析/            已采集 JSON 和对应人工分析结果
└── README.md               项目总览
```

## 模块说明

| 模块 | 说明 |
| --- | --- |
| `:anr-monitor-sdk` | SDK 主体，包名 `com.valiantyan.anrmonitor`，最低支持 `minSdk=23` |
| `:app` | Demo App，包名 `com.valiantyan.vibeanrmonitoring`，用于接入 SDK 并复现 ANR 场景 |
| `docs-anr` | 面向研发和评审的技术文档目录 |
| `SDK案例分析` | 按场景保存 JSON 样本和 HTML 分析报告 |

## SDK 核心能力

| 能力 | 作用 |
| --- | --- |
| 主 Looper 时间线 | 记录当前消息、历史消息、慢消息和消息 CPU 耗时 |
| Watchdog | 根据主线程心跳判断疑似 ANR 并触发快照 |
| 主线程栈快照 | 捕获疑似 ANR 时主线程正在执行的位置 |
| Pending 队列快照 | 查看主线程消息队列中等待执行的消息 |
| Sync Barrier 证据 | 辅助识别 Barrier 泄漏和 `nativePollOnce` 类 ANR |
| 线程 CPU 排名 | 区分主线程计算、后台线程 CPU 竞争等场景 |
| Checktime 与系统环境 | 辅助判断系统压力、调度延迟、资源不足 |
| Binder 疑似阻塞 | 结合主线程和 Binder 线程栈判断跨进程阻塞风险 |
| 本地 JSON 报告 | 报告默认写入宿主 app 私有目录 |
| 上报扩展点 | 宿主可接入自己的网络或埋点链路 |

## 归因类型

| 归因码 | 含义 |
| --- | --- |
| `CURRENT_MESSAGE_SLOW` | 当前正在 dispatch 的主线程消息耗时过长 |
| `HISTORY_MESSAGE_SLOW` | 历史消息慢，当前栈不一定是根因 |
| `MESSAGE_STORM` | 大量短消息持续占用主线程 |
| `SYNC_BARRIER_STUCK` | 同步屏障残留导致同步消息无法推进 |
| `BINDER_BLOCK_SUSPECTED` | 主线程疑似卡在 Binder 或跨进程调用 |
| `UNKNOWN_INSUFFICIENT_EVIDENCE` | 当前证据不足，不能给出可信根因 |

## Demo 已覆盖场景

| 场景 | 入口 | 预期观察 |
| --- | --- | --- |
| 输入事件当前慢消息 | 点击“当前消息慢” | `CURRENT_MESSAGE_SLOW`，主线程栈定位到当前慢消息场景 |
| 主线程 CPU 忙等 | 点击“当前消息忙等” | `CURRENT_MESSAGE_SLOW`，`cpuMs` 和线程 CPU 支持主线程持续计算 |
| 消息风暴 | 点击“消息风暴” | `MESSAGE_STORM`，Pending 队列中出现大量重复消息 |
| Sync Barrier 泄漏 / nativePollOnce | 点击“Sync Barrier 泄漏” | `SYNC_BARRIER_STUCK`，Barrier 与 `nativePollOnce` 证据对齐 |
| BroadcastReceiver 超时 | 点击“BroadcastReceiver 超时” | 主线程栈定位到 `BroadcastTimeoutReceiver.onReceive` |
| Service 超时 | 点击“Service 超时” | 主线程栈定位到 `ServiceTimeoutService.onStartCommand`，说明 Service 生命周期回调阻塞 |
| ContentProvider 阻塞 | 点击“ContentProvider 阻塞” | 主线程栈定位到 `BlockingContentProvider.query` 和 `ContentProviderBlocker.block` |
| IO / 数据库 / 文件阻塞 | 点击“IO / 数据库 / 文件阻塞” | `CURRENT_MESSAGE_SLOW`，主线程栈定位到 `IoDatabaseFileBlockScenario.run` 和 `FileAndDatabaseBlockingWorkload` |
| Binder 跨进程阻塞 | 点击“Binder 跨进程阻塞” | `BINDER_BLOCK_SUSPECTED`，主线程栈命中 `BinderProxy.transact` 并回溯到 Demo 场景入口 |

后续场景计划包括主线程锁等待、线程池耗尽等待、GC/内存抖动、进程内 CPU 竞争等。

## 快速构建

```bash
./gradlew :app:assembleDebug
```

安装 Demo App：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest :anr-monitor-sdk:testDebugUnitTest
```

完整本地验收常用命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :anr-monitor-sdk:testDebugUnitTest
```

## SDK 接入示例

Demo App 通过 Gradle project 方式接入：

```kotlin
dependencies {
    implementation(project(":anr-monitor-sdk"))
}
```

在 `Application.onCreate()` 中尽早安装：

```kotlin
AnrMonitor.install(
    context = this,
    config = AnrMonitorConfig(
        appId = "demo",
        environment = "debug",
        enabled = true,
        uploadEnabled = false,
        sampleRate = 1.0f,
        suspectAnrMs = 3_000L,
        watchdogIntervalMs = 500L,
    ),
)
```

完整接入、配置、上报和隐私说明见 [docs-anr/103-ANR监控SDK使用说明.md](docs-anr/103-ANR监控SDK使用说明.md)。

## JSON 报告位置

SDK 默认把报告写入宿主 app 私有目录：

```text
<context.filesDir>/anr-monitor-reports/<eventId>.json
```

Demo App 拉取方式：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > anr-report.json
```

报告可以按目录理解：

| 节点 | 作用 |
| --- | --- |
| `schemaVersion` | JSON 协议版本 |
| `event` | 事件 ID、时间、进程、线程等基础信息 |
| `attribution` | SDK 给出的主因、置信度和证据摘要 |
| `mainThread` | 当前消息、历史消息、主线程栈和慢消息采样 |
| `pendingQueue` | 主线程消息队列快照 |
| `barrierEvidence` | Sync Barrier、token、`nativePollOnce` 相关证据 |
| `binderBlock` | Binder 或跨进程阻塞疑似证据 |
| `threadCpu` | 线程 CPU 排名和主线程 CPU 信息 |
| `checktime` | Watchdog 调度延迟和系统压力辅助判断 |
| `systemEnvironment` | 内存、存储、负载、设备环境 |
| `systemAnr` | 系统是否确认 ANR、组件类型和阈值信息 |
| `sdkDiagnostics` | SDK 自身运行状态、落盘和上报诊断 |

新人排查 JSON 时建议先读 [docs-anr/104-ANR监控JSON日志根因排查指南.md](docs-anr/104-ANR监控JSON日志根因排查指南.md)。

## 重点文档

| 文档 | 内容 |
| --- | --- |
| [docs-anr/01-第一篇-设计原理及影响因素.md](docs-anr/01-第一篇-设计原理及影响因素.md) | ANR 设计原理和影响因素总结 |
| [docs-anr/02-第二篇-监控工具与分析思路.md](docs-anr/02-第二篇-监控工具与分析思路.md) | 监控工具和分析思路总结 |
| [docs-anr/03-第三篇-实例剖析集锦.md](docs-anr/03-第三篇-实例剖析集锦.md) | 典型 ANR 实例剖析总结 |
| [docs-anr/04-第四篇-Barrier导致主线程假死.md](docs-anr/04-第四篇-Barrier导致主线程假死.md) | Sync Barrier 与 `nativePollOnce` 分析 |
| [docs-anr/05-第五篇-告别SharedPreference等待.md](docs-anr/05-第五篇-告别SharedPreference等待.md) | SharedPreferences ANR 实战复盘材料 |
| [docs-anr/99-ANR监控SDK设计开发文档.md](docs-anr/99-ANR监控SDK设计开发文档.md) | SDK 总体设计开发文档 |
| [docs-anr/100-ANR监控SDK-阶段一验收记录.md](docs-anr/100-ANR监控SDK-阶段一验收记录.md) | 阶段一验收记录 |
| [docs-anr/101-ANR监控SDK全量验收记录.md](docs-anr/101-ANR监控SDK全量验收记录.md) | 全量验收记录 |
| [docs-anr/102-ANR监控SDK服务端消费协议.md](docs-anr/102-ANR监控SDK服务端消费协议.md) | 服务端消费 JSON 字段协议 |
| [docs-anr/103-ANR监控SDK使用说明.md](docs-anr/103-ANR监控SDK使用说明.md) | SDK 接入、配置、验证和报告读取 |
| [docs-anr/104-ANR监控JSON日志根因排查指南.md](docs-anr/104-ANR监控JSON日志根因排查指南.md) | 面向新人排查 JSON 根因的步骤 |
| [docs-anr/105-Demo-ANR场景实现计划.md](docs-anr/105-Demo-ANR场景实现计划.md) | Demo ANR 场景矩阵和验收记录 |

## 案例分析

`SDK案例分析` 目录按场景保存真实 JSON 样本和 HTML 分析结果：

| 场景 | 内容 |
| --- | --- |
| `输入事件当前慢消息` | 当前消息阻塞导致疑似 ANR |
| `主线程 CPU 忙等` | 主线程持续计算导致疑似 ANR |
| `消息风暴等` | 大量消息占用主线程导致疑似 ANR |
| `Sync Barrier 泄漏 - nativePollOnce` | Barrier 泄漏导致 `nativePollOnce` 假死 |

这些案例适合和 JSON 排查指南一起阅读，用来训练从字段到根因的分析路径。

## 注意事项

- 第五篇 SharedPreferences 文档是 ANR 实战复盘材料，不作为 SDK 基础需求直接实现。
- SDK 默认不替换系统 SharedPreferences，也不提供 QueuedWork 绕过能力。
- `systemAnr` 未确认不代表 SDK 捕获无效；疑似 ANR 报告仍可通过主线程栈、当前消息、Pending 队列和辅助证据定位问题。
- `nativePollOnce` 只是主线程等待消息或被 Barrier 卡住时的表现之一，根因需要结合 `pendingQueue`、`barrierEvidence`、`mainThread.stackFrames` 一起判断。
