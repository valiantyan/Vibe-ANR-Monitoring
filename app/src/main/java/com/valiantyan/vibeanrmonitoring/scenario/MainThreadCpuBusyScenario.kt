package com.valiantyan.vibeanrmonitoring.scenario

import android.os.SystemClock

/**
 * 输入事件触发的主线程 CPU 忙等场景，点击按钮后故意让主线程持续计算以验证忙等证据。
 *
 * @param cpuBusyAction 实际 CPU 消耗动作，测试中可替换为记录器。
 * @param durationMs 主线程忙等时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class MainThreadCpuBusyScenario(
    private val cpuBusyAction: CpuBusyAction = DefaultCpuBusyAction(),
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "main_thread_cpu_busy"
    override val title: String = "当前消息忙等"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.current.wallMs >= 3000",
        "mainThread.current.cpuMs 明显高于等待类场景",
        "threadCpu.topThreads 包含主线程高 CPU 证据",
        "mainThread.stackFrames 包含 MainThreadCpuBusyScenario.run",
    )

    /**
     * 在按钮点击消息中持续消耗主线程 CPU，制造和 [Thread.sleep] 不同的当前消息慢现场。
     */
    override fun run(): Unit {
        cpuBusyAction.burn(durationMs = durationMs).toString()
    }

    private class DefaultCpuBusyAction : CpuBusyAction {
        // 执行真实 busy loop，让 SDK 可以在报告里看到主线程 CPU 消耗。
        override fun burn(durationMs: Long): Double {
            val endAtMs: Long = SystemClock.uptimeMillis() + durationMs
            var checksum: Double = 0.0
            while (SystemClock.uptimeMillis() < endAtMs) {
                checksum += Math.sqrt(checksum + CHECKSUM_OFFSET)
                if (checksum > CHECKSUM_RESET_THRESHOLD) {
                    checksum = 0.0
                }
            }
            return checksum
        }
    }

    private companion object {
        /**
         * 默认忙等 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_DURATION_MS: Long = 6_000L

        /**
         * 每轮计算的偏移值，避免初始值过小时循环特征过弱。
         */
        private const val CHECKSUM_OFFSET: Double = 42.0

        /**
         * 校验值上限，防止长时间运行时数值无限增大影响复现场景稳定性。
         */
        private const val CHECKSUM_RESET_THRESHOLD: Double = 1_000_000.0
    }
}
