package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.os.MessageQueue
import android.util.Log

/**
 * Sync Barrier 泄漏场景，用于复现主线程停在 [MessageQueue.nativePollOnce] 的真实 ANR。
 *
 * @param barrierPoster Sync Barrier 插入器。
 * @param debugRecorder Barrier token 记录器。
 * @param blockedMessagePoster Barrier 后方同步消息投递器。
 * @param componentStarter 组件消息触发器。
 * @param failureNotifier 失败提示器。
 * @param blockedMessageCount Barrier 后方同步消息数量。
 */
class SyncBarrierLeakScenario(
    private val barrierPoster: SyncBarrierPoster,
    private val debugRecorder: BarrierDebugRecorder,
    private val blockedMessagePoster: BarrierBlockedMessagePoster,
    private val componentStarter: BarrierLeakComponentStarter,
    private val failureNotifier: ScenarioFailureNotifier,
    private val blockedMessageCount: Int = DEFAULT_BLOCKED_MESSAGE_COUNT,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        failureNotifier = ToastScenarioFailureNotifier(context = context),
        barrierPoster = ReflectionSyncBarrierPoster(),
        debugRecorder = SdkBarrierDebugRecorder,
        blockedMessagePoster = HandlerBarrierBlockedMessagePoster(),
        componentStarter = ServiceBarrierLeakComponentStarter(context = context),
    )

    override val id: String = "sync_barrier_native_poll_once"
    override val title: String = "Sync Barrier 泄漏 / nativePollOnce"
    override val expectedAttribution: String = "SYNC_BARRIER_STUCK"
    override val expectedJsonSignals: List<String> = listOf(
        "attribution.primary = SYNC_BARRIER_STUCK",
        "pendingQueue.messages[0].isBarrierLike = true",
        "pendingQueue.messages[0].targetClass = null",
        "barrierEvidence.stuckTokens[].token = pendingQueue.messages[0].arg1",
        "barrierEvidence.alignedWithPendingBarrier = true",
        "barrierEvidence.nativePollOnceRecords 包含 STACK_INFERENCE 或 HOOK",
    )

    /**
     * 插入一个故意不移除的 Sync Barrier，并投递会被挡住的同步消息和组件消息。
     */
    override fun run(): Unit {
        val token: Int = barrierPoster.post() ?: run {
            failureNotifier.notifyFailure(message = "postSyncBarrier failed", error = null)
            return
        }
        debugRecorder.recordPostSyncBarrier(
            token = token,
            stackFrames = captureStackFrames(),
        )
        postBlockedSynchronousMessages()
        componentStarter.start()
    }

    // 投递 Barrier 后方同步消息，帮助 Pending 队列快照留下被挡住的业务证据。
    private fun postBlockedSynchronousMessages(): Unit {
        repeat(times = blockedMessageCount) { index: Int ->
            blockedMessagePoster.post(
                callback = BlockedSynchronousMessage(index = index),
            )
        }
    }

    // 捕获场景插入栈，报告中依靠它定位是谁插入了未移除的 Barrier。
    private fun captureStackFrames(): List<String> {
        return Thread.currentThread().stackTrace
            .drop(n = STACK_FRAMES_TO_DROP)
            .take(n = MAX_STACK_FRAMES)
            .map { element: StackTraceElement -> element.toString() }
    }

    private class BlockedSynchronousMessage(
        private val index: Int,
    ) : Runnable {
        // 如果这个 callback 被执行，说明 Barrier 没有持续挡住同步消息，需要在 Logcat 暴露出来。
        override fun run(): Unit {
            Log.w(TAG, "Sync Barrier 泄漏验证失败，同步消息被执行: index=$index")
        }
    }

    private companion object {
        /**
         * Sync Barrier 场景日志标签。
         */
        private const val TAG: String = "SyncBarrierLeak"

        /**
         * Barrier 后方投递的同步消息数量。
         */
        private const val DEFAULT_BLOCKED_MESSAGE_COUNT: Int = 8

        /**
         * 调试栈需要跳过的 SDK 辅助帧数量。
         */
        private const val STACK_FRAMES_TO_DROP: Int = 2

        /**
         * 报告中最多保留的插入栈帧数量。
         */
        private const val MAX_STACK_FRAMES: Int = 16
    }
}
