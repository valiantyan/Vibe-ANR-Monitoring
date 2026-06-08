package com.valiantyan.anrmonitor.collector.sharedprefs

import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationRecord
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType
import java.util.ArrayDeque

/**
 * SharedPreferences 包装入口的轻量级进程内记录器。
 *
 * @param maxRecords 最多保留的最近操作数量。
 */
class SharedPreferencesOperationRecorder(
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
) {
    // 同步锁保护 [records] 和 [pendingFinisherCount] 的一致视图。
    private val lock: Any = Any()

    // 最近 SP 操作环形队列，避免高频写入放大内存占用。
    private val records: ArrayDeque<SharedPreferencesOperationRecord> = ArrayDeque()

    // wrapper 观测到的 pending finisher 数量，无法读取系统队列时作为近似证据。
    private var pendingFinisherCount: Int = 0

    /**
     * 记录一次 SP 操作，超出容量时丢弃最旧记录。
     *
     * @param record 待记录的 SP 操作。
     */
    fun record(record: SharedPreferencesOperationRecord): Unit {
        synchronized(lock) {
            records.addLast(record)
            while (records.size > maxRecords) {
                records.removeFirst()
            }
        }
    }

    /**
     * 记录一次 apply 触发的 pending finisher，返回更新后的观测数量。
     *
     * @return 当前观测到的 pending finisher 数量。
     */
    fun recordPendingFinisherScheduled(): Int {
        synchronized(lock) {
            pendingFinisherCount += 1
            return pendingFinisherCount
        }
    }

    /**
     * 结束 wrapper 对一次 pending finisher 的观测窗口，避免把历史 apply 次数当作当前 pending 数。
     *
     * @return 更新后的 pending finisher 近似数量。
     */
    fun recordPendingFinisherObserved(): Int {
        synchronized(lock) {
            pendingFinisherCount = (pendingFinisherCount - 1).coerceAtLeast(minimumValue = 0)
            return pendingFinisherCount
        }
    }

    /**
     * 读取最近 SP 操作，保持时间顺序。
     *
     * @param maxCount 最大返回数量。
     * @return 最近操作列表。
     */
    fun recentOperations(maxCount: Int): List<SharedPreferencesOperationRecord> {
        synchronized(lock) {
            return records.toList().takeLast(n = maxCount)
        }
    }

    /**
     * 读取当前观测到的 pending finisher 数量。
     *
     * @return pending finisher 数量。
     */
    fun pendingFinisherCount(): Int {
        synchronized(lock) {
            return pendingFinisherCount
        }
    }

    /**
     * 测试或卸载场景清空记录，避免跨会话污染。
     */
    fun clear(): Unit {
        synchronized(lock) {
            records.clear()
            pendingFinisherCount = 0
        }
    }

    /**
     * 进程级默认记录器，供公开包装入口和运行时扫描器共享证据。
     */
    companion object Global {
        /**
         * 默认最多保留 64 条 SP 操作。
         */
        private const val DEFAULT_MAX_RECORDS: Int = 64

        /**
         * 全局 SP 操作记录器。
         */
        val global: SharedPreferencesOperationRecorder = SharedPreferencesOperationRecorder()
    }
}
