package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 进程内 CPU 竞争工作负载。测试中替换为记录器，Demo 运行时启动真实后台竞争线程。
 */
fun interface ProcessCpuContentionWorkload {
    /**
     * 启动后台 CPU 竞争线程，并在调用线程维持一个可观测等待窗口。
     *
     * @param contenderCount 后台竞争线程数量。
     * @param contentionDurationMs 后台竞争线程持续消耗 CPU 的时长。
     * @param mainThreadWaitMs 主线程当前消息保持阻塞的时长，用于稳定触发 SDK 报告。
     */
    fun createContentionAndWaitOnMainThread(
        contenderCount: Int,
        contentionDurationMs: Long,
        mainThreadWaitMs: Long,
    ): Unit
}
