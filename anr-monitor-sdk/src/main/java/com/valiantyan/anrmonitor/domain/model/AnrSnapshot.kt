package com.valiantyan.anrmonitor.domain.model

/**
 * 疑似或确认 ANR 的现场快照。
 *
 * @property eventId 事件唯一 ID。
 * @property eventType 事件阶段。
 * @property appId 宿主应用标识。
 * @property environment 运行环境标识。
 * @property timeUptimeMs 采集发生时的 uptime。
 * @property currentMessage 当前正在执行的主线程消息。
 * @property historyMessages 疑似 ANR 前的历史消息窗口。
 * @property pendingQueue Pending 队列快照。
 * @property mainThreadStack 主线程 Java 栈快照。
 */
data class AnrSnapshot(
    val eventId: String,
    val eventType: AnrEventType,
    val appId: String,
    val environment: String,
    val timeUptimeMs: Long,
    val currentMessage: MessageRecord?,
    val historyMessages: List<MessageRecord>,
    val pendingQueue: PendingQueueSnapshot,
    val mainThreadStack: StackTraceSnapshot,
)
