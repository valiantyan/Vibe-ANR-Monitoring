package com.valiantyan.anrmonitor.domain.model

/**
 * 系统确认 ANR 信息快照，来自 [android.app.ActivityManager.ProcessErrorStateInfo]。
 *
 * @property available 系统接口是否可读；未确认 ANR 时仍可为 true。
 * @property isConfirmedAnr 当前进程是否已被系统标记为 ANR。
 * @property anrType 从系统消息推断出的 ANR 组件类型。
 * @property shortMsg 系统 shortMsg，通常是较短的 ANR 描述。
 * @property longMsg 系统 longMsg，通常包含组件类型和等待原因。
 * @property condition 系统错误状态条件值。
 * @property failureReason 系统接口读取失败时的原因，未确认不算失败。
 */
data class AnrInfoSnapshot(
    val available: Boolean,
    val isConfirmedAnr: Boolean,
    val anrType: AnrType,
    val shortMsg: String?,
    val longMsg: String?,
    val condition: Int?,
    val failureReason: String? = null,
) {
    /**
     * 系统确认信息辅助构造器。
     */
    companion object {
        /**
         * 表示系统接口可读，但当前进程尚未被系统确认 ANR。
         *
         * @return 未确认 ANR 的默认快照。
         */
        fun unconfirmed(): AnrInfoSnapshot {
            return AnrInfoSnapshot(
                available = true,
                isConfirmedAnr = false,
                anrType = AnrType.UNKNOWN,
                shortMsg = null,
                longMsg = null,
                condition = null,
                failureReason = null,
            )
        }

        /**
         * 表示系统接口不可读，报告需要展示证据缺口。
         *
         * @param reason 读取失败原因。
         * @return 不可用的系统 ANR 信息快照。
         */
        fun unavailable(reason: String): AnrInfoSnapshot {
            return AnrInfoSnapshot(
                available = false,
                isConfirmedAnr = false,
                anrType = AnrType.UNKNOWN,
                shortMsg = null,
                longMsg = null,
                condition = null,
                failureReason = reason,
            )
        }
    }
}
