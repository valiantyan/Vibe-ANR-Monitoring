package com.valiantyan.anrmonitor.core.clock

import android.os.Debug

/**
 * 线程 CPU 时间源，用于区分主线程 wall time 阻塞和真实 CPU 消耗。
 */
class ThreadCpuClock {
    /**
     * 返回当前线程累计 CPU 毫秒值，用于消息 dispatch 前后差值计算。
     *
     * @return 当前线程 CPU 时间，单位毫秒。
     */
    fun currentThreadCpuMs(): Long {
        return Debug.threadCpuTimeNanos() / NANOS_PER_MILLI
    }

    private companion object {
        /**
         * 纳秒到毫秒的换算比例。
         */
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
