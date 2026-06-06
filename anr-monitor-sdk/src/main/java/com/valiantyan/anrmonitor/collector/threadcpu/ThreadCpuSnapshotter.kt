package com.valiantyan.anrmonitor.collector.threadcpu

import android.system.Os
import android.system.OsConstants
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import java.io.File

/**
 * `/proc/self/task` 中单个线程的 CPU 原始读数。
 *
 * @property tid Linux 线程 ID。
 * @property threadName 线程名。
 * @property userMs 用户态 CPU 时间，单位毫秒。
 * @property systemMs 内核态 CPU 时间，单位毫秒。
 */
data class ThreadCpuStat(
    val tid: Int,
    val threadName: String,
    val userMs: Long,
    val systemMs: Long,
)

/**
 * 线程 CPU 快照器，用于在 ANR 现场输出进程内 CPU TopN 资源证据。
 *
 * @param statReader 线程 CPU 原始读数提供方，测试可注入确定性数据。
 */
class ThreadCpuSnapshotter(
    private val statReader: () -> List<ThreadCpuStat> = ProcSelfTaskStatReader()::readThreadStats,
) {
    /**
     * 采集并按 CPU 总消耗降序输出 TopN 线程。
     *
     * @param maxCount 最多返回的线程数量，小于等于 0 时返回空列表。
     * @return 按 CPU 消耗排序后的线程记录。
     */
    fun captureTopThreads(maxCount: Int): List<ThreadCpuRecord> {
        if (maxCount <= 0) {
            return emptyList()
        }
        return runCatching {
            statReader()
                .map { stat: ThreadCpuStat -> stat.toRecord() }
                .sortedByDescending { record: ThreadCpuRecord -> record.totalCpuMs }
                .take(n = maxCount)
        }.getOrElse {
            emptyList()
        }
    }

    // 将原始 user/system 读数合并成报告模型，避免领域层依赖采集细节。
    private fun ThreadCpuStat.toRecord(): ThreadCpuRecord {
        return ThreadCpuRecord(
            tid = tid,
            threadName = threadName,
            totalCpuMs = userMs + systemMs,
        )
    }
}

/**
 * 进程内线程 stat 读取器，只访问当前进程 `/proc/self/task`，避免跨进程权限风险。
 *
 * @param taskDirectory 当前进程线程目录。
 * @param clockTicksPerSecondProvider 系统时钟 tick 提供方。
 */
private class ProcSelfTaskStatReader(
    private val taskDirectory: File = File("/proc/self/task"),
    private val clockTicksPerSecondProvider: () -> Long = ::readClockTicksPerSecond,
) {
    /**
     * 读取当前进程所有线程 CPU stat；无法读取的线程会被跳过。
     *
     * @return 可解析成功的线程 CPU 读数列表。
     */
    fun readThreadStats(): List<ThreadCpuStat> {
        val taskFiles: Array<File> = taskDirectory.listFiles() ?: return emptyList()
        val clockTicksPerSecond: Long = clockTicksPerSecondProvider().coerceAtLeast(minimumValue = 1L)
        return taskFiles.mapNotNull { taskFile: File ->
            readThreadStat(
                taskFile = taskFile,
                clockTicksPerSecond = clockTicksPerSecond,
            )
        }
    }

    // 读取单个线程的 stat 文件并转换为毫秒，失败时跳过该线程。
    private fun readThreadStat(
        taskFile: File,
        clockTicksPerSecond: Long,
    ): ThreadCpuStat? {
        val tid: Int = taskFile.name.toIntOrNull() ?: return null
        val statFile = File(taskFile, "stat")
        val statLine: String = runCatching { statFile.readText() }.getOrNull() ?: return null
        return parseThreadStat(
            tid = tid,
            statLine = statLine,
            clockTicksPerSecond = clockTicksPerSecond,
        )
    }

    /**
     * 解析 Linux stat 格式；线程名可能包含空格，因此必须通过最后一个右括号定位后续字段。
     *
     * @param tid Linux 线程 ID。
     * @param statLine stat 文件单行文本。
     * @param clockTicksPerSecond 每秒 tick 数。
     * @return 解析成功的线程 CPU 读数，格式异常时返回 null。
     */
    private fun parseThreadStat(
        tid: Int,
        statLine: String,
        clockTicksPerSecond: Long,
    ): ThreadCpuStat? {
        val nameStart: Int = statLine.indexOf(char = '(')
        val nameEnd: Int = statLine.lastIndexOf(char = ')')
        if (nameStart < 0 || nameEnd <= nameStart) {
            return null
        }
        val fields: List<String> = statLine.substring(startIndex = nameEnd + 1).trim().split(regex = WHITE_SPACE)
        val userTicks: Long = fields.getOrNull(index = USER_TICKS_INDEX)?.toLongOrNull() ?: return null
        val systemTicks: Long = fields.getOrNull(index = SYSTEM_TICKS_INDEX)?.toLongOrNull() ?: return null
        return ThreadCpuStat(
            tid = tid,
            threadName = statLine.substring(startIndex = nameStart + 1, endIndex = nameEnd),
            userMs = ticksToMillis(
                ticks = userTicks,
                clockTicksPerSecond = clockTicksPerSecond,
            ),
            systemMs = ticksToMillis(
                ticks = systemTicks,
                clockTicksPerSecond = clockTicksPerSecond,
            ),
        )
    }

    // 将 Linux CPU tick 转成毫秒，统一报告单位。
    private fun ticksToMillis(
        ticks: Long,
        clockTicksPerSecond: Long,
    ): Long {
        return ticks * MILLIS_PER_SECOND / clockTicksPerSecond
    }

    private companion object {
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

// 读取系统 CPU tick 频率；Android 受限时使用常见的 100Hz 作为降级值。
private fun readClockTicksPerSecond(): Long {
    return runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(defaultValue = 100L)
}
