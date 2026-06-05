package com.valiantyan.anrmonitor.domain.model

/**
 * ANR 事件阶段，用于区分 Watchdog 疑似现场和系统确认后的完整报告。
 */
enum class AnrEventType {
    /**
     * Watchdog 已超过阈值但尚未被系统确认为 ANR。
     */
    SUSPECT_ANR,

    /**
     * 系统错误状态或外部信号已确认 ANR。
     */
    CONFIRMED_ANR,
}
