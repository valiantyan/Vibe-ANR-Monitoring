package com.valiantyan.anrmonitor.domain.model

/**
 * Binder 和跨进程阻塞疑似证据快照。
 *
 * @property available 证据采集或分类是否可用。
 * @property suspected 是否疑似 Binder/跨进程阻塞，不能等同于确认死锁。
 * @property mainThreadInBinder 主线程是否停在 Binder transact 相关栈。
 * @property binderThreadWaitsMain Binder 线程是否出现等待主线程或锁等待迹象。
 * @property mainThreadEvidence 主线程命中的 Binder 栈帧。
 * @property binderThreadEvidence Binder 线程命中的等待栈帧。
 * @property failureReason 不可用时的失败或降级原因。
 */
data class BinderBlockSnapshot(
    val available: Boolean,
    val suspected: Boolean,
    val mainThreadInBinder: Boolean,
    val binderThreadWaitsMain: Boolean,
    val mainThreadEvidence: List<String>,
    val binderThreadEvidence: List<String>,
    val failureReason: String?,
) {
    /**
     * Binder 疑似证据辅助构造器，统一表达空证据和降级状态。
     */
    companion object {
        /**
         * 创建可用但未命中疑似阻塞的快照。
         *
         * @return 可用于报告协议占位的空快照。
         */
        fun empty(): BinderBlockSnapshot {
            return BinderBlockSnapshot(
                available = true,
                suspected = false,
                mainThreadInBinder = false,
                binderThreadWaitsMain = false,
                mainThreadEvidence = emptyList(),
                binderThreadEvidence = emptyList(),
                failureReason = null,
            )
        }

        /**
         * 创建不可用快照，保留原因以便评估证据缺口。
         *
         * @param reason 采集关闭或失败原因。
         * @return 不包含疑似结论的不可用快照。
         */
        fun unavailable(reason: String): BinderBlockSnapshot {
            return BinderBlockSnapshot(
                available = false,
                suspected = false,
                mainThreadInBinder = false,
                binderThreadWaitsMain = false,
                mainThreadEvidence = emptyList(),
                binderThreadEvidence = emptyList(),
                failureReason = reason,
            )
        }
    }
}
