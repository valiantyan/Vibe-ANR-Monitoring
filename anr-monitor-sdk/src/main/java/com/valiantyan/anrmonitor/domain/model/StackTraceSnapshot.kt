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
