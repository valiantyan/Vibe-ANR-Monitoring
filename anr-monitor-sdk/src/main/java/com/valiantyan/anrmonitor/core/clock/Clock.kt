package com.valiantyan.anrmonitor.core.clock

/**
 * SDK 内部时间源接口，用于让纯 Kotlin 状态机和 Android 运行时采集解耦。
 */
interface Clock {
    /**
     * 返回当前 uptime 毫秒值，后续 Watchdog 与消息耗时计算都以该时间为基准。
     *
     * @return 当前单调递增 uptime 毫秒值。
     */
    fun uptimeMillis(): Long
}
