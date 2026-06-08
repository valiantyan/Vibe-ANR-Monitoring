package com.valiantyan.anrmonitor.collector.looper

import android.util.Printer
import com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSampler
import com.valiantyan.anrmonitor.collector.stack.StackSample
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * 主 Looper 消息时间线采集器，通过 [Printer] 接收 dispatch 起止日志并维护当前消息和历史窗口。
 *
 * @param clock uptime 时间源，用于计算 wall time。
 * @param threadCpuClock 主线程 CPU 时间源，用于区分 CPU 忙和等待阻塞。
 * @param sanitizer 类名脱敏器，保证落盘或上报前已经符合隐私策略。
 * @param historyBuffer 历史消息环形缓冲区。
 * @param slowMessageMs 慢消息栈采样启动阈值。
 * @param stackSampleIntervalMs 慢消息栈采样间隔。
 * @param slowMessageSampler 慢消息栈采样器，为空时不采样。
 */
class MainLooperTimelineCollector(
    private val clock: Clock,
    private val threadCpuClock: CpuClock,
    private val sanitizer: ClassNameSanitizer,
    private val historyBuffer: MessageRingBuffer,
    private val slowMessageMs: Long = Long.MAX_VALUE,
    private val stackSampleIntervalMs: Long = Long.MAX_VALUE,
    private val slowMessageSampler: SlowMessageStackSampler? = null,
) : Printer {
    // 主线程消息序号，用于恢复 dispatch 顺序。
    private val sequence: AtomicLong = AtomicLong(0L)

    // 当前正在执行的消息起点，可能被 Watchdog 线程读取。
    @Volatile
    private var currentRecordStart: CurrentRecordStart? = null

    // 慢消息采样状态锁，保护采样时间和样本索引的跨线程访问。
    private val sampleLock: Any = Any()

    // 每条消息最近一次采样时间，避免 Watchdog 高频读取时无限采样。
    private val lastSampleUptimeBySeq: MutableMap<Long, Long> = mutableMapOf()

    // 当前报告窗口内已观察到的栈样本，按栈 ID 去重。
    private val stackSamplesById: LinkedHashMap<String, StackSampleRecord> = linkedMapOf()

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
        val sampleStackIds: List<String> = collectSlowStackSampleIfNeeded(
            start = start,
            nowUptimeMs = nowUptimeMs,
        )
        return start.toRecord(
            kind = MessageRecordKind.CURRENT,
            endUptimeMs = null,
            wallMs = (nowUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = currentCpuMs(start = start),
            sampleStackIds = sampleStackIds,
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
     * 返回当前报告窗口观察到的慢消息栈样本，供 [com.valiantyan.anrmonitor.domain.model.AnrSnapshot] 编码。
     *
     * @return 按首次观察顺序排列的栈样本。
     */
    fun stackSamples(): List<StackSampleRecord> {
        synchronized(sampleLock) {
            return stackSamplesById.values.toList()
        }
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

        /**
         * 返回当前线程 ID，用于后续跨线程读取同一 dispatch 的 CPU 时间。
         *
         * @return 当前线程 ID；无法读取时可返回 0。
         */
        fun currentThreadId(): Int = 0

        /**
         * 返回指定线程累计 CPU 毫秒值；读取失败时返回 null，调用方按保守值降级。
         *
         * @param threadId 目标线程 ID。
         * @return 目标线程 CPU 时间，单位毫秒。
         */
        fun threadCpuMs(threadId: Int): Long? = null
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
            threadId = threadCpuClock.currentThreadId(),
        ).also { start: CurrentRecordStart ->
            startSlowSampling(start = start)
        }
    }

    /**
     * 结束当前消息并写入历史窗口；结束事件 target 缺失时保留开始事件中的 target。
     */
    private fun finishCurrentRecord(event: LooperDispatchEvent): Unit {
        val start: CurrentRecordStart = currentRecordStart ?: return
        val endUptimeMs: Long = clock.uptimeMillis()
        val endCpuMs: Long = threadCpuClock.currentThreadCpuMs()
        val sanitizedEndTarget: String = sanitizer.sanitizeClassName(className = event.targetClass)
        val sampleStackIds: List<String> = finishSlowSampling(seq = start.seq)
        val record: MessageRecord = start.toRecord(
            kind = MessageRecordKind.HISTORY,
            endUptimeMs = endUptimeMs,
            wallMs = (endUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L),
            cpuMs = (endCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L),
            sampleStackIds = sampleStackIds,
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

    // 为新的 dispatch 周期开启采样桶，只有配置采样器时才会产生额外状态。
    private fun startSlowSampling(start: CurrentRecordStart): Unit {
        val sampler: SlowMessageStackSampler = slowMessageSampler ?: return
        synchronized(sampleLock) {
            sampler.startMessage(seq = start.seq)
            lastSampleUptimeBySeq[start.seq] = start.startUptimeMs
        }
    }

    // 当前消息超过慢消息阈值且达到采样间隔时，采集一次主线程栈样本。
    private fun collectSlowStackSampleIfNeeded(
        start: CurrentRecordStart,
        nowUptimeMs: Long,
    ): List<String> {
        val sampler: SlowMessageStackSampler = slowMessageSampler ?: return emptyList()
        val wallMs: Long = (nowUptimeMs - start.startUptimeMs).coerceAtLeast(minimumValue = 0L)
        synchronized(sampleLock) {
            val previousSampleUptimeMs: Long = lastSampleUptimeBySeq[start.seq] ?: start.startUptimeMs
            if (wallMs >= slowMessageMs && nowUptimeMs - previousSampleUptimeMs >= stackSampleIntervalMs) {
                sampler.collectSample(seq = start.seq)
                lastSampleUptimeBySeq[start.seq] = nowUptimeMs
            }
            return syncSampleRecords(seq = start.seq)
        }
    }

    // 消息结束时回收采样桶，并保留报告窗口内的样本 ID。
    private fun finishSlowSampling(seq: Long): List<String> {
        val sampler: SlowMessageStackSampler = slowMessageSampler ?: return emptyList()
        synchronized(sampleLock) {
            val records: List<StackSampleRecord> = sampler.finishMessage(seq = seq).map { sample: StackSample ->
                StackSampleRecord(
                    stackId = sample.stackHash,
                    frames = sample.frames,
                    hitCount = sample.hitCount,
                )
            }
            lastSampleUptimeBySeq.remove(key = seq)
            rememberSampleRecords(records = records)
            return records.map { record: StackSampleRecord -> record.stackId }
        }
    }

    // 同步当前采样桶到报告窗口，并返回消息关联的采样 ID。
    private fun syncSampleRecords(seq: Long): List<String> {
        val records: List<StackSampleRecord> = slowMessageSampler?.snapshotSampleRecords(seq = seq).orEmpty()
        rememberSampleRecords(records = records)
        return records.map { record: StackSampleRecord -> record.stackId }
    }

    // 采样记录按栈 ID 去重，避免同一报告窗口重复输出相同栈。
    private fun rememberSampleRecords(records: List<StackSampleRecord>): Unit {
        records.forEach { record: StackSampleRecord ->
            stackSamplesById[record.stackId] = record
        }
    }

    // 当前消息 CPU 必须读取开始消息所在目标线程，不能误用 Watchdog 线程 CPU。
    private fun currentCpuMs(start: CurrentRecordStart): Long {
        val nowCpuMs: Long = threadCpuClock.threadCpuMs(threadId = start.threadId) ?: start.startCpuMs
        return (nowCpuMs - start.startCpuMs).coerceAtLeast(minimumValue = 0L)
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
     * @property threadId dispatch 所在线程 ID。
     */
    private data class CurrentRecordStart(
        val seq: Long,
        val targetClass: String,
        val callbackClass: String?,
        val what: Int?,
        val isCriticalComponent: Boolean,
        val startUptimeMs: Long,
        val startCpuMs: Long,
        val threadId: Int,
    ) {
        /**
         * 将起点快照转换为当前消息或历史消息记录，保持字段口径一致。
         */
        fun toRecord(
            kind: MessageRecordKind,
            endUptimeMs: Long?,
            wallMs: Long,
            cpuMs: Long,
            sampleStackIds: List<String> = emptyList(),
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
                sampleStackIds = sampleStackIds,
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
