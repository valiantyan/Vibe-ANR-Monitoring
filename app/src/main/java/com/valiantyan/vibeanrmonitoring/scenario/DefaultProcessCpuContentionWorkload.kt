package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demo 专用进程内 CPU 竞争工作负载。
 *
 * 这个类会启动多个命名后台线程持续 busy loop，制造进程内 CPU 抢占证据。
 * 调用线程会进入低 CPU 等待窗口，确保 SDK 有机会生成疑似 ANR 报告。
 */
class DefaultProcessCpuContentionWorkload : ProcessCpuContentionWorkload {
    /**
     * 启动后台竞争线程，并在当前线程等待指定窗口。Demo 按钮会在主线程调用此方法。
     */
    override fun createContentionAndWaitOnMainThread(
        contenderCount: Int,
        contentionDurationMs: Long,
        mainThreadWaitMs: Long,
    ): Unit {
        val running: AtomicBoolean = AtomicBoolean(true)
        val startedLatch: CountDownLatch = CountDownLatch(contenderCount)
        val workerThreads: List<Thread> = createContenderThreads(
            contenderCount = contenderCount,
            contentionDurationMs = contentionDurationMs,
            running = running,
            startedLatch = startedLatch,
        )
        workerThreads.forEach { thread: Thread ->
            thread.start()
        }
        try {
            val allStarted: Boolean = startedLatch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            check(allStarted) {
                "进程内 CPU 竞争场景启动失败，竞争线程未在 ${START_TIMEOUT_MS}ms 内全部开始"
            }
            Log.w(TAG, "进程内 CPU 竞争场景开始: contenders=$contenderCount")
            waitOnMainThread(waitDurationMs = mainThreadWaitMs)
        } catch (error: InterruptedException) {
            Log.w(TAG, "进程内 CPU 竞争场景被中断", error)
            Thread.currentThread().interrupt()
        } finally {
            running.set(false)
            workerThreads.forEach { thread: Thread ->
                thread.interrupt()
            }
            workerThreads.forEach { thread: Thread ->
                joinQuietly(thread = thread)
            }
            Log.w(TAG, "进程内 CPU 竞争场景结束")
        }
    }

    // 创建命名竞争线程，便于在 JSON 的线程 CPU 排名里识别 Demo 根因。
    private fun createContenderThreads(
        contenderCount: Int,
        contentionDurationMs: Long,
        running: AtomicBoolean,
        startedLatch: CountDownLatch,
    ): List<Thread> {
        val nextThreadId: AtomicInteger = AtomicInteger(1)
        return List(contenderCount) {
            val threadName: String = "DemoCpuContender-${nextThreadId.getAndIncrement()}"
            Thread(
                {
                    runCpuContentionLoop(
                        threadName = threadName,
                        contentionDurationMs = contentionDurationMs,
                        running = running,
                        startedLatch = startedLatch,
                    )
                },
                threadName,
            ).apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    }

    // 在线程内持续计算，形成可被线程 CPU 排名捕获的后台竞争证据。
    private fun runCpuContentionLoop(
        threadName: String,
        contentionDurationMs: Long,
        running: AtomicBoolean,
        startedLatch: CountDownLatch,
    ): Unit {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (error: SecurityException) {
            Log.w(TAG, "$threadName 无法提升线程优先级，继续使用默认优先级", error)
        }
        val endUptimeMs: Long = SystemClock.uptimeMillis() + contentionDurationMs
        var checksum: Double = CHECKSUM_SEED
        startedLatch.countDown()
        while (running.get() && SystemClock.uptimeMillis() < endUptimeMs) {
            checksum += Math.sqrt(checksum + CHECKSUM_OFFSET)
            if (checksum > CHECKSUM_RESET_THRESHOLD) {
                checksum = CHECKSUM_SEED
            }
        }
        Log.w(TAG, "$threadName finished checksum=$checksum")
    }

    // 主线程保持低 CPU 等待窗口，让报告和后台 CPU 证据可以在同一时间窗出现。
    private fun waitOnMainThread(waitDurationMs: Long): Unit {
        val endUptimeMs: Long = SystemClock.uptimeMillis() + waitDurationMs
        while (SystemClock.uptimeMillis() < endUptimeMs) {
            try {
                Thread.sleep(MAIN_THREAD_SLEEP_SLICE_MS)
            } catch (error: InterruptedException) {
                Log.w(TAG, "主线程等待窗口被中断", error)
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    // 停止场景时等待后台线程退出，避免污染后续 Demo 场景。
    private fun joinQuietly(thread: Thread): Unit {
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (error: InterruptedException) {
            Log.w(TAG, "等待竞争线程退出时被中断: ${thread.name}", error)
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /**
         * 等待竞争线程全部启动的最长时间。
         */
        private const val START_TIMEOUT_MS: Long = 1_500L

        /**
         * 主线程睡眠切片，保持低 CPU 等待特征，避免和主线程 busy loop 混淆。
         */
        private const val MAIN_THREAD_SLEEP_SLICE_MS: Long = 250L

        /**
         * 停止竞争线程后的 join 等待时间。
         */
        private const val JOIN_TIMEOUT_MS: Long = 300L

        /**
         * 校验值初始值。
         */
        private const val CHECKSUM_SEED: Double = 1.0

        /**
         * 每轮计算偏移，避免循环被过度优化。
         */
        private const val CHECKSUM_OFFSET: Double = 31.0

        /**
         * 校验值上限，防止长时间运行时数值无限增大。
         */
        private const val CHECKSUM_RESET_THRESHOLD: Double = 1_000_000.0

        /**
         * Demo 场景日志标签。
         */
        private const val TAG: String = "ProcessCpuContention"
    }
}
