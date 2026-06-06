package com.valiantyan.anrmonitor.domain.model

/**
 * 单个线程的 CPU 消耗记录，用于在 ANR 报告中表达进程内资源竞争证据。
 *
 * @property tid Linux 线程 ID。
 * @property threadName 线程名，来自 `/proc/self/task/<tid>/stat` 或测试注入。
 * @property totalCpuMs 用户态和内核态累计 CPU 时间，单位毫秒。
 */
data class ThreadCpuRecord(
    val tid: Int,
    val threadName: String,
    val totalCpuMs: Long,
)
