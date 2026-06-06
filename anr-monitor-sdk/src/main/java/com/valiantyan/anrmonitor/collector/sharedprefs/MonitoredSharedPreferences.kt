package com.valiantyan.anrmonitor.collector.sharedprefs

import android.content.SharedPreferences
import com.valiantyan.anrmonitor.core.clock.AndroidClock
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType

/**
 * SharedPreferences 包装入口，用于记录首次加载、apply 和 commit 证据。
 *
 * @param fileName SP 文件名。
 * @param delegate 被包装的真实 [SharedPreferences]。
 * @param recorder 运行期操作记录器。
 * @param clock uptime 时间源。
 * @param threadNameProvider 线程名读取器。
 * @param stackTraceProvider 调用栈读取器。
 * @param pendingFinisherReader pending finisher 数量读取器。
 */
class MonitoredSharedPreferences private constructor(
    private val fileName: String,
    private val delegate: SharedPreferences,
    private val recorder: SharedPreferencesOperationRecorder,
    private val clock: Clock,
    private val threadNameProvider: () -> String,
    private val stackTraceProvider: () -> List<String>,
    private val pendingFinisherReader: () -> Int?,
) : SharedPreferences by delegate {
    /**
     * 创建被监控的编辑器，记录 apply 和 commit 写入成本。
     */
    override fun edit(): SharedPreferences.Editor {
        return MonitoredSharedPreferencesEditor(
            fileName = fileName,
            delegate = delegate.edit(),
            recorder = recorder,
            clock = clock,
            threadNameProvider = threadNameProvider,
            stackTraceProvider = stackTraceProvider,
            pendingFinisherReader = pendingFinisherReader,
        )
    }

    /**
     * SharedPreferences 包装入口辅助方法。
     */
    companion object {
        /**
         * 包装打开动作并记录首次加载耗时。
         *
         * @param fileName SP 文件名。
         * @param opener 原始 SP 打开动作。
         * @param recorder 运行期操作记录器。
         * @param clock uptime 时间源。
         * @param threadNameProvider 线程名读取器。
         * @param stackTraceProvider 调用栈读取器。
         * @param pendingFinisherReader pending finisher 数量读取器。
         * @return 被监控的 [SharedPreferences]。
         */
        fun open(
            fileName: String,
            opener: () -> SharedPreferences,
            recorder: SharedPreferencesOperationRecorder = SharedPreferencesOperationRecorder.global,
            clock: Clock = AndroidClock(),
            threadNameProvider: () -> String = SharedPreferencesOperationReporter::currentThreadName,
            stackTraceProvider: () -> List<String> = SharedPreferencesOperationReporter::currentStackFrames,
            pendingFinisherReader: () -> Int? = recorder::pendingFinisherCount,
        ): SharedPreferences {
            val startUptimeMs: Long = clock.uptimeMillis()
            return try {
                val preferences: SharedPreferences = opener()
                recordOpen(
                    fileName = fileName,
                    startUptimeMs = startUptimeMs,
                    success = true,
                    recorder = recorder,
                    clock = clock,
                    threadNameProvider = threadNameProvider,
                    stackTraceProvider = stackTraceProvider,
                    pendingFinisherReader = pendingFinisherReader,
                )
                wrap(
                    fileName = fileName,
                    delegate = preferences,
                    recorder = recorder,
                    clock = clock,
                    threadNameProvider = threadNameProvider,
                    stackTraceProvider = stackTraceProvider,
                    pendingFinisherReader = pendingFinisherReader,
                )
            } catch (error: RuntimeException) {
                recordOpen(
                    fileName = fileName,
                    startUptimeMs = startUptimeMs,
                    success = false,
                    recorder = recorder,
                    clock = clock,
                    threadNameProvider = threadNameProvider,
                    stackTraceProvider = stackTraceProvider,
                    pendingFinisherReader = pendingFinisherReader,
                )
                throw error
            }
        }

        /**
         * 包装已有 [SharedPreferences]，用于记录后续写入入口证据。
         *
         * @param fileName SP 文件名。
         * @param delegate 原始 [SharedPreferences]。
         * @param recorder 运行期操作记录器。
         * @param clock uptime 时间源。
         * @param threadNameProvider 线程名读取器。
         * @param stackTraceProvider 调用栈读取器。
         * @param pendingFinisherReader pending finisher 数量读取器。
         * @return 被监控的 [SharedPreferences]。
         */
        fun wrap(
            fileName: String,
            delegate: SharedPreferences,
            recorder: SharedPreferencesOperationRecorder = SharedPreferencesOperationRecorder.global,
            clock: Clock = AndroidClock(),
            threadNameProvider: () -> String = SharedPreferencesOperationReporter::currentThreadName,
            stackTraceProvider: () -> List<String> = SharedPreferencesOperationReporter::currentStackFrames,
            pendingFinisherReader: () -> Int? = recorder::pendingFinisherCount,
        ): SharedPreferences {
            return MonitoredSharedPreferences(
                fileName = fileName,
                delegate = delegate,
                recorder = recorder,
                clock = clock,
                threadNameProvider = threadNameProvider,
                stackTraceProvider = stackTraceProvider,
                pendingFinisherReader = pendingFinisherReader,
            )
        }

        // 记录打开入口，确保首次加载失败也有证据。
        private fun recordOpen(
            fileName: String,
            startUptimeMs: Long,
            success: Boolean,
            recorder: SharedPreferencesOperationRecorder,
            clock: Clock,
            threadNameProvider: () -> String,
            stackTraceProvider: () -> List<String>,
            pendingFinisherReader: () -> Int?,
        ): Unit {
            SharedPreferencesOperationReporter.recordOperation(
                fileName = fileName,
                operationType = SharedPreferencesOperationType.LOAD,
                startUptimeMs = startUptimeMs,
                success = success,
                recorder = recorder,
                clock = clock,
                threadNameProvider = threadNameProvider,
                stackTraceProvider = stackTraceProvider,
                pendingFinisherReader = pendingFinisherReader,
            )
        }
    }
}
