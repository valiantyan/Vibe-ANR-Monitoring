package com.valiantyan.anrmonitor.internal

import android.content.Context
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.collector.anrinfo.AnrInfoCollector
import com.valiantyan.anrmonitor.collector.barrier.BarrierEvidenceCollector
import com.valiantyan.anrmonitor.collector.barrier.BarrierTokenTracker
import com.valiantyan.anrmonitor.collector.binder.BinderBlockClassifier
import com.valiantyan.anrmonitor.collector.binder.BinderThreadStackCollector
import com.valiantyan.anrmonitor.collector.checktime.ChecktimeMonitor
import com.valiantyan.anrmonitor.collector.environment.EnvironmentSnapshotter
import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller
import com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollector
import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSnapshotter
import com.valiantyan.anrmonitor.collector.stack.MainThreadStackCollector
import com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSampler
import com.valiantyan.anrmonitor.collector.threadcpu.ThreadCpuSnapshotter
import com.valiantyan.anrmonitor.collector.watchdog.AnrWatchdog
import com.valiantyan.anrmonitor.collector.watchdog.HeartbeatState
import com.valiantyan.anrmonitor.core.clock.AndroidClock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.BarrierEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.BinderBlockSnapshot
import com.valiantyan.anrmonitor.domain.model.ChecktimeSummary
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import com.valiantyan.anrmonitor.domain.model.SystemEnvironmentSnapshot
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import com.valiantyan.anrmonitor.reporter.local.LocalAnrReportWriter
import com.valiantyan.anrmonitor.reporter.retry.ReportEnqueueResult
import com.valiantyan.anrmonitor.reporter.retry.ReportRetentionPolicy
import com.valiantyan.anrmonitor.reporter.retry.ReportRetryDispatcher
import com.valiantyan.anrmonitor.reporter.retry.ReportRetryQueue
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SDK 运行时编排器，把消息时间线、Watchdog、证据采集、归因和本地报告写入串成闭环。
 *
 * @param context 宿主上下文，内部只持有 application context。
 * @param config SDK 安装配置。
 * @param uploader 宿主报告上报扩展点。
 * @param listener 宿主事件监听器。
 */
internal class AnrMonitorRuntime(
    context: Context,
    private val config: AnrMonitorConfig,
    private val uploader: AnrReportUploader,
    private val listener: AnrEventListener,
) : AnrMonitor.RuntimeHandle {
    // application context 避免运行时持有 Activity。
    private val appContext: Context = context.applicationContext

    // Android uptime 时间源，统一支撑 collector 和报告成本统计。
    private val clock: AndroidClock = AndroidClock()

    // SDK 自监控器，统一记录报告写入、治理和上传队列健康度。
    private val sdkSelfMonitor: SdkSelfMonitor = SdkSelfMonitor()

    // 类名脱敏器，保证 collector 阶段就只输出安全类名。
    private val sanitizer: ClassNameSanitizer = ClassNameSanitizer(privacyMode = config.privacyMode)

    // Looper 历史消息缓冲区，疑似 ANR 时作为前序消息证据。
    private val historyBuffer: MessageRingBuffer = MessageRingBuffer(capacity = config.historyBufferSize)

    // 主线程 Java 栈采集器，同时供疑似 ANR 快照和慢消息采样复用。
    private val stackCollector: MainThreadStackCollector = MainThreadStackCollector()

    // 慢消息栈采样器，按配置限制单消息采样次数。
    private val slowMessageStackSampler: SlowMessageStackSampler = SlowMessageStackSampler(
        maxSamplesPerMessage = config.maxStackSamplesPerMessage,
        frameProvider = { stackCollector.capture().frames },
    )

    // 主 Looper 消息时间线采集器。
    private val timelineCollector: MainLooperTimelineCollector = MainLooperTimelineCollector(
        clock = clock,
        threadCpuClock = MainThreadCpuClock(),
        sanitizer = sanitizer,
        historyBuffer = historyBuffer,
        slowMessageMs = config.slowMessageMs,
        stackSampleIntervalMs = config.stackSampleIntervalMs,
        slowMessageSampler = slowMessageStackSampler,
    )

    // Pending 队列快照器，用于同步屏障和消息堆积证据。
    private val pendingSnapshotter: PendingQueueSnapshotter = PendingQueueSnapshotter(
        clock = clock,
        sanitizer = sanitizer,
    )

    // 线程 CPU TopN 采集器，用于补充进程内资源证据。
    private val threadCpuSnapshotter: ThreadCpuSnapshotter = ThreadCpuSnapshotter()

    // Checktime 监控器，用于判断 Watchdog 检测线程是否被系统调度拖慢。
    private val checktimeMonitor: ChecktimeMonitor = ChecktimeMonitor(
        expectedIntervalMs = config.watchdogIntervalMs,
        severeDelayMs = config.suspectAnrMs,
    )

    // 系统环境采集器，用于补充外部负载、内存、存储和进程 I/O 证据。
    private val environmentSnapshotter: EnvironmentSnapshotter = EnvironmentSnapshotter()

    // Barrier token 追踪器，默认只消费已有记录，不主动改变 Looper 行为。
    private val barrierTokenTracker: BarrierTokenTracker = BarrierTokenTracker.global

    // nativePollOnce 监控器，供灰度 hook 或安全入口记录轮询窗口。
    private val nativePollOnceMonitor: NativePollOnceMonitor = NativePollOnceMonitor.global

    // Barrier 增强证据聚合器，将高风险入口记录与 Pending 队列对齐。
    private val barrierEvidenceCollector: BarrierEvidenceCollector = BarrierEvidenceCollector(
        tokenTracker = barrierTokenTracker,
        nativePollOnceMonitor = nativePollOnceMonitor,
    )

    // Binder 阻塞疑似分类器，只输出 suspected 证据，不确认跨进程死锁。
    private val binderBlockClassifier: BinderBlockClassifier = BinderBlockClassifier()

    // 当前进程 Binder 线程栈采集器，作为跨进程阻塞疑似的进程内辅助证据。
    private val binderThreadStackCollector: BinderThreadStackCollector = BinderThreadStackCollector()

    // 系统确认 ANR 信息采集器，用于区分疑似 ANR 和 ActivityManager 确认 ANR。
    private val anrInfoCollector: AnrInfoCollector = AnrInfoCollector.create(context = appContext)

    // 完整报告拼装器，统一维护归因和 SDK 自诊断字段。
    private val reportAssembler: AnrReportAssembler = AnrReportAssembler(
        config = config,
        clock = clock,
        selfMonitor = sdkSelfMonitor,
    )

    // JSON 编码器由本地写入和上传重试队列共用，避免报告出口 schema 分叉。
    private val reportEncoder: AnrReportJsonEncoder = AnrReportJsonEncoder()

    // 本地报告保留策略，防止大量疑似事件导致 app 私有目录无限增长。
    private val reportRetentionPolicy: ReportRetentionPolicy = ReportRetentionPolicy(
        maxFileCount = config.reportRetentionMaxFileCount,
        maxTotalBytes = config.reportRetentionMaxTotalBytes,
        maxAgeMs = config.reportRetentionMaxAgeMs,
    )

    // 本地报告写入器，写入后会按保留策略清理历史报告。
    private val localWriter: LocalAnrReportWriter = LocalAnrReportWriter(
        context = appContext,
        encoder = reportEncoder,
        retentionPolicy = reportRetentionPolicy,
        selfMonitor = sdkSelfMonitor,
    )

    // 报告上传重试队列，上传开关开启时负责采样、限频和 gzip payload。
    private val reportRetryQueue: ReportRetryQueue = ReportRetryQueue(
        sampleRate = config.normalizedSampleRate,
        minEnqueueIntervalMs = config.reportUploadMinIntervalMs,
        initialRetryDelayMs = config.reportRetryInitialDelayMs,
        maxRetryDelayMs = config.reportRetryMaxDelayMs,
        selfMonitor = sdkSelfMonitor,
    )

    // 上传重试调度器，负责消费到期队列并重新调用宿主 uploader。
    private val reportRetryDispatcher: ReportRetryDispatcher = ReportRetryDispatcher(
        retryQueue = reportRetryQueue,
        clock = clock,
        uploader = uploader::upload,
    )

    // 运行态开关，保证 start/stop 幂等。
    private val isRunning: AtomicBoolean = AtomicBoolean(false)

    // 活跃 ANR 去重器，避免同一阻塞窗口重复写入多份 JSON。
    private val incidentDeduplicator: AnrIncidentDeduplicator = AnrIncidentDeduplicator()

    // Looper Printer 安装句柄，停止时用于恢复宿主安装前状态。
    @Volatile
    private var looperPrinterHandle: MainLooperPrinterInstaller.InstallHandle? = null

    // 上传重试后台线程，只有开启上传时才启动。
    @Volatile
    private var uploadRetryThread: Thread? = null

    // 最近一次疑似 ANR 报告时间，用于按阈值限频。
    @Volatile
    private var lastSuspectReportUptimeMs: Long = 0L

    // Watchdog 心跳检测器，检测到超时后回调捕获现场。
    private val watchdog: AnrWatchdog = AnrWatchdog(
        clock = clock,
        intervalMs = config.watchdogIntervalMs,
        heartbeatState = HeartbeatState(timeoutMs = config.suspectAnrMs),
        onChecktimeInterval = ::recordChecktimeInterval,
        onSuspectAnr = ::captureSuspectAnr,
        onRecovered = ::markAnrRecovered,
    )

    /**
     * 启动 Looper Printer 和 Watchdog；配置关闭时保持空操作。
     */
    override fun start(): Unit {
        if (!config.enabled || !isRunning.compareAndSet(false, true)) {
            return
        }
        looperPrinterHandle = MainLooperPrinterInstaller().install(printer = timelineCollector)
        watchdog.start()
        startUploadRetryLoop()
    }

    /**
     * 停止 Watchdog 后台线程；Looper Printer 当前阶段保留链式安装状态。
     */
    override fun stop(): Unit {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }
        watchdog.stop()
        looperPrinterHandle?.uninstall()
        looperPrinterHandle = null
        markAnrRecovered()
        stopUploadRetryLoop()
    }

    /**
     * Watchdog 发现疑似 ANR 后捕获现场；异常只回调监听器，不向宿主线程扩散。
     */
    private fun captureSuspectAnr(): Unit {
        val nowUptimeMs: Long = clock.uptimeMillis()
        if (!shouldCapture(nowUptimeMs = nowUptimeMs)) {
            return
        }
        try {
            captureAndReport(nowUptimeMs = nowUptimeMs)
        } catch (error: IOException) {
            listener.onMonitorError(error = error)
        } catch (error: RuntimeException) {
            listener.onMonitorError(error = error)
        }
    }

    // 统一处理运行态和低成本限频判断，完整事件去重在快照生成后执行。
    private fun shouldCapture(nowUptimeMs: Long): Boolean {
        if (!isRunning.get()) {
            return false
        }
        if (nowUptimeMs - lastSuspectReportUptimeMs < config.suspectAnrMs) {
            return false
        }
        lastSuspectReportUptimeMs = nowUptimeMs
        return true
    }

    // 采集快照、执行归因、写入本地报告，并按配置调用宿主上报扩展点。
    private fun captureAndReport(nowUptimeMs: Long): Unit {
        val buildStartMs: Long = clock.uptimeMillis()
        val snapshot: AnrSnapshot = buildSnapshot(nowUptimeMs = nowUptimeMs)
        if (!incidentDeduplicator.shouldReport(snapshot = snapshot)) {
            return
        }
        listener.onSuspectAnr(snapshot = snapshot)
        val report: AnrReport = reportAssembler.build(
            snapshot = snapshot,
            buildStartMs = buildStartMs,
        )
        localWriter.write(report = report)
        listener.onConfirmedAnr(report = report)
        uploadIfEnabled(report = report)
    }

    // 构造疑似 ANR 现场快照，Pending 关闭时保留明确缺失原因。
    private fun buildSnapshot(nowUptimeMs: Long): AnrSnapshot {
        val anrInfo: AnrInfoSnapshot = anrInfoCollector.collect()
        val pendingQueue: PendingQueueSnapshot = capturePendingQueue()
        val mainThreadStack: StackTraceSnapshot = stackCollector.capture()
        return AnrSnapshot(
            eventId = UUID.randomUUID().toString(),
            eventType = eventType(anrInfo = anrInfo),
            appId = config.appId,
            environment = config.environment,
            timeUptimeMs = nowUptimeMs,
            anrInfo = anrInfo,
            componentTimeoutMs = config.componentTimeoutMs[anrInfo.anrType],
            currentMessage = timelineCollector.currentMessage(),
            historyMessages = timelineCollector.historyMessages(),
            pendingQueue = pendingQueue,
            mainThreadStack = mainThreadStack,
            stackSamples = timelineCollector.stackSamples(),
            threadCpuRecords = captureThreadCpuRecords(),
            checktimeSummary = captureChecktimeSummary(),
            environmentSnapshot = captureEnvironmentSnapshot(),
            barrierEvidenceSnapshot = captureBarrierEvidenceSnapshot(
                nowUptimeMs = nowUptimeMs,
                pendingQueue = pendingQueue,
                mainThreadStack = mainThreadStack,
            ),
            binderBlockSnapshot = captureBinderBlockSnapshot(mainThreadStack = mainThreadStack),
        )
    }

    // 主线程心跳恢复后清理活跃 ANR 状态，避免后续新卡顿被旧签名压制。
    private fun markAnrRecovered(): Unit {
        incidentDeduplicator.markRecovered()
        lastSuspectReportUptimeMs = 0L
    }

    // 系统确认状态只改变事件阶段，不参与根因归因。
    private fun eventType(anrInfo: AnrInfoSnapshot): AnrEventType {
        if (anrInfo.isConfirmedAnr) {
            return AnrEventType.CONFIRMED_ANR
        }
        return AnrEventType.SUSPECT_ANR
    }

    // 记录 Watchdog 实际调度间隔；禁用时不累积样本，避免报告输出误导。
    private fun recordChecktimeInterval(actualIntervalMs: Long): Unit {
        if (!config.captureChecktime) {
            return
        }
        checktimeMonitor.recordDelay(actualIntervalMs = actualIntervalMs)
    }

    // 根据配置输出 Checktime 摘要，禁用时保留明确缺失原因。
    private fun captureChecktimeSummary(): ChecktimeSummary {
        if (!config.captureChecktime) {
            return ChecktimeSummary.unavailable(reason = "checktime capture disabled")
        }
        return checktimeMonitor.summary()
    }

    // 根据配置采集系统环境，禁用时保留明确缺失原因。
    private fun captureEnvironmentSnapshot(): SystemEnvironmentSnapshot {
        if (!config.captureSystemEnvironment) {
            return SystemEnvironmentSnapshot.unavailable(reason = "system environment capture disabled")
        }
        return environmentSnapshotter.capture()
    }

    // 根据配置采集线程 CPU TopN；禁用或读取失败时由采集器降级为空列表。
    private fun captureThreadCpuRecords(): List<ThreadCpuRecord> {
        if (!config.captureThreadCpu) {
            return emptyList()
        }
        return threadCpuSnapshotter.captureTopThreads(maxCount = DEFAULT_THREAD_CPU_MAX_COUNT)
    }

    // 根据配置采集 Pending 队列，禁用时返回不可用快照供报告说明。
    private fun capturePendingQueue(): PendingQueueSnapshot {
        if (!config.capturePendingQueue) {
            return PendingQueueSnapshot.unavailable(
                maxDepth = config.pendingSnapshotMaxDepth,
                failureReason = "pending capture disabled",
            )
        }
        return pendingSnapshotter.capture(maxDepth = config.pendingSnapshotMaxDepth)
    }

    // 根据配置采集 Barrier token 和 nativePollOnce 增强证据，高风险能力默认可降级。
    private fun captureBarrierEvidenceSnapshot(
        nowUptimeMs: Long,
        pendingQueue: PendingQueueSnapshot,
        mainThreadStack: StackTraceSnapshot,
    ): BarrierEvidenceSnapshot {
        return barrierEvidenceCollector.collect(
            enabled = config.captureBarrierEvidence,
            nowUptimeMs = nowUptimeMs,
            stuckThresholdMs = config.barrierTokenStuckThresholdMs,
            maxRecords = config.barrierEvidenceMaxRecords,
            pendingQueue = pendingQueue,
            mainThreadFrames = mainThreadStack.frames,
        )
    }

    // 根据配置采集 Binder 疑似证据；关闭时保留原因，避免误读为未命中。
    private fun captureBinderBlockSnapshot(mainThreadStack: StackTraceSnapshot): BinderBlockSnapshot {
        if (!config.captureBinderEvidence) {
            return BinderBlockSnapshot.unavailable(reason = "binder evidence capture disabled")
        }
        val binderThreadFrames: List<String> = binderThreadStackCollector.capture(
            maxThreadCount = config.binderThreadMaxCount,
            maxFramesPerThread = config.binderThreadStackMaxFrames,
        )
        return binderBlockClassifier.classify(
            mainFrames = mainThreadStack.frames,
            binderThreadFrames = binderThreadFrames,
        )
    }

    // 上传开关开启时调用宿主扩展点，失败结果转成监控错误回调。
    private fun uploadIfEnabled(report: AnrReport): Unit {
        if (!config.uploadEnabled) {
            return
        }
        val fileName: String = "${report.snapshot.eventId}.json.gz"
        val enqueueResult: ReportEnqueueResult = reportRetryDispatcher.enqueueReport(
            fileName = fileName,
            payloadText = reportEncoder.encode(report = report),
            report = report,
        )
        if (enqueueResult is ReportEnqueueResult.Skipped) {
            return
        }
        val result: UploadResult = uploader.upload(report = report)
        reportRetryDispatcher.recordUploadResult(
            fileName = fileName,
            result = result,
        )
        if (result is UploadResult.Failure) {
            listener.onMonitorError(error = IllegalStateException(result.reason))
        }
    }

    // 上传开启时启动重试循环，避免失败报告只停留在内存队列里。
    private fun startUploadRetryLoop(): Unit {
        if (!config.uploadEnabled || uploadRetryThread != null) {
            return
        }
        uploadRetryThread = Thread(::runUploadRetryLoop, UPLOAD_RETRY_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    // 停止上传重试循环，避免卸载后后台线程继续持有 runtime。
    private fun stopUploadRetryLoop(): Unit {
        uploadRetryThread?.interrupt()
        uploadRetryThread = null
    }

    // 周期性消费已到期的失败报告；上传器异常由调度器转成失败结果。
    private fun runUploadRetryLoop(): Unit {
        while (isRunning.get() && config.uploadEnabled) {
            reportRetryDispatcher.flushDueReports(maxCount = DEFAULT_RETRY_BATCH_SIZE)
            sleepUploadRetryInterval()
        }
    }

    // 等待下一轮重试；停止时保留中断状态，让线程自然退出循环。
    private fun sleepUploadRetryInterval(): Unit {
        try {
            Thread.sleep(config.reportRetryInitialDelayMs.coerceAtLeast(minimumValue = MIN_RETRY_LOOP_SLEEP_MS))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /**
         * 单次报告最多保留的线程 CPU 记录数，避免报告被线程数量放大。
         */
        private const val DEFAULT_THREAD_CPU_MAX_COUNT: Int = 5

        /**
         * 单轮最多重试报告数量。
         */
        private const val DEFAULT_RETRY_BATCH_SIZE: Int = 3

        /**
         * 上传重试后台线程名。
         */
        private const val UPLOAD_RETRY_THREAD_NAME: String = "vibe-anr-upload-retry"

        /**
         * 重试循环最小睡眠间隔，避免误配 0ms 导致空转。
         */
        private const val MIN_RETRY_LOOP_SLEEP_MS: Long = 1_000L
    }
}
