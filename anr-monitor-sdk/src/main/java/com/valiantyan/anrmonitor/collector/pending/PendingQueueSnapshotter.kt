package com.valiantyan.anrmonitor.collector.pending

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot

/**
 * Pending 队列快照器，通过反射读取主线程 [MessageQueue]，为 Barrier 假死保留现场证据。
 *
 * @param clock 提供采集时刻，用于计算每条消息的等待和阻塞时长。
 * @param sanitizer 对类名做隐私处理，避免报告泄露业务实现路径。
 * @param looper 待读取队列的 [Looper]，默认读取主线程。
 */
class PendingQueueSnapshotter(
    private val clock: Clock,
    private val sanitizer: ClassNameSanitizer,
    private val looper: Looper = Looper.getMainLooper(),
) {
    /**
     * 采集 Pending 队列快照；反射受限时返回不可用快照，避免监控逻辑影响业务线程。
     *
     * @param maxDepth 单次最多读取的队列深度。
     * @return 可用或不可用的队列快照。
     */
    fun capture(maxDepth: Int): PendingQueueSnapshot {
        return runCatching {
            captureUnsafe(maxDepth = maxDepth)
        }.getOrElse { throwable ->
            PendingQueueSnapshot.unavailable(
                maxDepth = maxDepth,
                failureReason = throwable.javaClass.simpleName,
            )
        }
    }

    /**
     * 读取 [Looper] 内部队列并顺序遍历链表，调用方负责兜底反射异常。
     */
    private fun captureUnsafe(maxDepth: Int): PendingQueueSnapshot {
        val queueField = Looper::class.java.getDeclaredField("mQueue")
        queueField.isAccessible = true
        val queue: MessageQueue = queueField.get(looper) as MessageQueue
        val messagesField = MessageQueue::class.java.getDeclaredField("mMessages")
        messagesField.isAccessible = true
        val nextField = Message::class.java.getDeclaredField("next")
        nextField.isAccessible = true
        val nowUptimeMs: Long = clock.uptimeMillis()
        val messages: MutableList<PendingMessage> = mutableListOf()
        var current: Message? = messagesField.get(queue) as? Message
        var index = 0
        while (current != null && index < maxDepth) {
            messages += current.toPendingMessage(
                index = index,
                nowUptimeMs = nowUptimeMs,
            )
            current = nextField.get(current) as? Message
            index += 1
        }
        return PendingQueueSnapshot(
            available = true,
            truncated = current != null,
            maxDepth = maxDepth,
            messages = messages,
            failureReason = null,
        )
    }

    /**
     * 将 Android [Message] 映射为 SDK 领域模型，并在映射时完成类名脱敏。
     */
    private fun Message.toPendingMessage(
        index: Int,
        nowUptimeMs: Long,
    ): PendingMessage {
        val rawTargetClass: String? = target?.javaClass?.name
        val rawCallbackClass: String? = callback?.javaClass?.name
        val rawObjClass: String? = obj?.javaClass?.name
        val blockedMs: Long = (nowUptimeMs - `when`).coerceAtLeast(minimumValue = 0L)
        return PendingMessage(
            index = index,
            whenUptimeMs = `when`,
            delayMs = `when` - nowUptimeMs,
            blockedMs = blockedMs,
            what = what,
            arg1 = arg1,
            arg2 = arg2,
            targetClass = sanitizer.sanitizeClassName(className = rawTargetClass).ifBlank { null },
            callbackClass = sanitizer.sanitizeClassName(className = rawCallbackClass).ifBlank { null },
            objClass = sanitizer.sanitizeClassName(className = rawObjClass).ifBlank { null },
            isAsynchronous = runCatching { isAsynchronous }.getOrNull(),
            isBarrierLike = target == null,
            isCriticalComponent = rawTargetClass == "android.app.ActivityThread\$H",
        )
    }
}
