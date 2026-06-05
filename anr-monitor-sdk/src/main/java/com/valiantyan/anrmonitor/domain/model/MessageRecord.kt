package com.valiantyan.anrmonitor.domain.model

/**
 * 主线程消息记录类型，支持当前消息、历史消息和累计短消息等证据形态。
 */
enum class MessageRecordKind {
    /**
     * 触发疑似 ANR 时正在执行的消息。
     */
    CURRENT,

    /**
     * 疑似 ANR 前已经完成的历史消息。
     */
    HISTORY,

    /**
     * 被归并后的短消息累计记录。
     */
    AGGREGATED,

    /**
     * 主线程进入空闲或 native poll 的观察记录。
     */
    IDLE,

    /**
     * ActivityThread.H 关键组件消息。
     */
    COMPONENT,
}

/**
 * 主线程消息时间线中的单条记录。
 *
 * @property seq 单调递增序号，用于恢复消息顺序。
 * @property kind 消息证据类型。
 * @property messageType 解析后的消息类型名称。
 * @property what Handler 消息 what，未知时为空。
 * @property targetClass Handler target 类名，已按隐私策略处理。
 * @property callbackClass Runnable callback 类名，未知时为空。
 * @property isCriticalComponent 是否属于输入、广播、服务或生命周期等关键组件消息。
 * @property startUptimeMs dispatch 开始 uptime。
 * @property endUptimeMs dispatch 结束 uptime，当前未完成消息可为空。
 * @property wallMs wall time 耗时。
 * @property cpuMs 主线程 CPU 耗时。
 * @property count 归并记录中的消息数量。
 * @property sampleStackIds 关联的慢消息栈采样 ID 列表。
 */
data class MessageRecord(
    val seq: Long,
    val kind: MessageRecordKind,
    val messageType: String,
    val what: Int?,
    val targetClass: String,
    val callbackClass: String?,
    val isCriticalComponent: Boolean,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val wallMs: Long,
    val cpuMs: Long,
    val count: Int = 1,
    val sampleStackIds: List<String> = emptyList(),
)
