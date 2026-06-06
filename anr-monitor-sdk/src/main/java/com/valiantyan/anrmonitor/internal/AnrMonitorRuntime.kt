package com.valiantyan.anrmonitor.internal

import android.content.Context
import com.valiantyan.anrmonitor.api.AnrEventListener
import com.valiantyan.anrmonitor.api.AnrMonitor
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.collector.looper.MainLooperPrinterInstaller
import com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollector
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSnapshotter
import com.valiantyan.anrmonitor.collector.stack.MainThreadStackCollector
import com.valiantyan.anrmonitor.collector.threadcpu.ThreadCpuSnapshotter
import com.valiantyan.anrmonitor.collector.watchdog.AnrWatchdog
import com.valiantyan.anrmonitor.collector.watchdog.HeartbeatState
import com.valiantyan.anrmonitor.core.clock.AndroidClock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import com.valiantyan.anrmonitor.reporter.local.LocalAnrReportWriter
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

    // 类名脱敏器，保证 collector 阶段就只输出安全类名。
    private val sanitizer: ClassNameSanitizer = ClassNameSanitizer(privacyMode = config.privacyMode)

    // Looper 历史消息缓冲区，疑似 ANR 时作为前序消息证据。
    private val historyBuffer: MessageRingBuffer = MessageRingBuffer(capacity = config.historyBufferSize)

    // 主 Looper 消息时间线采集器。
    private val timelineCollector: MainLooperTimelineCollector = MainLooperTimelineCollector(
        clock = clock,
        threadCpuClock = MainThreadCpuClock(),
        sanitizer = sanitizer,
        historyBuffer = historyBuffer,
    )

    // Pending 队列快照器，用于同步屏障和消息堆积证据。
    private val pendingSnapshotter: PendingQueueSnapshotter = PendingQueueSnapshotter(
        clock = clock,
        sanitizer = sanitizer,
    )

    // 主线程 Java 栈采集器。
    private val stackCollector: MainThreadStackCollector = MainThreadStackCollector()

    // 线程 CPU TopN 采集器，用于补充进程内资源证据。
    private val threadCpuSnapshotter: ThreadCpuSnapshotter = ThreadCpuSnapshotter()

    // 完整报告拼装器，统一维护归因和 SDK 自诊断字段。
    private val reportAssembler: AnrReportAssembler = AnrReportAssembler(
        config = config,
        clock = clock,
    )

    // 本地报告写入器，阶段一先保证报告可落盘排查。
    private val localWriter: LocalAnrReportWriter = LocalAnrReportWriter(context = appContext)

    // 运行态开关，保证 start/stop 幂等。
    private val isRunning: AtomicBoolean = AtomicBoolean(false)

    // 最近一次疑似 ANR 报告时间，用于按阈值限频。
    @Volatile
    private var lastSuspectReportUptimeMs: Long = 0L

    // Watchdog 心跳检测器，检测到超时后回调捕获现场。
    private val watchdog: AnrWatchdog = AnrWatchdog(
        clock = clock,
        intervalMs = config.watchdogIntervalMs,
        heartbeatState = HeartbeatState(timeoutMs = config.suspectAnrMs),
        onSuspectAnr = ::captureSuspectAnr,
    )

    /**
     * 启动 Looper Printer 和 Watchdog；配置关闭时保持空操作。
     */
    override fun start(): Unit {
        if (!config.enabled || !isRunning.compareAndSet(false, true)) {
            return
        }
        MainLooperPrinterInstaller().install(printer = timelineCollector)
        watchdog.start()
    }

    /**
     * 停止 Watchdog 后台线程；Looper Printer 当前阶段保留链式安装状态。
     */
    override fun stop(): Unit {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }
        watchdog.stop()
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

    // 统一处理运行态和限频判断，避免同一轮阻塞重复生成报告。
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
        return AnrSnapshot(
            eventId = UUID.randomUUID().toString(),
            eventType = AnrEventType.SUSPECT_ANR,
            appId = config.appId,
            environment = config.environment,
            timeUptimeMs = nowUptimeMs,
            currentMessage = timelineCollector.currentMessage(),
            historyMessages = timelineCollector.historyMessages(),
            pendingQueue = capturePendingQueue(),
            mainThreadStack = stackCollector.capture(),
            threadCpuRecords = captureThreadCpuRecords(),
        )
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

    // 上传开关开启时调用宿主扩展点，失败结果转成监控错误回调。
    private fun uploadIfEnabled(report: AnrReport): Unit {
        if (!config.uploadEnabled) {
            return
        }
        val result: UploadResult = uploader.upload(report = report)
        if (result is UploadResult.Failure) {
            listener.onMonitorError(error = IllegalStateException(result.reason))
        }
    }

    private companion object {
        /**
         * 单次报告最多保留的线程 CPU 记录数，避免报告被线程数量放大。
         */
        private const val DEFAULT_THREAD_CPU_MAX_COUNT: Int = 5
    }
}
