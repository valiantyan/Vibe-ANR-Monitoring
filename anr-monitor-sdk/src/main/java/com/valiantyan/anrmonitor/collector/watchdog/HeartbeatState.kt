package com.valiantyan.anrmonitor.collector.watchdog

/**
 * Watchdog 心跳状态机，记录已投递但尚未被主线程处理的心跳。
 *
 * @param timeoutMs 疑似 ANR 超时阈值，单位毫秒。
 */
class HeartbeatState(
    private val timeoutMs: Long,
) {
    // 当前等待主线程消费的心跳序号。
    @Volatile
    private var pendingSeq: Long? = null

    // 当前等待心跳的投递时间，用于计算超时窗口。
    @Volatile
    private var pendingPostedUptimeMs: Long = 0L

    /**
     * 标记后台 Watchdog 已经向主线程投递心跳。
     *
     * @param seq 心跳序号，必须和处理回调里的序号一致才会清除 pending。
     * @param postedUptimeMs 心跳投递时的 uptime 毫秒值。
     */
    @Synchronized
    fun markPosted(
        seq: Long,
        postedUptimeMs: Long,
    ): Unit {
        pendingSeq = seq
        pendingPostedUptimeMs = postedUptimeMs
    }

    /**
     * 标记主线程已处理指定心跳，避免旧回调误清除新的 pending 心跳。
     *
     * @param seq 主线程实际处理的心跳序号。
     */
    @Synchronized
    fun markHandled(seq: Long): Unit {
        if (pendingSeq == seq) {
            pendingSeq = null
        }
    }

    /**
     * 判断是否仍有未被主线程处理的心跳。
     *
     * @return 有 pending 心跳时返回 true。
     */
    fun hasPending(): Boolean {
        return pendingSeq != null
    }

    /**
     * 判断当前 pending 心跳是否已经超过疑似 ANR 阈值。
     *
     * @param nowUptimeMs 当前 uptime 毫秒值。
     * @return pending 心跳存在且耗时达到阈值时返回 true。
     */
    fun isTimedOut(nowUptimeMs: Long): Boolean {
        val seq: Long = pendingSeq ?: return false
        val elapsedMs: Long = nowUptimeMs - pendingPostedUptimeMs
        return seq > 0L && elapsedMs >= timeoutMs
    }
}
