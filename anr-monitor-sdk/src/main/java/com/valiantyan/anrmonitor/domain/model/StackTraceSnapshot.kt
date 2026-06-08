package com.valiantyan.anrmonitor.domain.model

/**
 * 线程栈快照。
 *
 * @property stackId 栈内容的稳定 ID，用于报告去重和采样聚合。
 * @property threadName 线程名称。
 * @property frames 脱敏后的栈帧列表。
 */
data class StackTraceSnapshot(
    val stackId: String,
    val threadName: String,
    val frames: List<String>,
)

/**
 * 慢消息期间采集到的主线程栈样本。
 *
 * @property stackId 栈帧内容生成的稳定 ID。
 * @property frames 本次采样代表的主线程栈帧。
 * @property hitCount 同一 [stackId] 在当前消息内命中的次数。
 */
data class StackSampleRecord(
    val stackId: String,
    val frames: List<String>,
    val hitCount: Int,
)
