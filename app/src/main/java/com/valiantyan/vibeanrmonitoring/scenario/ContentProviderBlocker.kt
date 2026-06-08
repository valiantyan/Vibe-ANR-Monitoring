package com.valiantyan.vibeanrmonitoring.scenario

/**
 * ContentProvider 内部的阻塞动作，单独拆出便于 JVM 单元测试。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs Provider 阻塞时长，默认超过 Demo 疑似 ANR 阈值。
 */
class ContentProviderBlocker(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    /**
     * 在 Provider 查询所在线程阻塞，Demo 按钮触发时就是主线程。
     */
    fun block(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        /**
         * 使用休眠制造 Provider 查询长时间不返回的现场。
         *
         * @param durationMs 休眠时长，单位毫秒。
         */
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 12 秒，稳定超过 Demo SDK 疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 12_000L
    }
}
