package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 在线程池耗尽后让主线程同步等待排队任务结果，用于复现等待类当前消息慢。
 *
 * @param workload 可测试工作负载，真实 Demo 中执行固定线程池耗尽和 Future 等待。
 */
class ThreadPoolExhaustionWaitScenario(
    private val workload: ThreadPoolWaitWorkload = ThreadPoolExhaustionWorkload(),
) : AnrDemoScenario {
    /** 场景唯一标识，后续文档、intent 触发和分析报告用它区分 Demo 类型。 */
    override val id: String = "thread_pool_exhaustion_wait"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "线程池耗尽 + 主线程等待"

    /** 预期归因说明，线程池等待通常先表现为当前消息慢，并依靠等待栈定位根因。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + thread pool wait stack evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 ThreadPoolExhaustionWaitScenario.run",
        "mainThread.stackFrames 包含 ThreadPoolExhaustionWorkload.exhaustPoolAndWait",
        "mainThread.stackFrames 包含 java.util.concurrent.FutureTask.get",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发线程池耗尽并让当前点击消息在主线程等待排队任务结果。
     */
    override fun run(): Unit {
        workload.exhaustPoolAndWait()
    }
}
