package com.valiantyan.anrmonitor.collector.sharedprefs

import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationRecord
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType

/**
 * 记录 SharedPreferences 操作的公共小工具，供打开入口和 editor 共享。
 */
internal object SharedPreferencesOperationReporter {
    /**
     * 记录一次 SP 操作，统一计算耗时、线程、调用栈和 pending finisher。
     *
     * @param fileName SP 文件名。
     * @param operationType 操作类型。
     * @param startUptimeMs 操作开始时间。
     * @param success 操作是否成功。
     * @param recorder 操作记录器。
     * @param clock uptime 时间源。
     * @param threadNameProvider 线程名读取器。
     * @param stackTraceProvider 调用栈读取器。
     * @param pendingFinisherReader pending finisher 数量读取器。
     */
    fun recordOperation(
        fileName: String,
        operationType: SharedPreferencesOperationType,
        startUptimeMs: Long,
        success: Boolean,
        recorder: SharedPreferencesOperationRecorder,
        clock: Clock,
        threadNameProvider: () -> String,
        stackTraceProvider: () -> List<String>,
        pendingFinisherReader: () -> Int?,
    ): Unit {
        val endUptimeMs: Long = clock.uptimeMillis()
        recorder.record(
            record = SharedPreferencesOperationRecord(
                fileName = fileName,
                operationType = operationType,
                costMs = endUptimeMs - startUptimeMs,
                timestampUptimeMs = endUptimeMs,
                threadName = threadNameProvider(),
                stackFrames = stackTraceProvider(),
                success = success,
                pendingFinisherCount = pendingFinisherReader(),
            ),
        )
    }

    /**
     * 读取当前线程名，作为默认操作现场。
     *
     * @return 当前线程名。
     */
    fun currentThreadName(): String = Thread.currentThread().name

    /**
     * 读取当前调用栈，跳过 wrapper 内部栈帧以便定位宿主调用点。
     *
     * @return 当前调用栈字符串列表。
     */
    fun currentStackFrames(): List<String> {
        return Thread.currentThread().stackTrace
            .drop(n = DEFAULT_STACK_SKIP)
            .map { frame: StackTraceElement -> frame.toString() }
    }

    /**
     * 默认跳过 Thread 和 wrapper 内部栈帧数量。
     */
    private const val DEFAULT_STACK_SKIP: Int = 4
}
