# ANR 监控 SDK 使用说明

本文面向接入方、测试同学和后续评审，说明当前项目中的 ANR 监控 SDK 如何接入、如何配置、如何验证、如何读取报告。设计背景见 [99-ANR监控SDK设计开发文档.md](./99-ANR监控SDK设计开发文档.md)，服务端字段消费口径见 [102-ANR监控SDK服务端消费协议.md](./102-ANR监控SDK服务端消费协议.md)。

## 1. 当前 SDK 能力边界

当前 SDK 模块为 `:anr-monitor-sdk`，包名为 `com.valiantyan.anrmonitor`，最低支持 `minSdk=23`。SDK 不声明额外 Manifest 权限，不需要宿主添加公开反射 keep 规则，报告默认写入宿主 app 私有目录。

已覆盖的核心能力：

| 能力 | 用途 |
| --- | --- |
| 主 Looper 时间线 | 采集当前消息、历史消息、消息风暴、慢消息栈采样和消息 CPU 耗时 |
| Watchdog | 按心跳阈值发现疑似 ANR，并触发现场快照 |
| 主线程栈 | 捕获疑似 ANR 时主线程 Java 栈 |
| Pending 队列 | 采集队列中待执行消息，用于消息堆积和 Barrier 复核 |
| Barrier 证据 | 输出 Barrier token 和 `nativePollOnce` 增强证据，默认关闭 |
| SharedPreferences 证据 | 采集文件健康度、最近操作、pending finisher 和 QueuedWork 治理状态 |
| 线程 CPU TopN | 识别进程内线程 CPU 竞争 |
| Checktime | 判断 Watchdog 调度是否被系统压力拖慢 |
| 系统环境 | 采集系统负载、内存、存储、进程 I/O、设备和 ROM 信息 |
| Binder 疑似阻塞 | 结合主线程和 Binder 线程栈输出 suspected 证据 |
| 本地 JSON 报告 | 每次报告落盘到 app 私有目录，并受保留策略治理 |
| 上传扩展点 | 宿主提供 `AnrReportUploader` 后，SDK 负责调用、限频和运行时重试 |

端侧归因码包括：

| 归因码 | 含义 |
| --- | --- |
| `CURRENT_MESSAGE_SLOW` | 当前正在 dispatch 的消息耗时过长 |
| `HISTORY_MESSAGE_SLOW` | 历史消息慢，当前 Trace 不一定是根因 |
| `MESSAGE_STORM` | 大量短消息累计占满主线程窗口 |
| `SYNC_BARRIER_STUCK` | 同步屏障残留导致同步消息无法推进 |
| `SP_LOAD_WAIT` | SharedPreferences 首次加载等待 |
| `SP_APPLY_WAIT` | SharedPreferences `apply()` 后等待落盘 |
| `BINDER_BLOCK_SUSPECTED` | 主线程疑似阻塞在 Binder 或跨进程调用 |
| `UNKNOWN_INSUFFICIENT_EVIDENCE` | 证据不足，无法给出可信主因 |

## 2. 接入依赖

当前仓库内 Demo App 通过 Gradle project 方式接入：

```kotlin
dependencies {
    implementation(project(":anr-monitor-sdk"))
}
```

如果后续发布为 AAR 或 Maven 依赖，宿主只需要把上述依赖替换为对应坐标；公开 API 仍以 `com.valiantyan.anrmonitor.api` 包下类型为准。

宿主 Application 需要在 Manifest 中声明：

```xml
<application
    android:name=".App"
    ...>
</application>
```

## 3. 初始化 SDK

建议在主进程 `Application.onCreate()` 尽早安装，确保首屏、启动阶段和 Activity 场景都能被采集。

```kotlin
import android.app.Application
import android.util.Log
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        AnrMonitor.install(
            context = this,
            config = AnrMonitorConfig(
                appId = "your-app-id",
                environment = "debug",
                enabled = true,
                uploadEnabled = false,
                sampleRate = 1.0f,
                suspectAnrMs = 3_000L,
                watchdogIntervalMs = 500L,
                privacyMode = AnrPrivacyMode.SAFE,
            ),
            uploader = AnrReportUploader { report: AnrReport ->
                // 接入方在这里转交到自己的网络、日志或埋点链路。
                // 返回 Success/Skip/Failure 会影响 SDK 的重试和自监控记录。
                UploadResult.Skip
            },
            listener = object : AnrEventListener {
                override fun onSuspectAnr(snapshot: AnrSnapshot) {
                    Log.w("AnrMonitor", "suspect ANR: ${snapshot.eventId}")
                }

                override fun onConfirmedAnr(report: AnrReport) {
                    Log.w("AnrMonitor", "report generated: ${report.snapshot.eventId}")
                }

                override fun onMonitorError(error: Throwable) {
                    Log.e("AnrMonitor", "monitor error: ${error.message}", error)
                }
            },
        )
    }
}
```

安装语义：

- `AnrMonitor.install()` 在当前进程内幂等，重复安装会返回已有会话。
- `AnrMonitor.uninstall()` 会停止 Watchdog、恢复 Looper Printer，并允许后续重新安装。
- `AnrMonitorSession.stop()` 适合调试、自动化测试或动态关闭场景。
- SDK 内部异常会通过 `AnrEventListener.onMonitorError()` 回调，不应影响宿主主流程。

多进程应用建议由宿主自行判断进程名，只在需要监控的进程安装；否则每个进程都会维护自己的 Watchdog、报告目录和上传链路。

## 4. 配置项建议

核心配置推荐：

| 配置 | 默认值 | 建议 |
| --- | --- | --- |
| `enabled` | `true` | 总开关，线上可接远程配置 |
| `uploadEnabled` | `false` | 调试阶段先关闭；灰度确认后开启 |
| `sampleRate` | `1.0f` | 线上按流量调低，例如 `0.01f` 到 `0.1f` |
| `historyBufferSize` | `120` | 主线程历史消息窗口；过小会影响历史慢消息判断 |
| `slowMessageMs` | `1000L` | 单条慢消息阈值，建议按业务帧率和主线程预算调整 |
| `stackSampleIntervalMs` | `500L` | 慢消息栈采样间隔，越小成本越高 |
| `maxStackSamplesPerMessage` | `10` | 控制单消息栈采样体积 |
| `watchdogIntervalMs` | `1000L` | Watchdog 检查间隔；Debug 可设 `500L` |
| `suspectAnrMs` | `5000L` | 疑似 ANR 阈值；Debug 可设 `3000L` 便于验证 |
| `pendingSnapshotMaxDepth` | `200` | Pending 队列反射快照深度 |
| `captureChecktime` | `true` | 建议开启，便于解释系统调度压力 |
| `captureSystemEnvironment` | `true` | 建议开启，便于排除外部资源压力 |
| `captureThreadCpu` | `true` | 建议开启，便于发现进程内 CPU 竞争 |
| `capturePendingQueue` | `true` | 建议开启，Barrier 和消息风暴依赖该证据 |
| `captureSpHealth` | `true` | 建议开启，SharedPreferences 专项依赖该证据 |
| `captureBarrierEvidence` | `false` | 高风险增强证据，建议灰度开启 |
| `captureBinderEvidence` | `true` | 输出 suspected 证据，不直接确认跨进程死锁 |
| `reportRetentionMaxFileCount` | `30` | 控制本地报告文件数量 |
| `reportRetentionMaxTotalBytes` | `10MB` | 控制本地报告总体积 |
| `reportRetentionMaxAgeMs` | `7天` | 控制本地报告最长保留时间 |
| `reportUploadMinIntervalMs` | `60000L` | 控制上报入队频率 |
| `privacyMode` | `SAFE` | 更严格线上环境可切到 `STRICT` |

环境配置建议：

| 环境 | 推荐配置 |
| --- | --- |
| Debug | `uploadEnabled=false`、`sampleRate=1.0f`、`suspectAnrMs=3000L`、`watchdogIntervalMs=500L` |
| 灰度 | `uploadEnabled=true`、`sampleRate=0.05f` 到 `0.1f`、保留 `SAFE` 隐私模式，先不开启 QueuedWork 绕过 |
| 线上 | `uploadEnabled=true`、低采样率、`privacyMode=STRICT` 或按合规要求配置；Barrier 和 QueuedWork 能力按白名单灰度 |

## 5. 上传接入

SDK 不绑定任何网络库，宿主通过 `AnrReportUploader` 接收领域对象 `AnrReport`。SDK 总是先写本地 JSON；只有 `uploadEnabled=true` 时才会调用 uploader。

```kotlin
val uploader = AnrReportUploader { report: AnrReport ->
    val success: Boolean = runCatching {
        // 这里调用宿主已有网络/埋点 SDK。
        // 建议只上传 AnrReportJsonEncoder 对齐的 schema 字段，不上传原始业务参数。
        sendToBackend(report)
    }.getOrDefault(false)

    if (success) {
        UploadResult.Success
    } else {
        UploadResult.Failure(reason = "backend unavailable")
    }
}
```

返回值语义：

| 返回值 | SDK 行为 |
| --- | --- |
| `UploadResult.Success` | 记录成功，不进入失败重试 |
| `UploadResult.Skip` | 主动跳过，适合离线、采样、限频或不需要上传的环境 |
| `UploadResult.Failure(reason)` | 记录失败，通过运行时重试队列按配置延迟重试，并回调 `onMonitorError()` |

注意：当前重试队列是运行时内存队列；进程退出后仍以本地 JSON 报告作为持久化事实。如果需要进程重启后补偿上传，应由宿主或后续 SDK 版本扫描本地报告目录并重放。

## 6. 本地报告读取

报告目录：

```text
<context.filesDir>/anr-monitor-reports/<eventId>.json
```

在 Demo App 中，包名为 `com.valiantyan.vibeanrmonitoring`，可使用：

```bash
adb shell run-as com.valiantyan.vibeanrmonitoring ls files/anr-monitor-reports
adb shell run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json
adb exec-out run-as com.valiantyan.vibeanrmonitoring cat files/anr-monitor-reports/<eventId>.json > anr-report.json
```

报告顶层字段：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | JSON 协议版本，当前为 `1` |
| `event` | 事件 ID、环境、appId、发生时间 |
| `systemAnr` | ActivityManager 确认 ANR 信息和组件阈值 |
| `mainThread` | 当前消息、历史消息、主线程栈和慢消息栈样本 |
| `pendingQueue` | Pending 队列快照 |
| `barrierEvidence` | Barrier token 和 nativePollOnce 增强证据 |
| `binderBlock` | Binder 阻塞疑似证据 |
| `threadCpu` | 线程 CPU TopN |
| `checktime` | Watchdog 调度延迟 |
| `environmentSnapshot` | 系统负载、内存、存储、进程 I/O、设备信息 |
| `sharedPreferences` | SharedPreferences 文件健康、最近操作、QueuedWork 治理 |
| `attribution` | 主因、辅因、置信度、证据、缺失证据、治理建议 |
| `sdkDiagnostics` | SDK 自监控、采集失败、隐私模式和报告构建成本 |

评审报告时不要只看 `mainThread.stackFrames`。推荐按顺序看：

1. `attribution.primary` 和 `confidence`，先确认 SDK 的主结论和可信度。
2. `systemAnr`，确认是否是系统已确认 ANR，以及对应组件阈值。
3. `mainThread.current`、`history`、`stackSamples`，判断当前慢、历史慢还是消息风暴。
4. `pendingQueue` 和 `barrierEvidence`，复核是否存在同步屏障或队列无法推进。
5. `sharedPreferences`，复核 SP_LOAD_WAIT、SP_APPLY_WAIT、文件健康和 pending finisher。
6. `threadCpu`、`checktime`、`environmentSnapshot`，排除进程内 CPU 或系统资源压力。
7. `sdkDiagnostics.collectorFailures` 和 `missingEvidence`，确认是否因为 ROM、权限或隐私模式导致证据缺失。

## 7. SharedPreferences 接入方式

SharedPreferences 文件健康度会由 SDK 扫描 app 私有目录，但“首次加载耗时、apply/commit 调用入口、pending finisher 数”等运行期证据需要使用包装入口才能记录完整。

打开新实例时推荐：

```kotlin
val prefs = AnrMonitor.openSharedPreferences(fileName = "user_settings") {
    context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
}
```

已有实例需要补充写入证据时：

```kotlin
val rawPrefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
val prefs = AnrMonitor.monitorSharedPreferences(
    fileName = "user_settings",
    sharedPreferences = rawPrefs,
)
```

使用建议：

- `fileName` 建议与实际 XML 文件名一致，不需要添加 `.xml` 后缀。
- 不要在主线程首次访问大 SP 文件；核心路径可提前预热或拆小文件。
- 高频 `apply()` 应合并写入，避免生命周期边界触发 `QueuedWork.waitToFinish()` 时等待过长。
- 报告不会输出 key/value，只输出文件名、大小、key 数、操作类型、耗时、线程名和脱敏调用栈。

## 8. QueuedWork 绕过治理

`enableQueuedWorkBypass` 默认关闭。该能力用于 SharedPreferences `apply()` 等待治理，属于高风险开关，必须配合白名单、黑名单、厂商限制和回滚。

推荐灰度配置：

```kotlin
AnrMonitorConfig(
    appId = "your-app-id",
    environment = "gray",
    enableQueuedWorkBypass = true,
    queuedWorkBypassAllowedFiles = setOf("non_critical_flags"),
    queuedWorkBypassBlockedFiles = setOf("account", "payment", "security"),
    queuedWorkBypassAllowedManufacturers = emptySet(),
    queuedWorkBypassBlockedManufacturers = setOf("unknown-risk-rom"),
    queuedWorkBypassRollbackEnabled = false,
)
```

上线原则：

- 默认不对账号、支付、安全、协议确认等关键数据文件开启绕过。
- 每次只放开少量文件，并观察 `sharedPreferences.queuedWorkBypass`、ANR 率、数据一致性告警。
- 一旦出现一致性风险，打开 `queuedWorkBypassRollbackEnabled=true` 立即回滚。

## 9. Barrier 和 nativePollOnce 证据

`captureBarrierEvidence=false` 是默认值。开启后报告会补充：

- `barrierEvidence.stuckTokens`：超过 `barrierTokenStuckThresholdMs` 仍未移除的 token。
- `barrierEvidence.nativePollOnceRecords`：最近 nativePollOnce 轮询记录。
- `barrierEvidence.alignedWithPendingBarrier`：Barrier 证据是否与 Pending 队列头部同步屏障对齐。

建议只在 Debug、专项灰度或已知 Barrier 风险页面开启。线上开启前需要确认 ROM 兼容性、性能成本和回滚策略。

## 10. Demo App 验证

构建并安装：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

观察日志：

```bash
adb logcat -s VibeAnrApplication AnrMonitor
```

Demo 页面按钮：

| 按钮 | 触发场景 | 预期证据 |
| --- | --- | --- |
| `Current Slow` | 主线程 sleep | `CURRENT_MESSAGE_SLOW`、当前消息 wall time 高 |
| `Message Storm` | 大量主线程消息后阻塞 | 历史消息、Pending 队列、消息风暴证据 |
| `SharedPreferences Apply Burst` | 高频 SP `apply()` 后阻塞 | SP 文件健康、recentOperations、可能命中 SP 等待类证据 |
| `Current Busy` | 主线程忙等 | 当前消息慢、线程 CPU TopN |
| `Binder Like Lock` | 模拟等待类窗口 | 主线程等待栈、Binder suspected 相关复核入口 |

验证步骤：

1. 打开 Demo App。
2. 点击目标按钮并等待 3 到 6 秒。
3. 在 logcat 中查找 `suspect ANR captured` 和 `confirmed ANR report`。
4. 使用 `adb shell run-as ... ls files/anr-monitor-reports` 查看报告文件。
5. `cat` 对应 JSON，按第 6 节顺序复核字段。

## 11. 线上接入清单

- [ ] 只在目标进程安装 SDK，多进程应用完成进程名判断。
- [ ] `appId`、`environment` 与服务端环境维度一致。
- [ ] 先关闭 `uploadEnabled` 完成本地报告验证，再灰度打开上传。
- [ ] 线上 `sampleRate`、`reportUploadMinIntervalMs`、本地保留策略已接远程配置。
- [ ] `privacyMode` 符合业务合规要求，服务端只消费脱敏字段。
- [ ] SharedPreferences 关键路径已用 `openSharedPreferences()` 或 `monitorSharedPreferences()` 包装。
- [ ] `captureBarrierEvidence` 和 `enableQueuedWorkBypass` 均有白名单、灰度和回滚方案。
- [ ] 服务端按 `schemaVersion=1` 消费字段，并展示 `missingEvidence` 和 `sdkDiagnostics`。
- [ ] 灰度期间观察 SDK 自身指标，包括报告构建耗时、collector 失败、上传失败和本地报告数量。
- [ ] 完成典型场景手动验证：当前慢、历史慢、消息风暴、SP 等待、Barrier、Binder、CPU 压力。

## 12. 常见问题

| 问题 | 排查方向 |
| --- | --- |
| 没有报告文件 | 确认 `enabled=true`、SDK 在目标进程安装、阻塞时间超过 `suspectAnrMs`、logcat 是否有 `onMonitorError` |
| 有本地报告但没有上传 | 确认 `uploadEnabled=true`、`sampleRate` 未采样掉、uploader 未返回 `Skip`、是否被 `reportUploadMinIntervalMs` 限频 |
| SP recentOperations 为空 | 确认是否通过 `openSharedPreferences()` 或 `monitorSharedPreferences()` 包装了 SP 实例 |
| Barrier 字段不可用 | `captureBarrierEvidence` 默认关闭；开启后仍需具备 token 或 nativePollOnce 记录 |
| Pending 队列不可用 | 可能是反射受限、ROM 差异或 `capturePendingQueue=false`，查看 `pendingQueue.failureReason` |
| 归因为 UNKNOWN | 查看 `attribution.missingEvidence` 和 `sdkDiagnostics.collectorFailures`，补齐 Pending、历史消息、SP 或 Barrier 证据 |
| 主线程栈看起来不是根因 | 对照 `history`、`stackSamples`、`pendingQueue`；ANR 的当前 Trace 可能只是超时窗口内的一个结果 |
| 本地报告太多 | 调整 `reportRetentionMaxFileCount`、`reportRetentionMaxTotalBytes`、`reportRetentionMaxAgeMs` |

## 13. 与设计文档的关系

本文是接入使用说明，不替代设计评审。需要判断方案是否覆盖五篇 ANR 资料时，请同时查看：

- [01-第一篇-设计原理及影响因素.md](./01-第一篇-设计原理及影响因素.md)
- [02-第二篇-监控工具与分析思路.md](./02-第二篇-监控工具与分析思路.md)
- [03-第三篇-实例剖析集锦.md](./03-第三篇-实例剖析集锦.md)
- [04-第四篇-Barrier导致主线程假死.md](./04-第四篇-Barrier导致主线程假死.md)
- [05-第五篇-告别SharedPreference等待.md](./05-第五篇-告别SharedPreference等待.md)
- [99-ANR监控SDK设计开发文档.md](./99-ANR监控SDK设计开发文档.md)
- [102-ANR监控SDK服务端消费协议.md](./102-ANR监控SDK服务端消费协议.md)
