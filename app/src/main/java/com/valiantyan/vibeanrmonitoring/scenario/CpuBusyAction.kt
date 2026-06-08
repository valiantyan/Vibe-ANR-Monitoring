package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入 CPU 忙等动作，测试中记录调用，Demo 运行时执行真实 CPU 消耗。
 */
fun interface CpuBusyAction {
    /**
     * 在当前线程持续消耗 CPU。
     *
     * @param durationMs 忙等时长，单位毫秒。
     * @return 防止循环计算被过度优化的校验值。
     */
    fun burn(durationMs: Long): Double
}
