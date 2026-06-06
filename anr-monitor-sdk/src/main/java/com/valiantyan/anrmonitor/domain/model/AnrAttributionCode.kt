package com.valiantyan.anrmonitor.domain.model

/**
 * 归因主因和辅因编码，覆盖五篇资料中的关键 ANR 模式。
 */
enum class AnrAttributionCode {
    /**
     * 当前正在 dispatch 的消息耗时过长。
     */
    CURRENT_MESSAGE_SLOW,

    /**
     * 历史消息慢，系统 traces 看到的栈不一定是根因。
     */
    HISTORY_MESSAGE_SLOW,

    /**
     * 大量短消息累计占满主线程窗口。
     */
    MESSAGE_STORM,

    /**
     * 同步屏障阻塞同步消息，导致主线程看似空闲但队列无法推进。
     */
    SYNC_BARRIER_STUCK,

    /**
     * SharedPreferences 首次加载等待。
     */
    SP_LOAD_WAIT,

    /**
     * SharedPreferences apply 后在 [android.app.QueuedWork] 等待落盘。
     */
    SP_APPLY_WAIT,

    /**
     * 主线程疑似阻塞在 Binder/跨进程调用，仍需线下 Trace 复核确认。
     */
    BINDER_BLOCK_SUSPECTED,

    /**
     * 证据不足，暂不能给出可信归因。
     */
    UNKNOWN_INSUFFICIENT_EVIDENCE,
}
