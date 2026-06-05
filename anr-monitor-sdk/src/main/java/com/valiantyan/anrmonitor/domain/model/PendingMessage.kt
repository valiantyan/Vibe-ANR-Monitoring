package com.valiantyan.anrmonitor.domain.model

/**
 * 主线程 Pending 队列中的消息快照。
 *
 * @property index 队列中的顺序位置，0 表示队头。
 * @property whenUptimeMs 消息计划执行时间。
 * @property delayMs 相对采集时刻的剩余延迟。
 * @property blockedMs 已经被队头或屏障阻挡的时间。
 * @property what Handler 消息 what，未知时为空。
 * @property arg1 消息 arg1。
 * @property arg2 消息 arg2。
 * @property targetClass Handler target 类名，屏障消息通常为空。
 * @property callbackClass Runnable callback 类名，未知时为空。
 * @property objClass obj 对象类名，未知时为空。
 * @property isAsynchronous 是否为异步消息，反射失败时为空。
 * @property isBarrierLike 是否疑似同步屏障消息。
 * @property isCriticalComponent 是否属于关键组件消息。
 */
data class PendingMessage(
    val index: Int,
    val whenUptimeMs: Long,
    val delayMs: Long,
    val blockedMs: Long,
    val what: Int?,
    val arg1: Int,
    val arg2: Int,
    val targetClass: String?,
    val callbackClass: String?,
    val objClass: String?,
    val isAsynchronous: Boolean?,
    val isBarrierLike: Boolean,
    val isCriticalComponent: Boolean,
)
