package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 主线程执行的 IO/DB 工作负载。测试中替换为记录器，Demo 运行时执行真实文件和数据库操作。
 */
fun interface MainThreadIoWorkload {
    /**
     * 执行一组同步文件和数据库操作。调用方必须保证此方法在主线程触发，才能复现 ANR。
     */
    fun runIoDatabaseFileWorkload(): Unit
}
