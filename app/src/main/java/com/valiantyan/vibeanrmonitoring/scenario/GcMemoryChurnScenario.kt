package com.valiantyan.vibeanrmonitoring.scenario

/**
 * GC / 内存抖动 ANR 场景。
 *
 * 点击按钮后在主线程持续分配对象，制造 GC 压力和当前消息耗时，用于验证 SDK 能否保留
 * 当前消息、业务栈和系统环境内存证据。
 */
class GcMemoryChurnScenario(
    private val workload: MemoryChurnWorkload = GcMemoryChurnWorkload(),
    private val targetDurationMs: Long = DEFAULT_TARGET_DURATION_MS,
) : AnrDemoScenario {
    /** 场景唯一标识，后续文档、intent 触发和分析报告用它区分 Demo 类型。 */
    override val id: String = "gc_memory_churn"

    /** Demo 页面展示的中文标题。 */
    override val title: String = "GC / 内存抖动"

    /** 预期归因说明，当前 SDK 以当前消息慢为主归因，并结合 GC 和内存压力证据人工确认。 */
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + GC/memory pressure evidence"

    /** 预期 JSON 证据，给人工验收和小白排查文档使用。 */
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.stackFrames 包含 GcMemoryChurnScenario.run",
        "mainThread.stackFrames 包含 GcMemoryChurnWorkload.churnMemoryOnMainThread",
        "environmentSnapshot.memory 可用",
        "logcat 同一时间窗出现系统 GC 日志",
        "barrierEvidence.stuckTokens 不是主因",
        "binderBlock.suspected 不是主因",
    )

    /**
     * 运行 GC / 内存抖动工作负载。由按钮点击或 adb intent 在主线程调用。
     */
    override fun run(): Unit {
        workload.churnMemoryOnMainThread(targetDurationMs = targetDurationMs)
    }

    private companion object {
        /**
         * 默认持续 6 秒，超过 Demo SDK 的 `suspectAnrMs=3000`。
         */
        private const val DEFAULT_TARGET_DURATION_MS: Long = 6_000L
    }
}
