package com.valiantyan.anrmonitor.collector.nativepoll

import com.valiantyan.anrmonitor.domain.model.NativePollOnceRecord

/**
 * [nativePollOnce] 调用窗口记录器，记录 timeout、进入退出时间和无限等待次数。
 */
class NativePollOnceMonitor {
    // 保护记录窗口，避免 hook 回调和 Watchdog 采集并发访问。
    private val lock: Any = Any()

    // 已完成的 [nativePollOnce] 调用窗口。
    private val records: MutableList<NativePollOnceRecord> = mutableListOf()

    // 当前仍在执行的 [nativePollOnce] 调用。
    private var inFlightRecord: NativePollOnceRecord? = null

    /**
     * 记录一次已完成调用，适合只能在调用后拿到 timeout 的低风险入口。
     *
     * @param timeoutMillis [nativePollOnce] timeout 参数。
     * @param uptimeMs 记录发生时的 uptime。
     */
    fun record(
        timeoutMillis: Int,
        uptimeMs: Long,
    ): Unit {
        synchronized(lock) {
            records += NativePollOnceRecord(
                timeoutMillis = timeoutMillis,
                enterUptimeMs = uptimeMs,
                exitUptimeMs = uptimeMs,
                durationMs = 0L,
            )
            trimRecords()
        }
    }

    /**
     * 记录进入 [nativePollOnce]，用于高风险 hook 灰度时计算调用持续时间。
     *
     * @param timeoutMillis [nativePollOnce] timeout 参数。
     * @param uptimeMs 进入调用时的 uptime。
     */
    fun onEnter(
        timeoutMillis: Int,
        uptimeMs: Long,
    ): Unit {
        synchronized(lock) {
            inFlightRecord = NativePollOnceRecord(
                timeoutMillis = timeoutMillis,
                enterUptimeMs = uptimeMs,
                exitUptimeMs = null,
                durationMs = null,
            )
        }
    }

    /**
     * 记录退出 [nativePollOnce]，缺少进入事件时按无操作降级。
     *
     * @param uptimeMs 退出调用时的 uptime。
     */
    fun onExit(uptimeMs: Long): Unit {
        synchronized(lock) {
            val activeRecord: NativePollOnceRecord = inFlightRecord ?: return
            records += activeRecord.copy(
                exitUptimeMs = uptimeMs,
                durationMs = (uptimeMs - activeRecord.enterUptimeMs).coerceAtLeast(minimumValue = 0L),
            )
            inFlightRecord = null
            trimRecords()
        }
    }

    /**
     * 返回最近调用窗口，包含仍在执行中的记录。
     *
     * @param maxRecords 最大返回数量。
     * @return 最近 [nativePollOnce] 记录。
     */
    fun recentRecords(maxRecords: Int): List<NativePollOnceRecord> {
        synchronized(lock) {
            val safeMaxRecords: Int = maxRecords.coerceAtLeast(minimumValue = 0)
            val visibleRecords: List<NativePollOnceRecord> = records + listOfNotNull(inFlightRecord)
            return visibleRecords.takeLast(n = safeMaxRecords)
        }
    }

    /**
     * 统计最近窗口里的无限等待次数，用于判断 [timeoutMillis=-1] 是否反复出现。
     *
     * @param maxRecords 参与统计的最近记录数量。
     * @return 最近窗口内无限等待记录数。
     */
    fun countRecentInfiniteWaits(maxRecords: Int): Int {
        return recentRecords(maxRecords = maxRecords).count { record: NativePollOnceRecord ->
            record.isInfiniteWait
        }
    }

    // 控制内存窗口大小，避免高频轮询记录持续增长。
    private fun trimRecords(): Unit {
        if (records.size <= MAX_RECORDS) {
            return
        }
        val removeCount: Int = records.size - MAX_RECORDS
        repeat(times = removeCount) {
            records.removeAt(index = 0)
        }
    }

    companion object {
        /**
         * 进程内共享监控器，供运行时报告和未来 hook 入口复用。
         */
        val global: NativePollOnceMonitor = NativePollOnceMonitor()

        /**
         * 记录窗口上限。
         */
        private const val MAX_RECORDS: Int = 200
    }
}
