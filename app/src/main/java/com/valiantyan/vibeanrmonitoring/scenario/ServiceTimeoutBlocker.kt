package com.valiantyan.vibeanrmonitoring.scenario

/**
 * Service 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Service 阻塞时长，默认超过常见 Service 执行超时窗口。
 */
class ServiceTimeoutBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Service 所在线程阻塞，真实运行时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        /**
         * 使用休眠制造 Service 生命周期回调长时间不返回的现场。
         *
         * @param durationMs 休眠时长，单位毫秒。
         */
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 25 秒，稳定覆盖常见前台进程 Service 执行超时窗口。
         */
        private const val DEFAULT_DURATION_MS: Long = 25_000L
    }
}
