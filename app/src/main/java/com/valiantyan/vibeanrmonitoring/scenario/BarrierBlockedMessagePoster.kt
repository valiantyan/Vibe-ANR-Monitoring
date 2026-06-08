package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Handler
import android.os.Looper

/**
 * 可注入同步消息投递器，Barrier 后方的这些消息应在 JSON Pending 队列中保持 blocked 状态。
 */
fun interface BarrierBlockedMessagePoster {
    /**
     * 投递一个同步消息 callback。
     *
     * @param callback 会被 Sync Barrier 挡住的同步任务。
     */
    fun post(callback: Runnable): Unit
}

/**
 * 使用主线程 [Handler] 投递同步消息的真实实现。
 */
class HandlerBarrierBlockedMessagePoster : BarrierBlockedMessagePoster {
    // 主线程 Handler 投递的是同步消息，能被队头 Sync Barrier 挡住。
    private val handler: Handler = Handler(Looper.getMainLooper())

    /**
     * 把 callback 投递到主线程队列，真实场景中会排在泄漏 Barrier 后方。
     */
    override fun post(callback: Runnable): Unit {
        handler.post(callback)
    }
}
