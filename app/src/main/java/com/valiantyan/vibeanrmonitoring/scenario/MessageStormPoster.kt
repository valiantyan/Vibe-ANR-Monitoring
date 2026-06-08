package com.valiantyan.vibeanrmonitoring.scenario

/**
 * 可注入主线程消息投递器，测试中记录 callback，Demo 运行时委托专属 Handler.post。
 */
fun interface MessageStormPoster {
    /**
     * 投递一个待执行的主线程 callback。
     *
     * @param callback 需要进入主线程 Pending 队列的任务。
     */
    fun post(callback: Runnable): Unit
}
