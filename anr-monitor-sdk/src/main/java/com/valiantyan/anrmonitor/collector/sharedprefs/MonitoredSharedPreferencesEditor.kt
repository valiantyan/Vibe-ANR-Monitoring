package com.valiantyan.anrmonitor.collector.sharedprefs

import android.content.SharedPreferences
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType

/**
 * 被监控的 SharedPreferences editor，用于记录 apply 和 commit 写入成本。
 */
internal class MonitoredSharedPreferencesEditor(
    private val fileName: String,
    private val delegate: SharedPreferences.Editor,
    private val recorder: SharedPreferencesOperationRecorder,
    private val clock: Clock,
    private val threadNameProvider: () -> String,
    private val stackTraceProvider: () -> List<String>,
    private val pendingFinisherReader: () -> Int?,
) : SharedPreferences.Editor {
    /**
     * 写入字符串并返回当前 editor，保持链式调用。
     */
    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor {
        delegate.putString(key, value)
        return this
    }

    /**
     * 写入字符串集合并返回当前 editor。
     */
    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        delegate.putStringSet(key, values)
        return this
    }

    /**
     * 写入整型值并返回当前 editor。
     */
    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor {
        delegate.putInt(key, value)
        return this
    }

    /**
     * 写入长整型值并返回当前 editor。
     */
    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor {
        delegate.putLong(key, value)
        return this
    }

    /**
     * 写入浮点值并返回当前 editor。
     */
    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor {
        delegate.putFloat(key, value)
        return this
    }

    /**
     * 写入布尔值并返回当前 editor。
     */
    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor {
        delegate.putBoolean(key, value)
        return this
    }

    /**
     * 移除 key 并返回当前 editor。
     */
    override fun remove(key: String?): SharedPreferences.Editor {
        delegate.remove(key)
        return this
    }

    /**
     * 清空 key 并返回当前 editor。
     */
    override fun clear(): SharedPreferences.Editor {
        delegate.clear()
        return this
    }

    /**
     * 同步提交并记录写入耗时。
     */
    override fun commit(): Boolean {
        val startUptimeMs: Long = clock.uptimeMillis()
        return try {
            val success: Boolean = delegate.commit()
            recordOperation(
                operationType = SharedPreferencesOperationType.COMMIT,
                startUptimeMs = startUptimeMs,
                success = success,
            )
            success
        } catch (error: RuntimeException) {
            recordOperation(
                operationType = SharedPreferencesOperationType.COMMIT,
                startUptimeMs = startUptimeMs,
                success = false,
            )
            throw error
        }
    }

    /**
     * 异步提交并记录入口耗时和 pending finisher 近似数量。
     */
    override fun apply(): Unit {
        val startUptimeMs: Long = clock.uptimeMillis()
        try {
            delegate.apply()
            recorder.recordPendingFinisherScheduled()
            recordOperation(
                operationType = SharedPreferencesOperationType.APPLY,
                startUptimeMs = startUptimeMs,
                success = true,
            )
        } catch (error: RuntimeException) {
            recordOperation(
                operationType = SharedPreferencesOperationType.APPLY,
                startUptimeMs = startUptimeMs,
                success = false,
            )
            throw error
        }
    }

    // 记录 editor 操作，复用 wrapper 的现场读取器。
    private fun recordOperation(
        operationType: SharedPreferencesOperationType,
        startUptimeMs: Long,
        success: Boolean,
    ): Unit {
        SharedPreferencesOperationReporter.recordOperation(
            fileName = fileName,
            operationType = operationType,
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
