package com.valiantyan.anrmonitor.reporter.retry

import com.valiantyan.anrmonitor.api.UploadResult
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * 报告重试队列，负责采样、限频、gzip 压缩和失败退避元信息。
 *
 * @param sampleRate 上传采样率，范围会归一化到 [0, 1]。
 * @param minEnqueueIntervalMs 两次入队之间的最小间隔。
 * @param initialRetryDelayMs 首次失败后的重试延迟。
 * @param maxRetryDelayMs 最大重试延迟。
 * @param selfMonitor SDK 自监控器。
 * @param sampleBucketProvider 采样桶注入点，便于测试稳定复现。
 */
class ReportRetryQueue(
    sampleRate: Float,
    private val minEnqueueIntervalMs: Long = 60_000L,
    private val initialRetryDelayMs: Long = 1_000L,
    private val maxRetryDelayMs: Long = 60_000L,
    private val selfMonitor: SdkSelfMonitor = SdkSelfMonitor(),
    private val sampleBucketProvider: () -> Float = { Random.nextFloat() },
) {
    // 归一化采样率，避免宿主误配导致队列行为异常。
    private val normalizedSampleRate: Float = sampleRate.coerceIn(
        minimumValue = 0.0f,
        maximumValue = 1.0f,
    )

    // 内存重试队列，文件名是同一事件的稳定键。
    private val reports: LinkedHashMap<String, QueuedReport> = linkedMapOf()

    // 最近一次入队时间，用于上报限频。
    private var lastEnqueueUptimeMs: Long? = null

    /**
     * 尝试将报告加入重试队列，成功时保存 gzip 压缩后的 payload。
     *
     * @param fileName 报告文件名。
     * @param payloadText JSON 报告文本。
     * @param nowUptimeMs 当前 uptime 时间。
     * @return 入队或跳过结果。
     */
    @Synchronized
    fun enqueue(
        fileName: String,
        payloadText: String,
        nowUptimeMs: Long,
    ): ReportEnqueueResult {
        if (!isSampledIn()) {
            selfMonitor.increment(name = "report_queue_sampled_out")
            return ReportEnqueueResult.Skipped(reason = ReportSkipReason.SAMPLED_OUT)
        }
        if (isRateLimited(nowUptimeMs = nowUptimeMs)) {
            selfMonitor.increment(name = "report_queue_rate_limited")
            return ReportEnqueueResult.Skipped(reason = ReportSkipReason.RATE_LIMITED)
        }
        val report = QueuedReport(
            fileName = fileName,
            payloadBytes = gzip(text = payloadText),
            isCompressed = true,
            attemptCount = 0,
            nextRetryUptimeMs = nowUptimeMs,
            lastFailureReason = null,
        )
        reports[fileName] = report
        lastEnqueueUptimeMs = nowUptimeMs
        selfMonitor.increment(name = "report_queue_enqueued")
        return ReportEnqueueResult.Enqueued(report = report)
    }

    /**
     * 查询当前已到期的重试报告。
     *
     * @param nowUptimeMs 当前 uptime 时间。
     * @param maxCount 最多返回数量。
     * @return 已到重试时间的报告列表。
     */
    @Synchronized
    fun dueReports(
        nowUptimeMs: Long,
        maxCount: Int = Int.MAX_VALUE,
    ): List<QueuedReport> {
        return reports.values
            .filter { report: QueuedReport -> report.nextRetryUptimeMs <= nowUptimeMs }
            .sortedBy { report: QueuedReport -> report.nextRetryUptimeMs }
            .take(n = maxCount.coerceAtLeast(minimumValue = 0))
    }

    /**
     * 根据宿主上报结果更新队列，失败保留并退避，成功或跳过则移除。
     *
     * @param fileName 报告文件名。
     * @param result 宿主上报结果。
     * @param nowUptimeMs 当前 uptime 时间。
     */
    @Synchronized
    fun recordUploadResult(
        fileName: String,
        result: UploadResult,
        nowUptimeMs: Long,
    ): Unit {
        when (result) {
            is UploadResult.Failure -> recordFailure(fileName = fileName, reason = result.reason, nowUptimeMs = nowUptimeMs)
            UploadResult.Skip -> removeReport(fileName = fileName, metricName = "report_upload_skipped")
            UploadResult.Success -> removeReport(fileName = fileName, metricName = "report_upload_success")
        }
    }

    // 判断采样是否命中，采样桶小于采样率才允许上报。
    private fun isSampledIn(): Boolean {
        return sampleBucketProvider().coerceIn(
            minimumValue = 0.0f,
            maximumValue = 1.0f,
        ) < normalizedSampleRate
    }

    // 判断是否命中入队限频，防止同一段卡顿产生大量上传请求。
    private fun isRateLimited(nowUptimeMs: Long): Boolean {
        val lastUptimeMs: Long = lastEnqueueUptimeMs ?: return false
        return nowUptimeMs - lastUptimeMs < minEnqueueIntervalMs
    }

    // 记录失败并按指数退避安排下一次重试。
    private fun recordFailure(
        fileName: String,
        reason: String,
        nowUptimeMs: Long,
    ): Unit {
        val currentReport: QueuedReport = reports[fileName] ?: return
        val nextAttemptCount: Int = currentReport.attemptCount + 1
        reports[fileName] = currentReport.copy(
            attemptCount = nextAttemptCount,
            nextRetryUptimeMs = nowUptimeMs + retryDelayMs(attemptCount = nextAttemptCount),
            lastFailureReason = reason,
        )
        selfMonitor.increment(name = "report_upload_failure")
    }

    // 成功或主动跳过后移除队列，避免无限重试。
    private fun removeReport(
        fileName: String,
        metricName: String,
    ): Unit {
        if (reports.remove(key = fileName) != null) {
            selfMonitor.increment(name = metricName)
        }
    }

    // 按失败次数计算退避时间，避免网络故障时持续打扰宿主上报链路。
    private fun retryDelayMs(attemptCount: Int): Long {
        var delayMs: Long = initialRetryDelayMs.coerceAtLeast(minimumValue = 0L)
        repeat(times = (attemptCount - 1).coerceAtLeast(minimumValue = 0)) {
            delayMs = (delayMs * RETRY_BACKOFF_MULTIPLIER).coerceAtMost(maximumValue = maxRetryDelayMs)
        }
        return delayMs.coerceAtMost(maximumValue = maxRetryDelayMs.coerceAtLeast(minimumValue = 0L))
    }

    // gzip 压缩报告文本，降低本地重试和后续上传载荷成本。
    private fun gzip(text: String): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzipStream: GZIPOutputStream ->
            gzipStream.write(text.toByteArray(charset = Charsets.UTF_8))
        }
        return outputStream.toByteArray()
    }

    private companion object {
        /**
         * 失败重试退避倍数。
         */
        private const val RETRY_BACKOFF_MULTIPLIER: Int = 2
    }
}
