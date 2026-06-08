package com.valiantyan.anrmonitor.internal

import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.PendingMessage

/**
 * 活跃 ANR 事件去重器，避免同一个卡死窗口持续生成多份报告。
 */
internal class AnrIncidentDeduplicator {
    // 当前仍未恢复的 ANR 签名。
    private var activeSignature: String? = null

    /**
     * 判断本次快照是否应该生成报告，并在允许时记录活跃签名。
     *
     * @param snapshot 疑似 ANR 快照。
     * @return 首次出现或签名变化时返回 true。
     */
    @Synchronized
    fun shouldReport(snapshot: AnrSnapshot): Boolean {
        val signature: String = buildSignature(snapshot = snapshot)
        if (activeSignature == signature) {
            return false
        }
        activeSignature = signature
        return true
    }

    /**
     * 主线程心跳恢复后清空活跃签名，允许后续新 ANR 再次上报。
     */
    @Synchronized
    fun markRecovered(): Unit {
        activeSignature = null
    }

    // 优先使用 Barrier token 作为稳定签名，其次使用当前消息，最后退化到主线程栈。
    private fun buildSignature(snapshot: AnrSnapshot): String {
        val headMessage: PendingMessage? = snapshot.pendingQueue.messages.firstOrNull()
        if (snapshot.pendingQueue.available && headMessage?.isBarrierLike == true) {
            return "barrier:${headMessage.arg1}"
        }
        val currentMessage: MessageRecord? = snapshot.currentMessage
        if (currentMessage != null) {
            return "current:${currentMessage.messageType}:${currentMessage.targetClass}:${currentMessage.callbackClass}"
        }
        return "stack:${snapshot.mainThreadStack.stackId}"
    }
}
