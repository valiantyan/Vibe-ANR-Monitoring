package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程等待线程池结果的工作负载。测试中替换为记录器，Demo 运行时制造真实线程池耗尽。
 */
fun interface ThreadPoolWaitWorkload {
    /**
     * 占满线程池 worker，并在调用线程同步等待排队任务结果。
     */
    fun exhaustPoolAndWait(): Unit
}
