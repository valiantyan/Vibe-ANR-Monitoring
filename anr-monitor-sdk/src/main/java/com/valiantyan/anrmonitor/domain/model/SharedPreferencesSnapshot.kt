package com.valiantyan.anrmonitor.domain.model

/**
 * SharedPreferences 文件健康度和运行期操作证据。
 *
 * @property available 本次 SP 专项证据是否可用。
 * @property topFiles 按风险排序后的 SP 文件列表。
 * @property recentOperations 最近通过 SDK 包装入口观测到的 SP 操作。
 * @property pendingFinisherCount 当前进程内观测到的 pending finisher 数量。
 * @property queuedWorkBypass 当前 QueuedWork 绕过治理状态。
 * @property failureReason 采集不可用时的明确原因。
 */
data class SharedPreferencesSnapshot(
    val available: Boolean,
    val topFiles: List<SharedPreferencesFileStat>,
    val recentOperations: List<SharedPreferencesOperationRecord>,
    val pendingFinisherCount: Int?,
    val queuedWorkBypass: QueuedWorkBypassState,
    val failureReason: String? = null,
) {
    /**
     * SharedPreferences 快照辅助构造器。
     */
    companion object {
        /**
         * 创建空但可用的 SP 快照，用于没有发现 SP 风险的场景。
         *
         * @return 空 SP 快照。
         */
        fun empty(): SharedPreferencesSnapshot {
            return SharedPreferencesSnapshot(
                available = true,
                topFiles = emptyList(),
                recentOperations = emptyList(),
                pendingFinisherCount = 0,
                queuedWorkBypass = QueuedWorkBypassState.disabled(),
                failureReason = null,
            )
        }

        /**
         * 创建不可用 SP 快照，用于权限、开关或文件读取失败场景。
         *
         * @param reason 证据缺失原因。
         * @return 带失败原因的 SP 快照。
         */
        fun unavailable(reason: String): SharedPreferencesSnapshot {
            return SharedPreferencesSnapshot(
                available = false,
                topFiles = emptyList(),
                recentOperations = emptyList(),
                pendingFinisherCount = null,
                queuedWorkBypass = QueuedWorkBypassState.disabled(),
                failureReason = reason,
            )
        }
    }
}

/**
 * 单个 SharedPreferences XML 文件的健康度指标。
 *
 * @property fileName SP 文件名。
 * @property sizeBytes 文件大小。
 * @property keyCount XML 内 key 数量。
 * @property firstLoadCostMs SDK 包装入口观测到的首次加载耗时。
 * @property applyCount SDK 包装入口观测到的 apply 次数。
 * @property commitCount SDK 包装入口观测到的 commit 次数。
 * @property lastWriteCostMs 最近一次写入入口耗时。
 */
data class SharedPreferencesFileStat(
    val fileName: String,
    val sizeBytes: Long,
    val keyCount: Int,
    val firstLoadCostMs: Long? = null,
    val applyCount: Int = 0,
    val commitCount: Int = 0,
    val lastWriteCostMs: Long? = null,
)

/**
 * SharedPreferences 操作类型，区分首次加载和两类写入等待链路。
 */
enum class SharedPreferencesOperationType {
    /**
     * 首次加载或显式打开入口。
     */
    LOAD,

    /**
     * 异步 apply 写入入口，生命周期边界可能被 [android.app.QueuedWork] 等待。
     */
    APPLY,

    /**
     * 同步 commit 写入入口。
     */
    COMMIT,
}

/**
 * SDK 包装入口记录到的一次 SharedPreferences 操作。
 *
 * @property fileName SP 文件名。
 * @property operationType 操作类型。
 * @property costMs 操作入口耗时。
 * @property timestampUptimeMs 操作结束时 uptime。
 * @property threadName 操作发生线程。
 * @property stackFrames 调用栈证据。
 * @property success 操作是否成功。
 * @property pendingFinisherCount 操作结束后观测到的 pending finisher 数量。
 */
data class SharedPreferencesOperationRecord(
    val fileName: String,
    val operationType: SharedPreferencesOperationType,
    val costMs: Long,
    val timestampUptimeMs: Long,
    val threadName: String,
    val stackFrames: List<String>,
    val success: Boolean,
    val pendingFinisherCount: Int?,
)

/**
 * QueuedWork 绕过治理状态，默认关闭并保留白名单、黑名单和回滚信息。
 *
 * @property enabled 是否允许执行绕过。
 * @property allowedFiles 文件白名单。
 * @property blockedFiles 文件黑名单。
 * @property rollbackEnabled 是否处于回滚状态。
 */
data class QueuedWorkBypassState(
    val enabled: Boolean,
    val allowedFiles: Set<String>,
    val blockedFiles: Set<String>,
    val rollbackEnabled: Boolean,
) {
    /**
     * QueuedWork 绕过状态辅助构造器。
     */
    companion object {
        /**
         * 创建默认关闭状态，避免误改变系统等待语义。
         *
         * @return 默认关闭的绕过状态。
         */
        fun disabled(): QueuedWorkBypassState {
            return QueuedWorkBypassState(
                enabled = false,
                allowedFiles = emptySet(),
                blockedFiles = emptySet(),
                rollbackEnabled = false,
            )
        }
    }
}
