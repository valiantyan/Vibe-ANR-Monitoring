package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demo 专用线程池耗尽工作负载。
 *
 * 这个类故意让固定线程池的所有 worker 被长任务占满，然后在调用线程等待排队任务结果，
 * 用于制造可观测的主线程等待类 ANR 现场。
 *
 * @param workerCount 固定线程池 worker 数量。
 * @param workerBlockDurationMs worker 被占用的时长。
 */
class ThreadPoolExhaustionWorkload(
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
    private val workerBlockDurationMs: Long = DEFAULT_WORKER_BLOCK_DURATION_MS,
) : ThreadPoolWaitWorkload {
    /**
     * 先占满 worker，再提交一个排队任务并在当前线程等待它完成。此方法由按钮点击在主线程调用。
     */
    override fun exhaustPoolAndWait(): Unit {
        val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            DemoThreadFactory(),
        )
        val workersStarted: CountDownLatch = CountDownLatch(workerCount)
        val blockUntilUptimeMs: Long = SystemClock.uptimeMillis() + workerBlockDurationMs
        var queuedFuture: Future<String>? = null
        repeat(workerCount) {
            executor.execute {
                workersStarted.countDown()
                blockWorkerUntil(targetUptimeMs = blockUntilUptimeMs)
            }
        }
        try {
            val allWorkersStarted: Boolean = workersStarted.await(WORKER_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            check(allWorkersStarted) {
                "线程池耗尽等待场景启动失败，worker 未在 ${WORKER_START_TIMEOUT_MS}ms 内全部开始执行"
            }
            queuedFuture = executor.submit(
                Callable {
                    QUEUED_TASK_RESULT
                },
            )
            queuedFuture?.get(workerBlockDurationMs + FUTURE_WAIT_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            queuedFuture?.cancel(true)
            throw IllegalStateException("线程池耗尽等待场景超时，排队任务未恢复执行", error)
        } finally {
            executor.shutdownNow()
        }
    }

    // worker 只做睡眠等待，避免和“主线程 CPU 忙等”场景混淆。
    private fun blockWorkerUntil(targetUptimeMs: Long): Unit {
        while (SystemClock.uptimeMillis() < targetUptimeMs) {
            try {
                Thread.sleep(WORKER_SLEEP_SLICE_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private class DemoThreadFactory : ThreadFactory {
        // 线程名带序号，方便在调试器或系统线程列表里识别 Demo worker。
        private val nextId: AtomicInteger = AtomicInteger(1)

        /**
         * 给 Demo worker 设置稳定名称，方便和 JSON 线程证据互相印证。
         */
        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "DemoPoolExhaustedWorker-${nextId.getAndIncrement()}")
        }
    }

    private companion object {
        /**
         * 默认固定线程池大小，足够稳定复现且不会给测试设备制造过多后台线程。
         */
        private const val DEFAULT_WORKER_COUNT: Int = 2

        /**
         * worker 默认占用 10 秒，稳定超过 Demo SDK 的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_WORKER_BLOCK_DURATION_MS: Long = 10_000L

        /**
         * 等待 worker 全部启动的最长时间，避免场景没有真正耗尽线程池。
         */
        private const val WORKER_START_TIMEOUT_MS: Long = 2_000L

        /**
         * worker 睡眠切片，既保持占用又能及时响应 [Thread.interrupt]。
         */
        private const val WORKER_SLEEP_SLICE_MS: Long = 250L

        /**
         * 等待排队任务恢复执行的额外宽限时间。
         */
        private const val FUTURE_WAIT_GRACE_MS: Long = 2_000L

        /**
         * 固定线程池不依赖空闲回收，保持默认 0ms。
         */
        private const val KEEP_ALIVE_MS: Long = 0L

        /**
         * 排队任务返回值只用于让 [Future.get] 有一个明确结果。
         */
        private const val QUEUED_TASK_RESULT: String = "thread-pool-result"
    }
}
