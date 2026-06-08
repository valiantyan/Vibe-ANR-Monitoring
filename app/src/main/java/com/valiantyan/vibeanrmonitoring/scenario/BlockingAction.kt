package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入阻塞动作，测试中记录调用，Demo 运行时执行真实阻塞。
 */
fun interface BlockingAction {
    /**
     * 阻塞当前线程指定时间。
     *
     * @param durationMs 阻塞时长，单位毫秒。
     */
    fun block(durationMs: Long): Unit
}
