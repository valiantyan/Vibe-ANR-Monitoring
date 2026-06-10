package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 进程内 CPU 竞争 ANR 场景。
 *
 * 点击按钮后启动多个后台 CPU 竞争线程，再让当前主线程消息保持低 CPU 等待窗口，
 * 用于验证 SDK 能否通过线程 CPU 排名识别进程内后台线程抢占。
 */
class ProcessCpuContentionScenario(
    private val workload: ProcessCpuContentionWorkload = DefaultProcessCpuContentionWorkload(),
    private val contenderCount: Int = defaultContenderCount(),
    private val contentionDurationMs: Long = DEFAULT_CONTENTION_DURATION_MS,
    private val mainThreadWaitMs: Long = DEFAULT_MAIN_THREAD_WAIT_MS,
) : AnrDemoScenario {
    /** 场景唯一标识，后续文档、intent 触发和分析报告用它区分 Demo 类型。 */
    override val id: String = "process_cpu_contention"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "进程内 CPU 竞争"

    /** 预期归因说明，当前 SDK 以当前消息慢或未知为主，并结合线程 CPU 排名人工确认。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + process thread CPU contention evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 ProcessCpuContentionScenario.run",
        "threadCpu.topThreads 包含 DemoCpuContender-",
        "后台竞争线程 totalCpuMs 位于 Top 线程前列",
        "mainThread.current.cpuMs 低于纯主线程忙等场景",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 触发进程内后台线程 CPU 竞争，并让当前点击消息形成可观测等待窗口。
     */
    override fun run(): Unit {
        workload.createContentionAndWaitOnMainThread(
            contenderCount = contenderCount,
            contentionDurationMs = contentionDurationMs,
            mainThreadWaitMs = mainThreadWaitMs,
        )
    }

    private companion object {
        /**
         * 后台竞争线程默认持续 8 秒，覆盖 SDK 疑似 ANR 抓取窗口。
         */
        private const val DEFAULT_CONTENTION_DURATION_MS: Long = 8_000L

        /**
         * 主线程等待 6 秒，稳定超过 Demo SDK 的 `suspectAnrMs=3000`。
         */
        private const val DEFAULT_MAIN_THREAD_WAIT_MS: Long = 6_000L

        /**
         * 根据 CPU 核数创建竞争线程，至少 4 个，最多 8 个，避免低端设备线程过多。
         */
        private fun defaultContenderCount(): Int {
            val availableProcessors: Int = Runtime.getRuntime().availableProcessors()
            return (availableProcessors + 1).coerceIn(
                minimumValue = MIN_CONTENDER_COUNT,
                maximumValue = MAX_CONTENDER_COUNT,
            )
        }

        /**
         * 最少竞争线程数量，保证低核设备也能看见 CPU 竞争。
         */
        private const val MIN_CONTENDER_COUNT: Int = 4

        /**
         * 最多竞争线程数量，避免 Demo 对设备造成过大压力。
         */
        private const val MAX_CONTENDER_COUNT: Int = 8
    }
}
