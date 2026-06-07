package com.valiantyan.anrmonitor.reporter.retry

/**
 * 报告入队结果，调用方可据此决定是否继续上报扩展点。
 */
sealed interface ReportEnqueueResult {
    /**
     * 报告已入队并生成压缩载荷。
     *
     * @property report 入队后的报告元信息。
     */
    data class Enqueued(
        val report: QueuedReport,
    ) : ReportEnqueueResult

    /**
     * 报告按治理规则跳过。
     *
     * @property reason 跳过原因。
     */
    data class Skipped(
        val reason: ReportSkipReason,
    ) : ReportEnqueueResult
}

/**
 * 报告跳过原因，用于自监控和后续服务端聚合。
 */
enum class ReportSkipReason {
    /**
     * 采样未命中。
     */
    SAMPLED_OUT,

    /**
     * 上报限频命中。
     */
    RATE_LIMITED,
}

/**
 * 待上报或待重试的报告载荷。
 *
 * @property fileName 报告文件名。
 * @property payloadBytes 压缩后的报告内容。
 * @property isCompressed 当前载荷是否为 gzip 压缩。
 * @property attemptCount 已失败尝试次数。
 * @property nextRetryUptimeMs 下次允许重试的时间。
 * @property lastFailureReason 最近一次失败原因。
 */
data class QueuedReport(
    val fileName: String,
    val payloadBytes: ByteArray,
    val isCompressed: Boolean,
    val attemptCount: Int,
    val nextRetryUptimeMs: Long,
    val lastFailureReason: String?,
)
