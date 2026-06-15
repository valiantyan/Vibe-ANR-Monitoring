package com.valiantyan.anrmonitor.reporter.delivery

import android.content.Context
import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.api.AnrReportUploader
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoder
import com.valiantyan.anrmonitor.reporter.local.LocalAnrReportWriter
import com.valiantyan.anrmonitor.reporter.retry.ReportRetentionPolicy
import com.valiantyan.anrmonitor.reporter.retry.ReportRetryDispatcher
import com.valiantyan.anrmonitor.reporter.retry.ReportRetryQueue
import java.io.File

/**
 * delivery implementation 的内部组件集合，保持编码器和出口策略的 locality。
 *
 * @property reportEncoder 本地写入和上传入队共用的 JSON 编码器。
 * @property localWriter 本地报告写入器。
 * @property retryDispatcher 上传失败重试调度器。
 */
internal data class AnrReportDeliveryParts(
    val reportEncoder: AnrReportJsonEncoder,
    val localWriter: LocalAnrReportWriter,
    val retryDispatcher: ReportRetryDispatcher,
)

/**
 * 报告 delivery factory，集中把 SDK 配置翻译成本地写入和上传重试 implementation。
 */
internal object AnrReportDeliveryFactory {
    /**
     * 创建 Android 运行时组件，统一绑定本地目录、保留策略和上传重试策略。
     *
     * @param context 宿主应用上下文。
     * @param config SDK 安装配置。
     * @param clock uptime 时间源。
     * @param uploader 宿主报告上传扩展点。
     * @param selfMonitor SDK 自监控器。
     * @return delivery 内部组件集合。
     */
    fun createAndroidParts(
        context: Context,
        config: AnrMonitorConfig,
        clock: Clock,
        uploader: AnrReportUploader,
        selfMonitor: SdkSelfMonitor,
    ): AnrReportDeliveryParts {
        val reportEncoder = AnrReportJsonEncoder()
        return AnrReportDeliveryParts(
            reportEncoder = reportEncoder,
            localWriter = LocalAnrReportWriter(
                context = context,
                encoder = reportEncoder,
                retentionPolicy = createRetentionPolicy(config = config),
                selfMonitor = selfMonitor,
            ),
            retryDispatcher = createRetryDispatcher(
                config = config,
                clock = clock,
                uploader = uploader,
                selfMonitor = selfMonitor,
            ),
        )
    }

    /**
     * 创建 JVM 单测组件，使用测试目录替代 Android [Context]。
     *
     * @param config SDK 安装配置。
     * @param clock uptime 时间源。
     * @param uploader 宿主报告上传扩展点。
     * @param reportDirectory 本地报告写入目录。
     * @return delivery 内部组件集合。
     */
    fun createTestParts(
        config: AnrMonitorConfig,
        clock: Clock,
        uploader: AnrReportUploader,
        reportDirectory: File,
    ): AnrReportDeliveryParts {
        val reportEncoder = AnrReportJsonEncoder()
        val selfMonitor = SdkSelfMonitor()
        return AnrReportDeliveryParts(
            reportEncoder = reportEncoder,
            localWriter = LocalAnrReportWriter(
                reportDirectory = reportDirectory,
                encoder = reportEncoder,
                retentionPolicy = createRetentionPolicy(config = config),
                selfMonitor = selfMonitor,
            ),
            retryDispatcher = createRetryDispatcher(
                config = config,
                clock = clock,
                uploader = uploader,
                selfMonitor = selfMonitor,
            ),
        )
    }

    // 根据 SDK 配置生成本地报告保留策略，保证磁盘治理和 delivery 生命周期绑定。
    private fun createRetentionPolicy(config: AnrMonitorConfig): ReportRetentionPolicy {
        return ReportRetentionPolicy(
            maxFileCount = config.reportRetentionMaxFileCount,
            maxTotalBytes = config.reportRetentionMaxTotalBytes,
            maxAgeMs = config.reportRetentionMaxAgeMs,
        )
    }

    // 根据 SDK 配置生成重试调度器，集中采样、限频和退避依赖。
    private fun createRetryDispatcher(
        config: AnrMonitorConfig,
        clock: Clock,
        uploader: AnrReportUploader,
        selfMonitor: SdkSelfMonitor,
    ): ReportRetryDispatcher {
        val retryQueue = ReportRetryQueue(
            sampleRate = config.normalizedSampleRate,
            minEnqueueIntervalMs = config.reportUploadMinIntervalMs,
            initialRetryDelayMs = config.reportRetryInitialDelayMs,
            maxRetryDelayMs = config.reportRetryMaxDelayMs,
            selfMonitor = selfMonitor,
        )
        return ReportRetryDispatcher(
            retryQueue = retryQueue,
            clock = clock,
            uploader = uploader::upload,
        )
    }
}
