package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/**
 * Demo 专用 GC / 内存抖动工作负载。
 *
 * 这个类故意在主线程持续分配 byte array，并保留一小段时间，促使运行时更频繁地执行 GC。
 * 分配规模被限制在一个滑动窗口内，避免 Demo 直接 OOM。
 */
class GcMemoryChurnWorkload(
    private val allocationSizeBytes: Int = DEFAULT_ALLOCATION_SIZE_BYTES,
    private val retainedBatchCount: Int = DEFAULT_RETAINED_BATCH_COUNT,
    private val gcIntervalMs: Long = DEFAULT_GC_INTERVAL_MS,
) : MemoryChurnWorkload {
    /**
     * 在当前线程制造内存抖动。Demo 按钮会在主线程调用此方法。
     */
    override fun churnMemoryOnMainThread(targetDurationMs: Long): Unit {
        val retainedObjects: ArrayDeque<ByteArray> = ArrayDeque()
        val endUptimeMs: Long = SystemClock.uptimeMillis() + targetDurationMs
        var nextGcUptimeMs: Long = SystemClock.uptimeMillis() + gcIntervalMs
        var allocationCount: Int = 0
        while (SystemClock.uptimeMillis() < endUptimeMs) {
            retainedObjects.add(ByteArray(allocationSizeBytes))
            allocationCount += 1
            while (retainedObjects.size > retainedBatchCount) {
                retainedObjects.removeFirst()
            }
            val nowUptimeMs: Long = SystemClock.uptimeMillis()
            if (nowUptimeMs >= nextGcUptimeMs) {
                Log.w(TAG, "GC / 内存抖动场景请求 GC: allocations=$allocationCount")
                System.gc()
                nextGcUptimeMs = nowUptimeMs + gcIntervalMs
            }
        }
        Log.w(TAG, "GC / 内存抖动场景完成: allocations=$allocationCount retained=${retainedObjects.size}")
        retainedObjects.clear()
        System.gc()
    }

    private companion object {
        /**
         * 单次分配大小。256 KiB 可以产生明显分配压力，又不容易在普通测试机上直接 OOM。
         */
        private const val DEFAULT_ALLOCATION_SIZE_BYTES: Int = 256 * 1024

        /**
         * 保留最近 12 个对象，形成约 3 MiB 的短暂活动集，避免被立即全部回收。
         */
        private const val DEFAULT_RETAINED_BATCH_COUNT: Int = 12

        /**
         * 周期性请求 GC，帮助 logcat 出现可观测系统 GC 日志。
         */
        private const val DEFAULT_GC_INTERVAL_MS: Long = 450L

        /**
         * Demo 场景日志标签。
         */
        private const val TAG: String = "GcMemoryChurn"
    }
}
