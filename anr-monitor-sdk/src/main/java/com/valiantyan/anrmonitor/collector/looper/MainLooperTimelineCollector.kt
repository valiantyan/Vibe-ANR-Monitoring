package com.valiantyan.anrmonitor.collector.looper

import android.util.Printer
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import java.util.concurrent.atomic.AtomicLong

/**
 * 主 Looper 消息时间线采集器，通过 [Printer] 接收 dispatch 起止日志并维护当前消息和历史窗口。
 *
 * @param clock uptime 时间源，用于计算 wall time。
 * @param threadCpuClock 主线程 CPU 时间源，用于区分 CPU 忙和等待阻塞。
 * @param sanitizer 类名脱敏器，保证落盘或上报前已经符合隐私策略。
 * @param historyBuffer 历史消息环形缓冲区。
 */
class MainLooperTimelineCollector(
    private val clock: Clock,
    private val threadCpuClock: CpuClock,
    private val sanitizer: ClassNameSanitizer,
    private val historyBuffer: MessageRingBuffer,
) : Printer {
    // 主线程消息序号，用于恢复 dispatch 顺序。
    private val sequence: AtomicLong = AtomicLong(0L)

    // 当前正在执行的消息起点，可能被 Watchdog 线程读取。
    @Volatile
    private var currentRecordStart: CurrentRecordStart? = null

    /**
     * 接收 Looper Printer 回调，并委托给 [onLooperLog] 统一处理。
     *
     * @param x Android Looper Printer 输出的单行文本。
     */
    override fun println(x: String): Unit {
        onLooperLog(line = x)
    }

    /**
     * 处理单行 Looper 日志，开始日志记录当前消息，结束日志归档为历史消息。
     *
     * @param line Looper Printer 输出文本。
     */
    fun onLooperLog(line: String): Unit {
        val event: LooperDispatchEvent = LooperMessageParser.parse(line = line)
        if (event.isStart) {
            currentRecordStart = createCurrentRecordStart(event = event)
            return
        }
        finishCurrentRecord(event = event)
    }

    /**
     * 生成当前未完成消息快照，供 Watchdog 在疑似 ANR 时读取正在执行的 Handler。
     *
     * @return 当前消息快照；主线程空闲或消息已完成时返回 null。
     */
    fun currentMessage(): MessageRecord? {
        val start: CurrentRecordStart = currentRecordStart ?: return null
        val nowUptimeMs: Long = clock.uptimeMillis()
        val nowCpuMs: Long = threadCpuClock.currentThreadCpuMs()
        return start.toRecord(
            kind = MessageRecordKind.CURRENT,
            endUptimeMs = null,
            wallMs = (nowUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = (nowCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L),
        )
    }

    /**
     * 返回历史消息快照，避免调用方直接持有内部环形缓冲区。
     *
     * @return 按 dispatch 完成顺序排列的历史消息。
     */
    fun historyMessages(): List<MessageRecord> {
        return historyBuffer.snapshot()
    }

    /**
     * 基于开始事件创建当前消息起点，并在入口处完成类名脱敏。
     */
    private fun createCurrentRecordStart(event: LooperDispatchEvent): CurrentRecordStart {
        return CurrentRecordStart(
            seq = sequence.incrementAndGet(),
            targetClass = sanitizer.sanitizeClassName(className = event.targetClass),
            callbackClass = sanitizer.sanitizeClassName(className = event.callbackClass).ifBlank { null },
            what = event.what,
            isCriticalComponent = isCriticalComponent(targetClass = event.targetClass),
            startUptimeMs = clock.uptimeMillis(),
            startCpuMs = threadCpuClock.currentThreadCpuMs(),
        )
    }

    /**
     * 结束当前消息并写入历史窗口；结束事件 target 缺失时保留开始事件中的 target。
     */
    private fun finishCurrentRecord(event: LooperDispatchEvent): Unit {
        val start: CurrentRecordStart = currentRecordStart ?: return
        val endUptimeMs: Long = clock.uptimeMillis()
        val endCpuMs: Long = threadCpuClock.currentThreadCpuMs()
        val sanitizedEndTarget: String = sanitizer.sanitizeClassName(className = event.targetClass)
        val record: MessageRecord = start.toRecord(
            kind = MessageRecordKind.HISTORY,
            endUptimeMs = endUptimeMs,
            wallMs = (endUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = (endCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L),
        )
        historyBuffer.add(record = record.copy(targetClass = sanitizedEndTarget.ifBlank { record.targetClass }))
        currentRecordStart = null
    }

    /**
     * 识别 ActivityThread.H 关键组件消息，后续归因会优先展示这类生命周期/广播/服务消息。
     */
    private fun isCriticalComponent(targetClass: String): Boolean {
        return targetClass == ACTIVITY_THREAD_HANDLER_CLASS
    }

    /**
     * 主线程 CPU 时间源，测试中可替换为确定性实现。
     */
    interface CpuClock {
        /**
         * 返回当前线程累计 CPU 毫秒值，用于 dispatch 前后差值计算。
         *
         * @return CPU 时间，单位毫秒。
         */
        fun currentThreadCpuMs(): Long
    }

    /**
     * 当前 dispatch 的起点快照，保存开始时间和已经脱敏的消息元信息。
     *
     * @property seq 消息序号。
     * @property targetClass 脱敏后的 target 类名。
     * @property callbackClass 脱敏后的 callback 类名。
     * @property what Handler 消息 what。
     * @property isCriticalComponent 是否为关键组件消息。
     * @property startUptimeMs dispatch 开始 uptime。
     * @property startCpuMs dispatch 开始 CPU 时间。
     */
    private data class CurrentRecordStart(
        val seq: Long,
        val targetClass: String,
        val callbackClass: String?,
        val what: Int?,
        val isCriticalComponent: Boolean,
        val startUptimeMs: Long,
        val startCpuMs: Long,
    ) {
        /**
         * 将起点快照转换为当前消息或历史消息记录，保持字段口径一致。
         */
        fun toRecord(
            kind: MessageRecordKind,
            endUptimeMs: Long?,
            wallMs: Long,
            cpuMs: Long,
        ): MessageRecord {
            return MessageRecord(
                seq = seq,
                kind = kind,
                messageType = MESSAGE_TYPE,
                what = what,
                targetClass = targetClass,
                callbackClass = callbackClass,
                isCriticalComponent = isCriticalComponent,
                startUptimeMs = startUptimeMs,
                endUptimeMs = endUptimeMs,
                wallMs = wallMs,
                cpuMs = cpuMs,
            )
        }
    }

    private companion object {
        /**
         * Looper dispatch 消息类型标识。
         */
        private const val MESSAGE_TYPE: String = "looper_dispatch"

        /**
         * Android 主线程关键 Handler，承载生命周期、广播、服务等组件消息。
         */
        private const val ACTIVITY_THREAD_HANDLER_CLASS: String = "android.app.ActivityThread\$H"
    }
}
