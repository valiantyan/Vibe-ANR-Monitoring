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
| `BINDER_BLOCK_SUSPECTED` | 主线程疑似阻塞在 Binder 或跨进程调用 |
| `UNKNOWN_INSUFFICIENT_EVIDENCE` | 证据不足，无法给出可信主因 |

当前明确不属于 SDK 使用范围的能力：

| 非能力项 | 使用口径 |
| --- | --- |
| SharedPreferences 专项包装 | SDK 不提供 `openSharedPreferences()`、`monitorSharedPreferences()` 或替换系统 SP 的入口 |
| SP 专项归因码 | SDK 不输出 `SP_LOAD_WAIT`、`SP_APPLY_WAIT`，避免把第五篇实战案例固化成端侧基础归因 |
| QueuedWork 绕过 | SDK 不提供 QueuedWork 反射绕过、SP apply/commit 治理或 SP 文件扫描 |
| SP Demo 场景 | Demo 不再提供 `SharedPreferences Apply Burst`；SP 风险应在采集到通用 ANR 报告后，按第五篇作为专项复盘材料处理 |

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
| 灰度 | `uploadEnabled=true`、`sampleRate=0.05f` 到 `0.1f`、保留 `SAFE` 隐私模式 |
| 线上 | `uploadEnabled=true`、低采样率、`privacyMode=STRICT` 或按合规要求配置；Barrier 增强证据按白名单灰度 |

当前 Demo App 为了验证 Sync Barrier 场景，Debug 配置已开启 `captureBarrierEvidence=true`，并把 `barrierTokenStuckThresholdMs` 调低到 `2000L`，便于第一份报告中看到 `stuckTokens`。

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

### 6.1 JSON 报告目录

ANR 报告可以按“一本书”来读。不要把它看成一大坨字段，而是先看目录，知道每一章负责回答什么问题：

```text
anr-report.json
├── schemaVersion          报告协议版本
├── event                  封面：这份报告是谁、何时、在哪个环境生成的
├── attribution            摘要：SDK 对本次问题的主因判断、置信度和建议
├── systemAnr              系统结论：Android 系统是否也确认了 ANR
├── mainThread             主线程现场：当前消息、历史消息、主线程栈、慢消息采样
│   ├── current            当前正在执行但还没结束的主线程消息
│   ├── history            最近已经执行完的主线程消息
│   ├── stackFrames        疑似 ANR 时刻的主线程栈
│   └── stackSamples       慢消息期间多次采样到的栈
├── pendingQueue           排队现场：主线程后面还堵着哪些消息
│   └── messages           Pending 消息列表，按队列顺序排列
├── barrierEvidence        Barrier 专项：同步屏障和 nativePollOnce 证据
├── binderBlock            Binder 专项：跨进程等待或 Binder 阻塞疑似证据
├── threadCpu              CPU 专项：进程内线程 CPU 排名
├── checktime              Watchdog 自检：监控线程是否也被系统调度拖慢
├── environmentSnapshot    外部环境：内存、存储、I/O、设备、ROM 信息
└── sdkDiagnostics         SDK 自检：采集失败、构建耗时、隐私模式、自身指标
```

每一章的阅读价值如下：

| 报告章节 | 它回答的问题 | 新人阅读方式 |
| --- | --- | --- |
| `event` | 这份报告的 ID、环境、发生时间是什么 | 用来和日志、用户反馈、服务端记录对齐 |
| `attribution` | SDK 认为主因是什么，可信度如何 | 第一眼先看这里，但不要只看这里下结论 |
| `systemAnr` | 系统是否真正弹过/记录过 ANR | `true` 按系统 ANR 处理，`false` 按疑似 ANR 或严重卡顿治理 |
| `mainThread` | 主线程当时正在执行什么、卡在哪里 | 最关键章节，定位业务代码入口主要靠它 |
| `pendingQueue` | 主线程后面排了多少消息，是否大量重复或被 Barrier 挡住 | 用来判断消息风暴、队列堆积、Barrier 风险 |
| `barrierEvidence` | 是否存在同步屏障残留证据 | 只有证据可用且与 Pending 队列对齐时才适合判断 Barrier |
| `binderBlock` | 是否疑似 Binder 或跨进程等待 | 为跨进程问题提供方向，不单独等同于根因 |
| `threadCpu` | 哪些线程 CPU 占用高 | 区分等待类卡顿和 CPU 忙等/竞争 |
| `checktime` | Watchdog 自己是否被系统拖慢 | 如果这里异常，说明报告结论需要更谨慎 |
| `environmentSnapshot` | 当时设备资源是否异常 | 用来排除低内存、存储不足、I/O 异常等外部因素 |
| `sdkDiagnostics` | SDK 哪些证据没采到、报告构建是否正常 | 用来解释为什么某些字段为空或不可用 |

字段词典如下。这里解释的是“人工定位时需要理解的含义”，不是服务端协议的完整约束；服务端字段消费口径以 [102-ANR监控SDK服务端消费协议.md](./102-ANR监控SDK服务端消费协议.md) 为准。

通用约定：

| 字段形态 | 含义 |
| --- | --- |
| `available=false` | 这个采集器本次没有拿到有效证据，通常要结合 `failureReason` 看原因 |
| `failureReason` | 采集失败或能力关闭原因；不为空时，不能用该节点反向证明问题不存在 |
| `null` | 字段本次没有值，可能是未采集、未命中、系统未提供或当前阶段不适用 |
| `*UptimeMs` | 设备启动后的相对时间，不是北京时间；适合比较同一份报告内的先后关系 |
| `wallMs` | 墙钟耗时，也就是人感知到的等待时间 |
| `cpuMs` | CPU 执行耗时；和 `wallMs` 对比可区分“等待阻塞”和“CPU 忙等” |

`schemaVersion`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `schemaVersion` | JSON 报告协议版本 | 服务端或人工工具按版本解析；当前为 `1` |

`event`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `eventId` | 本次报告唯一 ID | 用来和日志、上传记录、用户反馈对齐 |
| `eventType` | 事件阶段，常见为 `SUSPECT_ANR` 或 `CONFIRMED_ANR` | 区分 SDK 疑似现场和系统确认 ANR |
| `appId` | 接入方配置的应用标识 | 多业务、多 app 聚合时用于筛选 |
| `environment` | 接入方配置的环境，例如 debug、gray、prod | 判断是否来自测试、灰度或线上 |
| `timeUptimeMs` | 报告生成时的设备 uptime | 与主线程消息时间、Pending 时间做相对比较 |

`systemAnr`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `available` | 系统 ANR 信息是否可用 | 不可用时只能按 SDK 疑似现场分析 |
| `isConfirmedAnr` | Android 系统是否确认 ANR | `true` 优先按系统 ANR 处理；`false` 仍可能是严重卡顿 |
| `anrType` | 系统组件类型，例如 `INPUT`、`SERVICE`、`BROADCAST_FOREGROUND`、`UNKNOWN` | 辅助判断对应系统阈值和业务入口 |
| `componentTimeoutMs` | 组件对应超时阈值 | 用来解释为什么系统在该时间点确认 ANR |
| `shortMsg` / `longMsg` | ActivityManager 的 ANR 文案 | 与系统日志、Bugreport 对齐 |
| `condition` | 系统错误状态码 | 高级排查字段，一般结合系统日志看 |
| `failureReason` | 系统信息采集失败原因 | 说明为什么系统 ANR 字段不可用 |

`attribution`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `primary` | SDK 归因主因 | 第一眼先看，但必须结合主线程栈和辅助证据复核 |
| `secondary` | SDK 归因辅因列表 | 说明除主因外还命中了哪些风险 |
| `confidence` | 归因置信度：`HIGH`、`MEDIUM`、`LOW`、`UNKNOWN` | 置信度低时，结论要写“疑似”，不能写死 |
| `evidence` | 支持归因的证据摘要 | 可直接放进问题分析结论 |
| `missingEvidence` | 缺失的关键证据 | 说明为什么结论不够强，指导下一步补采 |
| `suggestions` | SDK 给出的治理建议 | 作为修复方向参考，不替代业务代码分析 |

`mainThread`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `stackId` | 当前主线程栈 ID | 和 `sampleStackIds`、`stackSamples` 做关联 |
| `threadName` | 主线程线程名 | 正常一般是 `main`，可用于确认采样对象 |
| `current` | 当前正在执行、尚未结束的主线程消息 | 当前消息慢的核心证据 |
| `history` | 最近已经执行完成的主线程消息列表 | 用于判断历史慢消息，避免只看当前 Trace |
| `stackFrames` | 疑似 ANR 时刻主线程 Java 栈 | 找第一个业务栈帧，定位代码入口 |
| `stackSamples` | 慢消息期间采样到的栈集合 | 多次命中同一业务栈时，可信度更高 |

`mainThread.current` 和 `mainThread.history[]` 中的消息属性：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `seq` | 主线程消息序号 | 对齐当前消息和历史消息顺序 |
| `kind` | 消息类型，通常是 `CURRENT` 或 `HISTORY` | 区分当前未结束消息和已完成消息 |
| `messageType` | 消息来源类型，例如 Looper dispatch | 了解消息来自哪条采集链路 |
| `what` | Android `Message.what` | Handler 消息分类线索 |
| `targetClass` | 处理该消息的 Handler 类 | 判断消息投递给系统还是业务 Handler |
| `callbackClass` | Runnable、点击事件或回调类名 | 判断是否是点击、业务 Runnable、动画或帧消息 |
| `isCriticalComponent` | 是否命中关键组件标记 | 系统/关键链路消息可优先关注 |
| `startUptimeMs` / `endUptimeMs` | 消息开始和结束 uptime | 计算消息执行窗口；当前消息未结束时 `endUptimeMs=null` |
| `wallMs` | 消息墙钟耗时 | 判断是否慢消息或疑似 ANR |
| `cpuMs` | 消息 CPU 耗时 | 低 CPU 偏等待阻塞，高 CPU 偏忙等/重计算 |
| `count` | 聚合计数 | 用于重复消息或聚合记录解释 |
| `sampleStackIds` | 关联到 `stackSamples` 的栈 ID | 回查慢消息期间采样到的具体栈 |

`mainThread.stackSamples[]`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `stackId` | 栈样本 ID | 与消息里的 `sampleStackIds` 对齐 |
| `frames` | 该次采样的栈帧 | 多次出现的业务栈通常更值得优先排查 |
| `hitCount` | 该栈样本命中次数 | 命中次数越多，说明主线程越稳定地卡在该位置 |

`pendingQueue`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `available` | Pending 队列是否采集成功 | `false` 时不能判断消息风暴或 Barrier |
| `truncated` | 队列是否被截断 | `true` 表示只采了前 `maxDepth` 条，数量判断要保守 |
| `maxDepth` | 本次最多采集多少条 Pending 消息 | 解释为什么报告里只看到固定数量消息 |
| `failureReason` | Pending 队列采集失败原因 | 常见原因是反射受限、ROM 差异或配置关闭 |
| `messages` | 待执行消息列表，按队列顺序排列 | 判断队列头部阻塞、重复投递、同步屏障 |

`pendingQueue.messages[]`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `index` | 消息在 Pending 队列中的位置 | 越靠前越先被主线程处理 |
| `whenUptimeMs` | 消息计划执行时间 | 与当前 uptime 比较可判断已经延迟多久 |
| `delayMs` | 距计划执行时间的差值 | 负数通常表示已经过期但仍未执行 |
| `blockedMs` | 消息已被阻塞的时长 | 越高说明队列越久没有推进 |
| `what` / `arg1` / `arg2` | Android Message 基础参数 | 辅助识别 Handler 消息类型 |
| `targetClass` | 目标 Handler 类名 | 大量相同 target 可能是重复投递 |
| `callbackClass` | Runnable 或回调类名 | 大量相同 callback 是消息风暴的重要线索 |
| `objClass` | `Message.obj` 的类名，只输出类名不输出内容 | 保护隐私，同时保留类型线索 |
| `isAsynchronous` | 是否异步消息 | Barrier 场景下异步消息可能绕过同步屏障 |
| `isBarrierLike` | 是否像同步屏障消息 | 出现在队列头附近时，要结合 `barrierEvidence` |
| `isCriticalComponent` | 是否关键组件消息 | 关键组件被堵时优先级更高 |

`barrierEvidence`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `available` | Barrier 增强证据是否可用 | 默认可能关闭；不可用时不能直接判断 Barrier |
| `failureReason` | Barrier 证据不可用原因 | 区分配置关闭、反射失败或无证据 |
| `repeatedInfinitePollCount` | 连续无限等待 nativePollOnce 的次数 | 多次出现时说明 Looper 可能长时间无同步消息推进 |
| `alignedWithPendingBarrier` | Barrier 证据是否与 Pending 队列头部屏障对齐 | 判断 Barrier 卡住时最关键的复核字段 |
| `stuckTokens` | 疑似长时间未移除的 Barrier token | 找 token 生命周期和插入栈 |
| `nativePollOnceRecords` | 最近 nativePollOnce 轮询记录 | 判断 Looper 是否在无限等待或仍在等待中 |

`barrierEvidence.stuckTokens[]`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `token` | Barrier token | 标识具体同步屏障 |
| `postUptimeMs` / `removeUptimeMs` | token 插入和移除时间 | `removeUptimeMs=null` 可能表示仍未移除 |
| `aliveMs` | token 存活时长 | 超过阈值时支持 Barrier 残留判断 |
| `postStack` | 插入 Barrier 时的调用栈 | 找是谁插入了屏障 |

`barrierEvidence.nativePollOnceRecords[]`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `timeoutMillis` | nativePollOnce 等待超时参数 | 小于 0 代表无限等待 |
| `enterUptimeMs` / `exitUptimeMs` | 进入和退出 nativePollOnce 的时间 | `exitUptimeMs=null` 表示采样时仍在等待 |
| `durationMs` | 本次等待持续时间 | 持续时间长时支持 Looper 假死判断 |
| `isInfiniteWait` | 是否无限等待 | Barrier 假死场景的重要线索 |
| `isInFlight` | 是否仍在等待中 | 判断当前现场是否还卡在 nativePollOnce |
| `source` | nativePollOnce 证据来源 | `HOOK` 表示探针或 hook 直接记录；`STACK_INFERENCE` 表示由主线程栈和 Pending 队头推断 |

`binderBlock`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `available` | Binder 证据是否可用 | 不可用时不能判断跨进程等待 |
| `suspected` | 是否疑似 Binder 阻塞 | 只是疑似，需要结合栈和对端证据确认 |
| `mainThreadInBinder` | 主线程栈是否命中 Binder 调用 | 主线程可能在等待跨进程返回 |
| `binderThreadWaitsMain` | Binder 线程是否出现等待主线程迹象 | 支持进程内互等或跨线程等待判断 |
| `mainThreadEvidence` | 主线程相关证据摘要 | 可写入问题结论 |
| `binderThreadEvidence` | Binder 线程相关证据摘要 | 辅助排查服务端或对端进程 |
| `failureReason` | Binder 证据采集失败原因 | 说明为什么本次没有 Binder 结论 |

`threadCpu`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `topThreads` | 进程内 CPU 排名前几的线程 | 判断是否存在主线程忙等、业务线程抢 CPU、RenderThread 压力 |

`threadCpu.topThreads[]`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `tid` | Linux 线程 ID | 和系统 trace、logcat 线程号对齐 |
| `threadName` | 线程名 | 判断是 main、RenderThread、Binder 还是业务线程 |
| `totalCpuMs` | 线程累计 CPU 时间 | 越高说明该线程在窗口内越忙 |

`checktime`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `available` | Watchdog 调度延迟数据是否可用 | 不可用时少一类系统压力解释 |
| `maxDelayMs` | 最近窗口最大调度延迟 | 很高时说明监控线程本身也可能被系统压力影响 |
| `severeDelayCount` | 严重调度延迟次数 | 多次严重延迟时，业务归因要更谨慎 |
| `recentDelayMs` | 最近调度延迟序列 | 看系统压力是否持续存在 |
| `failureReason` | Checktime 采集失败原因 | 解释该节点不可用 |

`environmentSnapshot`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `loadAverage1m` | 1 分钟系统负载 | 读不到时通常为 `null`，不要当成 0 |
| `memory.availableBytes` / `totalBytes` | 可用内存和总内存 | 排查低内存压力 |
| `memory.isLowMemory` | 系统是否低内存 | `true` 时要谨慎判断业务根因 |
| `availableStorageBytes` | 可用存储空间 | 排查存储不足导致的 I/O 卡顿 |
| `processIo.readBytes` / `writeBytes` | 进程读写字节数 | 辅助判断是否有 I/O 压力 |
| `processIo.cancelledWriteBytes` | 被取消的写入字节数 | 辅助观察异常 I/O 行为 |
| `androidVersion` / `manufacturer` / `model` | 系统版本、厂商、机型 | 对齐 ROM 兼容性和设备维度 |
| `availability` | CPU、内存、存储、进程 I/O 各类证据是否可用 | 区分“没问题”和“没采到” |
| `failureReasons` | 环境证据采集失败原因列表 | 解释为什么负载、内存或 I/O 字段为空 |

`sdkDiagnostics`：

| 属性 | 含义 | 分析价值 |
| --- | --- | --- |
| `pendingAvailable` | SDK 自检认为 Pending 队列是否可用 | 与 `pendingQueue.available` 交叉验证 |
| `reportBuildCostMs` | 构建报告耗时 | 评估 SDK 自身成本 |
| `collectorFailures` | 各采集器失败原因列表 | 判断报告证据是否完整 |
| `privacyMode` | 当前隐私模式 | 解释栈、类名、字段是否被脱敏 |
| `missingEvidenceCount` | 缺失证据数量 | 数量越多，归因越需要保守 |
| `selfMetrics` | SDK 自身计数指标 | 观察本地写入、上传、构建等运行状态 |

读报告的推荐顺序：

```text
先读摘要 attribution
再读系统结论 systemAnr
重点读主线程现场 mainThread
然后看排队现场 pendingQueue
最后用 Barrier / Binder / CPU / 环境 / SDK 自检做复核
```

新人第一次拿到 JSON 时，把它当成一份“ANR 现场记录”，不要从第一行逐字段读到最后一行。建议用 Android Studio、VS Code 或浏览器 JSON Viewer 打开文件，先折叠顶层对象，只展开下面几个区域：

```text
attribution
systemAnr
mainThread
pendingQueue
barrierEvidence / binderBlock / threadCpu / checktime / environmentSnapshot
sdkDiagnostics
```

人工分析的目标不是复述 JSON，而是写出一段能交给业务负责人、测试同学和评审同学都看懂的结论：

```text
本次卡顿/ANR 属于什么类型，主线程当时卡在哪里，业务代码位置是什么，哪些可能性已被排除，下一步应该谁来修。
```

### 6.2 第一步：先判断“这是哪一类问题”

先展开 `attribution` 和 `systemAnr`。

| 看哪里 | 人工判断 |
| --- | --- |
| `attribution.primary` | SDK 对这次问题的主分类，例如当前消息慢、消息风暴、Barrier、Binder |
| `attribution.confidence` | 结论可信度；`HIGH`/`MEDIUM` 可以直接进入业务定位，`LOW` 要写“疑似”并继续补证据 |
| `attribution.evidence` | SDK 为什么这么判断，这里是证据摘要，不是最终结论 |
| `attribution.missingEvidence` | 缺了哪些证据；非空时不要把结论写死 |
| `systemAnr.isConfirmedAnr` | 系统是否也确认了 ANR；`false` 表示 SDK 疑似 ANR 或严重卡顿，不代表没问题 |
| `systemAnr.anrType` | 如果系统已确认，这里能辅助判断是输入、广播、服务还是前台服务超时 |

常见主因这样理解：

| `primary` | 给人的解释 |
| --- | --- |
| `CURRENT_MESSAGE_SLOW` | 主线程正在执行的这条消息太慢，优先看当前消息和当前栈 |
| `HISTORY_MESSAGE_SLOW` | 根因可能发生在之前某条慢消息，当前栈可能只是后续现场 |
| `MESSAGE_STORM` | 主线程消息太多或重复投递，队列消费不过来 |
| `SYNC_BARRIER_STUCK` | 同步屏障残留，普通同步消息被挡住 |
| `BINDER_BLOCK_SUSPECTED` | 主线程疑似卡在跨进程/Binder 等待 |
| `UNKNOWN_INSUFFICIENT_EVIDENCE` | 证据不足，需要先看缺失证据和采集失败原因 |

### 6.3 第二步：找“主线程当时卡在哪里”

再展开 `mainThread.current` 和 `mainThread.stackFrames`。

| 看哪里 | 人工判断 |
| --- | --- |
| `mainThread.current.wallMs` | 当前消息已经执行多久；越大越说明主线程被这条消息占住 |
| `mainThread.current.cpuMs` | 当前消息消耗的 CPU；低 CPU 多半是等待，高 CPU 多半是计算或死循环 |
| `mainThread.current.targetClass` | 这条消息投递给谁处理，例如 Handler、ViewRoot、Choreographer |
| `mainThread.current.callbackClass` | 这条消息具体执行哪个回调，例如点击事件、业务 Runnable |
| `mainThread.stackFrames` | 当前主线程栈，从上往下找第一个业务包名栈帧 |

读栈时按这三个动作做：

1. 先看栈顶动作：`sleep`、`wait`、`lock`、`read`、`binder`、`nativePollOnce`、业务计算。
2. 再找第一个业务包名，例如 `com.xxx.yourapp`。
3. 最后记录类名、方法名、文件行号，这个位置就是优先排查入口。

主线程现场的常见解释：

| 现象 | 说明 |
| --- | --- |
| `wallMs` 高、`cpuMs` 低，栈顶是 `Thread.sleep` / `Object.wait` / 锁等待 | 主线程在等待，不是 CPU 算不过来 |
| `wallMs` 高、`cpuMs` 高，栈里有业务循环或计算 | 主线程忙等或重计算 |
| `callbackClass` 是 `View$PerformClick` | 用户点击后触发的主线程回调卡住 |
| `callbackClass` 是业务 Runnable | 业务主动投递到主线程的任务卡住 |
| 栈里没有业务包名 | 继续看历史消息、Pending 队列、Binder、Barrier，当前栈可能不是根因 |

### 6.4 第三步：确认根因是不是“当前这条消息”

只看当前栈容易误判。接着展开 `mainThread.history`、`mainThread.stackSamples` 和 `pendingQueue.messages`。

| 如果看到 | 应该怎么判断 |
| --- | --- |
| 当前消息 `wallMs` 高，当前栈能落到业务代码 | 根因大概率就是当前消息慢 |
| 历史消息里某条 `wallMs` 很高，但当前栈不明显 | 根因可能是历史消息慢，当前栈只是被采样到的后续现场 |
| `stackSamples` 多次命中同一个业务栈 | 该业务栈可信度更高，优先排查 |
| Pending 头部多条消息 `blockedMs` 很高 | 主线程已经卡住，后续消息排队等它释放 |
| Pending 里大量相同 `callbackClass` 或 `targetClass` | 可能是消息风暴或重复投递 |
| Pending 头部附近出现 `isBarrierLike=true` | 继续看 `barrierEvidence`，可能是同步屏障问题 |

这一轮要回答的问题是：

```text
这次问题是“当前正在跑的代码慢”，还是“前面有慢消息/队列堆积导致现在看起来卡”？
```

### 6.5 第四步：排除专项问题和环境干扰

最后展开 `barrierEvidence`、`binderBlock`、`threadCpu`、`checktime`、`environmentSnapshot`、`sdkDiagnostics`。

| 看哪里 | 人工判断 |
| --- | --- |
| `binderBlock.suspected=true` | 疑似 Binder 或跨进程等待，需要结合对端进程、服务端日志继续排查 |
| `barrierEvidence.alignedWithPendingBarrier=true` | Barrier 证据和 Pending 队列对齐，才更适合判断同步屏障卡住 |
| `threadCpu.topThreads` | 看是不是主线程或某个业务线程 CPU 占用特别高 |
| `checktime.maxDelayMs` / `severeDelayCount` | Watchdog 自己是否也被系统调度拖慢；严重时结论要谨慎 |
| `environmentSnapshot.memory` / `storage` / `processIo` | 排查低内存、存储不足、I/O 异常等环境因素 |
| `sdkDiagnostics.collectorFailures` | 看哪些证据没有采到；采集失败不等于问题不存在 |

注意：`available=false` 或 `failureReason` 非空，只能说明这类证据没有采集到，不能反向证明没有这类问题。

### 6.6 第五步：写成人能评审的定位结论

建议每次报告都按下面模板写结论，避免只贴 JSON：

```text
【问题类型】<attribution.primary>，置信度 <confidence>。
【是否系统确认】systemAnr.isConfirmedAnr=<true/false>，eventType=<eventType>。
【主线程现场】当前消息 <callbackClass/targetClass> 执行 <wallMs>ms，CPU <cpuMs>ms。
【业务位置】第一个业务栈为 <类名.方法名(文件:行号)>。
【辅助证据】Pending/Barrier/Binder/CPU/系统环境中支持该结论的证据。
【已排除】例如 Binder suspected=false、Barrier 证据不可用或未命中、没有大量重复消息。
【处理建议】把主线程耗时逻辑迁移到后台，或治理重复消息/Barrier/Binder/CPU 竞争。
```

以 Demo 的 `Current Slow` 报告为例，人类可读结论应写成：

```text
这次报告是 SDK 捕获到的疑似 ANR，系统尚未确认 ANR。
SDK 主因判断为 CURRENT_MESSAGE_SLOW，说明主线程当前正在执行的消息耗时过长。
现场显示主线程正在处理一次点击事件 View.performClick，当前消息已执行约 3082ms，但 CPU 几乎没有消耗。
主线程栈顶是 Thread.sleep，第一个业务栈是 MainActivity.blockMainThread(MainActivity.kt:38)。
因此这次问题不是 CPU 忙等，也不是消息风暴，而是点击回调里发生了主线程等待/阻塞。
处理方向是移除主线程 sleep 或同步等待，把耗时逻辑迁移到后台线程，完成后再回到主线程更新 UI。
```

如果只想快速人工扫一遍 JSON，可以在编辑器里依次搜索这些字段：

```text
primary
confidence
isConfirmedAnr
current
stackFrames
history
pendingQueue
binderBlock
barrierEvidence
threadCpu
collectorFailures
```

会命令行的同学可以用 `jq` 提取字段，但 `jq` 只是辅助查看工具，最终评审仍以上面的人工结论为准。

## 7. Barrier 和 nativePollOnce 证据

`captureBarrierEvidence=false` 是默认值。开启后报告会补充：

- `barrierEvidence.stuckTokens`：超过 `barrierTokenStuckThresholdMs` 仍未移除的 token。
- `barrierEvidence.nativePollOnceRecords`：最近 nativePollOnce 轮询记录，包含 `source` 用于区分真实探针和栈推断。
- `barrierEvidence.alignedWithPendingBarrier`：Barrier 证据是否与 Pending 队列头部同步屏障对齐。

当前 P2 默认不会主动安装 native hook，避免 SDK 改变宿主运行风险；但当主线程栈命中 `MessageQueue.nativePollOnce` 且 Pending 队头是 Sync Barrier 时，SDK 会输出一条 `source=STACK_INFERENCE` 的 in-flight `nativePollOnce(timeoutMillis=-1)` 证据。若宿主后续接入自有 hook、JVMTI 或灰度探针，可调用 `AnrNativePollProbe.recordEnter()` / `recordExit()` / `recordCompleted()` 写入 `source=HOOK` 的真实轮询窗口。

建议只在 Debug、专项灰度或已知 Barrier 风险页面开启。线上开启前需要确认 ROM 兼容性、性能成本和回滚策略。

## 8. Demo App 验证

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
| `当前消息慢` | 主线程 sleep | `CURRENT_MESSAGE_SLOW`、当前消息 wall time 高 |
| `消息风暴` | 大量主线程消息后阻塞 | 历史消息、Pending 队列、消息风暴证据 |
| `当前消息忙等` | 主线程忙等 | 当前消息慢、线程 CPU TopN |
| `Binder 模拟等待` | 模拟等待类窗口 | 主线程等待栈、Binder suspected 相关复核入口 |
| `Sync Barrier 泄漏 ANR` | 反射插入 Sync Barrier 并故意不移除 | `SYNC_BARRIER_STUCK`、队头 `isBarrierLike=true`、`stuckTokens` token 对齐 |

验证步骤：

1. 打开 Demo App。
2. 点击目标按钮并等待 3 到 6 秒。
3. 在 logcat 中查找 `suspect ANR captured` 和 `ANR report written`。
4. 使用 `adb shell run-as ... ls files/anr-monitor-reports` 查看报告文件。
5. `cat` 对应 JSON，按第 6 节五步排查法输出定位结论。

### 当前消息慢场景

这个场景用于验证最基础的输入事件无响应：用户点击按钮后，当前主线程消息被业务代码阻塞超过疑似 ANR 阈值。

操作步骤：

1. 安装并打开 debug Demo App。
2. 点击“当前消息慢”。
3. 等待 logcat 出现 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。
5. 先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。
6. 再看 `mainThread.current.wallMs`，预期大于 `3000`。
7. 最后看 `mainThread.stackFrames`，预期包含 `CurrentSlowInputScenario.run`。

新人分析结论可以这样写：

```text
本次报告是 Demo 当前慢消息场景触发的疑似 ANR。当前主线程消息执行时间超过 3000ms，主线程栈包含 CurrentSlowInputScenario.run，说明按钮点击消息被业务代码主动阻塞。Barrier 和 Binder 证据不构成本次主因，因此根因是主线程当前消息执行耗时过长。
```

### 当前消息忙等场景

这个场景用于验证“主线程不是在等待，而是在持续消耗 CPU”的当前消息慢问题。用户点击按钮后，按钮点击消息会在主线程执行 6 秒 busy loop。

操作步骤：

1. 安装并打开 debug Demo App。
2. 点击“当前消息忙等”。
3. 等待 logcat 出现 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。
5. 先看 `attribution.primary`，预期为 `CURRENT_MESSAGE_SLOW`。
6. 再看 `mainThread.current.wallMs`，预期大于 `3000`。
7. 再看 `mainThread.current.cpuMs`，它应明显高于 `当前消息慢` 的 sleep 场景。
8. 再看 `threadCpu.topThreads`，预期主线程在 CPU 排名中更靠前。
9. 最后看 `mainThread.stackFrames`，预期包含 `MainThreadCpuBusyScenario.run` 或 `DefaultCpuBusyAction.burn`。

新人分析结论可以这样写：

```text
本次报告是 Demo 当前消息忙等场景触发的疑似 ANR。当前主线程消息执行时间超过 3000ms，同时当前消息 CPU 耗时和线程 CPU 证据都偏高，主线程栈包含 MainThreadCpuBusyScenario.run 或 DefaultCpuBusyAction.burn。Barrier 和 Binder 证据不构成本次主因，因此根因是点击回调在主线程持续计算，导致输入事件无法及时处理。
```

### 消息风暴场景

用于验证“主线程 Pending 队列中堆积大量同类消息”的定位能力。这个场景不是看单条业务代码执行慢，而是看队列里是否出现大量重复 `MessageStormHandler` 或 `StormRunnable`。

操作步骤：

1. 安装并打开 Demo App。
2. 点击“消息风暴”。
3. 等待日志输出 `suspect ANR captured` 和 `ANR report written`。
4. 拉取最新 JSON 报告。

重点看这些字段：

| 字段 | 预期 | 怎么理解 |
| --- | --- | --- |
| `attribution.primary` | `MESSAGE_STORM` | SDK 判断本次主因是消息风暴 |
| `attribution.evidence` | 包含 `pending repeated target count=...` | Pending 队列里同类消息数量已经超过阈值 |
| `pendingQueue.messages` | 多条消息的 `targetClass` 包含 `MessageStormHandler`，或 `callbackClass` 包含 `StormRunnable` | 证明主线程不是卡在一个孤立任务，而是被重复消息压住 |
| `mainThread.current.wallMs` | 可能超过 3000ms | Demo 会短时间占住点击消息，目的是给 Pending 快照留下采集窗口 |
| `barrierEvidence.stuckTokens` | 空数组或不是主因 | 说明本次不是 Sync Barrier 泄漏 |
| `binderBlock.suspected` | `false` | 说明本次不是 Binder 或跨进程等待 |

定位结论写法：

```text
本次 ANR 主因是消息风暴。证据是 attribution.primary=MESSAGE_STORM，pendingQueue.messages 中存在大量 MessageStormHandler/StormRunnable，attribution.evidence 给出 pending repeated target count。当前消息耗时只是 Demo 为了保留采集窗口制造的阻塞，不是本次根因。
```

修复方向：

- 合并重复 `Handler.post` / `sendMessage`，同一类刷新任务只保留最后一次。
- 在投递前使用“是否已在队列中”的状态位做去重。
- 对高频事件增加防抖或节流，避免每次输入、滚动、数据变化都投递主线程任务。
- 页面销毁、数据源切换或请求取消时，及时 `removeCallbacks` / `removeMessages` 清理过期任务。

### Sync Barrier 泄漏 / nativePollOnce 场景

这个场景用于验证最容易误判的一类 ANR：系统 Trace 看到主线程在 `android.os.MessageQueue.nativePollOnce`，但真实原因不是“主线程空闲”，而是 Sync Barrier 留在队头，普通同步消息被挡住无法执行。

操作步骤：

1. 安装 debug 包并打开 Demo App。
2. 点击“Sync Barrier 泄漏 ANR”。
3. 点击后继续点屏幕 5 到 10 秒，让系统输入事件也进入等待窗口。
4. 观察 Logcat 中 `VibeAnrApplication` 是否输出 `confirmed ANR report: <eventId>`。
5. 从设备拉取 `files/anr-monitor-reports/<eventId>.json`。

优先检查这些字段：

```text
attribution.primary = SYNC_BARRIER_STUCK
pendingQueue.available = true
pendingQueue.messages[0].isBarrierLike = true
pendingQueue.messages[0].targetClass = null
pendingQueue.messages[0].arg1 = <barrier token>
barrierEvidence.available = true
barrierEvidence.stuckTokens[].token = pendingQueue.messages[0].arg1
barrierEvidence.stuckTokens[].postStack 包含 SyncBarrierLeakScenario.run
barrierEvidence.alignedWithPendingBarrier = true
barrierEvidence.nativePollOnceRecords[].source = STACK_INFERENCE 或 HOOK
```

再做排除检查：

| 字段 | 期望 | 说明 |
| --- | --- | --- |
| `attribution.primary` | `SYNC_BARRIER_STUCK` | SDK 已把队头 Barrier 识别为主因 |
| `pendingQueue.messages[0].isBarrierLike` | `true` | 队头消息像 Sync Barrier，普通同步消息会被挡住 |
| `pendingQueue.messages[0].targetClass` | `null` | Sync Barrier 的结构特征是没有 target |
| `barrierEvidence.alignedWithPendingBarrier` | `true` | token 证据能和 Pending 队头 Barrier 对上 |
| `barrierEvidence.stuckTokens[].postStack` | 包含 `SyncBarrierLeakScenario.run` | 能定位是谁插入了未移除的 Barrier |
| `binderBlock.suspected` | `false` | 排除 Binder / 跨进程等待主因 |
| `attribution.primary` | 不是 `MESSAGE_STORM` | 排除大量同类消息堆积主因 |

定位结论写法：

```text
本次 ANR 主因是 Sync Barrier 泄漏。证据是 attribution.primary=SYNC_BARRIER_STUCK，Pending 队头消息 isBarrierLike=true 且 targetClass=null，barrierEvidence.stuckTokens 中的 token 与 pendingQueue.messages[0].arg1 对齐，postStack 指向 SyncBarrierLeakScenario.run。主线程停在 nativePollOnce 是结果，不是根因；根因是未移除的 Sync Barrier 挡住了后续同步消息。
```

修复方向：

- 检查所有 `postSyncBarrier` 和 `removeSyncBarrier` 是否严格成对执行。
- 不要在异常分支、页面销毁、动画取消、任务取消时漏掉 `removeSyncBarrier`。
- 如果业务封装了 UI 调度、绘制、刷新合批能力，必须把 token 生命周期放进同一个 owner 中管理。
- 为 Barrier token 增加超时告警，超过安全阈值时输出插入栈和页面信息。
- 修复后重新验证同一场景，预期 `barrierEvidence.stuckTokens` 不再出现长期存活 token，Pending 队头不再是 `isBarrierLike=true`。

同一次主线程卡死在恢复前只会写出第一份报告。若看到多份报告，先比较 `pendingQueue.messages[0].arg1`、`barrierEvidence.stuckTokens[].token` 和 `event.timeUptimeMs`：token 相同且时间递增，通常是同一轮卡死的历史报告；token 不同或心跳恢复后再次卡死，才按新事件处理。

## 9. 线上接入清单

- [ ] 只在目标进程安装 SDK，多进程应用完成进程名判断。
- [ ] `appId`、`environment` 与服务端环境维度一致。
- [ ] 先关闭 `uploadEnabled` 完成本地报告验证，再灰度打开上传。
- [ ] 线上 `sampleRate`、`reportUploadMinIntervalMs`、本地保留策略已接远程配置。
- [ ] `privacyMode` 符合业务合规要求，服务端只消费脱敏字段。
- [ ] `captureBarrierEvidence` 有白名单、灰度和回滚方案。
- [ ] 服务端按 `schemaVersion=1` 消费字段，并展示 `missingEvidence` 和 `sdkDiagnostics`。
- [ ] 灰度期间观察 SDK 自身指标，包括报告构建耗时、collector 失败、上传失败和本地报告数量。
- [ ] 完成典型场景手动验证：当前慢、历史慢、消息风暴、Barrier、Binder、CPU 压力。
- [ ] 如果报告显示业务卡顿疑似与 SharedPreferences 有关，将其作为业务侧 SP 治理专项处理，不把第五篇案例当作 SDK 接入验收项。

## 10. 常见问题

| 问题 | 排查方向 |
| --- | --- |
| 没有报告文件 | 确认 `enabled=true`、SDK 在目标进程安装、阻塞时间超过 `suspectAnrMs`、logcat 是否有 `onMonitorError` |
| 有本地报告但没有上传 | 确认 `uploadEnabled=true`、`sampleRate` 未采样掉、uploader 未返回 `Skip`、是否被 `reportUploadMinIntervalMs` 限频 |
| Barrier 字段不可用 | `captureBarrierEvidence` 默认关闭；开启后仍需具备 token 或 nativePollOnce 记录 |
| Pending 队列不可用 | 可能是反射受限、ROM 差异或 `capturePendingQueue=false`，查看 `pendingQueue.failureReason` |
| 归因为 UNKNOWN | 查看 `attribution.missingEvidence` 和 `sdkDiagnostics.collectorFailures`，优先补齐 Pending、历史消息或 Barrier 证据 |
| 主线程栈看起来不是根因 | 对照 `history`、`stackSamples`、`pendingQueue`；ANR 的当前 Trace 可能只是超时窗口内的一个结果 |
| 本地报告太多 | 先确认是否是修复前版本重复采样；新版本同一活跃 ANR 会去重，再调整 `reportRetentionMaxFileCount`、`reportRetentionMaxTotalBytes`、`reportRetentionMaxAgeMs` |

## 11. 与设计文档的关系

本文是接入使用说明，不替代设计评审。需要判断 SDK 需求输入和第 5 篇实战样例边界时，请同时查看：

- [01-第一篇-设计原理及影响因素.md](./01-第一篇-设计原理及影响因素.md)
- [02-第二篇-监控工具与分析思路.md](./02-第二篇-监控工具与分析思路.md)
- [03-第三篇-实例剖析集锦.md](./03-第三篇-实例剖析集锦.md)
- [04-第四篇-Barrier导致主线程假死.md](./04-第四篇-Barrier导致主线程假死.md)
- [05-第五篇-告别SharedPreference等待.md](./05-第五篇-告别SharedPreference等待.md)
- [99-ANR监控SDK设计开发文档.md](./99-ANR监控SDK设计开发文档.md)
- [102-ANR监控SDK服务端消费协议.md](./102-ANR监控SDK服务端消费协议.md)

其中 01 到 04 是当前 SDK 的基础需求来源；第 05 篇是 ANR 监控完成后的 SharedPreferences 实战分析案例，只用于指导业务侧复盘和专项治理。
