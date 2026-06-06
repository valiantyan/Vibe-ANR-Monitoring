package com.valiantyan.anrmonitor.collector.watchdog

import android.os.Handler
import android.os.Looper
import com.valiantyan.anrmonitor.core.clock.Clock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台 Watchdog，通过向主线程投递心跳识别疑似 ANR。
 *
 * @param clock uptime 时间源。
 * @param intervalMs Watchdog 轮询间隔，单位毫秒。
 * @param heartbeatState 心跳状态机。
 * @param mainHandler 主线程 [Handler]，测试或宿主可替换。
 * @param onSuspectAnr 疑似 ANR 回调。
 */
class AnrWatchdog(
    private val clock: Clock,
    private val intervalMs: Long,
    private val heartbeatState: HeartbeatState,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val onSuspectAnr: () -> Unit,
) {
    // Watchdog 运行态，保证 [start] 幂等。
    private val isRunning: AtomicBoolean = AtomicBoolean(false)

    // 心跳序号，用于区分过期回调和当前 pending 心跳。
    private val sequence: AtomicLong = AtomicLong(0L)

    // 后台检测线程，停止时会被中断以尽快退出 sleep。
    private var thread: Thread? = null

    /**
     * 启动后台 Watchdog；重复调用不会创建多条检测线程。
     */
    fun start(): Unit {
        if (!isRunning.compareAndSet(false, true)) {
            return
        }
        thread = Thread(::loop, WATCHDOG_THREAD_NAME).apply { start() }
    }

    /**
     * 停止后台 Watchdog，并中断正在 sleep 的检测线程。
     */
    fun stop(): Unit {
        isRunning.set(false)
        thread?.interrupt()
        thread = null
    }

    /**
     * 轮询心跳状态；只有没有 pending 心跳时才继续投递，避免队列堆积造成误判。
     */
    private fun loop(): Unit {
        while (isRunning.get()) {
            if (heartbeatState.isTimedOut(nowUptimeMs = clock.uptimeMillis())) {
                onSuspectAnr()
            }
            if (!heartbeatState.hasPending()) {
                postHeartbeat()
            }
            sleepInterval()
        }
    }

    /**
     * 向主线程投递心跳，处理回调执行即说明主线程消息循环仍能推进。
     */
    private fun postHeartbeat(): Unit {
        val seq: Long = sequence.incrementAndGet()
        heartbeatState.markPosted(
            seq = seq,
            postedUptimeMs = clock.uptimeMillis(),
        )
        mainHandler.post {
            heartbeatState.markHandled(seq = seq)
        }
    }

    /**
     * 等待下一个检测周期；停止时保留中断状态，方便线程尽快退出循环。
     */
    private fun sleepInterval(): Unit {
        try {
            Thread.sleep(intervalMs)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /**
         * Watchdog 后台线程名，便于 traces 和线程列表识别。
         */
        private const val WATCHDOG_THREAD_NAME: String = "vibe-anr-watchdog"
    }
}
