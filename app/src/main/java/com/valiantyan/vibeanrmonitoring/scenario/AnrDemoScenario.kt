package com.valiantyan.vibeanrmonitoring.scenario

/**
 * Demo ANR 场景的最小契约，Activity 只负责触发，具体复现逻辑留在场景类中。
 */
interface AnrDemoScenario {
    val id: String
    val title: String
    val expectedAttribution: String
    val expectedJsonSignals: List<String>

    /**
     * 执行复现场景。此方法通常在主线程由按钮点击触发。
     */
    fun run(): Unit
}
