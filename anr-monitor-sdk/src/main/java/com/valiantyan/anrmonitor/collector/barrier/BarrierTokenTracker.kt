package com.valiantyan.anrmonitor.collector.barrier

import com.valiantyan.anrmonitor.domain.model.BarrierTokenRecord

/**
 * Barrier token 生命周期追踪器，供高风险 hook 或灰度入口写入配对证据。
 */
class BarrierTokenTracker {
    // 保护 token 状态，避免 hook 回调和 Watchdog 采集并发读写。
    private val lock: Any = Any()

    // 当前仍未移除的 token。
    private val activeTokens: MutableMap<Int, BarrierTokenRecord> = mutableMapOf()

    // 最近已完成配对的 token，便于评审确认正常 remove 行为。
    private val completedTokens: MutableList<BarrierTokenRecord> = mutableListOf()

    /**
     * 记录一次 [postSyncBarrier] 插入事件。
     *
     * @param token 系统返回的 Barrier token。
     * @param uptimeMs 插入时的 uptime。
     * @param stack 插入调用栈，调用方应先完成脱敏。
     */
    fun onPostBarrier(
        token: Int,
        uptimeMs: Long,
        stack: List<String>,
    ): Unit {
        synchronized(lock) {
            activeTokens[token] = BarrierTokenRecord(
                token = token,
                postUptimeMs = uptimeMs,
                removeUptimeMs = null,
                aliveMs = 0L,
                postStack = stack,
            )
        }
    }

    /**
     * 记录一次 [removeSyncBarrier] 事件，缺失的 token 按幂等无操作处理。
     *
     * @param token 需要移除的 Barrier token。
     * @param uptimeMs 移除时的 uptime，未知时为空。
     */
    fun onRemoveBarrier(
        token: Int,
        uptimeMs: Long? = null,
    ): Unit {
        synchronized(lock) {
            val activeRecord: BarrierTokenRecord = activeTokens.remove(key = token) ?: return
            completedTokens += activeRecord.copy(
                removeUptimeMs = uptimeMs,
                aliveMs = uptimeMs?.let { value: Long ->
                    (value - activeRecord.postUptimeMs).coerceAtLeast(minimumValue = 0L)
                } ?: activeRecord.aliveMs,
            )
            trimCompletedTokens()
        }
    }

    /**
     * 查找超过阈值仍未移除的 token。
     *
     * @param nowUptimeMs 当前采集 uptime。
     * @param thresholdMs 判定 token 卡住的最小时长。
     * @return 按存活时间从长到短排列的卡住 token。
     */
    fun findStuckTokens(
        nowUptimeMs: Long,
        thresholdMs: Long,
    ): List<BarrierTokenRecord> {
        synchronized(lock) {
            return activeTokens.values
                .map { record: BarrierTokenRecord ->
                    record.copy(
                        aliveMs = (nowUptimeMs - record.postUptimeMs).coerceAtLeast(minimumValue = 0L),
                    )
                }
                .filter { record: BarrierTokenRecord -> record.aliveMs >= thresholdMs }
                .sortedByDescending { record: BarrierTokenRecord -> record.aliveMs }
        }
    }

    /**
     * 返回最近 token 生命周期记录，用于验证正常配对和报告审计。
     *
     * @param maxRecords 最大返回数量。
     * @return 最近完成的 token 记录。
     */
    fun recentRecords(maxRecords: Int): List<BarrierTokenRecord> {
        synchronized(lock) {
            val safeMaxRecords: Int = maxRecords.coerceAtLeast(minimumValue = 0)
            return completedTokens.takeLast(n = safeMaxRecords)
        }
    }

    // 控制 completed 队列大小，避免长期运行时持续增长。
    private fun trimCompletedTokens(): Unit {
        if (completedTokens.size <= MAX_COMPLETED_RECORDS) {
            return
        }
        val removeCount: Int = completedTokens.size - MAX_COMPLETED_RECORDS
        repeat(times = removeCount) {
            completedTokens.removeAt(index = 0)
        }
    }

    companion object {
        /**
         * 进程内共享追踪器，供运行时报告和未来 hook 入口复用。
         */
        val global: BarrierTokenTracker = BarrierTokenTracker()

        /**
         * 已配对 token 的内存保留上限。
         */
        private const val MAX_COMPLETED_RECORDS: Int = 100
    }
}
