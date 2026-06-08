package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Handler
import android.os.Looper

/**
 * 主线程消息风暴场景，点击按钮后投递大量同类消息并短时间占住当前点击消息。
 *
 * @param poster 主线程消息投递器，测试中可替换为记录器。
 * @param blockingAction 当前点击消息的阻塞动作，用于给 Watchdog 和 Pending 快照留下采集窗口。
 * @param messageCount 投递的同类消息数量，默认超过 SDK 消息风暴阈值 20。
 * @param blockDurationMs 当前点击消息阻塞时长，默认超过 Demo SDK 的 3000ms 疑似 ANR 阈值。
 */
class MessageStormScenario(
    private val poster: MessageStormPoster = HandlerMessageStormPoster(),
    private val blockingAction: BlockingAction = BlockingAction { durationMs: Long ->
        Thread.sleep(durationMs)
    },
    private val messageCount: Int = DEFAULT_MESSAGE_COUNT,
    private val blockDurationMs: Long = DEFAULT_BLOCK_DURATION_MS,
) : AnrDemoScenario {
    override val id: String = "message_storm"
    override val title: String = "消息风暴"
    override val expectedAttribution: String = "MESSAGE_STORM"
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = MESSAGE_STORM",
        "pendingQueue.messages 中同类 MessageStormHandler 或 StormRunnable 数量 >= 20",
        "attribution.evidence 包含 pending repeated target count",
        "barrierEvidence.stuckTokens 为空或不是主因",
    )

    /**
     * 先投递大量同类 callback，再阻塞当前点击消息，让 SDK 采集到同类 Pending 消息堆积。
     */
    override fun run(): Unit {
        val callback: Runnable = StormRunnable()
        repeat(times = messageCount) {
            poster.post(callback = callback)
        }
        blockingAction.block(durationMs = blockDurationMs)
    }

    private class StormRunnable : Runnable {
        // 保留独立 callback 类型，方便 Pending 队列快照中出现可读的重复消息证据。
        override fun run(): Unit {
            val marker: String = "message_storm_callback"
            marker.length.toString()
        }
    }

    private class HandlerMessageStormPoster : MessageStormPoster {
        // 使用专属 [Handler] 子类，让报告优先显示业务场景名称而不是通用 android.os.Handler。
        private val handler: Handler = MessageStormHandler(looper = Looper.getMainLooper())

        // 将 callback 投递到主线程队列，真实 Demo 中用于制造 Pending 队列堆积。
        override fun post(callback: Runnable): Unit {
            handler.post(callback)
        }
    }

    private class MessageStormHandler(looper: Looper) : Handler(looper)

    private companion object {
        /**
         * 默认投递 80 条，明显超过 SDK 默认 `messageStormCount=20`，同时避免 Demo 过重。
         */
        private const val DEFAULT_MESSAGE_COUNT: Int = 80

        /**
         * 默认阻塞 6 秒，稳定超过 debug 配置中的 3 秒疑似 ANR 阈值。
         */
        private const val DEFAULT_BLOCK_DURATION_MS: Long = 6_000L
    }
}
