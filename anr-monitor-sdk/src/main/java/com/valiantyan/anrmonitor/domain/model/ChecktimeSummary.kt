package com.valiantyan.anrmonitor.domain.model

/**
 * Watchdog Checktime 调度延迟摘要，用于判断后台检测线程自身是否被系统调度拖慢。
 *
 * @property available Checktime 证据是否可用。
 * @property maxDelayMs 最近窗口内最大的调度延迟，单位毫秒。
 * @property severeDelayCount 最近窗口内达到严重阈值的调度延迟次数。
 * @property recentDelayMs 最近调度延迟窗口，单位毫秒。
 * @property failureReason Checktime 不可用时的降级原因。
 */
data class ChecktimeSummary(
    val maxDelayMs: Long,
    val severeDelayCount: Int,
    val recentDelayMs: List<Long>,
    val available: Boolean = true,
    val failureReason: String? = null,
) {
    /**
     * 创建默认 Checktime 摘要，用于无延迟样本但能力可用的初始报告。
     */
    companion object {
        /**
         * 返回空样本摘要，表示尚未记录到调度延迟。
         *
         * @return 可用但无样本的 Checktime 摘要。
         */
        fun empty(): ChecktimeSummary {
            return ChecktimeSummary(
                maxDelayMs = 0L,
                severeDelayCount = 0,
                recentDelayMs = emptyList(),
            )
        }

        /**
         * 返回不可用摘要，保留禁用或采集失败原因。
         *
         * @param reason Checktime 不可用原因。
         * @return 不可用 Checktime 摘要。
         */
        fun unavailable(reason: String): ChecktimeSummary {
            return ChecktimeSummary(
                maxDelayMs = 0L,
                severeDelayCount = 0,
                recentDelayMs = emptyList(),
                available = false,
                failureReason = reason,
            )
        }
    }
}
