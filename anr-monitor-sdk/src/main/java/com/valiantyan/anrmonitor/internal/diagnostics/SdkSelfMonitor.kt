package com.valiantyan.anrmonitor.internal.diagnostics

/**
 * SDK 自监控指标快照。
 *
 * @property name 指标名，使用稳定 snake_case 便于服务端聚合。
 * @property count 记录次数。
 * @property totalValue 累计值，计数类指标和 [count] 保持一致。
 * @property lastValue 最近一次记录值。
 */
data class SdkMetric(
    val name: String,
    val count: Long,
    val totalValue: Long = count,
    val lastValue: Long = count,
)

/**
 * SDK 内部轻量自监控器，用于记录采集、写入和上报治理链路的健康度。
 */
class SdkSelfMonitor {
    // 进程内指标聚合状态，由 [Synchronized] 保护。
    private val metrics: MutableMap<String, MutableSdkMetric> = mutableMapOf()

    /**
     * 递增计数类指标。
     *
     * @param name 指标名。
     */
    @Synchronized
    fun increment(name: String): Unit {
        recordValue(
            name = name,
            value = 1L,
        )
    }

    /**
     * 记录耗时类指标。
     *
     * @param name 指标名。
     * @param costMs 本次耗时，负值会归一化为 0。
     */
    @Synchronized
    fun recordCost(
        name: String,
        costMs: Long,
    ): Unit {
        recordValue(
            name = name,
            value = costMs.coerceAtLeast(minimumValue = 0L),
        )
    }

    /**
     * 输出完整指标快照，按指标名排序保证 JSON 稳定。
     *
     * @return 当前聚合指标列表。
     */
    @Synchronized
    fun snapshot(): List<SdkMetric> {
        return metrics.values
            .map { metric: MutableSdkMetric -> metric.toSnapshot() }
            .sortedBy { metric: SdkMetric -> metric.name }
    }

    /**
     * 输出只包含计数的快照，适合报告诊断字段保持轻量。
     *
     * @return 指标名到次数的映射。
     */
    @Synchronized
    fun snapshotCounters(): Map<String, Long> {
        return snapshot().associate { metric: SdkMetric ->
            metric.name to metric.count
        }
    }

    // 聚合单次指标值，计数、累计值和最近值一起更新。
    private fun recordValue(
        name: String,
        value: Long,
    ): Unit {
        val metric: MutableSdkMetric = metrics.getOrPut(key = name) {
            MutableSdkMetric(name = name)
        }
        metric.count += 1L
        metric.totalValue += value
        metric.lastValue = value
    }

    /**
     * 可变聚合态只在 [SdkSelfMonitor] 内部使用。
     */
    private data class MutableSdkMetric(
        val name: String,
        var count: Long = 0L,
        var totalValue: Long = 0L,
        var lastValue: Long = 0L,
    ) {
        /**
         * 转换为不可变快照，避免外部修改内部聚合态。
         */
        fun toSnapshot(): SdkMetric {
            return SdkMetric(
                name = name,
                count = count,
                totalValue = totalValue,
                lastValue = lastValue,
            )
        }
    }
}
