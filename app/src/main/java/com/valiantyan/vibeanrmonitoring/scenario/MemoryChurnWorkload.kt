package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程内存抖动工作负载。测试中替换为记录器，Demo 运行时制造真实分配和 GC 压力。
 */
fun interface MemoryChurnWorkload {
    /**
     * 在调用线程分配对象并制造内存抖动。
     *
     * @param targetDurationMs 目标持续时间，必须超过 SDK 疑似 ANR 阈值。
     */
    fun churnMemoryOnMainThread(targetDurationMs: Long): Unit
}
