package com.valiantyan.vibeanrmonitoring.scenario

/**
 * BroadcastReceiver 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Receiver 阻塞时长，默认超过前台广播 10 秒超时阈值。
 */
class BroadcastTimeoutBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Receiver 所在线程阻塞，真实运行时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        /**
         * 使用休眠制造 BroadcastReceiver 长时间不返回的现场。
         *
         * @param durationMs 休眠时长，单位毫秒。
         */
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，稳定超过 Android 前台广播常见 10 秒超时阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
