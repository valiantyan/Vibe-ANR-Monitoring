package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 输入事件触发的当前慢消息场景，点击按钮后故意阻塞主线程以验证 CURRENT_MESSAGE_SLOW 归因。
 *
 * @param blockingAction 实际阻塞动作，测试中可替换为记录器。
 * @param durationMs 主线程阻塞时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class CurrentSlowInputScenario(
    private val blockingAction: BlockingAction = ThreadSleepBlockingAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "current_slow_input"
    override val title: String = "当前消息慢"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 CurrentSlowInputScenario.run",
    )

    /**
     * 在按钮点击消息中阻塞主线程，制造最基础的输入事件无响应窗口。
     */
    override fun run(): Unit {
        blockingAction.block(durationMs = durationMs)
    }

    private class ThreadSleepBlockingAction : BlockingAction {
        override fun block(durationMs: Long): Unit {
            Thread.sleep(durationMs)
        }
    }

    private companion object {
        /**
         * 默认阻塞 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 6_000L
    }
}
