package com.valiantyan.anrmonitor.collector.sharedprefs

import android.content.SharedPreferences
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [MonitoredSharedPreferences] 能记录入口调用栈和写入成本。
 */
class MonitoredSharedPreferencesTest {
    /**
     * 包装打开入口时，需要记录首次加载耗时、线程和调用栈。
     */
    @Test
    fun openRecordsFirstLoadCostThreadAndStack(): Unit {
        val clock = MutableClock(value = 1_000L)
        val recorder = SharedPreferencesOperationRecorder(maxRecords = 10)

        MonitoredSharedPreferences.open(
            fileName = "settings.xml",
            opener = {
                clock.advance(deltaMs = 120L)
                FakeSharedPreferences(clock = clock)
            },
            recorder = recorder,
            clock = clock,
            threadNameProvider = { "main" },
            stackTraceProvider = { listOf("com.example.MainActivity.onCreate(MainActivity.kt:10)") },
            pendingFinisherReader = { 0 },
        )

        val record = recorder.recentOperations(maxCount = 1).first()

        assertEquals(SharedPreferencesOperationType.LOAD, record.operationType)
        assertEquals("settings.xml", record.fileName)
        assertEquals(120L, record.costMs)
        assertEquals("main", record.threadName)
        assertTrue(record.stackFrames.first().contains("MainActivity.onCreate"))
    }

    /**
     * apply 和 commit 都要记录写入耗时、调用栈和 pending finisher 数量。
     */
    @Test
    fun editRecordsApplyAndCommitWriteEvidence(): Unit {
        val clock = MutableClock(value = 2_000L)
        val recorder = SharedPreferencesOperationRecorder(maxRecords = 10)
        val preferences = MonitoredSharedPreferences.wrap(
            fileName = "settings.xml",
            delegate = FakeSharedPreferences(clock = clock),
            recorder = recorder,
            clock = clock,
            threadNameProvider = { "main" },
            stackTraceProvider = { listOf("com.example.MainActivity.onStop(MainActivity.kt:30)") },
            pendingFinisherReader = recorder::pendingFinisherCount,
        )

        preferences.edit().putString("feature", "enabled").apply()
        preferences.edit().putString("feature", "disabled").commit()

        val records = recorder.recentOperations(maxCount = 10)

        assertEquals(SharedPreferencesOperationType.APPLY, records[0].operationType)
        assertEquals(30L, records[0].costMs)
        assertEquals(1, records[0].pendingFinisherCount)
        assertEquals(SharedPreferencesOperationType.COMMIT, records[1].operationType)
        assertEquals(70L, records[1].costMs)
        assertEquals(0, records[1].pendingFinisherCount)
        assertTrue(records[1].stackFrames.first().contains("MainActivity.onStop"))
    }

    /**
     * apply 操作记录可保留当次 pending 证据，但全局 pending 数不能长期累计成历史 apply 次数。
     */
    @Test
    fun applyDoesNotLeakPendingFinisherCountAfterOperationRecord(): Unit {
        val clock = MutableClock(value = 3_000L)
        val recorder = SharedPreferencesOperationRecorder(maxRecords = 10)
        val preferences = MonitoredSharedPreferences.wrap(
            fileName = "settings.xml",
            delegate = FakeSharedPreferences(clock = clock),
            recorder = recorder,
            clock = clock,
            threadNameProvider = { "main" },
            stackTraceProvider = { listOf("com.example.MainActivity.onStop(MainActivity.kt:30)") },
            pendingFinisherReader = recorder::pendingFinisherCount,
        )

        preferences.edit().putString("feature", "enabled").apply()

        val record = recorder.recentOperations(maxCount = 1).first()

        assertEquals(1, record.pendingFinisherCount)
        assertEquals(0, recorder.pendingFinisherCount())
    }

    private class MutableClock(
        private var value: Long,
    ) : Clock {
        /**
         * 返回当前测试时间，供 wrapper 计算操作耗时。
         */
        override fun uptimeMillis(): Long = value

        // 推进测试时间，模拟真实加载和写盘成本。
        fun advance(deltaMs: Long): Unit {
            value += deltaMs
        }
    }

    private class FakeSharedPreferences(
        private val clock: MutableClock,
    ) : SharedPreferences {
        private val values: MutableMap<String, Any> = mutableMapOf()

        /**
         * 返回所有测试键值。
         */
        override fun getAll(): MutableMap<String, *> = values

        /**
         * 获取字符串值，测试中只用于满足接口约束。
         */
        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = values[key] as? String ?: defValue

        /**
         * 获取字符串集合，测试中只用于满足接口约束。
         */
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? {
            val value: Any? = values[key]
            if (value is Set<*>) {
                return value.filterIsInstance<String>().toMutableSet()
            }
            return defValues
        }

        /**
         * 获取整型值，测试中只用于满足接口约束。
         */
        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = values[key] as? Int ?: defValue

        /**
         * 获取长整型值，测试中只用于满足接口约束。
         */
        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = values[key] as? Long ?: defValue

        /**
         * 获取浮点值，测试中只用于满足接口约束。
         */
        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = values[key] as? Float ?: defValue

        /**
         * 获取布尔值，测试中只用于满足接口约束。
         */
        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = values[key] as? Boolean ?: defValue

        /**
         * 判断 key 是否存在。
         */
        override fun contains(key: String?): Boolean = values.containsKey(key = key)

        /**
         * 创建测试编辑器，写入时推进测试时间。
         */
        override fun edit(): SharedPreferences.Editor = FakeEditor(
            values = values,
            clock = clock,
        )

        /**
         * 注册监听器在测试中不需要真实行为。
         */
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ): Unit = Unit

        /**
         * 注销监听器在测试中不需要真实行为。
         */
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ): Unit = Unit
    }

    private class FakeEditor(
        private val values: MutableMap<String, Any>,
        private val clock: MutableClock,
    ) : SharedPreferences.Editor {
        /**
         * 写入字符串值。
         */
        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor {
            if (key != null && value != null) {
                values[key] = value
            }
            return this
        }

        /**
         * 写入字符串集合。
         */
        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this

        /**
         * 写入整型值。
         */
        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = this

        /**
         * 写入长整型值。
         */
        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = this

        /**
         * 写入浮点值。
         */
        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = this

        /**
         * 写入布尔值。
         */
        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = this

        /**
         * 移除指定 key。
         */
        override fun remove(key: String?): SharedPreferences.Editor = this

        /**
         * 清空所有 key。
         */
        override fun clear(): SharedPreferences.Editor = this

        /**
         * 同步提交写入，测试中固定消耗 70ms。
         */
        override fun commit(): Boolean {
            clock.advance(deltaMs = 70L)
            return true
        }

        /**
         * 异步提交写入，测试中固定消耗 30ms。
         */
        override fun apply(): Unit {
            clock.advance(deltaMs = 30L)
        }
    }
}
