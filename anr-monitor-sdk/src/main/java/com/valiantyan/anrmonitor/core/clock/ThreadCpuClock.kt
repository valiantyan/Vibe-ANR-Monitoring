package com.valiantyan.anrmonitor.core.clock

import android.os.Debug
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

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

    /**
     * 返回当前 Linux 线程 ID，用于后续从 Watchdog 线程读取主线程 CPU。
     *
     * @return 当前线程 ID。
     */
    fun currentThreadId(): Int {
        return Process.myTid()
    }

    /**
     * 读取指定线程累计 CPU 毫秒值；系统限制或线程退出时返回 null。
     *
     * @param threadId 目标线程 ID。
     * @return 目标线程 CPU 时间，单位毫秒。
     */
    fun threadCpuMs(threadId: Int): Long? {
        if (threadId <= 0) {
            return null
        }
        return runCatching {
            parseThreadCpuMs(
                statLine = File("/proc/self/task/$threadId/stat").readText(),
                clockTicksPerSecond = readClockTicksPerSecond(),
            )
        }.getOrNull()
    }

    // 解析 Linux stat 中的 utime/stime 字段，线程名可能包含空格，必须从最后一个右括号后切分。
    private fun parseThreadCpuMs(
        statLine: String,
        clockTicksPerSecond: Long,
    ): Long? {
        val nameEnd: Int = statLine.lastIndexOf(char = ')')
        if (nameEnd < 0) {
            return null
        }
        val fields: List<String> = statLine.substring(startIndex = nameEnd + 1).trim().split(regex = WHITE_SPACE)
        val userTicks: Long = fields.getOrNull(index = USER_TICKS_INDEX)?.toLongOrNull() ?: return null
        val systemTicks: Long = fields.getOrNull(index = SYSTEM_TICKS_INDEX)?.toLongOrNull() ?: return null
        return (userTicks + systemTicks) * MILLIS_PER_SECOND / clockTicksPerSecond.coerceAtLeast(minimumValue = 1L)
    }

    // 读取系统 CPU tick 频率，Android 受限时使用常见 100Hz 降级。
    private fun readClockTicksPerSecond(): Long {
        return runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(defaultValue = 100L)
    }

    private companion object {
        /**
         * 纳秒到毫秒的换算比例。
         */
        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /**
         * stat 中 `utime` 相对线程名后字段的索引。
         */
        private const val USER_TICKS_INDEX: Int = 11

        /**
         * stat 中 `stime` 相对线程名后字段的索引。
         */
        private const val SYSTEM_TICKS_INDEX: Int = 12

        /**
         * 秒到毫秒换算值。
         */
        private const val MILLIS_PER_SECOND: Long = 1_000L

        // 连续空白分隔符。
        private val WHITE_SPACE: Regex = "\\s+".toRegex()
    }
}
