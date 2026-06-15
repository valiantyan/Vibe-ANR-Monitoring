package com.valiantyan.anrmonitor.reporter.delivery

import android.content.Context
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import com.valiantyan.anrmonitor.reporter.local.LocalAnrReportWriter
import com.valiantyan.anrmonitor.reporter.retry.ReportEnqueueResult
import com.valiantyan.anrmonitor.reporter.retry.ReportRetryDispatcher
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 报告 delivery implementation，集中本地落盘、上传入队、直接上传和失败重试细节。
 */
internal class AnrReportDelivery private constructor(
    private val config: AnrMonitorConfig,
    private val uploader: AnrReportUploader,
    private val reportEncoder: AnrReportJsonEncoder,
    private val localWriter: LocalAnrReportWriter,
    private val retryDispatcher: ReportRetryDispatcher,
) {
    // 上传重试后台线程只属于 delivery implementation，避免 runtime 了解队列消费细节。
    @Volatile
    private var uploadRetryThread: Thread? = null

    // delivery 自身的重试循环停止态，避免只依赖外部 runtime 状态。
    private val isRetryLoopStopped: AtomicBoolean = AtomicBoolean(true)

    /**
     * 使用宿主 app 私有目录创建报告 delivery。
     *
     * @param context 宿主应用上下文。
     * @param config SDK 安装配置。
     * @param uploader 宿主报告上传扩展点。
     * @param clock uptime 时间源。
     * @param selfMonitor SDK 自监控器。
     */
    constructor(
        context: Context,
        config: AnrMonitorConfig,
        uploader: AnrReportUploader,
        clock: Clock,
        selfMonitor: SdkSelfMonitor,
    ) : this(
        config = config,
        uploader = uploader,
        parts = AnrReportDeliveryFactory.createAndroidParts(
            context = context,
            config = config,
            clock = clock,
            uploader = uploader,
            selfMonitor = selfMonitor,
        ),
    )

    /**
     * 使用测试目录创建报告 delivery，避免 JVM 单测依赖 Android [Context]。
     *
     * @param config SDK 安装配置。
     * @param uploader 宿主报告上传扩展点。
     * @param clock uptime 时间源。
     * @param reportDirectory 本地报告写入目录。
     */
    internal constructor(
        config: AnrMonitorConfig,
        uploader: AnrReportUploader,
        clock: Clock,
        reportDirectory: File,
    ) : this(
        config = config,
        uploader = uploader,
        parts = AnrReportDeliveryFactory.createTestParts(
            config = config,
            clock = clock,
            uploader = uploader,
            reportDirectory = reportDirectory,
        ),
    )

    // 使用预创建组件完成构造，确保本地写入和上传重试共用同一份报告编码器。
    private constructor(
        config: AnrMonitorConfig,
        uploader: AnrReportUploader,
        parts: AnrReportDeliveryParts,
    ) : this(
        config = config,
        uploader = uploader,
        reportEncoder = parts.reportEncoder,
        localWriter = parts.localWriter,
        retryDispatcher = parts.retryDispatcher,
    )

    /**
     * 将报告写入本地文件，调用方不需要知道 JSON 编码和治理策略。
     *
     * @param report 待落盘的 ANR 报告。
     * @return 已写入的报告文件。
     */
    fun writeLocalReport(report: AnrReport): File {
        return localWriter.write(report = report)
    }

    /**
     * 上传开关开启时执行入队、直接上传和结果记录。
     *
     * @param report 待上传的 ANR 报告。
     * @return 宿主上传结果；上传关闭或采样限频跳过时返回 null。
     */
    fun uploadReportIfEnabled(report: AnrReport): UploadResult? {
        if (!config.uploadEnabled) {
            return null
        }
        val fileName: String = "${report.snapshot.eventId}.json.gz"
        val enqueueResult: ReportEnqueueResult = retryDispatcher.enqueueReport(
            fileName = fileName,
            payloadText = reportEncoder.encode(report = report),
            report = report,
        )
        if (enqueueResult is ReportEnqueueResult.Skipped) {
            return null
        }
        return uploadAndRecordResult(
            fileName = fileName,
            report = report,
        )
    }

    /**
     * 上传开启时启动重试循环，避免失败报告只停留在内存队列里。
     *
     * @param isRunning 判断 SDK runtime 是否仍在运行。
     */
    @Synchronized
    fun startRetryLoop(isRunning: () -> Boolean): Unit {
        if (!config.uploadEnabled || uploadRetryThread != null) {
            return
        }
        isRetryLoopStopped.set(false)
        uploadRetryThread = Thread({ runRetryLoop(isRunning = isRunning) }, UPLOAD_RETRY_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 停止上传重试循环，避免卸载后后台线程继续持有 delivery implementation。
     */
    fun stopRetryLoop(): Unit {
        isRetryLoopStopped.set(true)
        uploadRetryThread?.interrupt()
        uploadRetryThread = null
    }

    /**
     * 消费已到期的失败报告，供重试线程和单测共享同一条路径。
     *
     * @param maxCount 单轮最多重试数量。
     * @return 实际触发上传器的报告数量。
     */
    fun flushDueReports(maxCount: Int): Int {
        return retryDispatcher.flushDueReports(maxCount = maxCount)
    }

    // 首次上传与结果记录保持原子顺序，避免后台重试线程并发重传同一报告。
    private fun uploadAndRecordResult(
        fileName: String,
        report: AnrReport,
    ): UploadResult {
        return try {
            val result: UploadResult = uploader.upload(report = report)
            retryDispatcher.recordUploadResult(
                fileName = fileName,
                result = result,
            )
            result
        } catch (error: RuntimeException) {
            retryDispatcher.recordUploadResult(
                fileName = fileName,
                result = UploadResult.Failure(reason = error.message ?: error.javaClass.simpleName),
            )
            throw error
        }
    }

    // 周期性消费已到期的失败报告，停止信号由 runtime 状态和上传开关共同控制。
    private fun runRetryLoop(isRunning: () -> Boolean): Unit {
        while (!isRetryLoopStopped.get() && isRunning() && config.uploadEnabled) {
            flushDueReports(maxCount = DEFAULT_RETRY_BATCH_SIZE)
            sleepRetryInterval()
        }
    }

    // 等待下一轮重试；停止时保留中断状态，让线程自然退出循环。
    private fun sleepRetryInterval(): Unit {
        try {
            Thread.sleep(config.reportRetryInitialDelayMs.coerceAtLeast(minimumValue = MIN_RETRY_LOOP_SLEEP_MS))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
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
