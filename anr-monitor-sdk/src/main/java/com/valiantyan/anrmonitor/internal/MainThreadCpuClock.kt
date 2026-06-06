package com.valiantyan.anrmonitor.internal

import com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollector
import com.valiantyan.anrmonitor.core.clock.ThreadCpuClock

/**
 * 主线程 dispatch CPU 时间源适配器。
 */
internal class MainThreadCpuClock : MainLooperTimelineCollector.CpuClock {
    // Android 线程 CPU 时间源。
    private val threadCpuClock: ThreadCpuClock = ThreadCpuClock()

    /**
     * 返回当前线程 CPU 毫秒值，供 Looper dispatch 前后差值计算。
     */
    override fun currentThreadCpuMs(): Long {
        return threadCpuClock.currentThreadCpuMs()
    }
}
