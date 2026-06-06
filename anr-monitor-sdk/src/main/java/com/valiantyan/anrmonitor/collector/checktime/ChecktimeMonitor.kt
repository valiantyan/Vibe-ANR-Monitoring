package com.valiantyan.anrmonitor.collector.checktime

import com.valiantyan.anrmonitor.domain.model.ChecktimeSummary

/**
 * Watchdog Checktime 监控器，记录后台检测周期相对预期间隔的调度延迟。
 *
 * @param expectedIntervalMs 预期检测周期，单位毫秒。
 * @param severeDelayMs 严重调度延迟阈值，单位毫秒。
 */
class ChecktimeMonitor(
    private val expectedIntervalMs: Long,
    private val severeDelayMs: Long,
) {
    // 最近调度延迟窗口，只保留报告需要的少量样本。
    private val delays: MutableList<Long> = mutableListOf()

    /**
     * 记录一次实际检测间隔，并转换成相对预期间隔的延迟。
     *
     * @param actualIntervalMs 实际检测间隔，单位毫秒。
     */
    @Synchronized
    fun recordDelay(actualIntervalMs: Long): Unit {
        val delayMs: Long = (actualIntervalMs - expectedIntervalMs).coerceAtLeast(minimumValue = 0L)
        delays += delayMs
        trimOldDelays()
    }

    /**
     * 汇总当前窗口内的 Checktime 延迟证据。
     *
     * @return 包含最大延迟、严重延迟次数和最近样本的摘要。
     */
    @Synchronized
    fun summary(): ChecktimeSummary {
        return ChecktimeSummary(
            maxDelayMs = delays.maxOrNull() ?: 0L,
            severeDelayCount = delays.count { delayMs: Long -> delayMs >= severeDelayMs },
            recentDelayMs = delays.toList(),
        )
    }

    // 保持固定窗口，避免后台线程长时间运行后累积无限样本。
    private fun trimOldDelays(): Unit {
        while (delays.size > MAX_RECENT_DELAY_COUNT) {
            delays.removeAt(index = 0)
        }
    }

    private companion object {
        /**
         * 单次报告最多保留的 Checktime 延迟样本数。
         */
        private const val MAX_RECENT_DELAY_COUNT: Int = 20
    }
}
